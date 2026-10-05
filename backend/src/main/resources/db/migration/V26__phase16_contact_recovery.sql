-- =====================================================================
--  · 上线前需求（二 / 三 / 四 / 五）：找回密码 · 联系方式 · 验证开关
-- V26__phase16_contact_recovery.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：
--   ① users 新增 phone，email 补唯一索引 ⇒「一个手机号 / 邮箱只能绑一个账号」；
--   ② system_config 新增四项：验证码长度 / 有效期 / 手机验证开关 / 邮箱验证开关。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **email 列早已存在（ AD 对接时加的），因此本迁移只补唯一索引**。
--     刻意不重复 ADD COLUMN —— 重复加列会直接让迁移失败、库停在半途。
--
--  B. **唯一索引为什么不与「AD 同步覆盖 email」冲突（风险与取舍）**。
--     需求方明确要求「一个邮箱只能绑一个账号」。AD 同步会把域控 mail 属性写进本地
--     email 列；若域里两个人配了同一个邮箱，同步会撞唯一索引。
--     取舍：唯一性是**产品硬要求**（找回密码要靠它定位唯一账号，重了就无法判定
--     该给谁发验证码），而「AD 里两个人同邮箱」本身是域侧的数据质量问题。
--     同步侧已按 best-effort 处理（失败只记日志、不影响整批），不会因一条脏数据
--     让整个同步任务中断。
--
--  C. **MySQL 唯一索引对 NULL 的语义正好是我们要的**。
--     MySQL 的 UNIQUE 允许**多行 NULL**（NULL 之间不判等），因此
--     「679 个账号里绝大多数还没绑手机 / 邮箱」不会互相冲突；
--     而一旦填了具体值，第二行填同一个值就会撞唯一键。
--     这比「先建普通索引 + 服务层预检」更可靠：服务层的预检在并发下会漏，
--     唯一索引是最后一道、且无法被绕过的事实源。
--
--  D. **验证码四项参数的键名与分组**。
--     键名沿用既有 `snake_case` 风格（与 password_min_length / jwt_expire_minutes 一致），
--     分组取既有的 `security`（前端 GROUP_LABELS 映射为「登录与安全」），
--     不新开分组 —— 这四项与登录安全同域，分到别处反而要用户在两页之间跳。
--     `editable` 一律为 1：与站点品牌同理，「谁可以改」是按**调用者**判定的权限
--     （见 SystemConfigServiceImpl#updateValues 的 ADMIN_ONLY_KEYS），
--     不是按参数判定的；把 editable 置 0 会连内置超管一起挡掉。
--
--  E. **取值范围的兜底放在 Java 侧（ConfigRules），不放在 DDL**。
--     MySQL 不提供 CHECK 之外的「区间」约束，且参数页的失败提示需要中文说明，
--     因此这里只落初值，区间校验统一由 ConfigRules 负责（单一事实源）。
--
--  F. **回滚方式**：
--     ALTER TABLE users DROP INDEX uk_users_email, DROP INDEX uk_users_phone, DROP COLUMN phone;
--     DELETE FROM system_config WHERE config_key IN
--       ('forgot_code_length','forgot_code_expire_minutes','sms_verify_enabled','email_verify_enabled');
--     代码侧四项参数均有内置默认值（6 / 5 / true / true），删掉配置行不会 500。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. users.phone
-- ---------------------------------------------------------------------
-- 长度取 20（而不是 11）：本系统面向国内手机号（11 位），但字段留出余量，
-- 便于将来存放带国际区号（+86…）的号码，避免又要改一次 DDL。
ALTER TABLE `users`
  ADD COLUMN `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号（全局唯一；NULL = 未绑定）' AFTER `email`;

-- ---------------------------------------------------------------------
-- 2. 唯一索引（NULL 可重复，见设计要点 C）
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD UNIQUE KEY `uk_users_phone` (`phone`);

ALTER TABLE `users`
  ADD UNIQUE KEY `uk_users_email` (`email`);

-- ---------------------------------------------------------------------
-- 3. 找回密码 / 验证码四项系统参数
-- ---------------------------------------------------------------------
-- 幂等写法沿用 V16 / V22 / V23 / V24 / V25 的
-- `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`：
-- 库重建或手工修复后重复执行不会撞 uk_system_config_key。
-- 注：`FROM DUAL` 不可省 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，缺了会报 1064。

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'forgot_code_length', '6', 'security',
       '找回密码验证码长度（位）。建议 4~8 位；位数越多越难被暴力猜中，但用户输入负担也越大。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'forgot_code_length');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'forgot_code_expire_minutes', '5', 'security',
       '找回密码验证码有效期（分钟）。到期后验证码自动作废，用户需重新获取。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'forgot_code_expire_minutes');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'sms_verify_enabled', '1', 'security',
       '是否启用手机验证。关闭后：找回密码与首次绑定都不允许使用手机号，只能用邮箱；两项都关闭则找回密码整体不可用。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'sms_verify_enabled');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'email_verify_enabled', '1', 'security',
       '是否启用邮箱验证。关闭后：找回密码与首次绑定都不允许使用邮箱，只能用手机号；两项都关闭则找回密码整体不可用。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'email_verify_enabled');
