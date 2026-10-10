-- =====================================================================
-- V48 回滚脚本：把 V47 改过的表名恢复为原来的复数 / sys_ 前缀形态
--
-- ⚠️【默认存放位置声明】
--   本文件**故意放在 deploy/scripts/rollback/ 下，而不是 db/migration/ 里**。
--   原因：Flyway 只扫描 classpath:db/migration。若把本文件放进该目录，
--   它会在 V47 执行完之后**立刻**把表名改回去 —— 本次规范化等于白做。
--
-- 【回滚操作步骤】
--   1) 先把应用停掉（或确保没有任何实例在跑），避免改到一半有请求打进来；
--   2) 把本文件复制为 backend/src/main/resources/db/migration/V48__rollback_v47_rename_tables.sql；
--   3) 重新打包镜像并启动，Flyway 会自动执行 V48；
--   4) 确认无误后，把 db/migration 下的 V48 文件移除（否则每次启动都会重复执行；
--      虽然 RENAME 到不存在的表会直接报错终止，但没必要留着）；
--   5) 代码侧需要同时回退到改表名之前的版本（@TableName 与原生 SQL 都要回退），
--      镜像与数据必须保持一致，不允许只回退一半。
--
-- 【无法回滚的部分】
--   V47 里 DROP 掉的 users_username_backup_v27 不会重建 —— 它是 V27 的一次性备份表，
--   业务上不再需要，因此不提供回滚。
--
-- 【执行顺序】
--   先改子表、后改父表虽无强制要求（RENAME 会自动更新外键引用），
--   这里仍按「先叶子后根」的顺序书写，便于人工逐行核对。
-- =====================================================================

RENAME TABLE `employee`                    TO `users`;
RENAME TABLE `borrow_order`                TO `orders`;
RENAME TABLE `department`                  TO `departments`;
RENAME TABLE `message`                     TO `messages`;
RENAME TABLE `attachment`                  TO `attachments`;
RENAME TABLE `operation_log`               TO `operation_logs`;
RENAME TABLE `export_task`                 TO `export_tasks`;
RENAME TABLE `upgrade_task`                TO `upgrade_tasks`;
RENAME TABLE `order_approval_node`         TO `order_approval_nodes`;
RENAME TABLE `order_extend_approval_node`  TO `order_extend_approval_nodes`;
RENAME TABLE `role`                        TO `sys_role`;
RENAME TABLE `role_permission`             TO `sys_role_permission`;
