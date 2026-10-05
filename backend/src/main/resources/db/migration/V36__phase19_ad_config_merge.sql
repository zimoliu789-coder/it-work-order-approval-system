-- ============================================================================
--  —— AD 域控简化 + 合并（需求文档 五·）
-- V36__phase19_ad_config_merge.sql
-- ----------------------------------------------------------------------------
-- 本脚本做三件事：
--   ① 给 ad_config 加 sync_enabled / sync_hour / attr_phone 三列；
--   ② 把 system_config 里 ad_sync_enabled / ad_sync_hour 的**现值搬进** ad_config；
--   ③ 删除 system_config 的这两个键（连同「AD 域控」参数组一起从界面消失）。
--
-- 为什么要把「同步节奏」从 system_config 搬进 ad_config：
--   配置页要合并成「一个页面搞定 AD」，若同步开关还留在系统参数里，
--   维护人员必须跨两个页面来回切，正是要消除的操作。
--   更本质的是：**同步节奏与连接参数是同一件事的两半** ——
--   「连不上域控」时该不该继续按点定时同步，取决于连接配置本身，
--   拆成两张表后，这个判断在库层面无法表达。
--   （连接参数为什么独立成表而不是键值化，见 V15 中 ad_config 的类头注释。）
--
-- 为什么 attr_phone 也加在 ad_config：
--   要求「手机号 → telephoneNumber」的属性映射与其他属性映射同页维护。
--   属性映射原本就是 ad_config 的一整组列（attr_login/attr_name/attr_email/
--   attr_dept/attr_status），补 attr_phone 是补齐既有形状，不是新增概念。
--   落点同步扩到 users.phone（该列  找回密码时已存在），
--   使「AD 同步用户」也能直接带出手机号，省掉每人手工补录。
--
-- ⚠️ 保数据（本脚本最要紧的一步）：
--   两个 system_config 键是**已经上线的活配置** —— 现场可能已经改成
--   「每天 6 点同步」。因此必须先 UPDATE ... JOIN 回填到 ad_config，
--   再 DELETE 旧键。顺序颠倒 = 现场已调的同步节奏被静默重置为默认值。
--   回填写成 JOIN 而非硬编码：JOIN 命中不到（键不存在）时整条 UPDATE 自然是空操作，
--   因此重复执行不会把人工在 ad_config 上改过的值再覆盖一遍。
--
-- 幂等性：
--   · ADD COLUMN 以 information_schema 判定包裹（MySQL 8 的 ADD COLUMN 不支持
--     IF NOT EXISTS），重复执行不会报 Duplicate column name；
--   · 回填用 JOIN，旧键删掉后自然空转；
--   · DELETE 命中 0 行是无害的。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- ① ad_config 追加三列
-- ----------------------------------------------------------------------------

-- 每日自动同步开关（原 system_config.ad_sync_enabled）
SET @add_sync_enabled = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ad_config' AND column_name = 'sync_enabled'),
    'SELECT 1',
    'ALTER TABLE `ad_config`
       ADD COLUMN `sync_enabled` TINYINT(1) NOT NULL DEFAULT 0
       COMMENT ''是否启用每日自动同步：0 关闭（仅手动「立即同步」）/ 1 开启（）'' AFTER `connect_timeout_seconds`');
PREPARE st FROM @add_sync_enabled; EXECUTE st; DEALLOCATE PREPARE st;

-- 每日同步时刻（原 system_config.ad_sync_hour）
SET @add_sync_hour = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ad_config' AND column_name = 'sync_hour'),
    'SELECT 1',
    'ALTER TABLE `ad_config`
       ADD COLUMN `sync_hour` INT NOT NULL DEFAULT 2
       COMMENT ''每日自动同步时刻（0-23 时，服务器本地时间）：默认 2 即凌晨 2 点（）'' AFTER `sync_enabled`');
PREPARE st FROM @add_sync_hour; EXECUTE st; DEALLOCATE PREPARE st;

-- 手机号属性映射
SET @add_attr_phone = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ad_config' AND column_name = 'attr_phone'),
    'SELECT 1',
    'ALTER TABLE `ad_config`
       ADD COLUMN `attr_phone` VARCHAR(64) NOT NULL DEFAULT ''telephoneNumber''
       COMMENT ''手机号属性映射（）；为空时不同步手机号'' AFTER `attr_email`');
PREPARE st FROM @add_attr_phone; EXECUTE st; DEALLOCATE PREPARE st;


-- ----------------------------------------------------------------------------
-- ② 回填：把 system_config 的现值搬进 ad_config（**必须在 DELETE 之前**）
-- ----------------------------------------------------------------------------
-- 同步开关：库里的值可能是 '1'/'0'，也可能是 'true'/'false'（人工改过），
-- 因此按「非 0 即开」判定，避免把 'true' 误读成关闭。
UPDATE `ad_config` a
JOIN `system_config` s ON s.`config_key` = 'ad_sync_enabled'
SET a.`sync_enabled` = CASE
        WHEN LOWER(TRIM(s.`config_value`)) IN ('1', 'true', 'yes', 'on') THEN 1
        ELSE 0
    END;

-- 同步时刻：钳到 0-23，非法值回落默认 2（与 SystemConfigServiceImpl 原口径一致）
UPDATE `ad_config` a
JOIN `system_config` s ON s.`config_key` = 'ad_sync_hour'
SET a.`sync_hour` = CASE
        WHEN s.`config_value` REGEXP '^[0-9]+$'
             AND CAST(s.`config_value` AS UNSIGNED) BETWEEN 0 AND 23
        THEN CAST(s.`config_value` AS UNSIGNED)
        ELSE 2
    END;


-- ----------------------------------------------------------------------------
-- ③ 删除已被 ad_config 接管的两个 AD 参数键
-- ----------------------------------------------------------------------------
-- 「AD 域控」参数组由 SystemConfigCatalog 的整个 Group 定义，
-- 删掉这两行后该组再无成员，目录里同步删除该 Group（代码侧，同-4）。
DELETE FROM `system_config` WHERE `config_key` IN ('ad_sync_enabled', 'ad_sync_hour');
