-- =====================================================================
-- V59 批次 3.5 · 内置类型「表单完全放开」（D3）：故障记录的自定义字段
--
-- 【需求依据】D3「表单、审批流程完全放开可自定义」+ Q1=①（第 7 项只剩表层放开）。
--   改造前借用 / 报修的表单是提交页硬编码页面，form_template_version_id 恒为 NULL；
--   本批给两者播下真实表单模板（见 BuiltInApplyCatalog#formSchemaOf），
--   管理员新增的字段需要地方存 —— 借用单复用既有的 order_form_data，
--   而 device_fault 原先没有任何表单列，故在此补两列。
--
-- 【为什么两列而不是一列】
--   form_template_version_id 是**快照指针**：管理员之后换绑表单版本，
--   历史故障记录仍要按「当初那一版」回显，否则改一次配置所有历史记录的样子都变了。
--   form_data_json 只存**新增字段**的值；系统字段（设备 / 时间 / 描述 / 分部）
--   仍在各自专用列上，两处各司其职，不互相覆盖。
--
-- 【为什么允许为空】
--   存量故障记录没有自定义字段；借用单之外的登记路径（归还时自动生成）也不填。
--   空值语义 = 「没有自定义字段」，查询与导出按此判空即可。
--
-- 【停机升级】本脚本为结构变更（ADD COLUMN），只能停机升级。
--
-- 【回滚】deploy/scripts/rollback/V59__rollback_fault_form_data.sql
-- =====================================================================

SET @has_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device_fault' AND column_name = 'form_template_version_id');
SET @sql = IF(@has_version = 0,
    'ALTER TABLE `device_fault` ADD COLUMN `form_template_version_id` BIGINT DEFAULT NULL COMMENT ''填写时的表单模板版本（快照指针，V59）'' AFTER `replaced_parts`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_data = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device_fault' AND column_name = 'form_data_json');
SET @sql = IF(@has_data = 0,
    'ALTER TABLE `device_fault` ADD COLUMN `form_data_json` TEXT DEFAULT NULL COMMENT ''自定义字段值 JSON（V59；系统字段在专用列上）'' AFTER `form_template_version_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
