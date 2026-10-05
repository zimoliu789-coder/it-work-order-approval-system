-- =====================================================================
--  —— 权限申请自动开通：用户级授权 + 可申请策略
--
-- 需求（用户原文）：「员工申请开通某项权限，审批通过后系统自动授权，无需管理员手动改」
--                  「哪些权限是可以申请的需要可配置」
--
-- 两条已拍板的口径（写在最前面，防止后人改回去）：
--   ① **风险等级放代码、能否申请放表** —— 权限「是什么」（码 / 中文名 / 默认等级）
--      是 PermissionCatalog 的事实源；「能不能申请」由本表 permission_apply_policy 决定。
--      两者职责不同，不冲突（见 PermissionCatalog#riskLevelOf 的注释）。
--   ② **高危判定必须服务端按 permissionCodes 重算** —— 表单值是用户输入，
--      信任前端传来的「是否高危」等于让攻击者改一个字段就绕过超管那一级。
-- =====================================================================

-- ----------------------------------------------------------------------------
-- ① 用户级授权
--
-- 为什么必须有这张表：现在是**纯角色制**（sys_role_permission）。「只给某个人开一项权限」
-- 不能靠改角色 —— 角色是共享的，改它会波及该角色下的所有人。
--
-- ⚠️ 为什么没有 (user_id, perm_code) 唯一键：
--    需求是「同一项权限可以**反复授予与撤销**」——撤销后必须能再授予，
--    而 MySQL 没有「部分唯一索引」（只对 revoked_at IS NULL 生效）。
--    若加普通唯一键，「撤销后重新申请」会被唯一约束挡住。
--    因此「同一时刻只有一条未撤销记录」由 UserPermissionService 在事务内保证
--    （授予前先查未撤销行，有则复用/更新），而不是靠数据库约束。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_permission` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT       NOT NULL COMMENT '被授权用户',
  `perm_code`     VARCHAR(64)  NOT NULL COMMENT '权限码（取值见 PermissionCatalog）',
  `source`        VARCHAR(16)  NOT NULL COMMENT 'APPLY 审批通过自动授予 / MANUAL 管理员手工授予',
  `order_id`      BIGINT                DEFAULT NULL COMMENT '来源工单 id（APPLY 时有值，便于回溯「谁批的」）',
  `granted_by`    BIGINT                DEFAULT NULL COMMENT '授予人 user_id；系统自动授予时为 NULL',
  `granted_at`    DATETIME     NOT NULL COMMENT '授予时间',
  `revoked_at`    DATETIME              DEFAULT NULL COMMENT '撤销时间；NULL = 当前有效',
  `revoke_reason` VARCHAR(255)          DEFAULT NULL COMMENT '撤销原因',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_permission_user` (`user_id`, `revoked_at`),
  KEY `idx_user_permission_code` (`perm_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户级授权（角色之外的附加权限，撤销后保留历史行）';

-- ----------------------------------------------------------------------------
-- ② 可申请策略
--
-- 「没有行」= 用代码里的默认值（PermissionCatalog#riskLevelOf + 默认可申请）。
-- 这样新增权限码时**不必同步插一行**，而管理员改过的行会覆盖代码默认值。
-- 与「代码即事实源」不冲突：代码说的是「这个码是什么」，表说的是「这个码能不能申请」。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `permission_apply_policy` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `perm_code`  VARCHAR(64)  NOT NULL COMMENT '权限码',
  `applicable` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否开放申请；0 = 不开放（提权类）',
  `risk_level` VARCHAR(8)   NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级：NORMAL 一级审批 / HIGH 需超管多走一级',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_apply_policy` (`perm_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限申请策略（能否申请 + 风险等级，覆盖代码默认值）';

-- 播种需求方点名的四类**提权类**：不开放申请。
-- 拿到「角色与权限管理」就能绕过本流程自行提权，等于流程形同虚设。
INSERT INTO `permission_apply_policy` (`perm_code`, `applicable`, `risk_level`)
SELECT 'role:manage', 0, 'HIGH' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `permission_apply_policy` WHERE `perm_code` = 'role:manage');
INSERT INTO `permission_apply_policy` (`perm_code`, `applicable`, `risk_level`)
SELECT 'ad:manage', 0, 'HIGH' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `permission_apply_policy` WHERE `perm_code` = 'ad:manage');
INSERT INTO `permission_apply_policy` (`perm_code`, `applicable`, `risk_level`)
SELECT 'system:upgrade:manage', 0, 'HIGH' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `permission_apply_policy` WHERE `perm_code` = 'system:upgrade:manage');
INSERT INTO `permission_apply_policy` (`perm_code`, `applicable`, `risk_level`)
SELECT 'config:manage', 0, 'HIGH' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `permission_apply_policy` WHERE `perm_code` = 'config:manage');
