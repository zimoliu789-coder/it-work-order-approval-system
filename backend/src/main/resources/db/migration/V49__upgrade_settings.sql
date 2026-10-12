-- =====================================================================
-- V49__upgrade_settings.sql
--
-- 在线升级开关落库（取代环境变量 UPGRADE_ENABLED）
--
-- <h2>它解决什么问题</h2>
-- 改造前「能不能在线升级」读的是环境变量，于是每次开关都要 SSH 改 .env 再重建容器。
-- 对一个「页面上点一下」的动作来说，这个代价明显不成比例，而且改错的表现是
-- 「功能用不了」—— 排查时还得回头看 .env 到底有没有生效。
--
-- 现在开关落在 system_config，与短信 / 邮箱 / 备份等开关同一套机制：
-- 页面点一下即刻生效，不需要动 .env、不需要重建容器。
--
-- <h2>为什么默认值是 0（关闭）</h2>
-- 在线升级能替换服务器上的可执行文件，等价于代码执行能力。
-- 与 backup_enabled（默认关闭）同一取向：**会改变系统运行形态的能力，默认都不开启。**
-- 装完 ≠ 该打开；要用时到「系统参数 → 在线升级」里打开。
--
-- <h2>为什么只加一个键</h2>
-- 不加 upgrade_apply_mode（agent / script / manual）：执行方式不是一个用户偏好，
-- 而是一个客观事实 —— 能自动执行当且仅当运维代理正在心跳。
-- 让管理员去选一个他无法验证的选项，只会制造「选了 agent 但其实没装」这类假配置。
-- 因此执行方式由后端在运行时按心跳自动判定，页面上不做选项。
--
-- <h2>键名契约</h2>
-- 与 ExceptionAlertSettings 同一约定：键名跨三处必须逐字一致 ——
-- UpgradeSettings.KEY_ENABLED、SystemConfigCatalog、本脚本。
-- =====================================================================

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'upgrade_enabled', '0', 'upgrade',
       '在线升级总开关。打开后才能上传升级包并应用；关闭时不会发起任何替换动作。本开关只管「能不能在线升级」，不影响由备份容器 / 部署脚本完成的常规发布。',
       1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'upgrade_enabled');
