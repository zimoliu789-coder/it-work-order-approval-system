-- =====================================================================
-- V58__attachment_limits_per_biz_type.sql
--
-- 附件单张上限按业务类型拆分（第 10 项 / R2 定稿）
--
-- 【为什么必须拆】
--   改造前只有一个部署配置 app.attachment.max-size-mb（10MB），它同时管
--   「借用申请附件」与「故障照片」。两者需求不同：
--     · 故障照片 —— 手机现场拍摄，单张常见 3~8MB，很快会顶到 10MB；
--     · 借用附件 —— 随单长期留存，是磁盘占用的大头。
--   想放宽照片就必须连带放宽附件，等于让磁盘占用随一次「照片不够大」的诉求一起涨。
--
-- 【为什么是系统参数，不是 application.yml】
--   R2 定稿要求「两个限额后台可视化可改」：现场换拍照设备后要提高上限，
--   不该要求运维改配置文件 + 重启服务。参数页「系统参数 → 附件与导出」可直接调。
--
-- 【默认值】
--   两个都是 10MB —— 与改造前行为完全一致。拆分的目的是「以后能分开调」，
--   不是「顺手放宽」；放宽要由管理员显式决定（并同步确认 Nginx / Cloudflare 放得下）。
--
-- 【键名契约】跨三处必须逐字一致，写错不会编译报错，只会让参数「改了不生效」：
--   1) StorageSettings.KEY_FAULT_PHOTO_MAX_MB / KEY_APPLY_ATTACHMENT_MAX_MB；
--   2) 本脚本的初值；
--   3) 读取方 AttachmentStorage#maxSizeMbOf（经 SystemConfigService 收敛）。
--
-- 【回滚】deploy/scripts/rollback/V58__rollback_attachment_limits.sql
-- =====================================================================

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'attachment_fault_photo_max_mb', '10', 'attach',
       '故障照片单张大小上限（MB）。调大前请确认边缘 Nginx 的 client_max_body_size 与 Cloudflare 隧道（免费版单请求 100MB）都能放得下。取值范围 1~100。',
       1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'attachment_fault_photo_max_mb');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'attachment_apply_attachment_max_mb', '10', 'attach',
       '借用申请附件单张大小上限（MB）。与故障照片分开配置：附件随单长期留存、是磁盘占用的大头。取值范围 1~100。',
       1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'attachment_apply_attachment_max_mb');
