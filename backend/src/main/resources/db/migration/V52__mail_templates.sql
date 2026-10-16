-- =====================================================================
-- V52 · 邮件模板个性化（验证码邮件 + 运维告警邮件）
-- 企业内部设备借用工单系统
--
-- 背景：邮件主题与正文此前**写死在代码里**（SystemConfigMailService 与 AlertService
--       各自拼字符串），管理员想改一句措辞都得改代码重新部署。
--       本次把它们抽成系统参数模板，配置页可改、可预览、可「发送测试邮件」看效果。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **初值一律为空串**，不预置默认文案。
--     默认文案保留在代码里（MailSettings.DEFAULT_*），服务端渲染时按
--     「模板为空 ⇒ 回落默认」处理。这样做的好处：
--       1) 升级后行为与升级前**逐字一致**（零回归）；
--       2) 默认文案只有一份（在代码里），不会出现「库里一份、代码一份，改了不同步」；
--       3) 管理员清空输入框即可「恢复默认」，不需要知道默认值是什么。
--     配套：配置页把 defaultValue 作为输入框的默认提示，管理员能看见当前生效的文案。
--
--  B. **必须先把 config_value 放宽到 TEXT**。
--     原列是 VARCHAR(512) —— 正文模板很容易超过（几行中文就 200+ 字符，
--     加上签名与说明就更长）。若不改列，保存超长模板会在库里被截断或直接报错，
--     而截断**不报错**，是最难察觉的一种数据损坏。
--     ALTER 是幂等的（列本来已是 TEXT 时再执行也无害），且只在本次迁移执行一次。
--
--  C. **只新增行，不动既有行**。既有 config_group 是历史值，仍参与缓存读取与校验，
--     改它们只会制造一次无收益的全表写入与潜在回归。
--     新行的 config_group 取 'mail'，便于运维在库里按分组筛选。
--
--  D. 分组与显示顺序由 Java 侧 SystemConfigCatalog 决定（代码即事实源）——
--     本脚本只负责「让这些键存在且可编辑」，界面长什么样由 catalog 的
--     「通知与验证 → 邮件模板」分区决定。
--
-- 回滚：见 deploy/scripts/rollback/V52__rollback_mail_templates.sql
--       （删 4 行配置；列宽不回收 —— 收回 VARCHAR(512) 会截断已保存的长模板）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 放宽 config_value：邮件正文模板会超过 VARCHAR(512)
-- ---------------------------------------------------------------------
ALTER TABLE `system_config`
    MODIFY COLUMN `config_value` TEXT DEFAULT NULL COMMENT '配置项值（统一按字符串存储，业务侧按类型解析）';

-- ---------------------------------------------------------------------
-- 2. 四个邮件模板参数（初值空串 ⇒ 回落代码里的默认文案）
--    占位符见 MailSettings.PLACEHOLDER_* 与配置页说明
-- ---------------------------------------------------------------------

-- 验证码邮件：主题 / 正文（可用 {siteName} {code} {minutes}）
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'mail_subject_verify', '', 'mail',
       '验证码邮件主题模板。留空使用默认「【{siteName}】邮箱验证码」。可用占位符：{siteName}', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'mail_subject_verify');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'mail_body_verify', '', 'mail',
       '验证码邮件正文模板。留空使用系统默认文案。可用占位符：{siteName} {code} {minutes}', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'mail_body_verify');

-- 运维告警邮件：主题 / 正文（可用 {siteName} {level} {title} {body} {time}）
-- 注意：发给普通员工的业务通知邮件**不套**这个模板（受众与语义不同）
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'mail_subject_alert', '', 'mail',
       '运维告警邮件主题模板。留空使用默认「【{siteName}】[{level}] {title}」。可用占位符：{siteName} {level} {title}', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'mail_subject_alert');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'mail_body_alert', '', 'mail',
       '运维告警邮件正文模板。留空使用系统默认文案。可用占位符：{siteName} {level} {title} {body} {time}', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'mail_body_alert');
