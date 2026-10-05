-- =====================================================================
--  —— 从管理员（admin）收回「部门维护」权限：组织结构只有超管能改
--
-- 背景（ 取证时发现的既有不一致）：
--    把 `department:manage` 一并给了 admin（见 V34 与本迁移要删的授权行），
--   但同一批次交付的组织页（views/staff/organization/index.vue）把**所有部门写操作**
--   （新增 / 编辑 / 移动 / 设主管 / 删除，含  新增的拖拽）都按 `isSuperAdmin` 显隐。
--   ⇒ **前端比后端更严**：admin 看不到入口，却能直接调接口改组织结构。
--   实测（以 admin 10003 直调）：
--       PUT  /api/departments/48/parent → 200 SUCCESS「部门已移动」
--       POST /api/departments           → 200 SUCCESS「部门已创建」
--
-- 为什么必须收回（而不是「把前端放开给 admin」）：
--   部门是**审批上级的事实源** —— 动一个部门主管等于改一批人的审批路径。
--   PermissionCatalog#DEPARTMENT_MANAGE 的 javadoc 意图就是
--   「业务管理员能维护员工，但只有超管能改组织结构」，与本迁移的终态一致。
--   显隐不是安全边界（项目硬约定：越权分支要在「取数据之前」return）。
--
-- 为什么必须是一条迁移，而不是只改代码：
--   授权行由 RolePermissionInitializer 按 PermissionCatalog.DEFAULT_PERMISSIONS 播种，
--   且**逐角色只播一次**（标记 system_config.rbac_role_seeded:admin=1）。
--   只改代码里的默认集合，对已经播过种的环境完全无效 —— 那台机器的库里早就写入了这一行。
--   所以：老库靠本脚本删掉，新库靠代码默认集合本来就没有，两条路径的终态一致
--   （与 V33「回收设计器只读权限」同一姿势）。
--
-- 幂等性：DELETE 命中 0 行是合法结果（全新库就是 0 行），可安全重跑。
-- 影响面：仅 admin 的这一行；其它角色（含自定义角色）的授权不受影响。
--         超管不写授权行（PermissionGuard 短路放行），本脚本同样不碰。
-- 不动的码：staff:view / staff:manage / staff:import 仍在 admin 默认集里 ——
--           admin 依旧能进「组织与人员」页、维护员工、批量导入，只是不能改组织结构。
-- =====================================================================

DELETE FROM sys_role_permission
 WHERE role_code = 'admin'
   AND perm_code = 'department:manage';
