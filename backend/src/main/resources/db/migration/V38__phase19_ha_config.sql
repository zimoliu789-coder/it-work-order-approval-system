-- ============================================================================
--  —— 主备双机热备配置（需求文档 八·）
-- V38__phase19_ha_config.sql
-- 企业内部设备借用工单系统
-- ----------------------------------------------------------------------------
-- 本脚本新建两张表：
--   ① `ha_config` —— 主备配置的**全局单行**（启用开关 / 域名 / 虚拟 IP / 心跳阈值 / 同步与切换的运行态快照）；
--   ② `ha_node`   —— 参与主备的**节点清单**（节点名 / IP / 角色 / 状态 / 是否本机 / 最后心跳）。
--
-- ----------------------------------------------------------------------------
-- ⚠️ 关于版本号：本脚本编号是 V38，而 `.docs/phase19-plan.md` 里写的是 V32。
--    原因：该计划成文时 Flyway 最新只到 V31，其后~F 又消耗了 V32~V37
--    （V37 见 `V37__phase19_system_copyright.sql`）。迁移号必须严格单调递增，
--    因此本次顺延到 V38。**编号不同、内容与需求不变**，特此标注以免后来者误以为漏做了 V32。
-- ----------------------------------------------------------------------------
--
-- ============================================================================
-- 设计要点（为什么这样做）
-- ============================================================================
--
--  A. **为什么 `ha_config` 独立成表，而不是往 `system_config` 里塞几个键**。
--     与 V15 的 `ad_config` 同源的理由（见该脚本类头注释）：`system_config` 的定位是
--     「键值型、可散落增删的可调参数」，而主备配置是一组**必须整体自洽**的字段 ——
--     「启用了主备但虚拟 IP 是空的」这种组合在键值化之后，库层面完全不可见、也无法约束，
--     只能在界面上靠代码临时判断。独立成表 + 单行约束把这条不变式下沉到结构里。
--     另外，运行态快照（最后同步时间 / 复制延迟 / 最后切换时间）会被**心跳线程频繁改写**，
--     放进 `system_config` 会让整个参数表（含附件保留天数、限流阈值…）每秒被写一次，
--     顺带把参数缓存打穿。
--
--  B. **为什么用「单行表 + `singleton_key` 唯一索引」，而不是 `id = 1` 的约定**。
--     照抄 V15 的 `uk_ad_config_singleton` 做法：靠唯一索引让**数据库**保证只有一行。
--     若只靠「代码里只 insert 一次」，一次并发初始化或一句人工 SQL 就能造出第二行 ——
--     此后「读到哪一行取决于 MySQL 返回顺序」，是最难复现的一类配置漂移。
--
--  C. **文本列一律 `NOT NULL DEFAULT ''`，不用 NULL**。
--     这是本项目的一条硬约定（/E 各踩过一次「`updateById` 写不了 null ⇒ 留空清不掉」）：
--     这些列在代码里走「显式 UpdateWrapper 逐列 set」来支持清空，而既有表示是**空串** ——
--     写成 NULL 会被数据库用 `Column 'domain' cannot be null` 直接拒掉。
--     ⚠️ 全表刻意保留为 NULL 的只有四个**运行态**时间/数值列
--     （`last_sync_at` / `last_switch_at` / `sync_delay_seconds`，以及节点表的 `last_heartbeat_at`）：
--     它们的 NULL 有确切语义 —— 「从未同步过 / 从未切换过 / 还没收到过心跳」，
--     与「同步延迟 0 秒」是两回事（后者代表数据完全一致）。
--
--  D. **`enabled` 默认 0**。绝大多数部署是单机，主备是**可选能力**；
--     默认开启会让这些环境的配置页一进来就是「已启用但没有任何节点」的自相矛盾状态。
--     与 `app.upgrade.enabled` 同取向：能力型开关默认关闭，要用先显式打开。
--
--  E. **`heartbeat_timeout_seconds` 默认 10**。需求文档 [174] 行原文
--     「主节点故障（心跳超时，默认10秒）」—— 这是唯一一个需求方写死了默认值的参数，
--     照抄，不擅自改动。
--
--  F. **为什么把运行态快照（`sync_state` / `sync_delay_seconds` / `last_sync_at`）
--     落在单行配置上，而不是每次查询都去读复制状态**。
--     读取真实复制延迟需要应用连上 MySQL 执行 `SHOW REPLICA STATUS`，
--     在**备节点库不可达**（正是要告警的场景）时这条查询本身就会挂住或报错 ——
--     于是「读取故障状态」反而成了新的故障点。因此由内部心跳脚本定期上报，
--     应用只读这一行快照：读操作恒为一次主键查询，绝不因对端不可达而阻塞。
--
--  G. **`ha_node` 为什么 `node_ip` 唯一**。同一 IP 出现在两行意味着
--     「同一个物理机被登记了两次」——这会让心跳更新命中不确定的行，
--     表现为「节点状态随机在运行中/异常之间跳」。用唯一键把它挡在写入之前。
--     不可用 `node_name` 做唯一键：节点名是人为填写的展示名，允许多副本环境下重复。
--
--  H. **为什么 `ha_node` 不加 `@TableLogic` / 不加 `deleted` 列**。
--     节点是「低基数、可反复增删」的运维对象（一年最多几十行），
--     逻辑删除只会让每一条查询都多带一个条件，却换不来任何实际收益
--     （与 `upgrade_tasks` / `export_tasks` 同一取舍）。移除备节点直接物理删除，
--     其历史痕迹由操作审计（`operation_log`）承担 —— 那才是「谁在什么时候删了哪个节点」的正确落点。
--
--  I. **权限码：本脚本不插入任何授权行**。`ha:view` / `ha:manage` 由
--     随权限目录重构时**已预埋**（见 `PermissionCatalog`：`menu(HA_VIEW,"主备配置","/system/ha")`），
--     且**刻意只归超级管理员**（不在 `DEFAULT_PERMISSIONS` 中）——
--     与 `ad:view` / `system:upgrade:view` 同取向。超管由 `PermissionGuard` 全量短路放行，
--     因此 `sys_role_permission` 里不需要、也不应该有 `ha:%` 行。
--     演示库现场核对：`sys_role_permission` 当前无任何 `ha:%` 行 —— 与预期一致。
--
--  J. **种子数据**：只播种 `ha_config` 的唯一一行（`enabled=0` + 心跳 10 秒），
--     **不播种任何 `ha_node` 行**。理由：主节点行应由「开启开关」这一动作
--     用当时真实的 `node_name` / `node_ip` 注册（见 `HaConfigService#setEnabled`），
--     迁移期填占位值只会让界面显示一台并不存在的机器。
--
--  K. **回滚方式**：`DROP TABLE ha_node; DROP TABLE ha_config;` 即可，
--     代码侧 `HaConfigService#current()` 会对缺表 / 缺行做兜底补建（与 `AdConfigServiceImpl` 同款兜底）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ① ha_config —— 主备配置全局单行
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ha_config` (
  `id`                        BIGINT       NOT NULL AUTO_INCREMENT,
  `singleton_key`             TINYINT      NOT NULL DEFAULT 1
                              COMMENT '单行约束位：恒为 1，配合 uk_ha_config_singleton 保证本表只能有一行',

  -- 能力开关
  `enabled`                   TINYINT(1)   NOT NULL DEFAULT 0
                              COMMENT '是否启用主备双机热备：0 关闭（下面的配置全部置灰）/ 1 启用',

  -- 本机身份（由「开启开关」或心跳上报填充，供同机判断与展示）
  `node_name`                 VARCHAR(64)  NOT NULL DEFAULT ''
                              COMMENT '本机节点名（展示用，如 ticket-master）',
  `node_ip`                   VARCHAR(64)  NOT NULL DEFAULT ''
                              COMMENT '本机 IP（心跳上报填充；用于和节点表对齐「谁是本机」）',

  -- 员工访问入口
  `domain`                    VARCHAR(128) NOT NULL DEFAULT ''
                              COMMENT '员工统一访问的域名（如 oa.company.com）；DNS 解析到虚拟 IP，主备切换时不变',
  `vip_web`                   VARCHAR(64)  NOT NULL DEFAULT ''
                              COMMENT 'Web 虚拟 IP（漂移 IP），如 192.168.1.100；员工统一访问它，不用管哪台是主',
  `vip_db`                    VARCHAR(64)  NOT NULL DEFAULT ''
                              COMMENT '数据库虚拟 IP（与 Web VIP 相互独立，见 deploy/DEPLOY.md ）',
  `vrrp_iface`                VARCHAR(32)  NOT NULL DEFAULT ''
                              COMMENT 'keepalived VRRP 使用的网卡名（如 eth0）；留空则用节点默认路由网卡',

  -- 切换判据
  `heartbeat_timeout_seconds` INT          NOT NULL DEFAULT 10
                              COMMENT '心跳超时（秒）：超过此时长未收到对端心跳即判定故障并触发接管；需求文档默认 10',

  -- 运行态快照（由内部心跳脚本定期上报，应用只读）
  `sync_state`                VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN'
                              COMMENT '数据一致性：IN_SYNC 一致 / LAGGING 落后 / FAILED 失败 / UNKNOWN 未知',
  `sync_delay_seconds`        INT          NULL DEFAULT NULL
                              COMMENT '复制延迟（秒）；NULL = 从未上报过（与「延迟 0」不同）',
  `last_sync_at`              DATETIME     NULL DEFAULT NULL
                              COMMENT '最近一次成功同步的时间；NULL = 从未同步过',
  `last_switch_at`            DATETIME     NULL DEFAULT NULL
                              COMMENT '最近一次主备切换的时间（手动或自动）；NULL = 从未切换过',

  `created_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ha_config_singleton` (`singleton_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '主备双机热备配置（全局单行，）';


-- ----------------------------------------------------------------------------
-- ② ha_node —— 参与主备的节点清单
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ha_node` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `node_name`         VARCHAR(64)  NOT NULL DEFAULT ''
                      COMMENT '节点名称（展示用，如 ticket-master / ticket-slave）',
  `node_ip`           VARCHAR(64)  NOT NULL
                      COMMENT '节点 IP（含端口或纯 IP 都可，按维护人员填写原样保存）',
  `node_role`         VARCHAR(16)  NOT NULL DEFAULT 'STANDBY'
                      COMMENT '节点角色：MASTER 主节点 / STANDBY 备节点',
  `node_status`       VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN'
                      COMMENT '运行状态：RUNNING 运行中 / STANDBY 待命 / ABNORMAL 异常 / UNKNOWN 未知',
  `is_local`          TINYINT(1)   NOT NULL DEFAULT 0
                      COMMENT '是否本机节点：0 否 / 1 是（用于「当前节点」卡片与禁止自删自切）',
  `last_heartbeat_at` DATETIME     NULL DEFAULT NULL
                      COMMENT '最后一次收到心跳的时间；NULL = 尚未收到过心跳',
  `remark`            VARCHAR(255) NOT NULL DEFAULT ''
                      COMMENT '备注（维护人员备注用途，留空表示不填）',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ha_node_ip` (`node_ip`),
  KEY `idx_ha_node_role` (`node_role`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '主备节点清单（）';


-- ----------------------------------------------------------------------------
-- ③ 种子：播种 ha_config 的唯一一行（幂等）
-- ----------------------------------------------------------------------------
-- 只播种开关与心跳阈值；域名 / 虚拟 IP / 本机身份一律留空串，
-- 由维护人员在「主备配置」页按 3 步配置逐项填入。
-- 幂等写法：`INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`（照 V16/V22/V23/V24/V25/V35/V37 的模式）；
-- `FROM DUAL` 不是装饰 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，缺了它在解析阶段就报 1064。
INSERT INTO `ha_config` (`singleton_key`, `enabled`, `heartbeat_timeout_seconds`)
SELECT 1, 0, 10
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `ha_config`);
