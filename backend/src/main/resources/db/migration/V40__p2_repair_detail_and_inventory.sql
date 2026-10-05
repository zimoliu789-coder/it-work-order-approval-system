-- ============================================================================
-- P2 —— 设备维修过程增强 + 设备盘点（任务 + 扫码核对）
-- V40__p2_repair_detail_and_inventory.sql
-- 企业内部设备借用工单系统
-- ----------------------------------------------------------------------------
-- 本脚本承载 P2 的全部 DDL + 授权：
--   ① `device_fault` 补三列：维修人 / 维修费用 / 更换配件；
--   ② 新建 `inventory_task`      —— 盘点任务（范围 / 状态 / 计数）；
--   ③ 新建 `inventory_task_item` —— 盘点明细（逐台快照 + 核对结果）；
--   ④ 幂等授予 admin 的 `inventory:view` / `inventory:manage`。
--
-- 顺序说明：④ 放在最后，纯属可读性 —— 它与前三项无依赖。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- ① `device_fault` 补三列 —— 维修过程记录
-- ----------------------------------------------------------------------------
-- 背景：故障模块（上报 → 待维修 → 维修完成 / 报废）在  已实现，
-- 但「处理」只有一个 `handle_remark` 自由文本。实际管理需要回答的是：
--   这台设备修了几次？累计花了多少？换过什么？谁修的？
-- 这三个问题都用自由文本回答不了（不可统计），所以要落成结构化列。
--
-- ⚠️ 三列**全部 NULL 可空**，不写 `NOT NULL DEFAULT ''`：
--   ① 既有 29 条历史记录本就没有这些信息，加 NOT NULL 会被填成空串，
--      于是「库里 29 条」看起来都填过了，而「没填」与「填了空」再也区分不开；
--   ② 填报本身就是可选的（外送维修时未必知道费用，报废更不涉及维修人）。
--   本项目的既有取向一致：`device.locked_by` / `orders.returned_by` 等均为可空。
--
-- ⚠️ `repair_cost` 用 DECIMAL(10,2) 而不是 DOUBLE：
--   金额一旦用浮点，多次累加后会出现 0.01 级误差，而维修成本统计恰恰是累加场景。
--
-- 为什么维修人用 `repairer_id` 而不是复用 `handled_by`：
--   `handled_by` 是**登记人**（点「维修完成」的管理员），`repairer_id` 是**实际维修人**
--   （可能是外部工程师，系统里没有账号 ⇒ 允许为空）。
--   两者合一会导致「谁修的都记成管理员」，维修外包统计直接失真。
-- ----------------------------------------------------------------------------
ALTER TABLE `device_fault`
    ADD COLUMN `repairer_id`     BIGINT       NULL COMMENT '实际维修人用户 id；外送/无账号时为空（与登记人 handled_by 区分）',
    ADD COLUMN `repair_cost`     DECIMAL(10, 2) NULL COMMENT '维修费用（元）；可空，累加统计用 DECIMAL 避免浮点误差',
    ADD COLUMN `replaced_parts`  VARCHAR(200) NULL COMMENT '更换配件说明；可空';


-- ----------------------------------------------------------------------------
-- ② `inventory_task` —— 盘点任务
-- ----------------------------------------------------------------------------
-- 设计要点（每条都对应一类「盘点做完却说不清」的真实问题）：
--
--  A. **范围必须落库（`scope_type` + `scope_value`），不能只存一句描述**。
--     「这次盘了哪些设备」是报告可信度的前提：范围写不清，盘亏就没有意义
--     （漏盘一台和丢了一台在报告里长得一模一样）。
--
--  B. **计数列冗余存储**，而不是每次从明细聚合。
--     列表页要显示「已盘 / 缺失」，报告页要显示全量计数 —— 任务完成后明细不再变化，
--     每次点开都去 COUNT 一遍全表明细是纯粹的浪费；且计数一旦与明细不一致，
--     说明有并发核对正在写入，反而需要能对比出来（见 D）。
--
--  C. **`status` 三值就够**：IN_PROGRESS / COMPLETED / CANCELLED。
--     刻意**不做「草稿」态** —— 创建任务时明细已一次性快照完毕（见 ③ 的说明），
--     此时任务已经可以开始核对，多一个草稿态只会让人不知道要不要点「开始」。
--
--  D. **`completed_at` 与 `status` 分开**：终态时间点在报告与「谁在什么时候盘完的」都要用。
--
--  E. 索引两条：`status`（列表按状态筛）+ `created_at`（列表按时间倒序）。
--     盘点任务是低频数据（一个月几次），不需要为它做额外优化。
-- ----------------------------------------------------------------------------
CREATE TABLE `inventory_task`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `task_no`         VARCHAR(32)  NOT NULL COMMENT '任务编号（PC-日期-序号），展示与检索用',
    `task_name`       VARCHAR(100) NOT NULL COMMENT '任务名称（如「2026 Q4 研发部盘点」）',
    `scope_type`      VARCHAR(16)  NOT NULL
        COMMENT 'ALL 全量 / CATEGORY 按设备分类 / LOCATION 按存放位置',
    `scope_value`     VARCHAR(64)  NULL
        COMMENT '范围取值：CATEGORY 存分类 id；LOCATION 存存放位置名；ALL 为空',
    `scope_label`     VARCHAR(100) NOT NULL DEFAULT ''
        COMMENT '范围的中文描述快照（分类/位置名可能后来被改名，报告要能复现「当时盘的是哪一片」）',
    `status`          VARCHAR(16)  NOT NULL
        COMMENT 'IN_PROGRESS 进行中 / COMPLETED 已完成 / CANCELLED 已取消',
    `total_count`     INT          NOT NULL DEFAULT 0 COMMENT '本次范围内设备总数（创建时快照）',
    `checked_count`   INT          NOT NULL DEFAULT 0 COMMENT '已核对台数',
    `in_place_count`  INT          NOT NULL DEFAULT 0 COMMENT '核对结果=在库',
    `missing_count`   INT          NOT NULL DEFAULT 0 COMMENT '核对结果=缺失（盘亏）',
    `wrong_location_count` INT     NOT NULL DEFAULT 0 COMMENT '核对结果=位置不符',
    `created_by`      BIGINT       NOT NULL COMMENT '创建人用户 id',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `started_at`      DATETIME     NULL COMMENT '首次核对时间；尚未核对过为 NULL',
    `completed_at`    DATETIME     NULL COMMENT '完成时间；非终态为 NULL',
    `remark`          VARCHAR(500) NULL COMMENT '备注 / 盘点结论',
    PRIMARY KEY (`id`),
    KEY `idx_inv_task_status` (`status`),
    KEY `idx_inv_task_created` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '设备盘点任务（P2）';


-- ----------------------------------------------------------------------------
-- ③ `inventory_task_item` —— 盘点明细（逐台）
-- ----------------------------------------------------------------------------
-- 设计要点：
--
--  A. **明细在「创建任务」时一次性快照，而不是核对时动态查设备**。
--     盘点的本质是「以某个时点为准去核对」：若边盘边取实时值，盘点期间有人提交借用、
--     有人搬了位置，报告就会出现「同一台设备前后两次核对状态不同」的自相矛盾，
--     而盘点恰恰是要拿这份报告去追责的。
--     ⇒ 因此这里冗余存 `asset_no` / `device_name` / `storage_location` / `expected_status` 快照。
--
--  B. **`(task_id, device_id)` 建唯一键**：同一任务内同一台设备只能有一条明细。
--     没有它，重复点击「开始盘点」或重跑范围解析会插入重复行，
--     报告里的「总数」立刻虚高 —— 而这类重复不报错、只让数字变大，最难发现。
--
--  C. **`check_result` 允许 NULL**（尚未核对），而不是默认 'PENDING' 字符串。
--     NULL 的确切语义是「还没盘到」，与「盘了、结果是在库」天然可分；
--     用 PENDING 字符串则要在所有统计里排除它，漏一处就把它算成一种结果。
--     与项目既有取向一致（`orders.return_condition` 之外的状态列均用 NULL 表「未发生」）。
--
--  D. **`expected_status` 与 `check_result` 是两个不同维度**：
--     前者是「台账上写它是什么状态」，后者是「现场看到它在不在」。
--     一台「使用中」的设备被盘到「在库」是正常结果（它可能刚被归还），
--     所以二者不能合并成一个枚举。
-- ----------------------------------------------------------------------------
CREATE TABLE `inventory_task_item`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`          BIGINT       NOT NULL COMMENT '所属盘点任务',
    `device_id`        BIGINT       NOT NULL COMMENT '设备 id',
    `asset_no`         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '资产编号快照（扫码核对按它定位）',
    `device_name`      VARCHAR(100) NOT NULL DEFAULT '' COMMENT '设备名快照',
    `storage_location` VARCHAR(100) NULL COMMENT '存放位置快照',
    `expected_status`  VARCHAR(16)  NOT NULL
        COMMENT '创建任务时的设备状态快照（与「现场核对结果」是两个维度，见脚本头 ③D）',
    `check_result`     VARCHAR(16)  NULL
        COMMENT 'IN_PLACE 在库 / MISSING 缺失 / WRONG_LOCATION 位置不符；NULL=尚未核对',
    `checked_by`       BIGINT       NULL COMMENT '核对人用户 id；未核对为 NULL',
    `checked_at`       DATETIME     NULL COMMENT '核对时间；未核对为 NULL',
    `remark`           VARCHAR(200) NULL COMMENT '核对备注（如缺失时的说明）',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inv_item_task_device` (`task_id`, `device_id`),
    KEY `idx_inv_item_result` (`task_id`, `check_result`),
    KEY `idx_inv_item_asset` (`task_id`, `asset_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '设备盘点明细（P2）';


-- ----------------------------------------------------------------------------
-- ④ 幂等授予 admin 盘点权限
-- ----------------------------------------------------------------------------
-- 【为什么必须配这条迁移】授权行由 RolePermissionInitializer 按「逐角色标记」
--   （system_config.rbac_role_seeded:admin）播种，**一个角色只播一次**。
--   存量环境早已播过 ⇒ 只改代码里的 DEFAULT_PERMISSIONS 不会给它们补上，
--   表现为「管理员看不到『设备盘点』菜单、接口 403」。
--   本项目既定做法：每次为既有角色新增权限码都配一条幂等授予迁移
--   （V16 / V17 / V22 / V23 / V24 / V34 全是这个模式）。
--   本次代码侧同步在 `PermissionCatalog.DEFAULT_PERMISSIONS` 的 admin 集合里加这两个码。
--
-- 授予对象是 **admin**（= super_admin / admin 两个角色里的后者）：
--   盘点属「设备台账」同一职责域（admin 的职责描述就是「设备台账、全部工单、报表」）。
--   超管走 PermissionGuard 的短路放行，与授权行无关，因此不写。
--
-- 幂等性：沿用 V34 的 `WHERE NOT EXISTS` 写法，可安全重跑。
--   `FROM DUAL` 不可省：MySQL 的「无表 SELECT」不允许直接跟 WHERE（1064）。
-- ----------------------------------------------------------------------------

-- inventory:view —— 「设备盘点」菜单与任务/报告查询
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'inventory:view' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'inventory:view');

-- inventory:manage —— 创建任务 / 逐台核对 / 完成盘点 / 取消
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'inventory:manage' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_permission`
                  WHERE `role_code` = 'admin' AND `perm_code` = 'inventory:manage');
