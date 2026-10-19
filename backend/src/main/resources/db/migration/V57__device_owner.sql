-- =====================================================================
-- V57 批次 3 · 第 10 项：设备台账「负责人」
--
-- 【需求依据】第 10 项「故障报修填报模式」的模式 1「我名下的设备」——
--   下拉只列**负责人 = 当前登录用户**的台账设备。原文的前提条件是
--   「设备台账没有负责人字段 ⇒ 模式 1 没有数据源」（阻塞项 R1 裁决 ①）：
--   新增 owner_id，允许为空，只有资产管理员可维护（复用 device:ledger:manage）。
--
-- 【负责人 ≠ 借用人】（需求明确语义边界）
--   owner_id  = 资产归属责任人，长期固定，由资产管理员维护；
--   借用人    = 从借用工单动态得到的临时人（order.applicant_id）。
--   两套字段互不冲突，不要拿「当前有没有在途借用单」来反推负责人。
--
-- 【为什么允许为空、为什么不加冗余 owner_name 列】
--   1. 允许为空：存量设备（生产约千台）没有这个信息，强行 NOT NULL 只能填一个假值，
--      反而让「未指定负责人」这件事变得不可查询。空值语义 = 未指定，只能走
--      「公用 / 其他设备」入口报修。
--   2. 不加 owner_name 冗余列：姓名会改（改名 / 纠正错字），冗余列必然出现
--      「员工档案里是新名字、台账里是旧名字」。列表展示统一走 id→name 批量解析
--      （与 OrderReferenceNames 同一套做法），不引入同步问题。
--
-- 【不加外键】
--   员工档案不做物理删除（离职是禁用 + is_dimission），外键没有实际保护价值；
--   与 device.model_id 保持同一取向，避免将来清理账号时被台账挡住。
--
-- 【回滚】deploy/scripts/rollback/V57__rollback_device_owner.sql
-- =====================================================================

SET @has_owner = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'owner_id');
SET @sql = IF(@has_owner = 0,
    'ALTER TABLE `device` ADD COLUMN `owner_id` BIGINT DEFAULT NULL COMMENT ''负责人 user_id（资产归属责任人，长期固定；≠借用人）；NULL 表示未指定'' AFTER `amount`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_owner_idx = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'device' AND index_name = 'idx_device_owner');
SET @sql = IF(@has_owner_idx = 0,
    'ALTER TABLE `device` ADD KEY `idx_device_owner` (`owner_id`)',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
