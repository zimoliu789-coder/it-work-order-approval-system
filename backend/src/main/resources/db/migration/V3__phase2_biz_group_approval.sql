-- =====================================================================
--  / V3__phase2_biz_group_approval.sql
-- 企业内部设备借用工单系统 —— 业务分组、审批节点、最终处理小组
-- 规范依据：
--   第 13 章   业务分组与审批配置（ 数据表结构 /  审批流转规则 /  可视化界面）
--   第 5 章    用户、姓名账号与角色（biz_group_id 归属与引用完整性）
--   第 20 章   操作日志（审批配置变更属高风险，必须留痕）
-- 说明：
--   1)  已预建 biz_group / handler_groups / handler_group_member 三张空表，
--      本脚本补齐缺失的 biz_group_approver，并为既有表补上外键约束与索引。
--   2) users.biz_group_id 的收紧范围（已与需求方在  约定「 收紧」）：
--      本阶段补外键 + 引用完整性，**不**加 NOT NULL 约束，原因见下方 ALTER 处注释。
--   3) 最终处理小组采用软删除（deleted），分组与小组均为允许跨分组共享的独立实体。
-- =====================================================================

-- ---------------------------------------------------------------------
-- biz_group_approver 分组审批步骤表（）
--   step_order 同分组内不可重复，数字越小越先审批（ 按 step_order 从小到大依次执行）
--   sign_type  ALL_SIGN=会签（该节点审批人全部通过）/ ANY_SIGN=或签（任一人通过）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `biz_group_approver` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `biz_group_id` BIGINT      NOT NULL                            COMMENT '关联 biz_group.id',
  `step_order`   INT         NOT NULL                            COMMENT '审批步骤序号（1、2、3…，越小越先审批），同分组内不可重复',
  `approver_id`  BIGINT      NOT NULL                            COMMENT '该步骤审批人 user_id（：业务表外键一律使用 user_id）',
  `sign_type`    VARCHAR(16) NOT NULL DEFAULT 'ANY_SIGN'         COMMENT '审批模式：ALL_SIGN 会签 / ANY_SIGN 或签',
  `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bga_group_step` (`biz_group_id`, `step_order`),
  KEY `idx_bga_approver` (`approver_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '分组审批步骤表';

-- ---------------------------------------------------------------------
-- 引用完整性收紧（ 预留的「 收紧」项）
--
-- users.biz_group_id：补外键，**不加 NOT NULL**。理由：
--   a)  定义了错误码 USER_WITHOUT_BIZ_GROUP（员工未分配业务分组），
--      说明「无分组」是系统认可的合法中间状态，应在提交工单时拦截（），
--      而不是让员工记录无法存在；
--   b) 超级管理员为管理账户，可能不属于任何业务部门（ 起其 biz_group_id 即为 NULL），
--      强制非空会导致超管账号无法创建；
--   c) 分组成员管理界面负责分配归属，外键保证不会引用到不存在的分组。
-- ON DELETE RESTRICT：分组下仍有员工时禁止删除分组，避免员工归属悬空。
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD CONSTRAINT `fk_users_biz_group`
  FOREIGN KEY (`biz_group_id`) REFERENCES `biz_group` (`id`)
  ON DELETE RESTRICT ON UPDATE CASCADE;

-- biz_group.final_handler_group_id → handler_groups.id（ 最终处理小组必选、绑定关系）
-- RESTRICT：小组仍被分组绑定时禁止删除（删除走软删除，见 handler_groups.deleted）
ALTER TABLE `biz_group`
  ADD CONSTRAINT `fk_biz_group_final_handler`
  FOREIGN KEY (`final_handler_group_id`) REFERENCES `handler_groups` (`id`)
  ON DELETE RESTRICT ON UPDATE CASCADE;

-- biz_group_approver 外键
--   分组删除时级联清理其审批节点（审批节点脱离分组无意义）
ALTER TABLE `biz_group_approver`
  ADD CONSTRAINT `fk_bga_biz_group`
  FOREIGN KEY (`biz_group_id`) REFERENCES `biz_group` (`id`)
  ON DELETE CASCADE ON UPDATE CASCADE;

--   审批人被引用时禁止删除用户（历史配置需可追溯； 生成快照时按「离职自动兜底」处理）
ALTER TABLE `biz_group_approver`
  ADD CONSTRAINT `fk_bga_approver`
  FOREIGN KEY (`approver_id`) REFERENCES `users` (`id`)
  ON DELETE RESTRICT ON UPDATE CASCADE;

-- handler_group_member 外键
ALTER TABLE `handler_group_member`
  ADD CONSTRAINT `fk_hgm_group`
  FOREIGN KEY (`group_id`) REFERENCES `handler_groups` (`id`)
  ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE `handler_group_member`
  ADD CONSTRAINT `fk_hgm_user`
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
  ON DELETE CASCADE ON UPDATE CASCADE;
