-- =====================================================================
-- V56 批次 3 核心：型号字典 + 借用单改「分类 + 型号」 + 移除临时锁
--
-- 【需求依据】
--   第 9 项：申请人**不挑具体资产**，表单只存「一级分类 + 二级分类 + 型号（可空）」，
--           不绑资产 ID；**绑定与锁定延后到 IT 扫码录入资产编号时执行**；原 5 分钟
--           设备临时锁定**直接移除**。
--   R3：借用表单「型号」下拉改读**型号字典**，保存 model_id；IT 扫码校验比对 model_id
--       （不再做大小写/写法敏感的字符串比对）。
--
-- 【本次做四件事】
--   一、新建 device_model 型号字典表（model_name 唯一，可停用）
--   二、device 增加 model_id 关联字典；**原自由文本 model 保留不删**
--       —— model_id IS NULL 即「待整理」，原始信息可回溯、迁移可回滚
--   三、存量 device.model 文本**去重导入**字典并回填 model_id
--   四、borrow_order 增加分类/型号三列 + device_id 改为可空（提交时无设备）
--   五、删除临时锁三件套（locked_by / locked_at / lock_token）与超时索引
--
-- 【为什么 device_id 允许为空】
--   设备绑定延后到交付扫码，提交瞬间确实没有设备。代码侧早已容忍 device_id 为空
--   （自定义申请工单就是 device_id = NULL，读侧普遍有空判），因此本次只是把
--   「数据层的事实」对齐到「业务层的新流程」，不需要大改读取路径。
--   外键 fk_orders_device 保留：NULL 值不参与外键校验，绑定后仍是强引用。
--
-- 【为什么删临时锁而不保留「不用」】
--   锁的意义是「填表期间占住设备」。改造后申请人**不再选设备**，锁没有持有者，
--   留着只会让后来读代码的人以为还有锁机制。残留的 LOCKED 设备先放回 AVAILABLE。
--   注意：DeviceStatus.LOCKED **枚举保留**（历史数据可能仍是该值、且状态机文档引用），
--   只是不再产生新的 LOCKED。
--
-- 【system_config.lock_timeout_minutes 保留不删】
--   system_config 有内存缓存，迁移直接 DELETE 会造成「库里没了、缓存里还在」的
--   短暂不一致；该配置项已无人读取，留着无害。停机升级重启后自然失效。
--
-- 【回滚】deploy/scripts/rollback/V56__rollback_device_model_and_borrow_category.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、型号字典表
--
-- status：ENABLED / DISABLED。停用后前端下拉不再展示，但**旧资产与历史工单
-- 仍保留关联**（停用不是删除，字典行永不物理删除）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `device_model` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `model_name` VARCHAR(128) NOT NULL                COMMENT '型号名称（唯一，大小写不敏感）',
  `brand`      VARCHAR(64)           DEFAULT NULL   COMMENT '品牌（可选）',
  `status`     VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 启用 / DISABLED 停用',
  `remark`     VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_model_name` (`model_name`),
  KEY `idx_device_model_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备型号字典（R3）';

-- ---------------------------------------------------------------------
-- 二、device 增加 model_id（原 model 文本列**保留**）
--
-- 刻意不加外键：型号字典允许停用，将来若要「清理」字典行也不该被台账挡住；
-- 悬空 id 在展示层回落为「型号已删除」。
-- ---------------------------------------------------------------------
SET @has_model_id = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'model_id');
SET @sql = IF(@has_model_id = 0,
    'ALTER TABLE `device` ADD COLUMN `model_id` BIGINT DEFAULT NULL COMMENT ''型号字典 device_model.id；NULL 表示待整理（原文见 model 列）'' AFTER `model`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_model_idx = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'device' AND index_name = 'idx_device_model');
SET @sql = IF(@has_model_idx = 0,
    'ALTER TABLE `device` ADD KEY `idx_device_model` (`model_id`)',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 三、存量文本去重导入字典
--
-- 按 LOWER(TRIM(model)) 分组：utf8mb4_general_ci 下唯一索引本就大小写不敏感，
-- 不分组会因「ThinkPad X1 / thinkpad x1」撞 uk_device_model_name 而整段失败。
-- 代表写法取 MIN() —— 大小写/写法无法自动判断谁「正确」，宁可留一个可用值，
-- 由资产管理员在型号管理页统一整理（原始文本仍在 device.model 里）。
-- 使用 INSERT IGNORE：任何未预料的重复都不会让迁移中断。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `device_model` (`model_name`, `status`, `created_at`, `updated_at`)
SELECT MIN(TRIM(d.`model`)), 'ENABLED', NOW(), NOW()
FROM `device` d
WHERE d.`model` IS NOT NULL AND TRIM(d.`model`) <> ''
GROUP BY LOWER(TRIM(d.`model`));

-- 回填 model_id（按大小写不敏感匹配；已回填过的不再动，保证幂等）
UPDATE `device` d
JOIN `device_model` m ON LOWER(m.`model_name`) = LOWER(TRIM(d.`model`))
SET d.`model_id` = m.`id`
WHERE d.`model` IS NOT NULL AND TRIM(d.`model`) <> '' AND d.`model_id` IS NULL;

-- ---------------------------------------------------------------------
-- 四、borrow_order：分类 + 型号三列，device_id 改可空
-- ---------------------------------------------------------------------
SET @has_pc = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND column_name = 'primary_category_id');
SET @sql = IF(@has_pc = 0,
    'ALTER TABLE `borrow_order` ADD COLUMN `primary_category_id` BIGINT DEFAULT NULL COMMENT ''申请一级分类 device_category.id（V56 起提交必填）'' AFTER `device_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_sc = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND column_name = 'secondary_category_id');
SET @sql = IF(@has_sc = 0,
    'ALTER TABLE `borrow_order` ADD COLUMN `secondary_category_id` BIGINT DEFAULT NULL COMMENT ''申请二级分类 device_category.id（可空，须归属所选一级分类）'' AFTER `primary_category_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_om = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND column_name = 'model_id');
SET @sql = IF(@has_om = 0,
    'ALTER TABLE `borrow_order` ADD COLUMN `model_id` BIGINT DEFAULT NULL COMMENT ''申请型号 device_model.id（可空；交付扫码时比对）'' AFTER `secondary_category_id`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- device_id 改可空：提交时无设备，交付扫码时才绑定。
-- 存量行全部有值，改可空不影响既有数据。
ALTER TABLE `borrow_order`
  MODIFY COLUMN `device_id` BIGINT DEFAULT NULL
    COMMENT '绑定设备 device.id（V56 起提交时为空，IT 交付扫码绑定时写入）';

SET @has_bind_idx = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'borrow_order' AND index_name = 'idx_borrow_pending_bind');
SET @sql = IF(@has_bind_idx = 0,
    'ALTER TABLE `borrow_order` ADD KEY `idx_borrow_pending_bind` (`status`, `device_id`)',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 五、移除临时锁
--
-- 5.1 残留 LOCKED 设备先放回 AVAILABLE —— 删列之后再想找「谁被锁着」就没依据了。
-- 5.2 删超时索引与三件套列（用 information_schema 判定，重复执行不报错）。
-- ---------------------------------------------------------------------
UPDATE `device` SET `status` = 'AVAILABLE' WHERE `status` = 'LOCKED';

SET @has_lock_idx = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'device' AND index_name = 'idx_device_locked_at');
SET @sql = IF(@has_lock_idx > 0,
    'ALTER TABLE `device` DROP INDEX `idx_device_locked_at`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @has_token = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'lock_token');
SET @sql = IF(@has_token > 0,
    'ALTER TABLE `device` DROP COLUMN `lock_token`, DROP COLUMN `locked_at`, DROP COLUMN `locked_by`',
    'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
