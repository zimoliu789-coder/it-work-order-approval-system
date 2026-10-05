-- =====================================================================
-- P0/P2 安全加固 —— 参数调整（全量验收 15 项不通过中的 4/6/7/9 项）
--
-- 需求（用户原文）：
--   · 「security_ip_whitelist默认加上内网网段（127.0.0.1/8、192.168.0.0/16、10.0.0.0/8）」
--   · 「IP封禁两级：10次→30分钟、20次→24小时」
--   · 「账号锁定改成30分钟」
--   · 「异常登录通知（凌晨/新设备/非常用IP）」
--
-- 口径（写在最前面，防止后人改回去）：
--   ① 白名单**默认预置内网网段**，但只在「当前为空」时才回填 ——
--      不覆盖管理员已经自定义过的值（尊重既有配置）。
--   ② login_lock_minutes 只在「仍为旧默认 15」时才改 30 —— 同上，不覆盖人工调整。
--   ③ 新增参数按惯例**三处登记**：本迁移 + SecuritySettings（键与默认值）+
--      SystemConfigCatalog（页面渲染）+ ConfigRules（校验）。少一处不会报错，只会静默失配。
-- =====================================================================

-- ----------------------------------------------------------------------------
-- ① 内网白名单默认值回填（仅在为空时）
--
--    办公网出口 IP 常被整层楼共用，一人输错几次口令就会把整片人挡在门外 ——
--    默认放行回环与 RFC1918 私网段。管理员仍可在参数页覆盖。
-- ----------------------------------------------------------------------------
UPDATE `system_config`
   SET `config_value` = '127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16',
       `config_desc`  = 'IP 白名单，多个用英文逗号分隔，支持网段（如 192.168.1.0/24）。白名单内的 IP 永不封禁 —— 办公网出口 IP 常被多人共用，默认已预置回环与内网网段。'
 WHERE `config_key` = 'security_ip_whitelist'
   AND (`config_value` IS NULL OR TRIM(`config_value`) = '');

-- ----------------------------------------------------------------------------
-- ② 账号锁定时长 15 → 30 分钟（仅在仍为旧默认值时）
-- ----------------------------------------------------------------------------
UPDATE `system_config`
   SET `config_value` = '30'
 WHERE `config_key` = 'login_lock_minutes'
   AND TRIM(`config_value`) = '15';

-- ----------------------------------------------------------------------------
-- ③ 二级（长封）封禁参数
-- ----------------------------------------------------------------------------
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_block_long_max_count', '20', 'security',
       '二级封禁阈值：同一 IP 在失败计数窗口内累计失败达到该值时改为长时封禁（应明显大于一级阈值）。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_block_long_max_count');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_block_long_minutes', '1440', 'security',
       '二级（长时）封禁时长（分钟），默认 1440 = 24 小时，到期自动解封。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_block_long_minutes');

-- ----------------------------------------------------------------------------
-- ④ 异常登录检测开关
-- ----------------------------------------------------------------------------
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_login_anomaly_enabled', '1', 'security',
       '异常登录检测：凌晨 0-6 点登录 / 新设备登录 / 非常用 IP 登录时记录安全事件并通知用户本人；管理员账号凌晨登录额外告警超管。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_login_anomaly_enabled');
