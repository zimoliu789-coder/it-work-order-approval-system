-- =====================================================================
--  —— 回收管理员（admin）对「申请类型管理 / 审批流程模板」的只读权限
--
-- 需求文档验收标准 5：普通管理员（非超管）看不到表单设计器、流程设计器，不需要学。
-- 这两个菜单项的可见性由权限码 apply_type:view / approval_flow:view 驱动 ——
-- 前端 config/menus.ts 的两项都是 `perm` 驱动，后端接口另有 @PreAuthorize。
-- 因此「看不见」的落点就是「授权行不存在」。
--
-- 为什么必须是一条迁移，而不是只改代码：
--   授权行由 RolePermissionInitializer 按 PermissionCatalog.DEFAULT_PERMISSIONS 播种，
--   且**逐角色只播一次**（标记 system_config.rbac_role_seeded:admin=1）。
--   只改代码里的默认集合，对已经播过种的环境完全无效 —— 那台机器的库里
--   早就写入了这两行。所以：老库靠本脚本删掉，新库靠代码默认集合本来就没有，
--   两条路径的终态一致（这也是为什么不能只做其中一边）。
--
-- 幂等性：DELETE 命中 0 行是合法结果（全新库就是 0 行），可安全重跑。
-- 影响面：仅 admin 的两行；其它角色（含自定义角色）的授权不受影响。
--         超管不写授权行（守卫短路放行），本脚本同样不碰。
-- =====================================================================

DELETE FROM sys_role_permission
 WHERE role_code = 'admin'
   AND perm_code IN ('apply_type:view', 'approval_flow:view');
