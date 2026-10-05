-- =====================================================================
--  · ：在线一键升级
-- V30__phase18_online_upgrade.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求：新增「在线升级」任务的持久化载体（upgrade_tasks），
--   并给存量环境补上两个新权限码的授权口径。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **为什么升级任务必须落库，而不是只放在内存 / Redis 里**。
--     升级的本质是「替换正在运行的进程」，而**替换的那一瞬间进程一定会死**。
--     若任务状态只存在内存（或随进程消失的 Redis 会话）里，重启后这个任务就
--     凭空蒸发了：管理员回到页面只看到一片空白，既不知道上次升级成功没成功，
--     也不知道新版本到底上去没有 —— 这正是升级功能最需要回答的问题。
--     因此状态必须落**与升级目标同库**的 MySQL 表。
--
--  B. **为什么状态机里有一个 READY_TO_APPLY（已就绪待应用）而不是直接 APPLYING**。
--     后端进程**无法安全地替换自己正在运行的 jar / 正在被 Nginx 读取的 dist 目录**：
--     在 Windows 上是「文件被占用」直接失败，在 Linux 上替换成功但已加载的类不会生效，
--     仍然必须重启才能上版本。所以「替换 + 重启」必须交给**进程外的编排脚本**完成，
--     后端只负责「校验 → 备份 → 把新产物落到 staging → 写一条待应用记录」。
--     READY_TO_APPLY 就是后端职责的终点，也是运维视角的起点。
--     好处是：这条链路在**任何环境**（含没有 systemd / Docker 的开发机）都能完整跑通，
--     不依赖「进程真的重启成功」才能验收 —— 沙箱与真机共用同一套代码。
--
--  C. **为什么要有 `task_no`（业务号）而不是只用自增 id**。
--     外部编排脚本是 shell，需要用它去查 / 回写任务状态。自增 id 在「库被重建、
--     多环境并存」时不具备唯一语义；而 task_no 是「时间戳 + 随机后缀」的业务号，
--     可以安全地出现在脚本参数、日志文件名与 state 目录里的结果标记文件名中，
--     不会因为环境间 id 撞车而串台。
--
--  D. **为什么列 operator_id 不建外键（与 operation_logs 同策略）**。
--     升级记录是**运维审计线索**：谁在什么时候把什么版本推上去了。
--     这类记录不应随用户被清理而级联消失（CASCADE），也不应被置空（SET NULL）——
--     两种都会破坏「这条记录是谁发起的」。因此照 operation_logs 的办法：
--     存 operator_id 便于关联，同时**冗余 operator_name 快照**，
--     保证即使该账号日后被删，记录本身仍然自洽可读。
--
--  E. **为什么 package_sha256 是 CHAR(64) 且 NOT NULL**。
--     升级包一旦损坏或被篡改，替换上去的就是一个起不来的进程 ——
--     而这一刻通常发生在「深夜、没人盯着」的时候。SHA-256 是唯一能在替换前
--     就断言「这个包与打包时是同一个字节流」的手段，因此它必须入库留痕
--     （而非只用后端的计算值），事后审计才能回答「当时上的是不是这个包」。
--     十六进制 64 定长，用 CHAR 而非 VARCHAR（定长查询更省，且天然拒绝长度异常值）。
--
--  F. **不加 deleted 逻辑删除列**。
--     与 export_tasks 同取舍：升级任务同样需要独立索引与清晰状态，
--     且**记录数量是「每次发版一行」量级**（一年也不过几十行），
--     逻辑删除带来的「查询处处要过滤」成本远大于收益。
--     清理交给「只保留最近 N 条」的运维习惯即可。
--
--  G. **权限码为什么只给 super_admin，连 view 都不给 admin**。
--     `system:upgrade:view` 与 `system:upgrade:execute` 都不进
--     PermissionCatalog.defaultPermissions('admin')，也不在本脚本里给 admin 授权。
--     理由与 AD 域控一致：升级接口能替换服务器上的可执行文件，
--     等价于拿到了这台机器的代码执行能力，远超「业务管理员」的职责范围。
--     super_admin 不需要授权行 —— PermissionGuard 对它恒定短路放行，
--     这正是「系统永远有人能救回来」的兜底。
--     ⇒ 本脚本**故意没有 INSERT 语句**：新权限码不需要向任何内置角色下发，
--       见下方 H 的说明（这也是本迁移只有 DDL 的原因）。
--
--  H. **本脚本只建表，不插任何授权行**（与 V24 的区别就在这里）。
--     V24 要给 admin 补 `config:view`，因为那是「新增了一条本该存在的授权」。
--     本次相反：两个新码**本就只该归超管**，而超管由守卫短路放行、不依赖授权行。
--     因此没有任何 INSERT 需要补 —— 这一点必须写清楚，否则后人会以为漏写了。
--
--  I. **回滚方式**。
--     DROP TABLE `upgrade_tasks` 即可（纯新增表，无任何既有对象依赖它）。
--     两个权限码由 PermissionCatalog 定义，代码回滚后自动从授权树消失，
--     不需要 DELETE 任何 sys_role_permission 行。
--
--  J. **为什么用「生成列 + 唯一索引」做活跃任务互斥，而不是应用层加锁**。
--     升级期间绝对不能有两个任务并行：两个任务会各自解压到自己的 staging，
--     然后外部脚本可能同时替换同一个 current 目录 —— 最终留下的是「谁的最后一个文件」，
--     即一个 jar 与 dist 来自不同版本的混合产物，且**没有任何日志能说明这件事**。
--     应用层锁挡不住这种情况：HA 双跑下两个节点各持一把自己的锁，
--     会同时读到「当前没有活跃任务」而双双放行。
--     MySQL 8 没有「部分唯一索引」（WHERE status NOT IN (...)）语法，
--     而**唯一索引不约束 NULL** 这一特性恰好可以构造出等价效果：
--     活跃行取 1（互斥）、终态取 NULL（互不冲突）。这是本约束能成立的全部理由。
--     ⚠️ 副作用：一旦某个任务卡在 READY_TO_APPLY 而外部始终没应用它，
--        它会**一直阻塞后续升级**（唯一键冲突）。这是刻意的 fail-closed ——
--        宁可让管理员看到「已有未完成的升级任务，请先回滚或标记完成」，
--        也不能放任两个升级同时改同一份产物。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `upgrade_tasks` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `task_no`         VARCHAR(64)   NOT NULL                            COMMENT '升级业务号（时间戳+随机后缀），供外部编排脚本引用与回写',
  `package_name`    VARCHAR(255)  NOT NULL                            COMMENT '升级包原始文件名',
  `package_size`    BIGINT                 DEFAULT NULL               COMMENT '升级包大小（字节）',
  `package_sha256`  CHAR(64)      NOT NULL                            COMMENT '升级包 SHA-256（十六进制，替换前校验的唯一依据，入库留痕）',
  `source_version`  VARCHAR(64)            DEFAULT NULL               COMMENT '升级前版本（取当前 state/current.json，可为空）',
  `target_version`  VARCHAR(64)   NOT NULL                            COMMENT '升级包 manifest 声明的目标版本',
  `status`          VARCHAR(32)   NOT NULL                            COMMENT '任务状态：PENDING 待处理 / VALIDATING 校验中 / BACKING_UP 备份中 / STAGING 落盘中 / READY_TO_APPLY 已就绪待应用 / APPLYING 应用中 / SUCCESS 成功 / FAILED 失败 / ROLLED_BACK 已回滚',
  `step`            VARCHAR(32)            DEFAULT NULL               COMMENT '当前步骤（供进度展示，与 status 的区别：status 是结果态，step 是过程态）',
  `progress`        INT           NOT NULL DEFAULT 0                  COMMENT '进度百分比 0-100',
  `message`         VARCHAR(1000)          DEFAULT NULL               COMMENT '当前步骤说明 / 失败原因',
  `staging_path`    VARCHAR(512)           DEFAULT NULL               COMMENT '新产物落盘目录（相对 app.upgrade.storage-root 的相对路径）',
  `backup_path`     VARCHAR(512)           DEFAULT NULL               COMMENT '本次升级前的备份目录（相对 app.upgrade.storage-root 的相对路径）',
  `operator_id`     BIGINT                 DEFAULT NULL               COMMENT '发起人 user_id（刻意不建外键，见设计要点 D）',
  `operator_name`   VARCHAR(64)            DEFAULT NULL               COMMENT '发起人姓名冗余快照（保证账号被删后记录仍可读）',
  `started_at`      DATETIME               DEFAULT NULL               COMMENT '任务开始时间',
  `finished_at`     DATETIME               DEFAULT NULL               COMMENT '任务结束时间（终态时写入）',
  `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  -- 活跃互斥标记（见设计要点 J）：活跃任务为 1，终态为 NULL。
  -- 之所以用「生成列 + 唯一索引」而不是应用层加锁，是因为**应用层锁在分布式/双跑下必然失效**
  -- （两个节点各持一把自己的锁，同时看到「没有活跃任务」），而 MySQL 8 没有部分唯一索引语法。
  -- NULL 不参与唯一约束这一特性，恰好等价于「只对活跃行做唯一」，是标准写法。
  `active_flag`     TINYINT       GENERATED ALWAYS AS (
      CASE WHEN `status` IN ('PENDING', 'VALIDATING', 'BACKING_UP', 'STAGING', 'READY_TO_APPLY', 'APPLYING')
           THEN 1 ELSE NULL END
  ) STORED COMMENT '活跃任务互斥标记：活跃=1 / 终态=NULL（NULL 不参与唯一约束 ⇒ 等价于部分唯一索引）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_upgrade_task_no` (`task_no`),
  UNIQUE KEY `uk_upgrade_active`  (`active_flag`),
  KEY `idx_upgrade_status_created` (`status`, `created_at`),
  KEY `idx_upgrade_created`        (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '在线升级任务表（：校验/备份/落盘/应用/回滚全链路留痕）';
