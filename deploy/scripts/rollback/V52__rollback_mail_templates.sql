-- =====================================================================
-- V52 回滚 · 撤销「邮件模板个性化」的参数行
-- ⚠️ 放在 deploy/scripts/rollback/ 而不是 db/migration/
--    —— 放进迁移目录会被 Flyway 当成新版本立即执行。
--
-- 用法：确认要回滚 V52 时，手工执行本脚本；
--       然后（若也要让 Flyway 重跑 V52）把 flyway_schema_history 里
--       version='52' 的那行 status 改成 'FAILED'（或删除该行）——
--       ⚠️ 不要直接删，先备份该表。
--
-- 说明：**列宽不回收**（config_value 保持 TEXT）。
--       收回 VARCHAR(512) 会**静默截断**已经保存的长模板 —— 截断不报错，
--       是最难察觉的数据损坏。多出来的空间没有任何副作用。
-- =====================================================================

-- 删配置行：用 DELETE 而非 TRUNCATE（保留自增、且这里是按 key 精确删除）
DELETE FROM `system_config`
 WHERE `config_key` IN (
     'mail_subject_verify',
     'mail_body_verify',
     'mail_subject_alert',
     'mail_body_alert'
 );

-- 回滚后行为：邮件主题与正文回到代码里的默认文案（SystemConfigMailService 的
-- 「模板为空 ⇒ 回落默认」分支），与 V52 之前完全一致。
