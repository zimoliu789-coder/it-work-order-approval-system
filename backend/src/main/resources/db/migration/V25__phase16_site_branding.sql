-- =====================================================================
--  · 上线前（系统名称与 logo 可配置）
-- V25__phase16_site_branding.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：
--   ① 系统参数新增两项 —— system.site-name（默认「设备借用工单系统」）、
--      system.site-logo（默认「IT」文字图标）；
--   ② 复用既有 system_config 表，**不新建表、不加列**。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **为什么复用 system_config 而不新建表**。
--     这两项就是「可按配置读写的标量字符串」，与 lock_timeout_minutes 同构；
--     新建表只会带来第二套读写路径（缓存、审计、参数页分组渲染都要各写一份）。
--     system_config 的 config_value 是 VARCHAR(512)，足以容纳系统名称，
--     也足以容纳「FILE:<落盘文件名>」形式的 logo 引用。
--
--  B. **为什么值里用 `FILE:` 前缀而不是直接存文件名**。
--     logo 有两种形态：文字图标（如 IT）与上传的图片。若直接存文件名，
--     「图片」与「恰好长得像文件名的文字 logo」在读取侧无法区分。
--     加前缀后，读取侧只需一条规则：以 `FILE:` 开头 = 图片，其余 = 文字。
--     前缀也让运维在库里一眼能看出当前用的是哪种形态。
--
--  C. **为什么 editable 仍写 1（而不是 0）**。
--     `editable=0` 会让 `updateValues` 对**所有人**（含 administrator）拒绝写入。
--     本需求的规则是「只有内置超管能改，其他超管 / admin 只读置灰」——
--     这是**按调用者**区分的权限，不是按参数区分的。
--     因此 DB 层保持可写，把「谁可以写」交给服务层按当前登录者判定
--     （见 SystemConfigServiceImpl#updateValues 与 listForAdmin）。
--
--  D. **为什么用 `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`（照 V16/V22/V23/V24 的幂等模式）**。
--     迁移可能被重复执行（库重建、手工修复）；普通 INSERT 会撞 uk_system_config_key
--     唯一键导致整个迁移失败、库停在半途。
--     注：`FROM DUAL` 不是装饰 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，
--     缺了它会在解析阶段报 1064。
--
--  E. **为什么 config_group 取新值 `site`**。
--     参数页按 config_group 聚合渲染分组标题（前端 GROUP_LABELS 映射中文名）。
--     放进 common 会让「品牌外观」与「借用期限」混在一张表里；
--     独立成组后，参数页会多出一个「站点品牌」分组，定位直观。
--
--  F. **回滚方式**。删除这两行即可（`DELETE FROM system_config WHERE config_key LIKE 'system.site-%'`），
--     代码侧 siteInfo 接口会回落到内置默认值（设备借用工单系统 / IT），不会 500。
-- =====================================================================

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'system.site-name', '设备借用工单系统', 'site',
       '系统名称：显示在侧边栏顶部、登录页标题与浏览器标签页。留空则使用默认名称。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'system.site-name');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'system.site-logo', 'IT', 'site',
       '系统 logo：填文字（如 IT）显示为文字图标；上传图片后自动变为图片（png / jpg，10MB 内）。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'system.site-logo');
