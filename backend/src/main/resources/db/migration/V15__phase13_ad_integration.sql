-- =====================================================================
--  · AD 域控对接（核心功能）+ 收尾优化
-- V15__phase13_ad_integration.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-19 本轮指示）：
--   一.1 AD 配置管理（启用开关 / 多服务器 / 端口 / LDAPS / 证书校验 / 基础 DN /
--        绑定 DN / 绑定密码加密 / 用户过滤器 / 属性映射 / 默认角色 / 超时）
--   一.2 用户同步（增量：更新 / 创建 / 禁用，不物理删除）
--   一.4 账号来源（本地 / AD）与 AD ↔ 本地互转
--   七   Flyway 新增 V15（AD 配置表 + users 表加 auth_type 字段）
--   十.10 强制干预消息类型拆分（FORCE_OPERATION → 四个子类型）
--
-- 规范依据：
--   第 5.1 章 AD/LDAP 域控对接（可选扩展）
--   第 19 章 密码与登录安全｜第 20 章 操作审计｜第 27 章 定时任务
--
-- ---------------------------------------------------------------------
-- 变更清单
-- ---------------------------------------------------------------------
--   A. **新建** `ad_config` 表 —— AD 配置的唯一事实来源。
--
--      ⚠️ 这里刻意**新建表**而不是继续用 `system_config` 的 ldap_* 参数行，原因有二：
--        1) `system_config` 的定位是「键值型、可散落增删的**可调参数**」（限流阈值、
--           保留天数…），而 AD 配置是一组**结构化、需整体校验**的强相关字段
--           （服务器列表 + 端口 + SSL + 证书策略必须自洽）。拆成 12 个键值对后，
--           「端口是 636 但 SSL 没开」这种自相矛盾的组合在库层面不可见、也无法约束。
--        2) `system_config` 的参数会全量展示在「参数设置」页并允许逐条编辑 ——
--           把绑定密码放进去，等于给了它一个明文输入框（V2 的 `ldap_admin_password`
--           就是这么埋下的隐患）。独立成表 + 密文列可以从结构上杜绝明文落库。
--
--      `singleton_key` 恒为 1 并配唯一索引：本表**只允许存在一行**。
--      用数据库约束而不是「代码里保证只 insert 一次」，
--      是因为后者一旦被并发初始化或人工 SQL 破坏，就会出现「读哪一行取决于 MySQL
--      返回顺序」这种最难复现的配置漂移。
--
--   B. `users` 表追加 4 列：`email` / `department`（AD 同步的属性落点）、
--      `ad_object_guid`（AD 对象唯一标识，用于识别「改名后的同一个人」）、
--      `ad_synced_at`（最后一次从 AD 同步的时间，便于排查「数据为什么是旧的」）。
--      `auth_type` / `ldap_dn` 两列 V1 已建，此处不再重复添加，只补索引。
--
--      ⚠️ 关于 `auth_type`：V1 在  就预留了该列（DEFAULT 'LOCAL'），
--      因此本迁移**不新增**该列 —— 需求方描述中的「users 表加 auth_type 字段」
--      在物理层面已于 V1 完成，本次只补上查询索引（按账号来源筛选是新需求）。
--
--   C. **移除** 11 个 `ldap_*` 参数行。它们由 V2（）作为「预留位」播种，
--      内容已被 `ad_config` 完全取代：
--        - 语义重叠：ldap_url / ldap_base_dn / ldap_admin_dn / ldap_user_filter /
--          ldap_name_attr / ldap_email_attr / ldap_default_role；
--        - 口径不同：ldap_sync_interval（分钟级轮询）→ 本需求明确为「每天凌晨同步一次」；
--        - 有害项：ldap_admin_password 标注「管理界面保存时加密存储」但**实际从未实现加密**
--          —— 留着它就是一个随时可能被填进明文口令的坑。
--      删除而非保留的原因：参数页上多出 11 个「改了也不生效」的输入框，
--      运维无法分辨该用哪一套，是比没有更糟的状态。
--
--   D. 新增 2 个 AD 同步参数（`ad_sync_enabled` / `ad_sync_hour`）——
--      「定时同步（可选）：每天凌晨同步一次，可在系统参数里开关」。
--
--   E. `messages` 表数据迁移：把历史 `FORCE_OPERATION` 消息按标题映射到四个子类型。
--      映射表是**穷举且可判定**的（这些标题全部由 OrderForceOperationServiceImpl 写死），
--      因此可以在迁移里一次性收敛干净，而不是让历史数据永远停留在「无法区分」的状态。
--
-- 幂等性：本脚本由 Flyway 保证只执行一次；表/列的创建不做 IF NOT EXISTS
-- （MySQL 8 的 ALTER TABLE 不支持 IF NOT EXISTS，且 Flyway 的版本表本身就是幂等保障）。
-- =====================================================================


-- ---------------------------------------------------------------------
-- A. AD 配置表（单行）
-- ---------------------------------------------------------------------
CREATE TABLE `ad_config` (
  `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键；业务上恒为 1',

  `singleton_key`           TINYINT      NOT NULL DEFAULT 1
                            COMMENT '单行约束位：恒为 1，配合 uk_ad_config_singleton 保证本表只能有一行',

  -- ---------------- 启用开关 ----------------
  `enabled`                 TINYINT(1)   NOT NULL DEFAULT 0
                            COMMENT '是否启用 AD 认证：0 关闭（全部走本地认证）/ 1 启用',

  -- ---------------- 服务器与传输 ----------------
  `server_urls`             VARCHAR(512) NOT NULL DEFAULT ''
                            COMMENT 'AD 服务器列表，逗号分隔；可写主机名/IP，也可写完整 ldap(s):// 地址；按顺序主备轮询',
  `server_port`             INT          NOT NULL DEFAULT 389
                            COMMENT '端口：LDAP 默认 389，LDAPS 默认 636',
  `use_ssl`                 TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否使用 LDAPS（SSL/TLS）：0 否 / 1 是',
  `strict_cert`             TINYINT(1)   NOT NULL DEFAULT 1
                            COMMENT '证书校验：1 严格（生产建议）/ 0 跳过（仅用于自建 CA 未导入的内网环境，有中间人风险）',

  -- ---------------- 目录结构 ----------------
  `base_dn`                 VARCHAR(255) NOT NULL DEFAULT '' COMMENT '用户搜索基础 DN，如 DC=company,DC=com',
  `bind_dn`                 VARCHAR(255) NOT NULL DEFAULT ''
                            COMMENT '查询用服务账号 DN，如 CN=ldapquery,CN=Users,DC=company,DC=com',
  `bind_password_cipher`    VARCHAR(512)          DEFAULT NULL
                            COMMENT '绑定密码密文（AES-GCM，密钥由 JWT_SECRET 派生）；**明文绝不落库**',

  -- ---------------- 过滤器与属性映射 ----------------
  `user_filter`             VARCHAR(512) NOT NULL DEFAULT '(&(objectClass=user)(sAMAccountName={0}))'
                            COMMENT '用户搜索过滤器，{0} 为登录名占位符；全量同步时 {0} 被替换为 *',
  `attr_login`              VARCHAR(64)  NOT NULL DEFAULT 'sAMAccountName' COMMENT '登录名属性映射',
  `attr_name`               VARCHAR(64)  NOT NULL DEFAULT 'displayName'    COMMENT '姓名属性映射（为空时回退 cn）',
  `attr_email`              VARCHAR(64)  NOT NULL DEFAULT 'mail'           COMMENT '邮箱属性映射',
  `attr_dept`               VARCHAR(64)  NOT NULL DEFAULT 'department'     COMMENT '部门属性映射',
  `attr_status`             VARCHAR(64)  NOT NULL DEFAULT 'userAccountControl'
                            COMMENT '账号状态属性映射（按 AD 的 ACCOUNTDISABLE 位判定禁用）',

  -- ---------------- 新用户默认值 ----------------
  `default_role`            VARCHAR(64)  NOT NULL DEFAULT 'user'
                            COMMENT 'AD 新用户首次登录 / 同步创建时自动分配的角色编码（须在 sys_role 中存在且启用）',

  -- ---------------- 连接行为 ----------------
  `connect_timeout_seconds` INT          NOT NULL DEFAULT 5
                            COMMENT '连接与读取超时（秒）。取值刻意偏小：AD 挂掉时全站登录不能被拖死',

  -- ---------------- 执行结果留痕（同表保存，便于配置页直接展示「上次同步 / 上次测试」） ----------------
  `last_test_at`            DATETIME              DEFAULT NULL COMMENT '最近一次「测试连接」时间',
  `last_test_result`        VARCHAR(512)          DEFAULT NULL COMMENT '最近一次「测试连接」结果摘要（成功含探测到的用户数）',
  `last_sync_at`            DATETIME              DEFAULT NULL COMMENT '最近一次同步完成时间',
  `last_sync_result`        VARCHAR(512)          DEFAULT NULL COMMENT '最近一次同步结果摘要（新增/更新/禁用/失败计数）',

  `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ad_config_singleton` (`singleton_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AD 域控配置（全局单行）';

-- 播种唯一一行（id 由自增分配，业务上永远读写 id 最小的那一行）
INSERT INTO `ad_config` (`singleton_key`) VALUES (1);


-- ---------------------------------------------------------------------
-- B. users 表追加 AD 同步落点
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD COLUMN `email`        VARCHAR(128) DEFAULT NULL COMMENT '邮箱（本地手工填写或由 AD 同步写入）' AFTER `display_name`,
  ADD COLUMN `department`   VARCHAR(128) DEFAULT NULL COMMENT '部门（本地手工填写或由 AD 同步写入）' AFTER `email`,
  ADD COLUMN `ad_object_guid` VARCHAR(64) DEFAULT NULL
      COMMENT 'AD 对象唯一标识 objectGUID（十六进制字符串）：识别「改了名还是同一个人」' AFTER `ldap_dn`,
  ADD COLUMN `ad_synced_at` DATETIME     DEFAULT NULL
      COMMENT '最后一次从 AD 同步到本地的时间（LOCAL 账号恒为 NULL）' AFTER `ad_object_guid`;

-- 账号来源索引：员工管理新增「账号来源」筛选与「AD 用户」统计都要走这一列
CREATE INDEX `idx_users_auth_type` ON `users` (`auth_type`);


-- ---------------------------------------------------------------------
-- C. 移除已被 ad_config 取代的 ldap_* 参数行
-- ---------------------------------------------------------------------
DELETE FROM `system_config` WHERE `config_key` IN (
  'ldap_enabled',
  'ldap_url',
  'ldap_base_dn',
  'ldap_admin_dn',
  'ldap_admin_password',
  'ldap_user_filter',
  'ldap_name_attr',
  'ldap_email_attr',
  'ldap_sync_interval',
  'ldap_default_role',
  'ldap_group_mapping'
);


-- ---------------------------------------------------------------------
-- D. AD 定时同步参数（「可在系统参数里开关」）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`) VALUES
('ad_sync_enabled', '0', 'ad',
 'AD 定时同步开关：1 开启（每天定点自动同步域用户）/ 0 关闭（仅支持手动「立即同步」）', 1),

('ad_sync_hour', '2', 'ad',
 'AD 定时同步执行时刻（0-23 时，服务器本地时间）：默认 2 即每天凌晨 2 点', 1);


-- ---------------------------------------------------------------------
-- E. 强制干预消息类型拆分（）
--    旧 FORCE_OPERATION 用一个类型承载四类操作，消息列表无法区分「被驳回」还是「被终止」。
--    标题 → 子类型的映射是穷举的（全部由 OrderForceOperationServiceImpl 写死），
--    因此历史数据可以一次性收敛，不留「无法区分」的存量。
-- ---------------------------------------------------------------------
UPDATE `messages` SET `message_type` = 'FORCE_REJECT'
  WHERE `message_type` = 'FORCE_OPERATION' AND `title` = '工单被强制驳回';

UPDATE `messages` SET `message_type` = 'FORCE_TERMINATE'
  WHERE `message_type` = 'FORCE_OPERATION' AND `title` = '工单被强制终止';

UPDATE `messages` SET `message_type` = 'FORCE_TRANSFER_APPROVAL'
  WHERE `message_type` = 'FORCE_OPERATION'
    AND `title` IN ('审批待办被强制改派', '审批待办已被改派', '工单审批人变更');

UPDATE `messages` SET `message_type` = 'FORCE_TRANSFER_HANDLER'
  WHERE `message_type` = 'FORCE_OPERATION'
    AND `title` IN ('工单被强制转交', '工单已被强制转交', '工单执行人变更');

-- 兜底：万一存在标题不在上述清单内的历史 FORCE_OPERATION 行（例如人工插入的数据），
-- 统一归到「强制转交执行人」而不是留在旧编码上 —— 旧编码已从 MessageType 枚举移除，
-- 留着它会在消息中心渲染成英文编码，比归类不准更难看。
UPDATE `messages` SET `message_type` = 'FORCE_TRANSFER_HANDLER'
  WHERE `message_type` = 'FORCE_OPERATION';
