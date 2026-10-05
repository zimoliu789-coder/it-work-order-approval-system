-- =====================================================================
--  / V1__phase1_init.sql
-- 企业内部设备借用工单系统 —— 基础表结构初始化
-- 规范依据：《企业内部设备借用工单系统_V1.1_AI全栈开发总规范》
--   第 5 章   用户、姓名账号与角色
--   第 13 章  业务分组与审批配置（本阶段只建空表，不做管理界面）
--   第 20 章  操作日志
-- 说明：
--   1) 本脚本只建结构，不写入业务数据（初始化参数见 V2__phase1_seed.sql）。
--   2) users.biz_group_id 本阶段允许 NULL 且不加外键， 做完分组管理后
--      由新迁移收紧为非空并补外键（已与需求方确认）。
--   3) 字符集统一 utf8mb4，排序规则用 utf8mb4_general_ci 以兼容多种 MySQL 8 发行版。
-- =====================================================================

-- ---------------------------------------------------------------------
-- users 员工表（）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id`                    BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键 user_id，不可变',
  `username`              VARCHAR(64)  NOT NULL                            COMMENT '登录账号（员工姓名），全局唯一；重名自动加 _2 后缀',
  `display_name`          VARCHAR(64)  NOT NULL                            COMMENT '显示名称',
  `password_hash`         VARCHAR(255)          DEFAULT NULL               COMMENT 'Argon2id 密码哈希；auth_type=LDAP 时为空',
  `role`                  VARCHAR(32)  NOT NULL DEFAULT 'user'             COMMENT '角色：super_admin / admin / user',
  `auth_type`             VARCHAR(16)  NOT NULL DEFAULT 'LOCAL'            COMMENT '认证来源：LOCAL 本地账户 / LDAP 域控账户',
  `ldap_dn`               VARCHAR(255)          DEFAULT NULL               COMMENT '域用户唯一标识 DN，auth_type=LDAP 时使用',
  `biz_group_id`          BIGINT                DEFAULT NULL               COMMENT '所属业务分组ID； 允许 NULL， 收紧为非空+外键',
  `force_change_password` TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '首次登录是否强制修改密码：0否 1是',
  `enabled`               TINYINT(1)   NOT NULL DEFAULT 1                  COMMENT '账号是否启用：0禁用 1启用',
  `is_dimission`          TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否离职：0在职 1离职',
  `dimission_at`          DATETIME              DEFAULT NULL               COMMENT '离职时间',
  `last_login_at`         DATETIME              DEFAULT NULL               COMMENT '最后登录时间',
  `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `idx_users_biz_group` (`biz_group_id`),
  KEY `idx_users_role` (`role`),
  KEY `idx_users_status` (`enabled`, `is_dimission`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '员工表';

-- ---------------------------------------------------------------------
-- system_config 系统配置表（ /  / ）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `system_config` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `config_key`   VARCHAR(64)  NOT NULL                            COMMENT '配置项键名',
  `config_value` VARCHAR(512)          DEFAULT NULL               COMMENT '配置项值（统一按字符串存储，业务侧按类型解析）',
  `config_group` VARCHAR(32)  NOT NULL DEFAULT 'common'           COMMENT '配置分组：lock/approval/borrow/security/log/ldap',
  `config_desc`  VARCHAR(255)          DEFAULT NULL               COMMENT '配置说明',
  `editable`     TINYINT(1)   NOT NULL DEFAULT 1                  COMMENT '是否允许在管理界面修改',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_config_key` (`config_key`),
  KEY `idx_system_config_group` (`config_group`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统配置表';

-- ---------------------------------------------------------------------
-- operation_logs 操作日志表（）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `operation_logs` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `operator_id`    BIGINT                DEFAULT NULL               COMMENT '操作人 user_id，匿名操作为 NULL',
  `operator_name`  VARCHAR(64)           DEFAULT NULL               COMMENT '操作人姓名冗余（便于日志追溯）',
  `operation_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '操作时间',
  `module`         VARCHAR(64)  NOT NULL                            COMMENT '模块：AUTH / USER / DEVICE / ORDER 等',
  `action`         VARCHAR(64)  NOT NULL                            COMMENT '动作：LOGIN / CHANGE_PASSWORD 等',
  `details`        TEXT                 DEFAULT NULL                COMMENT '操作详情（JSON 或文本，禁止写入密码明文）',
  `result`         VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS'          COMMENT '结果：SUCCESS / FAILED',
  `risk_level`     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL'           COMMENT '风险级别：HIGH 同步落库 / NORMAL 异步落库',
  `ip`             VARCHAR(64)           DEFAULT NULL               COMMENT '客户端 IP',
  `trace_id`       VARCHAR(64)           DEFAULT NULL               COMMENT '链路追踪 ID',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_oplog_operator_time` (`operator_id`, `operation_time`),
  KEY `idx_oplog_module_action` (`module`, `action`),
  KEY `idx_oplog_time` (`operation_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '操作日志表';

-- ---------------------------------------------------------------------
-- biz_group 业务分组表（）
-- 本阶段只建结构， 交付管理界面
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `biz_group` (
  `id`                     BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `group_name`             VARCHAR(64) NOT NULL                            COMMENT '分组名称（研发部、行政部等）',
  `final_handler_group_id` BIGINT               DEFAULT NULL               COMMENT '本分组绑定的最终处理小组ID',
  `sort_order`             INT         NOT NULL DEFAULT 0                  COMMENT '列表显示顺序（ 分组可拖拽排序）',
  `remark`                 VARCHAR(255)         DEFAULT NULL               COMMENT '备注',
  `created_at`             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_group_name` (`group_name`),
  KEY `idx_biz_group_sort` (`sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '业务分组表';

-- ---------------------------------------------------------------------
-- handler_groups 最终处理小组表（，软删除）
-- 本阶段只建结构， 交付管理界面
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `handler_groups` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `group_name` VARCHAR(64)  NOT NULL                            COMMENT '小组名称',
  `deleted`    TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '软删除标记：0正常 1已删除（不允许物理删除）',
  `remark`     VARCHAR(255)          DEFAULT NULL               COMMENT '备注',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_handler_groups_deleted` (`deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '最终处理小组表';

-- ---------------------------------------------------------------------
-- handler_group_member 最终小组成员关联表（）
-- 本阶段只建结构， 交付管理界面
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `handler_group_member` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `group_id`   BIGINT   NOT NULL                            COMMENT 'handler_groups.id',
  `user_id`    BIGINT   NOT NULL                            COMMENT '小组成员 user_id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hgm_group_user` (`group_id`, `user_id`),
  KEY `idx_hgm_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '最终小组成员关联表';
