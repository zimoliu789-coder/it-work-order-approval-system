-- =====================================================================
--  / V7__phase6_extend_fault.sql
-- 企业内部设备借用工单系统 —— 借用延期子工单、设备故障上报
-- 规范依据：
--   第 17 章   借用延期子工单（申请人主动发起，复用主单审批链路，最多 2 次可配）
--   第 18 章   设备故障上报（工单内上报 / 台账直接登记；维修完成 / 标记报废）
--   第 9 章    设备状态机（AVAILABLE / MAINTENANCE / SCRAPPED 的手工流转约束）
--   第 20 章   操作日志（故障与延期全部记入审计）
--   第 31 章   事务与并发控制（MySQL 为业务最终一致性权威来源）
-- 说明：
--   1) 本迁移**只新建 3 张表**，不修改任何既有表的结构 —— 对 –5 的存量数据与
--      代码完全向后兼容，没有任何回填动作，三段均为幂等写法（CREATE TABLE IF NOT EXISTS）。
--   2) 延期子工单**不修改主工单的申请记录**（），审批通过后仅回写
--      orders.planned_end_time / borrow_timeout / auto_extend_count 三个借用状态字段。
--   3) 延期审批节点单独建表（order_extend_approval_nodes），与主单的 order_approval_nodes
--      物理隔离：主单审批链路已交付并大量使用（待办查询依赖其索引与谓词），
--      共用一表会让「主单待办」误匹配延期节点，因此选择独立表 + 复制主单快照的方式实现
--      「复用主工单审批快照链路（同分组审批节点）」，零回归风险。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、order_extend 借用延期子工单（）
--
-- 业务规则（ 逐条落地）：
--   · 触发条件：主工单状态 = BORROWED；
--   · 延期属子工单，**不修改原始主工单申请记录**，独立生成延期快照；
--   · 延期后的结束时间不能早于当前系统时间（服务层校验，DB 只存结果）；
--   · 最多 2 次（system_config.extend_max_count， 已播种默认 2）；
--   · 审批通过 → 回写主单 planned_end_time、重算 borrow_timeout、auto_extend_count 归零；
--   · 审批驳回 → 不影响原有借用时间；
--   · 最终处理人沿用主单当前 actual_final_handler_id，不重新分配。
--
-- status 取值：PENDING_APPROVAL 审批中 / APPROVED 已通过 / REJECTED 已驳回。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_extend` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`       BIGINT       NOT NULL                            COMMENT '主工单 orders.id（：延期不改主单申请记录）',
  `applicant_id`   BIGINT       NOT NULL                            COMMENT '发起人 user_id（应等于主单申请人，服务层校验）',
  `original_end_time` DATETIME  NOT NULL                            COMMENT '发起时的原 planned_end_time（用于审批人对比「原定 → 新定」）',
  `new_end_time`   DATETIME     NOT NULL                            COMMENT '申请延长到的新结束时间（必须晚于当前系统时间）',
  `reason`         VARCHAR(500) NOT NULL                            COMMENT '延期原因（必填）',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '状态：PENDING_APPROVAL 审批中 / APPROVED 已通过 / REJECTED 已驳回',
  `action_time`    DATETIME              DEFAULT NULL               COMMENT '审批动作时间（通过 / 驳回）',
  `action_comment` VARCHAR(500)          DEFAULT NULL               COMMENT '审批意见（驳回时必填，与主单规则一致）',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_extend_order` (`order_id`, `status`),
  KEY `idx_extend_status` (`status`),
  KEY `idx_extend_applicant` (`applicant_id`),
  CONSTRAINT `fk_extend_order`     FOREIGN KEY (`order_id`)     REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_extend_applicant` FOREIGN KEY (`applicant_id`) REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '借用延期子工单（）';

-- ---------------------------------------------------------------------
-- 二、order_extend_approval_nodes 延期审批快照节点（「独立生成延期快照」）
--
-- 结构镜像 order_approval_nodes，语义完全一致（一行 = 一个「步骤 × 审批人」）：
--   · 生成时机 —— 申请人提交延期申请的瞬间，**复制主工单已固化的审批快照**
--     （approver_id / sign_type / step_order），并按 重新解析审批人可用性
--     （已离职/禁用则替换为 super_admin 兜底，置 is_fallback），
--     再按 应用「审批人 = 申请人则跳过」；
--   · is_super_backup —— 主单为 super_admin 兜底链（）时原样继承；
--   · status —— PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_extend_approval_nodes` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `extend_id`       BIGINT       NOT NULL                            COMMENT '延期子工单 order_extend.id',
  `step_order`      INT          NOT NULL                            COMMENT '审批步骤序号（同延期单内按此升序执行）',
  `approver_id`     BIGINT       NOT NULL                            COMMENT '审批人 user_id（快照固化）',
  `sign_type`       VARCHAR(16)  NOT NULL DEFAULT 'ANY_SIGN'         COMMENT '会签 ALL_SIGN / 或签 ANY_SIGN（快照固化）',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'          COMMENT 'PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED',
  `action_time`     DATETIME              DEFAULT NULL               COMMENT '操作时间',
  `action_comment`  VARCHAR(500)          DEFAULT NULL               COMMENT '审批意见（驳回时必填）',
  `is_super_backup` TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否 super_admin 兜底审批节点（）',
  `is_fallback`     TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否因原审批人离职/禁用自动替换为兜底（）',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_oean_extend` (`extend_id`, `step_order`),
  KEY `idx_oean_approver_status` (`approver_id`, `status`),
  CONSTRAINT `fk_oean_extend`   FOREIGN KEY (`extend_id`)   REFERENCES `order_extend` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_oean_approver` FOREIGN KEY (`approver_id`) REFERENCES `users` (`id`)        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '延期子工单审批快照表';

-- ---------------------------------------------------------------------
-- 三、device_fault 设备故障记录（）
--
-- 业务规则（ 逐条落地）：
--   · 工单内上报 —— 借用人在 BORROWED 状态上报，绑定 order_id；
--   · 无工单上报 —— 管理员 / 最终处理人在台账页面直接登记，order_id 为空，
--     设备立即 AVAILABLE → MAINTENANCE；
--   · 归还时登记故障 —— 收回环节登记「故障」时自动生成一条故障记录并关联工单；
--   · 维修完成 —— 管理员操作，设备 MAINTENANCE → AVAILABLE；
--   · 标记报废 —— 设备 → SCRAPPED（仅 AVAILABLE / MAINTENANCE 可报废，BORROWED 禁止，）；
--   · 所有故障操作记入审计日志（）。
--
-- status 取值：PENDING_REPAIR 待维修 / REPAIRED 维修完成 / SCRAPPED 已报废。
-- 附件（图片）按需求方本轮确认**不在本阶段交付**，随 通用附件能力统一实现。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `device_fault` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `device_id`         BIGINT       NOT NULL                            COMMENT '故障设备 device.id',
  `order_id`          BIGINT                DEFAULT NULL               COMMENT '关联工单 orders.id（无工单直接登记时为空，）',
  `reporter_id`       BIGINT       NOT NULL                            COMMENT '上报人 user_id',
  `fault_description` VARCHAR(500) NOT NULL                            COMMENT '故障描述',
  `occurred_at`       DATETIME     NOT NULL                            COMMENT '故障发生时间',
  `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING_REPAIR'   COMMENT '状态：PENDING_REPAIR 待维修 / REPAIRED 维修完成 / SCRAPPED 已报废',
  `handled_by`        BIGINT                DEFAULT NULL               COMMENT '处理人 user_id（维修完成 / 报废操作人）',
  `handled_at`        DATETIME              DEFAULT NULL               COMMENT '处理时间',
  `handle_remark`     VARCHAR(500)          DEFAULT NULL               COMMENT '处理说明（维修结果 / 报废原因）',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_fault_device_status` (`device_id`, `status`),
  KEY `idx_fault_status` (`status`),
  KEY `idx_fault_order` (`order_id`),
  KEY `idx_fault_occurred` (`occurred_at`),
  CONSTRAINT `fk_fault_device`   FOREIGN KEY (`device_id`)   REFERENCES `device` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_fault_order`    FOREIGN KEY (`order_id`)    REFERENCES `orders` (`id`)  ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `fk_fault_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`)   ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_fault_handler`  FOREIGN KEY (`handled_by`)  REFERENCES `users` (`id`)   ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备故障记录（）';
