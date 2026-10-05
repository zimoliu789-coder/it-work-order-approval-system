-- =====================================================================
--  —— 安全攻击告警：安全事件表 + IP 封禁表 + 防护参数
--
-- 需求（用户原文）：「同一账号或同一 IP 连续登录失败 5 次，锁定 30 分钟」
--                  「是不是可以根据 IP 段做白名单」
--
-- ⚠️ 开工侦察纠正了一处前提（方案文档写错了，这里记下事实）：
--   方案 ④ 写「登录失败计数 / 账号锁定全库不存在，是从零」——**不成立**。
--   实际 `module/auth/service/LoginProtectionService` 早已实现：
--     · `recordLoginFailure` 用 Redis 计数，达 `login_fail_max_count` 即写 `ticket:login:lock:*`
--       并 TTL 到 `login_lock_minutes`（自动解封）；
--     · `AuthService.login` 第 2 步就查 `isLocked` 并抛出带剩余分钟的 ACCOUNT_LOCKED。
--   ⇒ 本批**不重建账号锁定**，也不加 `users.failed_attempts` / `locked_until` 两列 ——
--      那会造出第二份事实源（Redis 与 MySQL 各记一份失败次数），两份必然分叉，
--      而分叉的表现是「明明锁了却还能登」这种最难查的一类问题。
--
-- 本批真正缺的三件事：
--   ① **IP 封禁**（含自动解封 + 白名单放行）—— 全库不存在，从零；
--   ② **安全事件落库** —— 现在只有 operation_logs 里几行 AUDIT 文本，无法按 IP / 类型检索；
--   ③ **安全告警通知** —— 复用  的 AlertService（这正是 C2 必须先做的原因）。
-- =====================================================================

-- ----------------------------------------------------------------------------
-- ① 安全事件
--
-- 为什么与 operation_logs 分开：操作日志是**审计**（谁做了什么，用于追责），
-- 本表是**威胁视图**（谁在攻击，用于封禁与趋势判断）。检索维度完全不同 ——
-- 「最近 24 小时失败最多的 10 个 IP」这类查询在 operation_logs 上要全表扫文本。
--
-- 为什么不加外键：攻击者用的账号名**可能根本不存在**（撞库），
-- 用户也可能事后被删。安全事件必须原样保留当时的输入，不能被外键约束或级联删除带走。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `security_event` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_type`  VARCHAR(32)  NOT NULL COMMENT 'LOGIN_FAIL / ACCOUNT_LOCKED / IP_BLOCKED / IP_UNBLOCKED / PERM_ESCALATION_ATTEMPT',
  `username`    VARCHAR(64)           DEFAULT NULL COMMENT '尝试登录的账号名（可能不存在）',
  `user_id`     BIGINT                DEFAULT NULL COMMENT '匹配到的用户 id；账号不存在时为 NULL',
  `ip`          VARCHAR(64)           DEFAULT NULL COMMENT '来源 IP',
  `user_agent`  VARCHAR(255)          DEFAULT NULL COMMENT 'User-Agent（截断）',
  `detail`      VARCHAR(500)          DEFAULT NULL COMMENT '说明（如「连续失败 5 次已锁定」）',
  `occurred_at` DATETIME(3)  NOT NULL COMMENT '发生时间（毫秒精度，趋势统计依赖它）',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_sec_event_occurred` (`occurred_at`),
  KEY `idx_sec_event_type_time` (`event_type`, `occurred_at`),
  KEY `idx_sec_event_ip_time` (`ip`, `occurred_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '安全事件（登录失败 / 锁定 / 封禁，攻击排查与趋势的数据源）';

-- ----------------------------------------------------------------------------
-- ② IP 封禁
--
-- `expire_at` 为 NULL 表示**永久封禁**（只由人工解除）。自动封禁一律带过期时间 ——
-- 「自动且永久」是最危险的组合：一次误判（例如整栋办公楼共用一个出口 IP）
-- 会把一整片人永久挡在门外，而没有人会想到去查一张自己不知道存在的表。
--
-- `active` + `expire_at` 的过期判定放在服务层（懒判定），不依赖定时任务：
-- 定时任务一旦停摆，封禁就变成永久的 —— 这与上面那条风险是同一个。
-- 定时任务只做「把过期行标为失效」的清理，不是解封的必要条件。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ip_block` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ip`           VARCHAR(64)  NOT NULL COMMENT '被封禁的 IP',
  `reason`       VARCHAR(255)          DEFAULT NULL COMMENT '封禁原因（自动封禁写失败次数，人工封禁写备注）',
  `source`       VARCHAR(16)  NOT NULL COMMENT 'AUTO 自动 / MANUAL 人工',
  `fail_count`   INT          NOT NULL DEFAULT 0 COMMENT '触发时的连续失败次数',
  `blocked_at`   DATETIME(3)  NOT NULL COMMENT '封禁时间',
  `expire_at`    DATETIME(3)           DEFAULT NULL COMMENT '解封时间；NULL = 永久（仅人工可解）',
  `unblocked_at` DATETIME(3)           DEFAULT NULL COMMENT '实际解除时间',
  `unblocked_by` BIGINT                DEFAULT NULL COMMENT '解除人 user_id（自动过期时为 NULL）',
  `active`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否仍生效（过期/人工解除后置 0）',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_ip_block_ip_active` (`ip`, `active`),
  KEY `idx_ip_block_active_expire` (`active`, `expire_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'IP 封禁（自动封禁带过期时间，人工封禁可为永久）';

-- ----------------------------------------------------------------------------
-- ③ 防护参数（按惯例**三处登记**：目录 + 配置页 + 本迁移）
--    配置页由目录驱动渲染，因此「加进目录」即同时完成页面登记。
--    ⚠️ login_fail_max_count / login_lock_minutes 已存在（V12 起），本迁移不重复插入。
-- ----------------------------------------------------------------------------

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_block_enabled', '1', 'security',
       'IP 封禁总开关。关闭后仍会记录安全事件，但不再自动封禁来源 IP。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_block_enabled');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_block_max_count', '10', 'security',
       '同一 IP 在失败计数窗口内累计失败多少次后自动封禁。比账号阈值宽（账号 5 次）—— 一个 IP 后面往往是整个办公室。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_block_max_count');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_block_minutes', '30', 'security',
       '自动封禁时长（分钟），到期自动解封。调大能压制持续攻击，但误封的代价也随之放大。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_block_minutes');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_ip_whitelist', '', 'security',
       'IP 白名单，多个用英文逗号分隔，支持网段（如 192.168.1.0/24）。白名单内的 IP 永不封禁 —— 办公网出口 IP 常被多人共用，误封会整片人上不来。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_ip_whitelist');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'security_event_retention_days', '90', 'security',
       '安全事件保留天数，超期由每日清理任务删除。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'security_event_retention_days');
