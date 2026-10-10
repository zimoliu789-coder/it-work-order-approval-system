-- =====================================================================
-- V47 表名规范化：统一为「单数风格」
--
-- 【背景】
--   41 张表里混着两种命名风格 —— 多数已是单数（device / approval_flow /
--   system_config / ip_block …），但下面这 12 张是复数或带 sys_ 前缀：
--     users orders departments messages attachments operation_logs
--     export_tasks upgrade_tasks order_approval_nodes
--     order_extend_approval_nodes sys_role sys_role_permission
--   本脚本把它们收敛为单数规范名。
--
-- 【为什么带反引号】
--   目标名 `role` 是 MySQL 关键字（非保留字，但语义上极易与权限模型混淆），
--   统一加反引号可避免任何解析歧义，也让脚本在严格模式下同样安全。
--
-- 【RENAME 的代价】
--   RENAME TABLE 是**纯元数据操作**：不拷贝数据行、不重建索引、瞬时完成。
--   InnoDB 会自动更新外键引用，索引名保持不变（如 uk_users_email 仍是该名）、
--   自增计数器与 AUTO_INCREMENT 当前值一并保留。
--
-- 【必须与镜像同步上线】
--   本迁移与 Java 侧的表名改造（@TableName 注解 + 原生 SQL）是**同一件事的两半**。
--   镜像与迁移必须同时上线；**严禁新旧版本实例混跑** ——
--   旧实例会去访问已不存在的 users / orders 表，直接报 1146。
--
-- 【回滚】
--   见 deploy/scripts/rollback/V48__rollback_v47_rename_tables.sql。
--   该文件刻意**不放在 db/migration 目录下**：若放进来，Flyway 会在 V47 执行完之后
--   立刻把表名改回复数，本次改造等于白做。需要回滚时再把它拷进 db/migration。
--
-- 【上线前】
--   尽管 RENAME 风险极低，仍建议先做一次数据库备份（deploy/scripts/restore.sh 可回灌）。
-- =====================================================================

RENAME TABLE `users`                       TO `employee`;
RENAME TABLE `orders`                      TO `borrow_order`;
RENAME TABLE `departments`                 TO `department`;
RENAME TABLE `messages`                    TO `message`;
RENAME TABLE `attachments`                 TO `attachment`;
RENAME TABLE `operation_logs`              TO `operation_log`;
RENAME TABLE `export_tasks`                TO `export_task`;
RENAME TABLE `upgrade_tasks`               TO `upgrade_task`;
RENAME TABLE `order_approval_nodes`        TO `order_approval_node`;
RENAME TABLE `order_extend_approval_nodes` TO `order_extend_approval_node`;
RENAME TABLE `sys_role`                    TO `role`;
RENAME TABLE `sys_role_permission`         TO `role_permission`;

-- V27「登录名规范化」的临时备份表：该迁移已稳定运行，备份不再需要
DROP TABLE IF EXISTS `users_username_backup_v27`;
