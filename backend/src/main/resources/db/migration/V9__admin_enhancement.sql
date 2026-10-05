-- =====================================================================
-- 管理端基础功能增强 / V9__admin_enhancement.sql
-- 企业内部设备借用工单系统 —— 员工管理增强 + 超管强制干预
-- 规范依据：
--   第 5 章   用户、姓名账号与角色（姓名字段、重名约束的边界）
--   第 6 章   角色权限（super_admin 全权、admin 只读）
--   第 19 章  密码与登录安全（重置密码后强制改密）
--   第 20 章  操作日志（权限/角色变更、密码重置、异常结案属高风险同步留痕）
--   第 28 章  离职员工数据管控
-- 说明：
--   1) users.real_name 是**新增列**： 明确允许 display_name 重复
--      （「张伟」与「张伟_2」可以显示同名），因此它无法承担「姓名唯一」校验；
--      需求方对「新增 / 批量导入员工」要求姓名唯一，需要独立的、语义明确的落点。
--   2) order_force_operation 是**新增表**：规范 V1.1 未覆盖「超管强制干预」，
--      按需求方 2026-09-18 确认的方案实现（强制驳回 / 强制终止 / 强制转交审批 /
--      强制转交执行人），并要求「时间线记录」，故独立建事件表而非只写 operation_logs ——
--      工单详情时间线需要按 order_id 查业务事件，扫描全量审计日志既不合适也不可靠。
--   3) 本迁移**不修改任何既有列的定义**，只做增量加列 + 建表 + 回填，向后兼容。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、users.real_name —— 员工姓名（「姓名唯一」校验锚点）
--
-- 为什么只建**普通索引**而不建唯一索引：
--   ·  明确允许 display_name 重复（同名员工是合法场景）；
--   · 历史数据由 ~7 的演示初始化器创建，其 display_name 已存在同名可能；
--   · 若强加唯一索引，迁移会在真实数据上直接失败，且与规范冲突。
-- 因此唯一性由服务层「预检 + 捕获 DuplicateKeyException」双层保证
-- （见 UserServiceImpl#assertRealNameAvailable），与项目既有的
-- 「唯一性＝预检 + 唯一索引」约定保持同一思路，只是这里的第二层是应用层。
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD COLUMN `real_name` VARCHAR(64) DEFAULT NULL COMMENT '员工姓名（姓名唯一性校验锚点；显示名称允许重名，故二者分离）' AFTER `username`;

-- 回填：既有账号的姓名取 display_name（~7 中它是「员工姓名」），
-- display_name 为空时退回 username，保证 real_name 尽量不为空。
UPDATE `users` SET `real_name` = `display_name` WHERE `real_name` IS NULL AND `display_name` IS NOT NULL;
UPDATE `users` SET `real_name` = `username`     WHERE `real_name` IS NULL;

CREATE INDEX `idx_users_real_name` ON `users` (`real_name`);

-- ---------------------------------------------------------------------
-- 二、order_force_operation —— 超管强制干预记录（）
--
-- 需求方原文：仅 super_admin 可操作；不做「强制通过」；每笔强制操作必须
-- 「必填原因 + 二次确认 + 高危审计 + 时间线记录 + 通知相关人」。
--
-- 四种操作（operation_type）：
--   FORCE_REJECT            强制驳回     仅 PENDING_APPROVAL
--   FORCE_TERMINATE         强制终止     所有非终态；使用中的设备随之释放
--   FORCE_TRANSFER_APPROVAL 强制转交审批 仅 PENDING_APPROVAL（改当前待办节点的审批人）
--   FORCE_TRANSFER_HANDLER  强制转交执行人 仅 PENDING_DELIVERY / BORROWED / PENDING_RETURN（可跨组）
--
-- old_/new_ 前缀字段按操作类型部分为空：
--   FORCE_TRANSFER_APPROVAL 填 old_approver_id / new_approver_id，并记录 node_id；
--   FORCE_TRANSFER_HANDLER  填 old_handler_id / new_handler_id；
--   FORCE_REJECT / FORCE_TERMINATE 四个字段均为空。
-- 这样既保留了「改了什么」的完整事实，又不需要为四种操作各建一张表。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_force_operation` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`        BIGINT       NOT NULL                            COMMENT '工单 orders.id',
  `operation_type`  VARCHAR(32)  NOT NULL                            COMMENT '强制操作类型：FORCE_REJECT 强制驳回 / FORCE_TERMINATE 强制终止 / FORCE_TRANSFER_APPROVAL 强制转交审批 / FORCE_TRANSFER_HANDLER 强制转交执行人',
  `operator_id`     BIGINT       NOT NULL                            COMMENT '操作人 user_id（恒为 super_admin）',
  `reason`          VARCHAR(500) NOT NULL                            COMMENT '强制原因（需求方要求必填）',
  `old_status`      VARCHAR(32)  NOT NULL                            COMMENT '操作前工单状态（）',
  `new_status`      VARCHAR(32)  NOT NULL                            COMMENT '操作后工单状态（转交类操作前后状态相同）',
  `node_id`         BIGINT                DEFAULT NULL               COMMENT '强制转交审批时被改派的待办节点 order_approval_nodes.id；其余操作为空',
  `old_approver_id` BIGINT                DEFAULT NULL               COMMENT '强制转交审批：原审批人 user_id',
  `new_approver_id` BIGINT                DEFAULT NULL               COMMENT '强制转交审批：新审批人 user_id',
  `old_handler_id`  BIGINT                DEFAULT NULL               COMMENT '强制转交执行人：原实际执行人 user_id（未分配时为空）',
  `new_handler_id`  BIGINT                DEFAULT NULL               COMMENT '强制转交执行人：新实际执行人 user_id',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_ofo_order` (`order_id`, `id`),
  KEY `idx_ofo_operator` (`operator_id`, `created_at`),
  KEY `idx_ofo_type` (`operation_type`, `created_at`),
  CONSTRAINT `fk_ofo_order`    FOREIGN KEY (`order_id`)     REFERENCES `orders` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_ofo_operator` FOREIGN KEY (`operator_id`)  REFERENCES `users` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '超管强制干预记录（；规范 V1.1 未覆盖）';
