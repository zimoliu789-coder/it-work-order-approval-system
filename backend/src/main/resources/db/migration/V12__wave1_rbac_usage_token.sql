-- =====================================================================
-- 三波遗漏补做 · 第一波（核心管理一体化）建表与改列
-- V12__wave1_rbac_usage_token.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-19 三波清单·第一波）：
--   1. JWT jti / 密码版本号（改密后旧 Token 立即失效，登出拉黑）
--   2. 使用记录模块（设备 / 员工借用历史查询）
--   3. 系统参数写入 + 配置页（本次无需改表：system_config 已具备 editable 列）
--   4. 角色与权限管理（角色 CRUD + 菜单/操作/数据权限）
--
-- 规范依据：
--   第 6 章  角色与权限
--   第 19 章 登录安全与会话机制
--   第 20 章 操作审计
-- 说明：
--   本迁移**只做增量**（加列 / 建表 / 加索引），不修改任何既有列定义，向后兼容。
--   权限目录（菜单 + 操作码）以代码为单一事实来源（PermissionCatalog），
--   本迁移只建「角色」「角色-权限」两张关系表，权限集合由启动初始化器按代码目录写入。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、users.token_version —— 令牌版本号（）
--
-- 为什么需要它：JWT 是无状态的，服务端不保存会话。若没有版本号，
--   ①「修改密码」后旧 Token 在有效期内（默认 720 分钟）仍然可用 —— 密码泄露场景下
--     改了密码也踢不掉攻击者；
--   ②「管理员重置某员工密码」后，该员工旧会话依旧有效；
--   ③「登出」只能清浏览器的 Cookie，被抄走的 Token 依旧能用。
-- 方案：把版本号写进 Token 的 `ver` 声明，每次请求与库中值比对，不等即失效。
--   改密 / 重置 / 强制下线 → version +1 → 该用户所有已签发 Token 立即作废。
--   单次登出（不牵连其它设备）由 jti 黑名单承担，见下方说明。
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD COLUMN `token_version` INT NOT NULL DEFAULT 0
      COMMENT '令牌版本号：改密 / 管理员重置 / 强制下线时 +1，旧的 JWT 立即失效' AFTER `last_login_at`;

-- ---------------------------------------------------------------------
-- 二、attachments.stored_path 索引（第二波·附件孤儿清理任务用）
--
-- 孤儿清理要「拿磁盘上的相对路径反查附件表」：路径在库里唯一（落盘名是 UUID），
-- 但表上原本只有 (biz_type, biz_id, deleted, id) 与 (uploader_id, created_at) 两个索引，
-- 反查 stored_path 会走全表扫描。附件表随工单量增长，这里补一个单列索引。
-- 刻意不建唯一索引：唯一性是「落盘实现」的保证（UUID），而非表约束；
-- 万一历史上出过重复文件，唯一索引会让本迁移直接失败 —— 迁移失败比多一条冗余记录更糟。
-- ---------------------------------------------------------------------
CREATE INDEX `idx_att_stored_path` ON `attachments` (`stored_path`);

-- ---------------------------------------------------------------------
-- 三、order_approval_nodes.last_remind_at —— 审批超时提醒幂等位（第二波·）
--
-- 「所有定时任务必须幂等」。审批超时提醒每小时跑一次，若只按「超 24 小时」判定，
-- 每小时都会给同一位审批人再发一条 —— 一天 24 条，很快把消息中心刷爆。
-- 因此记录「最近一次提醒时间」，任务只处理「从未提醒过」或「距上次提醒已超过阈值」的节点。
-- 与 orders 表上的 remind_before_sent_at / due_reminded_at / last_timeout_alert_at 同一套思路。
-- ---------------------------------------------------------------------
ALTER TABLE `order_approval_nodes`
  ADD COLUMN `last_remind_at` DATETIME DEFAULT NULL
      COMMENT '审批超时提醒最近发送时间（定时任务幂等位，需求方三波·第二波）' AFTER `action_comment`;

-- ---------------------------------------------------------------------
-- 四、sys_role —— 角色定义（）
--
-- 与 users.role 的关系：users.role 存的是**角色编码**（role_code），不是外键。
-- 不建外键的原因：① 删除角色时若级联会把员工角色一起抹掉，语义危险；
--   ② 角色删除必须先在服务层校验「没有员工在用」，属于业务规则，交给外键表达不清。
-- builtin=1 的三个内置角色（super_admin / admin / user）不允许删除、不允许改编码，
-- 其中 super_admin 的权限集合**恒定全量**且不可编辑 —— 保证系统永远有人能救回来。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `role_code`   VARCHAR(64)  NOT NULL                            COMMENT '角色编码（与 users.role 对应，全局唯一）',
  `role_name`   VARCHAR(64)  NOT NULL                            COMMENT '角色名称（中文，界面展示）',
  `data_scope`  VARCHAR(16)  NOT NULL DEFAULT 'SELF'             COMMENT '数据权限：ALL 全部数据 / GROUP 本业务分组 / SELF 仅本人',
  `remark`      VARCHAR(255)          DEFAULT NULL               COMMENT '备注（用途说明）',
  `builtin`     TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否内置角色：1 内置（不可删除、不可改编码）',
  `enabled`     TINYINT(1)   NOT NULL DEFAULT 1                  COMMENT '是否启用：0 停用（停用后不可再分配给员工）1 启用',
  `sort_no`     INT          NOT NULL DEFAULT 100                COMMENT '排序号（升序）',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_code` (`role_code`),
  KEY `idx_sys_role_sort` (`sort_no`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色定义表（需求方三波·第一波·）';

-- ---------------------------------------------------------------------
-- 五、sys_role_permission —— 角色与权限码的关联（）
--
-- 权限码（perm_code）形如 `device:ledger:manage`，取自代码内的权限目录
-- （com.enterprise.ticket.common.permission.PermissionCatalog）。
-- 刻意不建 sys_permission 目录表：目录是**代码资产**（决定后端是否有对应实现），
-- 落库会产生「库里有的码代码里没有 → 配了也不生效」这种最难排查的错配。
-- 用「唯一键防止重复授予」+ 服务层校验码是否存在于目录即可。
-- 外键指向 sys_role.role_code 并级联删除：删角色时清理其权限行是符合直觉的，
-- 且不会波及 users.role（users.role 不是外键）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `role_code`  VARCHAR(64) NOT NULL                            COMMENT '角色编码',
  `perm_code`  VARCHAR(64) NOT NULL                            COMMENT '权限码（菜单码或操作码）',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_code`, `perm_code`),
  KEY `idx_role_perm_role` (`role_code`),
  CONSTRAINT `fk_role_perm_role` FOREIGN KEY (`role_code`) REFERENCES `sys_role` (`role_code`)
      ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色-权限关联表（需求方三波·第一波·）';

-- ---------------------------------------------------------------------
-- 六、RBAC 初始化标记（「初始化导入现有三角色权限」）
--
-- 为什么需要一个显式标记，而不是「表里没有角色就初始化」：
-- 后者会把「运维明确删光了某个角色的权限」误判成「还没初始化」，下一次重启又把权限灌回去 ——
-- 这类「改了又回来」的幽灵行为极难排查。用一次性标记把「初始化」与「修复」彻底解耦：
-- 标记为 1 之后，无论角色表被改成什么样，启动器都不再插手。
--
-- config_group 用 internal：参数设置页按分组展示，internal 分组不出现在界面上
-- （它是系统内部状态位，不是可调参数）。editable=0 是第二道保险。
-- ---------------------------------------------------------------------
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
VALUES ('rbac_seeded', '0', 'internal',
        '角色权限初始化标记（0 待初始化 / 1 已完成）；系统内部使用，请勿修改', 0)
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;
