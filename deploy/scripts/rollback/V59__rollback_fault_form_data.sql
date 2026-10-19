-- =====================================================================
-- V59 回滚：删除 device_fault 的两列（form_template_version_id / form_data_json）
--
-- 【执行前必读】
--   1. 这是**结构回滚**，会丢掉本批之后产生的自定义字段值。执行前先确认
--      「管理员确实没有给报修表单加字段」，或已导出需要保留的数据。
--   2. 与升级一样只能停机执行（DDL 会自动提交，跑在事务里也回不去）。
--   3. 回滚后代码必须同步回退到不含 formData 读写的版本，否则 MyBatis
--      会因找不到列而报错（表现是所有故障查询 500）。
--
-- 【为什么直接 DROP 而不是「先备份列”】
--   备份列要新建列，本身还是 DDL；真需要留数据，请在执行前用
--   SELECT ... INTO OUTFILE 或 mysqldump 单表导出，不要靠改这个脚本。
-- =====================================================================

SET @has_data = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device_fault' AND column_name = 'form_data_json');
SET @sql = IF(@has_data = 1,
    'ALTER TABLE `device_fault` DROP COLUMN `form_data_json`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device_fault' AND column_name = 'form_template_version_id');
SET @sql = IF(@has_version = 1,
    'ALTER TABLE `device_fault` DROP COLUMN `form_template_version_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
