-- =====================================================================
-- 回滚 V56：型号字典 + 借用单分类/型号 + 恢复临时锁
--
-- ⚠️ 三条不可逆性，执行前必须读：
--   1. **临时锁的实时状态无法恢复** —— 原 locked_by / locked_at / lock_token 已删，
--      这里只能把三列按空值重建（设备状态在 V56 里已被统一放回 AVAILABLE）。
--      回滚后不会有任何设备处于 LOCKED，属于**可接受降级**（锁本就是短时占用）。
--   2. **borrow_order.device_id 恢复 NOT NULL 前，必须先处理空值行** ——
--      V56 之后提交的借用单没有设备。请先把这些单绑定设备或作废，否则本脚本
--      在 MODIFY 那一步会因「NULL 值不能写入 NOT NULL 列」而失败（脚本会先自检并报错）。
--   3. **device_model 表会被 DROP，字典数据丢失** —— 但 device.model 原文一直保留，
--      重新执行 V56 可再次从文本重建字典。
--
-- 用法：仅在需要回退到 V55 时手工执行（不要放进 db/migration 目录）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 自检：确认没有「无设备的借用单」挡路
-- ---------------------------------------------------------------------
SET @pending_null = (SELECT COUNT(*) FROM `borrow_order` WHERE `device_id` IS NULL);
SET @sql = IF(@pending_null = 0,
    'SELECT ''OK: 没有无设备的借用单，可以回滚'' AS check_result',
    CONCAT('SELECT ''BLOCKED: 存在 ', @pending_null,
           ' 条无设备的借用单，请先绑定设备或作废后再回滚'' AS check_result'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 若上一步输出 BLOCKED，请中断执行；以下语句会失败并整体回滚（DDL 自动提交，请分步人工确认）。

-- ---------------------------------------------------------------------
-- 1. 恢复 device_id NOT NULL
-- ---------------------------------------------------------------------
ALTER TABLE `borrow_order`
  MODIFY COLUMN `device_id` BIGINT NOT NULL COMMENT '申请设备 device.id';

-- ---------------------------------------------------------------------
-- 2. 删除借用单的分类/型号三列与索引
-- ---------------------------------------------------------------------
SET @has_bind_idx = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND index_name = 'idx_borrow_pending_bind');
SET @sql = IF(@has_bind_idx > 0, 'ALTER TABLE `borrow_order` DROP INDEX `idx_borrow_pending_bind`', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_col = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND column_name = 'model_id');
SET @sql = IF(@has_col > 0,
    'ALTER TABLE `borrow_order` DROP COLUMN `model_id`, DROP COLUMN `secondary_category_id`, DROP COLUMN `primary_category_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 3. 恢复 device 临时锁三件套（值一律为空 —— 原状态不可恢复）
-- ---------------------------------------------------------------------
SET @has_token = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'lock_token');
SET @sql = IF(@has_token = 0,
    'ALTER TABLE `device`
       ADD COLUMN `locked_by`  BIGINT      DEFAULT NULL COMMENT ''临时锁定人 user_id'' AFTER `status`,
       ADD COLUMN `locked_at`  DATETIME    DEFAULT NULL COMMENT ''临时锁定开始时间''         AFTER `locked_by`,
       ADD COLUMN `lock_token` VARCHAR(64) DEFAULT NULL COMMENT ''临时锁令牌''               AFTER `locked_at`,
       ADD KEY `idx_device_locked_at` (`locked_at`)',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 4. 删除 device.model_id 与索引；随后丢弃型号字典表
--    （device.model 原文本列自始未动，无需回填）
-- ---------------------------------------------------------------------
SET @has_model_idx = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'device' AND index_name = 'idx_device_model');
SET @sql = IF(@has_model_idx > 0, 'ALTER TABLE `device` DROP INDEX `idx_device_model`', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_model_id = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'model_id');
SET @sql = IF(@has_model_id > 0, 'ALTER TABLE `device` DROP COLUMN `model_id`', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

DROP TABLE IF EXISTS `device_model`;
