-- =====================================================================
--  ·  · M5（统计仪表盘）
-- V23__phase16_dashboard.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：工作台新增「统计仪表盘」聚合区块（区间内工单量 / 类型 / 状态 /
--   时间分布 / 部门分布 / 平均审批时长 / 超时率），并开放只读权限码 dashboard:view。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **为什么只有一条 INSERT，没有任何 DDL**。
--     仪表盘的四个分布维度全部落在既有列上：
--       · 类型 = orders.order_type      · 状态 = orders.status
--       · 时间 = orders.created_at      · 部门 = orders.biz_group_id → biz_group.group_name
--     审批时长与超时率落在 order_approval_nodes.action_time / deadline_at。
--     既不需要新表，也不需要新列 —— 这是一条纯粹的「授权」迁移。
--
--  B. **为什么刻意不加索引**。
--     相关索引已足够（idx_orders_created_at / idx_orders_status / idx_oan_status_action
--     / idx_oan_deadline）。这是 100 人规模、聚合只在业务管理员手动打开工作台时才触发的查询；
--     为一个低频只读查询去加索引，换来的是每一次工单写入的额外代价，不划算。
--     若日后数据量证明真的需要，再加是一条纯增量的独立迁移，回滚也干净。
--
--  C. **为什么用 `INSERT ... SELECT ... FROM DUAL`（照 V16 / V22 的幂等模式）**。
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
--  E. **为什么只授给 admin，不授给 user**。
--     dashboard:view 是「经营 / 效率数据」的查看权，部门维度对普通员工无意义；
--     与既有 asset:report:view / flow_monitor:view 的公开面保持一致（均归业务管理员）。
--     super_admin 不需要授权 —— PermissionGuard 对它恒定短路放行。
--     ⚠️ 上线检查项：确认本环境存在 role_code='admin' 的角色，否则会出现
--        「只有超管能看统计仪表盘」而 admin 用户看不到新增区块。
--
--  F. **回滚方式**。删除这条 sys_role_permission 记录即可（或不下发本次代码）。
--     权限码本身由 PermissionCatalog 定义，代码回滚后该码自动从授权树消失，
--     不需要 DROP 任何对象。
--
--  G. **与 RolePermissionInitializer 互为兜底**。启动时初始化器也会按目录为内置角色
--     补齐默认授权，但它受 rbac_seeded 一次性标记保护（存量环境不会再跑）——
--     故用本迁移把增量授权落进「已升级」的环境；反过来初始化器又保证新装环境即便
--     漏了本脚本也不会缺权限。
-- =====================================================================

INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'dashboard:view' FROM DUAL
  WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission`
      WHERE `role_code` = 'admin' AND `perm_code` = 'dashboard:view'
  );
