-- =====================================================================
--  · ：系统参数体系重构（ / 四 / 五）
-- V28__phase18_system_config_new_params.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：
--   三、邮件 SMTP 配置（6 项）：服务器 / 端口 / 账号 / 授权码 / 发件人名称 / SSL
--   四、短信配置（5 项，标注「暂未启用」）：服务商 / AK ID / AK Secret / 签名 / 模板
--   五、文件存储与保留天数（3 项）：附件目录 / 已删工单附件保留 / 导出文件保留
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **只新增行，不动既有行**。
--     分组与显示顺序由 Java 侧的 SystemConfigCatalog 决定（代码即事实源），
--     既有行的 config_group 是历史值（lock/approval/log/maintenance…），
--     它们仍然参与 ConfigRules 的校验与缓存读取，因此**一律不改**；
--     改它们只会制造一次无收益的全表写入与潜在回归。
--     新行的 config_group 取与目录一致的新编码（mail / sms / storage），
--     便于运维直接在库里按分组筛选。
--
--  B. **敏感项（smtp_password / sms_access_key_secret）的初值必须是空串，绝不能预置**。
--     这两项在 Java 侧加密落库（SecretCipher，用途分别 smtp-auth-code /
--     sms-access-key-secret）。若在此处写任何明文初值，就会在库里留下一条
--     「未加密的凭据」—— 而 SecretCipher 对无 ENC1: 前缀的值会按明文使用并告警，
--     等于给系统留了一个永久的口子。
--
--  C. **「暂未启用」不落库**。
--     短信卡片上的「暂未启用」角标由 SystemConfigCatalog 的 badge 字段提供，
--     不写进 config_desc —— 它是一句界面提示，会随接入网关而删除，
--     写进库里反而要在上线时再改一次数据。
--
--  D. **端口默认 465 而不是 25 / 587**。
--     465 是隐式 SSL（smtp_ssl 默认 1），主流邮箱服务商（QQ / 企业微信 / 阿里企业邮箱）
--     都支持且推荐；25 在多数内网被运营商与云厂商封禁，587 需要 STARTTLS，
--     与「SSL 开关」这一组配置语义不匹配（见 MailSettings 的参考说明）。
--
--  E. **editable 一律 1**：与 V26 同理，「谁可以改」是按**调用者**判定的权限
--     （见 SystemConfigServiceImpl#isAdminOnlyKey：凭据类与验证渠道一样，
--     仅内置超管可改），不是按参数判定的；置 0 会连内置超管一起挡掉。
--
--  F. **保留天数的下限由 Java 侧兜底**（ConfigRules：1 ~ 3650 天）。
--     MySQL 无法在此表达「≥1」的中文提示，因此这里只落初值。
--
--  G. **回滚方式**（删除本迁移新增的 14 行即可，代码侧全部有内置默认值）：
--     DELETE FROM system_config WHERE config_key IN
--       ('smtp_host','smtp_port','smtp_username','smtp_password','smtp_from_name','smtp_ssl',
--        'sms_provider','sms_access_key_id','sms_access_key_secret','sms_sign_name',
--        'sms_template_code',
--        'storage_attachment_path','attachment_retention_days','export_retention_days');
--     回滚后：邮件项缺失 ⇒ mailSettings() 回落空值 ⇒ complete()=false ⇒ 验证码退回日志输出；
--     存储项缺失 ⇒ 附件目录回落 app.attachment.storage-root、保留天数回落 30 / 7。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 邮件 SMTP（）
-- ---------------------------------------------------------------------
-- 幂等写法沿用 V16 / V22 / V23 / V24 / V25 / V26 的
-- `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`：
-- 库重建或手工修复后重复执行不会撞 uk_system_config_key。
-- 注：`FROM DUAL` 不可省 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，缺了会报 1064。

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_host', '', 'mail',
       'SMTP 服务器地址，例如 QQ 邮箱 smtp.qq.com、企业微信 smtp.exmail.qq.com。留空表示暂不使用邮件发码。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_host');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_port', '465', 'mail',
       'SMTP 端口，默认 465（隐式 SSL）。企业内网自建中继请填对方提供的端口。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_port');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_username', '', 'mail',
       '发件邮箱账号，同时作为 SMTP 认证用户与发件地址，请填完整邮箱地址。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_username');

-- 授权码：初值必须为空（见设计要点 B），写入时由服务端加密为 ENC1: 开头的密文
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_password', '', 'mail',
       'SMTP 授权码（加密存储）。多数邮箱不是登录密码，而是后台生成的授权码；留空表示不修改。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_password');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_from_name', '设备借用工单系统', 'mail',
       '发件人显示名，收件人看到的发件人名字。留空则回落「设备借用工单系统」。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_from_name');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'smtp_ssl', '1', 'mail',
       '是否启用 SSL 加密。465 端口需要开启；用 587 端口的服务商请先确认是否支持隐式 SSL。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'smtp_ssl');

-- ---------------------------------------------------------------------
-- 2. 短信通道（，预留）
-- ---------------------------------------------------------------------
-- 「暂未启用」角标由目录提供，不落库（见设计要点 C）。

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_provider', '', 'sms',
       '短信服务商：ALIYUN / TENCENT / HUAWEI / OTHER。当前短信通道未接入，保存不影响现有行为。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_provider');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_access_key_id', '', 'sms',
       '短信服务商 AccessKey ID（预留）。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_access_key_id');

-- AccessKey Secret：初值必须为空（见设计要点 B）
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_access_key_secret', '', 'sms',
       '短信服务商 AccessKey Secret（加密存储，预留）。留空表示不修改。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_access_key_secret');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_sign_name', '', 'sms',
       '短信签名（预留）。需在服务商后台完成备案。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_sign_name');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_template_code', '', 'sms',
       '验证码短信模板编号（预留）。不同服务商格式不同。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_template_code');

-- ---------------------------------------------------------------------
-- 3. 文件存储与保留天数（）
-- ---------------------------------------------------------------------

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'storage_attachment_path', '', 'storage',
       '附件存储根目录，可填 NAS 挂载路径（如 /mnt/nas/attachments）。留空则沿用部署配置 app.attachment.storage-root。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'storage_attachment_path');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'attachment_retention_days', '30', 'storage',
       '已删除工单的附件保留天数，超期由清理任务物理删除。这是误删恢复的最后机会，不宜过短。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'attachment_retention_days');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'export_retention_days', '7', 'storage',
       '导出 Excel 临时文件保留天数，超期自动清理。导出文件可随时重新生成，无需长期留存。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'export_retention_days');
