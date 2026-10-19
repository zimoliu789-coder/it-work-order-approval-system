-- =====================================================================
-- V54 回滚：删除「当前所在分部」两列（borrow_order / device_fault）
--
-- ⚠️ 回滚会**丢失**所有已提交单据上的「当前所在分部」与取值来源。
--    若只是想修正数据、不想丢字段，请勿执行本脚本。
--
-- 执行前建议先导出：
--   SELECT id, order_no, current_division_id, division_source FROM borrow_order;
--   SELECT id, device_id, current_division_id, division_source FROM device_fault;
-- =====================================================================

-- 索引与列必须分开删（MySQL 不支持在 DROP COLUMN 时顺带 DROP INDEX 的写法，
-- 虽然删列会连带删掉只依赖该列的索引，但显式删更可读、也避免不同版本行为差异）
ALTER TABLE `borrow_order` DROP INDEX `idx_current_division`;
ALTER TABLE `borrow_order`
    DROP COLUMN `division_source`,
    DROP COLUMN `current_division_id`;

ALTER TABLE `device_fault` DROP INDEX `idx_current_division`;
ALTER TABLE `device_fault`
    DROP COLUMN `division_source`,
    DROP COLUMN `current_division_id`;
