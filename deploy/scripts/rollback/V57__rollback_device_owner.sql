-- =====================================================================
-- 回滚 V57：删除设备负责人字段
--
-- 说明：owner_id / owner_name 属于**新增台账字段**，回滚只丢「负责人」这一项数据；
-- device 其余字段、报修与借用链路均不受影响。
-- 若回滚前已按负责人做过报修筛选用途，请先导出 owner_id 映射留档。
--
-- 用法：仅在需要回退到 V56 时手工执行（不要放进 db/migration 目录）。
-- =====================================================================

SET @has_owner_idx = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'device' AND index_name = 'idx_device_owner');
SET @sql = IF(@has_owner_idx > 0, 'ALTER TABLE `device` DROP INDEX `idx_device_owner`', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_owner = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'owner_id');
SET @sql = IF(@has_owner > 0, 'ALTER TABLE `device` DROP COLUMN `owner_id`', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
