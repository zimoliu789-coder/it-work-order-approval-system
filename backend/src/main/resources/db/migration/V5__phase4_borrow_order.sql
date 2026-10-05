-- =====================================================================
--  / V5__phase4_borrow_order.sql
-- 企业内部设备借用工单系统 —— 借用申请、临时锁、审批快照
-- 规范依据：
--   第 9 章    设备状态机（LOCKED / IN_APPROVAL / 使用中）
--   第 10 章   临时锁定机制（locked_by / locked_at / lock_token，5 分钟超时可配）
--   第 11 章   借用申请（设备、借用类型、原因、使用地点、期望归还日期）
--   第 12 章   工单状态机（PENDING_APPROVAL → PENDING_DELIVERY → BORROWED …）
--   第 13.2 章 审批快照（order_approval_nodes，提交瞬间固化，后续改配置不影响历史工单）
--   第 31 章   事务与并发控制（MySQL 为业务最终一致性权威来源）
-- 说明：
--   1) 本次迁移含三类改动：① 超管登录名归一；② 设备状态枚举改名（存量数据同步）；
--      ③ 新增  表结构与临时锁字段。三段均为幂等写法，重复执行不会失败。
--   2) 表名用 `orders` 而非 `order`：ORDER 是 SQL 保留字，避免全链路反引号。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、超级管理员登录名归一（需求方约定：超管登录名固定为 administrator，不可修改、不可被重置）
--
-- 迁移条件（同时满足才执行，任一不满足即整段空跑，保证幂等与安全）：
--   a) 角色为 super_admin；
--   b) 登录名还不是 administrator；
--   c) 全库只有 1 个 super_admin —— 多个超管同时改名会撞 uk_users_username 唯一索引；
--   d) 当前没有其它账号占用 administrator —— 避免把别人的登录名顶掉。
-- 只改 username 与 updated_at：**password_hash / display_name / 角色一律保留**，
-- 即「保留现有密码，用户改密后不会被重置」。后续启动由 SuperAdminInitializer 幂等跳过。
-- ---------------------------------------------------------------------
UPDATE `users` AS u
SET u.`username`   = 'administrator',
    u.`updated_at` = NOW()
WHERE u.`role` = 'super_admin'
  AND u.`username` <> 'administrator'
  AND (SELECT sa.cnt FROM (SELECT COUNT(*) AS cnt FROM `users` WHERE `role` = 'super_admin') AS sa) = 1
  AND NOT EXISTS (SELECT 1 FROM (SELECT `id` FROM `users` WHERE `username` = 'administrator') AS au);

-- ---------------------------------------------------------------------
-- 二、设备状态枚举改名：BORROWED（已借用）→ IN_USE（使用中）
--
-- 背景（需求方）：企业内部大量设备是长期领用而非短期借用，「已借用」语义不准确。
-- 范围：**仅设备状态**改名，流转逻辑不变；工单状态仍沿用 的 BORROWED 编码
--       （对外展示文案统一为「使用中」，见 OrderStatus）。
-- 注意：本 UPDATE 必须早于应用启动读取设备状态，否则存量行会因枚举无 BORROWED 而解析失败。
-- ---------------------------------------------------------------------
UPDATE `device` SET `status` = 'IN_USE' WHERE `status` = 'BORROWED';

-- ---------------------------------------------------------------------
-- 三、设备临时锁字段（）
--
-- 设计要点：
--   · locked_by / locked_at / lock_token 三件套；lock_token 用于防止「旧页面释放了后来属于
--     其他用户的新锁」——释放时必须比对 token，不匹配则拒绝（ 明确要求）；
--   · MySQL 是业务最终事实来源，Redis 仅作 TTL 加速（ / ），因此超时判断以 locked_at 为准；
--   · 锁释放（超时 / 提交成功 / 取消）时三个字段一并清空为 NULL。
-- ---------------------------------------------------------------------
ALTER TABLE `device`
  ADD COLUMN `locked_by`  BIGINT      DEFAULT NULL COMMENT '临时锁定人 user_id（；LOCKED 状态期间有效）' AFTER `status`,
  ADD COLUMN `locked_at`  DATETIME    DEFAULT NULL COMMENT '临时锁定开始时间（；超时判断以此为准）'          AFTER `locked_by`,
  ADD COLUMN `lock_token` VARCHAR(64) DEFAULT NULL COMMENT '临时锁令牌（；释放时比对，防止释放他人的新锁）' AFTER `locked_at`;

ALTER TABLE `device`
  ADD KEY `idx_device_locked_at` (`locked_at`);

-- ---------------------------------------------------------------------
-- 四、orders 借用工单主表（ /  / ）
--
-- 快照字段说明（ 精神：配置变更不影响历史工单）：
--   · biz_group_id            —— 提交时申请人所属业务分组，固化不再随调岗变化；
--   · final_handler_group_id  —— 提交时分组绑定的最终处理小组，后续改绑不影响本工单；
--   · 审批人快照见 order_approval_nodes。
-- 借用类型（本次需求新增）：
--   · SHORT_TERM 短期借用 —— expected_return_date 必填，到期参与预警/顺延；
--   · LONG_TERM  长期领用 —— 无固定归还日期，不催还，由离职/故障事件触发归还。
-- planned_end_time 由交付瞬间按 expected_return_date 落库，供  顺延/超时使用。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `orders` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_no`                 VARCHAR(32)  NOT NULL                            COMMENT '工单编号（业务可读，全局唯一）',
  `device_id`                BIGINT       NOT NULL                            COMMENT '申请设备 device.id',
  `applicant_id`             BIGINT       NOT NULL                            COMMENT '申请人 user_id（取自登录态）',
  `biz_group_id`             BIGINT       NOT NULL                            COMMENT '快照：提交时申请人所属业务分组ID',
  `use_type`                 VARCHAR(16)  NOT NULL                            COMMENT '借用类型：SHORT_TERM 短期借用 / LONG_TERM 长期领用',
  `reason`                   VARCHAR(500) NOT NULL                            COMMENT '借用原因',
  `use_place`                VARCHAR(128) NOT NULL                            COMMENT '使用地点（必填，支持预设下拉与手填）',
  `expected_return_date`     DATE                  DEFAULT NULL               COMMENT '期望归还日期（短期借用必填，长期领用为空）',
  `remark`                   VARCHAR(500)          DEFAULT NULL               COMMENT '备注',
  `status`                   VARCHAR(32)  NOT NULL                            COMMENT '工单状态：见 OrderStatus（）',
  `final_handler_group_id`   BIGINT       NOT NULL                            COMMENT '快照：提交时绑定的最终处理小组ID（）',
  `actual_final_handler_id`  BIGINT                DEFAULT NULL               COMMENT '实际执行人 user_id（审批通过随机分配，）',
  `borrow_timeout`           TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否借用超时：0否 1是（）',
  `auto_extend_count`        INT          NOT NULL DEFAULT 0                  COMMENT '自动顺延已用次数，最多 2 次（）',
  `planned_end_time`         DATETIME              DEFAULT NULL               COMMENT '计划借用结束时间（交付瞬间按期望归还日期落库）',
  `actual_end_time`          DATETIME              DEFAULT NULL               COMMENT '实际归还时间（）',
  `delivered_at`             DATETIME              DEFAULT NULL               COMMENT '交付确认时间',
  `delivered_by`             BIGINT                DEFAULT NULL               COMMENT '交付确认人 user_id（应等于实际执行人）',
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_orders_no` (`order_no`),
  KEY `idx_orders_device_status` (`device_id`, `status`),
  KEY `idx_orders_applicant` (`applicant_id`),
  KEY `idx_orders_status` (`status`),
  KEY `idx_orders_handler` (`actual_final_handler_id`),
  CONSTRAINT `fk_orders_device`   FOREIGN KEY (`device_id`)              REFERENCES `device` (`id`)         ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_orders_applicant` FOREIGN KEY (`applicant_id`)          REFERENCES `users` (`id`)          ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_orders_biz_group` FOREIGN KEY (`biz_group_id`)          REFERENCES `biz_group` (`id`)      ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_orders_handler_group` FOREIGN KEY (`final_handler_group_id`) REFERENCES `handler_groups` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_orders_handler`  FOREIGN KEY (`actual_final_handler_id`) REFERENCES `users` (`id`)          ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '借用工单主表';

-- ---------------------------------------------------------------------
-- 五、order_approval_nodes 审批快照表（）
--
-- 一行 = 一个「步骤 × 审批人」。当前分组配置每步仅 1 名审批人（biz_group_approver 的
-- uk_bga_group_step 保证 step_order 同组唯一），但表结构按「同一步骤可多人」设计，
-- 因此会签 / 或签在运行期按 step_order 分组判定（ALL_SIGN 全通过 / ANY_SIGN 任一通过），
-- 后续若放开一步多审批人，无需再改快照结构。
--   · is_super_backup —— 分组未配置审批节点时的 super_admin 兜底节点（）；
--   · is_fallback     —— 原审批人已离职/禁用，快照生成时替换为 super_admin（）；
--   · status          —— PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED。
-- 历史工单永远读取本表，不实时回查 biz_group_approver。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_approval_nodes` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`        BIGINT       NOT NULL                            COMMENT '工单ID',
  `step_order`      INT          NOT NULL                            COMMENT '审批步骤序号（同工单内按此升序执行）',
  `approver_id`     BIGINT       NOT NULL                            COMMENT '审批人 user_id（快照固化）',
  `sign_type`       VARCHAR(16)  NOT NULL DEFAULT 'ANY_SIGN'         COMMENT '会签 ALL_SIGN / 或签 ANY_SIGN（快照固化）',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'          COMMENT 'PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED',
  `action_time`     DATETIME              DEFAULT NULL               COMMENT '操作时间',
  `action_comment`  VARCHAR(500)          DEFAULT NULL               COMMENT '审批意见（驳回时必填，）',
  `is_super_backup` TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否 super_admin 兜底审批节点（）',
  `is_fallback`     TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否因原审批人离职/禁用自动替换为兜底（）',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_oan_order` (`order_id`, `step_order`),
  KEY `idx_oan_approver_status` (`approver_id`, `status`),
  CONSTRAINT `fk_oan_order`    FOREIGN KEY (`order_id`)    REFERENCES `orders` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_oan_approver` FOREIGN KEY (`approver_id`) REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单审批快照表';
