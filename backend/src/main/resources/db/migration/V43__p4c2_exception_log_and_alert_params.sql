-- =====================================================================
--  —— 异常邮件告警：异常日志表 + 告警参数
--
-- 需求（用户原文）：「系统出现异常时自动发送邮件通知管理员。同时记录详细的异常日志。
--   这类功能需要频率控制，不能发太频繁，比如同一问题短时间内不重复提醒。」
--
-- 本迁移只做「数据」这一半；「采集 + 分类 + 去重 + 汇总」在 common/alert 下实现。
--
-- 三条硬约束（已拍板，写在这里防止后人改回去）：
--   ① **业务异常（4xx）不告警** —— 只有未预期异常 / 5xx 才落库并告警。
--      否则用户填错一个字段就发一封邮件，告警疲劳之后真故障会被忽略。
--   ② **告警只发超管**（沿用「基础设施级告警只发超管」硬约定）+ 可配额外收件人。
--   ③ **绝不逐条发** —— 同类在静默期内合并，按周期汇总成一封。
-- =====================================================================

-- ----------------------------------------------------------------------------
-- ① 未预期异常日志
--
-- 为什么自建一张表而不是写文件：需求要「记录详细的异常日志」并且要能在界面上按
-- 分类 / 时间检索。「日志文件」满足不了检索，而「操作日志 operation_logs」装的是
-- **用户动作**（谁在什么时候做了什么），异常是**系统自身的故障**，
-- 两者的检索维度、保留期、告警语义都不同，混在一张表里会互相污染。
--
-- 为什么 user_id 不加外键：异常可能发生在匿名请求里（user_id 为 NULL），
-- 且外键 RESTRICT 会让「删除用户」被历史异常行挡住 —— 异常日志是取证数据，
-- 不该反过来约束业务表。用户被删后这里保留原始 id（审计口径：记录当时的事实）。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `exception_log` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id`        VARCHAR(64)            DEFAULT NULL COMMENT '请求链路 id，与响应体 traceId 同源',
  `category`        VARCHAR(32)   NOT NULL COMMENT '分类：DATABASE / NETWORK / THIRD_PARTY / PARAM / BUSINESS / UNKNOWN',
  `severity`        VARCHAR(8)    NOT NULL COMMENT '分级：P0 立即 / P1 汇总即发 / P2 攒日报',
  `module`          VARCHAR(64)            DEFAULT NULL COMMENT '来源模块标识（由记录方传入）',
  `exception_class` VARCHAR(255)  NOT NULL COMMENT '异常类全名',
  `message`         VARCHAR(1000)          DEFAULT NULL COMMENT '异常消息（截断后）',
  `stack_trace`     TEXT                   DEFAULT NULL COMMENT '异常堆栈（截断到 4000 字符）—— 排查的正文',
  `stack_digest`    CHAR(32)      NOT NULL COMMENT '同类指纹（去重合并键）：异常类 + 消息骨架 + 业务栈帧 的 MD5',
  `request_uri`     VARCHAR(255)           DEFAULT NULL COMMENT '请求路径',
  `http_method`     VARCHAR(16)            DEFAULT NULL COMMENT '请求方法',
  `user_id`         BIGINT                 DEFAULT NULL COMMENT '触发用户 id；匿名请求为 NULL',
  `ip`              VARCHAR(64)            DEFAULT NULL COMMENT '来源 IP',
  `occurred_at`     DATETIME(3)   NOT NULL COMMENT '发生时间（毫秒精度，静默期判定依赖它）',
  `alert_state`     VARCHAR(16)   NOT NULL COMMENT 'PENDING 待汇总 / SENT 已告警 / SUPPRESSED 不告警（总开关关闭、分类被忽略）',
  `alerted_at`      DATETIME(3)            DEFAULT NULL COMMENT '实际告警时间',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_exception_log_digest_time` (`stack_digest`, `occurred_at`),
  KEY `idx_exception_log_occurred_at` (`occurred_at`),
  KEY `idx_exception_log_state` (`alert_state`),
  KEY `idx_exception_log_category` (`category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '未预期异常日志（异常告警的数据源）';

-- ----------------------------------------------------------------------------
-- ② 异常告警参数（按惯例**三处登记**：目录 SystemConfigCatalog + 配置页 + 本迁移。
--    配置页由目录驱动渲染，因此「加进目录」即同时完成页面登记。）
-- ----------------------------------------------------------------------------

-- 总开关。默认**开启**：异常告警属于「装好就该生效」的基础设施能力，
-- 与 backup_enabled（默认关闭，因为 Docker 部署另有备份容器、两者会打架）不同。
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_enabled', '1', 'alert',
       '异常告警总开关。关闭后异常仍会记录到「异常日志」，但不再发送站内消息与邮件。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_enabled');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_silence_minutes', '5', 'alert',
       '同类异常的静默期（分钟）。同一问题在静默期内重复出现只计数、不重复告警。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_silence_minutes');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_summary_minutes', '10', 'alert',
       '汇总周期（分钟）。待告警的异常按这个周期合并成一封邮件，避免逐条轰炸邮箱。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_summary_minutes');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_extra_recipients', '', 'alert',
       '额外收件人邮箱，多个用英文逗号分隔。默认只发全部超级管理员，这里可再加运维同事。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_extra_recipients');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_ignore_categories', '', 'alert',
       '不告警的分类，多个用英文逗号分隔（如 THIRD_PARTY,PARAM）。留空表示全部分类都告警。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_ignore_categories');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_alert_quiet_hours', '22:00-08:00', 'alert',
       '静默时段（HH:mm-HH:mm）。P2 级异常在该时段内不发即时告警、攒到次日报；P0/P1 不受影响。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_alert_quiet_hours');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'exception_log_retention_days', '90', 'alert',
       '异常日志保留天数，超期由每日清理任务删除。取证数据不宜长期堆积。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'exception_log_retention_days');
