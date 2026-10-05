-- =====================================================================
--  附带修复 —— 补齐 admin 在**存量环境**里缺失的 5 个默认权限码
--
-- 【发现】 把 admin 的默认权限集合从 18 个扩到 23 个（新增
--   staff:manage / staff:import / department:manage / role:view / log:view），
--   但**只改了代码里的 PermissionCatalog.DEFAULT_PERMISSIONS，没有配迁移**。
--
-- 【为什么只改代码不够】授权行由 RolePermissionInitializer 按「逐角色标记」
--   （system_config.rbac_role_seeded:admin）播种，**一个角色只播一次**。
--   存量环境早在上一次播种时就置了标记 ⇒ 后来扩出来的码永远不会补上；
--   只有全新安装的库才拿得到完整集合。
--   实测本机库：admin 授权行 18 条，上述 5 个码全部缺失 ——
--   表现为「管理员看不到『角色与权限 / 操作日志』菜单、改不了员工、做不了批量导入、
--   动不了部门」，与（管理员 = 全部业务功能 + 系统设置不含高级设置）
--   和验收标准 9（维护人员能独立完成员工批量导入）直接冲突。
--
-- 【本项目的既定做法】每次为既有角色新增权限码，都配一条幂等授予迁移 ——
--   V16(apply_type:view) / V17(approval_flow:view) / V22(flow_monitor:view) /
--   V23(dashboard:view) / V24(config:view) 全是这个模式。本脚本补上 A+B 漏掉的那次。
--
-- 【为什么不做成「初始化器自愈」】那会把「运维主动清空了某角色的权限」误判为
--   「还没初始化」，重启一次权限全回来 —— 典型的幽灵缺陷。见 RolePermissionInitializer 类注释。
--
-- 幂等性：沿用 V22/V24 的 WHERE NOT EXISTS 写法，可安全重跑。
--   `FROM DUAL` 不可省：MySQL 的「无表 SELECT」不允许直接跟 WHERE（1064）。
-- 影响面：仅 admin 这 5 行；不碰超管（不写授权行）、不碰其它角色与自定义角色。
-- =====================================================================

-- staff:manage —— 员工新增 / 编辑 / 重置密码 / 启停
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'staff:manage' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'staff:manage');

-- staff:import —— 员工批量导入（模板下载 / 预览校验 / 确认）
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'staff:import' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'staff:import');

-- department:manage —— 部门树维护（新增 / 改名 / 移动 / 设主管 / 删除）
-- 刻意与 staff:manage 分开：组织调整 ≠ 改员工资料（见 PermissionCatalog 的说明）
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'department:manage' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'department:manage');

-- role:view —— 「角色与权限」菜单（只读；role:manage 仍只归超管）
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'role:view' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'role:view');

-- log:view —— 「操作日志」菜单（只读）
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'log:view' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'log:view');
