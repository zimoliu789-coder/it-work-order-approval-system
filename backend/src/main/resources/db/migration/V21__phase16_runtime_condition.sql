-- =====================================================================
--  ·  · M2（运行时条件 + 审批引擎增强）
-- V21__phase16_runtime_condition.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-23 确认 Q2/Q3/Q5 + A/B/C/D 四点建议）：
--   把「提交时一次性定格全部节点」升级为「骨架定格 + 运行期逐步激活」，
--   使流程条件可以引用**审批过程中才产生**的数据（上一节点结果 / 已耗时 / 驳回次数 …），
--   并在此之上支持三类引擎增强：驳回改道（onReject）、超时加签（onTimeout）、节点级转办。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **只加 2 个可空列 + 1 张日志表 + 1 条开关，不动任何既有语义**。
--     新增的第三个节点状态 INACTIVE（未激活）是**取值层面**的扩展：
--     存量行不会有该取值（默认 false 的开关保证代码路径也不会写入），
--     因此回滚时**无需删列、无需改表**，已产生的动态节点回滚后仍可正常读取展示。
--
--  B. **总开关 `flow_runtime_condition_enabled` 默认 0（关闭）**，这是回滚的第一层：
--     关闭时 recompute 的所有调用点短路、含运行期特性的定义在发布侧被拒 →
--     整个系统行为与第二期**逐行一致**。
--
--  C. **`order_flow_activation_log` 是对「破坏提交定格可复现性」的直接补偿**。
--     第二期最大的优点是「重放输入即可还原走哪条路」；引入运行期条件后，
--     路径在审批过程中才确定 —— 那么"每一步为什么激活/为什么跳过"就必须可回放。
--     本表记录每次决策的 from/to 状态、人类可读原因、以及决策时的 process.* 上下文快照。
--     没有它，出了分歧只能靠猜，这是比功能本身更大的风险。
--
--  D. **不加 node_type 新取值**：加签节点仍是 APPROVAL，其"动态来源"由
--     runtime_reason + 激活日志表达。给节点类型加取值会污染既有语义
--     （所有 `node_type='APPROVAL'` 的判定点都要重新审视一遍），而收益为零。
--
--  E. **不改 step_order 列定义**：idx_oan_order 本非唯一索引，
--     动态插入靠「整体重排」实现（新节点取前一步 +1，其后所有行整体 +1），
--     顺序始终可读、可审计，且一次 UPDATE 完成、不需要加锁。
--
--  F. **config_group 用既有分组名**（不再新增分组），避免参数页出现"只有一个配置项的分组"。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 一、order_approval_nodes 增量：激活时刻 + 运行期决策原因
-- ---------------------------------------------------------------------
-- activated_at：被激活为 PENDING 的时刻。
--   - 第二期物化的节点：提交时刻（与 created_at 同值语义）；
--   - 运行期激活的节点：recompute 判定激活的那一刻。
--   它的用途是**让动态节点的耗时也可统计**（M7 流程监控按 node_key 聚合平均耗时），
--   没有它就只能拿 created_at 当起点，动态节点会算出"负耗时"。
--
-- runtime_reason：运行期决策原因（人类可读）。
--   例：「上一节点审批通过，且耗时 30 小时 > 24 小时，已激活加签节点」
--       「上一节点被驳回、未走改道，本节点不会再被激活」
--   与 condition_desc 的分工：condition_desc 回答"为什么走这条分支"（提交时确定），
--   runtime_reason 回答"为什么现在激活/为什么永远不会激活"（运行期确定）。
ALTER TABLE `order_approval_nodes`
  ADD COLUMN `activated_at` DATETIME DEFAULT NULL
      COMMENT '被激活为 PENDING 的时刻（提交即激活=提交时刻；运行期激活=recompute 时刻；INACTIVE 时为 NULL）'
      AFTER `deadline_at`,
  ADD COLUMN `runtime_reason` VARCHAR(255) DEFAULT NULL
      COMMENT '运行期决策原因：为什么被激活 / 为什么被跳过（人类可读；第二期流程恒为 NULL）'
      AFTER `activated_at`;


-- ---------------------------------------------------------------------
-- 二、order_flow_activation_log：激活决策留痕（可复现 + 可解释 + 可回放）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_flow_activation_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT                          COMMENT '主键',
  `order_id`    BIGINT       NOT NULL                                         COMMENT '工单ID',
  `node_key`    VARCHAR(64)  NOT NULL                                         COMMENT '流程节点稳定标识',
  `from_status` VARCHAR(16)  NOT NULL                                         COMMENT '决策前状态（INACTIVE / SKIPPED / PENDING …）',
  `to_status`   VARCHAR(16)  NOT NULL                                         COMMENT '决策后状态',
  `reason`      VARCHAR(255)          DEFAULT NULL                            COMMENT '人类可读的决策原因',
  `ctx_json`    TEXT                  DEFAULT NULL                            COMMENT '决策时的运行期上下文快照（process.* 取值 JSON）',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ofal_order` (`order_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '流程节点激活决策日志（：让运行期条件可回放）';


-- ---------------------------------------------------------------------
-- 三、总开关（默认关闭 = 回滚开关）
-- ---------------------------------------------------------------------
-- 用 INSERT IGNORE 依赖 uk_system_config_key 唯一键，重复执行不报错（幂等），
-- 与 V14 的写法保持一致。config_value 用 '0'/'1' 而不是 'false'/'true'：
-- 本项目 system_config 的布尔口径统一是 0/1（见 V14 的 rate_limit_enabled），
-- 混用两种写法会让参数页的展示与校验出现分叉。
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`) VALUES
('flow_runtime_condition_enabled', '0', 'approval',
 '运行时条件引擎总开关：1 开启 / 0 关闭。关闭时流程仍在提交时一次性定格（与第二期行为完全一致），含运行期条件（process.*）的流程定义将被拒绝发布', 1);
