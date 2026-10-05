-- =====================================================================
--  ·  · W4-D（单一事实源与候选池）
-- V24__phase16_wave4_config_view.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：把「系统参数」页的**只读**查看权 `config:view` 授予内置角色 admin。
--   修改权 `config:manage` 刻意**不下发** —— 系统参数包含借用期限、预警阈值、
--   限流与定时任务开关等运行期行为，改动它们仍是超管职责。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **为什么只有一条 INSERT，没有任何 DDL**。
--     `config:view` / `config:manage` 两个权限码早已由 PermissionCatalog 定义
--     （菜单节点 /system/config 即挂在 config:view 上），只是**从未写入任何授权行**，
--     因此改造前该页面只有 super_admin 能打开（PermissionGuard 对超管恒定短路放行），
--     admin 访问 GET /api/system/configs 得到 403。
--     本迁移补齐的正是这条缺失的授权行 —— 纯授权迁移，零结构变更。
--
--  B. **为什么补这个洞**。
--     系统参数页对业务管理员是有意义的只读信息（例如当前借用期限、预警阈值是多少），
--     而「看不到」与「改不了」是两件事：只给 view 既满足了知情，又不改变写权限边界。
--     这与  的 flow_monitor:view、 的 dashboard:view 同一取向 ——
--     admin 拿到的是**只读视图**，对应的 manage 码一律不下发。
--
--  C. **为什么用 `INSERT ... SELECT ... FROM DUAL`（照 V16 / V22 / V23 的幂等模式）**。
--     迁移可能被重复执行（手工修复、库重建），普通 INSERT 会因 sys_role_permission
--     的唯一键冲突而让整个迁移失败、库停在半途。
--     注：`FROM DUAL` 不是装饰 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，
--     缺了它语句会在解析阶段报 1064，迁移直接卡住。
--
--  D. **为什么先判 sys_role 里 admin 是否存在**。
--     整洁部署里 admin 必然存在；但角色管理允许改名 / 删除，若该角色已不在，
--     这里会插入一条指向不存在角色的授权记录 —— 一条永远解释不通的脏数据。
--     先判存在再插，最坏情况是「什么都没发生」，而不是「留下一堆垃圾」。
--
--  E. **为什么不下发给 user**。
--     系统参数是运行期行为配置，与普通员工的日常提交/审批无关；
--     与既有 asset:report:view / dashboard:view 的公开面保持一致（均止于业务管理员）。
--     super_admin 不需要授权 —— PermissionGuard 对它恒定短路放行。
--     ⚠️ 上线检查项：确认本环境存在 role_code='admin' 的角色，否则会出现
--        「只有超管能看系统参数」而 admin 用户看不到该菜单。
--
--  F. **回滚方式**。删除这条 sys_role_permission 记录即可（或不下发本次代码）。
--     权限码本身由 PermissionCatalog 定义，代码回滚后该码自动从授权树消失，
--     不需要 DROP 任何对象。
--
--  G. **与 RolePermissionInitializer 互为兜底**。启动时初始化器按
--     PermissionCatalog.defaultPermissions('admin') 为内置角色补齐默认授权，
--     但受 rbac_seeded 一次性标记保护（存量环境不会再跑）—— 故用本迁移把增量授权
--     落进「已升级」的环境。反过来，本批已把 CONFIG_VIEW 加入
--     PermissionCatalog 的 admin 默认集，保证**新装环境**即便漏了本脚本也不会缺权限。
--     两者必须同时改：只改 SQL → 新装环境缺；只改默认集 → 存量环境缺。
-- =====================================================================

INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'config:view' FROM DUAL
  WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission`
      WHERE `role_code` = 'admin' AND `perm_code` = 'config:view'
  );
