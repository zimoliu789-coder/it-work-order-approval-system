-- =====================================================================
--  / V2__phase1_seed.sql
-- 系统配置默认参数初始化
-- 规范依据：第 10 章（临时锁）、第 16.3 章（自动顺延）、第 17 章（主动延期）、
--          第 19 章（密码与登录安全）、第 20 章（日志保留）、第 26.1 章（异步导出）、
--          第 27 章（定时任务可配置参数）、第 29 章（API 限流）、第 5.1 章（LDAP 预留）
-- 说明：本脚本只写参数默认值；super_admin 账号不在此脚本创建，
--      由应用启动时根据 SUPER_ADMIN_INIT_PASSWORD 环境变量创建（见 SuperAdminInitializer）。
-- =====================================================================

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`) VALUES
-- 设备临时锁（）
('lock_timeout_minutes',                 '5',     'lock',     '设备临时锁定超时时长（分钟），可配置范围 1-60'),
('lock_timeout_min_minutes',             '1',     'lock',     '临时锁定超时下限（分钟）'),
('lock_timeout_max_minutes',             '60',    'lock',     '临时锁定超时上限（分钟）'),

-- 审批（）
('approval_timeout_remind_hours',        '24',    'approval', '审批节点长时间未处理的提醒时长（小时）'),

-- 借用到期 / 顺延 / 超时（ ）
('borrow_expire_warning_days',           '1',     'borrow',   '借用到期前提前预警天数'),
('auto_extend_max_count',                '2',     'borrow',   '自动顺延最大次数'),
('extend_max_count',                     '2',     'borrow',   '主动延期最大次数'),
('timeout_alert_interval_hours',         '24',    'borrow',   '超时告警重复推送间隔（小时）'),

-- 登录安全（ ）
('login_fail_max_count',                 '5',     'security', '连续登录失败次数达到该值后锁定账号'),
('login_lock_minutes',                   '15',    'security', '登录失败锁定时长（分钟）'),
('login_rate_limit_per_minute',          '5',     'security', '登录接口限流：账号+IP 每分钟最多请求次数'),
('order_submit_rate_limit_per_minute',   '3',     'security', '工单提交/延期提交接口单用户每分钟最多提交次数'),
('password_min_length',                  '8',     'security', '密码最小长度'),
('password_min_char_types',              '2',     'security', '密码最少包含的字符类别数（大写/小写/数字/特殊）'),
('jwt_expire_minutes',                   '720',   'security', 'JWT 有效期（分钟）'),

-- 日志（ ）
('operation_log_retention_days',         '90',    'log',      '操作日志保留天数'),
('export_async_threshold',               '10000', 'log',      '导出数据量超过该值时改为异步生成并站内消息通知'),

-- LDAP / AD 域控预留（， 启用）
('ldap_enabled',                         '0',     'ldap',     '是否启用域控对接：0否 1是'),
('ldap_url',                             '',      'ldap',     '域控服务器地址，例如 ldap://192.168.1.10:389'),
('ldap_base_dn',                         '',      'ldap',     '用户搜索基准 DN，例如 OU=员工,DC=company,DC=com'),
('ldap_admin_dn',                        '',      'ldap',     '用于同步查询的管理员 DN'),
('ldap_admin_password',                  '',      'ldap',     '管理员密码（管理界面保存时加密存储）'),
('ldap_user_filter',                     '(objectClass=user)', 'ldap', '用户筛选条件'),
('ldap_name_attr',                       'sAMAccountName',     'ldap', '姓名字段映射（对应 username）'),
('ldap_email_attr',                      'mail',               'ldap', '邮箱字段映射'),
('ldap_sync_interval',                   '60',    'ldap',     '自动同步间隔（分钟），0=仅手动同步'),
('ldap_default_role',                    'user',  'ldap',     '同步过来的域用户默认角色'),
('ldap_group_mapping',                   '{}',    'ldap',     '域 OU/安全组 与本地业务分组的映射规则（JSON）');
