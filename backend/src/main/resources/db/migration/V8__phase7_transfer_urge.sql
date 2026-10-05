-- =====================================================================
--  / V8__phase7_transfer_urge.sql
-- 企业内部设备借用工单系统 —— 工单转交、催办 / 催还
-- 规范依据：
--   第 16.4 章  工单转交（状态约束、同组限制与超管例外、转交历史表、留痕要求）
--   第 28 章    员工离职（转交能力具备后，离职者名下在办工单自动转交）
--   第 20 章    操作日志（转交属高风险操作，必须同步落审计）
--   第 24 章    站内消息（转交通知 / 催办通知）
--   第 31 章    事务与并发控制（条件 UPDATE + 命中 0 行即回滚）
-- 说明：
--   1) 本迁移只新建 2 张表 + 播种 1 条配置，**不修改任何既有表结构**。
--      需求方明确：orders 表不加字段，执行人变更直接更新 actual_final_handler_id ——
--      「当前执行人」是状态，「谁转给谁」是历史，二者本就该分开建模。
--   2) 催办（审批催办 / 归还催办）在规范 V1.1 中**没有对应章节**，本表按需求方
--      2026-09-18 确认的方案实现：独立 order_urge 表承载「工单事件」语义，
--      与 messages 的「消息投递」语义分离 —— 否则「同单同节点 1 小时冷却」这类
--      业务查询会被迫扫描消息表，且消息的已读/未读语义会污染催办统计。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、order_handler_transfer 工单转交记录（）
--
--  原文要求的字段：id、order_id、old_handler_id、new_handler_id、
-- transfer_operator_id、transfer_comment、created_at —— 本表**逐字沿用规范命名**。
--
--  业务规则（逐条落地）：
--   · 可转交状态：PENDING_DELIVERY / BORROWED / PENDING_RETURN；
--   · RETURNED / REJECTED / CANCELLED 不允许转交；
--   · 普通执行人只能转给同 final_handler_group_id 小组内 enabled 且在职的成员；
--   · super_admin 不受小组限制，可转给任意系统内有效用户；
--   · 转交后更新 orders.actual_final_handler_id，超时告警与归还权限全部跟随新执行人。
--
-- transfer_type 为**本阶段新增字段**（规范字段清单之外）：
-- 需求方   要求「离职自动转交」必须可与人工转交区分，故显式标注来源。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_handler_transfer` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`             BIGINT       NOT NULL                            COMMENT '工单 orders.id',
  `old_handler_id`       BIGINT       NOT NULL                            COMMENT '原实际执行人 user_id（）',
  `new_handler_id`       BIGINT       NOT NULL                            COMMENT '新实际执行人 user_id（）',
  `transfer_operator_id` BIGINT       NOT NULL                            COMMENT '转交操作人 user_id（）：人工转交＝原执行人本人或代转的超管；自动转交＝原执行人',
  `transfer_comment`     VARCHAR(500)          DEFAULT NULL               COMMENT '转交备注（）：人工转交必填，自动转交由服务层写入系统说明',
  `transfer_type`        VARCHAR(16)  NOT NULL DEFAULT 'MANUAL'           COMMENT '转交来源：MANUAL 人工转交 / AUTO_DIMISSION 离职自动转交（ ）',
  `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '转交时间（ created_at）',
  PRIMARY KEY (`id`),
  KEY `idx_oht_order` (`order_id`, `id`),
  KEY `idx_oht_old_handler` (`old_handler_id`),
  KEY `idx_oht_new_handler` (`new_handler_id`, `created_at`),
  CONSTRAINT `fk_oht_order`   FOREIGN KEY (`order_id`)             REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_oht_old`     FOREIGN KEY (`old_handler_id`)       REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_oht_new`     FOREIGN KEY (`new_handler_id`)       REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_oht_operator` FOREIGN KEY (`transfer_operator_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单转交记录（）';

-- ---------------------------------------------------------------------
-- 二、order_urge 催办记录（ 新增能力；规范 V1.1 未覆盖）
--
-- 需求方  「催办」：
--   · 审批催办 —— 申请人对「审批中」工单催当前审批节点审批人，同单同节点 1 小时内只允许 1 次；
--   · 归还催办 —— 实际执行人对「使用中」且已到期/超时的工单催使用人归还；
--   · 催办记录写工单时间线（工单详情页展示）；
--   · 催办**不改变工单状态**，只发站内消息。
--
-- node_id 的用途：冷却判定键是「工单 + 节点」。审批推进到下一步后，node_id 变化，
-- 申请人即可对新节点再次催办 —— 这正是需求要的语义，也避免「整单只准催一次」。
-- 归还催办没有节点概念，node_id 为空。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_urge` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`       BIGINT      NOT NULL                            COMMENT '工单 orders.id',
  `urge_type`      VARCHAR(16) NOT NULL                            COMMENT '催办类型：APPROVAL 审批催办 / RETURN 归还催办',
  `node_id`        BIGINT               DEFAULT NULL               COMMENT '审批催办时锁定的当前节点 order_approval_nodes.id（冷却按「同单同节点」判定）；归还催办为空',
  `target_user_id` BIGINT      NOT NULL                            COMMENT '被催办人 user_id（审批催办＝当前节点审批人；归还催办＝借用人）',
  `operator_id`    BIGINT      NOT NULL                            COMMENT '催办发起人 user_id（审批催办＝申请人；归还催办＝实际执行人）',
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '催办时间',
  PRIMARY KEY (`id`),
  KEY `idx_ou_cooldown` (`order_id`, `urge_type`, `node_id`, `created_at`),
  KEY `idx_ou_order` (`order_id`, `id`),
  KEY `idx_ou_operator` (`operator_id`),
  CONSTRAINT `fk_ou_order`    FOREIGN KEY (`order_id`)       REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_ou_target`   FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_ou_operator` FOREIGN KEY (`operator_id`)    REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单催办记录（；规范 V1.1 未覆盖）';

-- ---------------------------------------------------------------------
-- 三、催办冷却时长配置（默认 60 分钟）
--
-- 需求方原文：「同一工单同一审批节点催办有冷却时间（比如 1 小时内只能催 1 次）」。
-- 冷却时长做成 system_config 项而非硬编码，便于运维按现场节奏调整（1–1440 分钟）。
-- INSERT IGNORE 依赖 uk_system_config_key 唯一键，重复执行不会报错，保证幂等。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
VALUES ('urge_cooldown_minutes', '60', 'borrow',
        '催办冷却时长（分钟）：同一工单同一审批节点（或同一次归还催办）在该时长内只能发起一次', 1);
