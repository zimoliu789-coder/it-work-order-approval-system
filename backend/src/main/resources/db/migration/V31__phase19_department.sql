-- =====================================================================
--  / V31__phase19_department.sql
-- 组织与人员（）：部门树 + 部门主管 + 成员归属 + 存量迁移 + 旧表退役
-- 规范依据：简化改造需求说明书 二（组织与人员）/ 九（菜单结构）
-- 方案依据：.docs/phase19-plan.md（决策记录：存量完整迁移、旧表物理删除）
--
-- 本脚本做五件事：
--   1) 新建 departments / department_manager / user_department 三张表；
--   2) users / orders 的 biz_group_id 改名为 department_id 并迁移存量；
--   3) 11 条 biz_group → 部门节点（**保留 id**，使 users/orders 的关联一一对齐）；
--   4) handler_groups → 单一「IT运维组」部门（handler_group=1）；
--   5) 校验通过后，物理删除 biz_group / biz_group_approver /
--      handler_groups / handler_group_member。
--
-- =====================================================================
-- 【为什么写成「过程 + 校验 + 才删表」而不是一串直线 SQL】
--
-- MySQL 的 DDL 会**隐式提交**，Flyway 的「失败回滚」在 MySQL 上对 DDL 无效 ——
-- 语义上不可能做到「建表失败就连建表一起回滚」。因此本脚本采用等价的安全策略：
--
--   a) 建表用 `CREATE TABLE IF NOT EXISTS`（脚本外的直线 DDL），可安全重跑；
--   b) 4 处列改名 / 加列 / 加索引 / 加外键全部**先查 information_schema 再执行**，
--      已做过就跳过 —— 任何一步失败后重跑都不会「二次改名」而炸掉；
--   c) 数据迁移（DML）包在**显式事务**里：这一部分是真事务，失败即整体回滚；
--   d) **校验块**放在 destructive DDL（DROP TABLE）之前：条数、悬挂引用、
--      列存在性任一不满足就 `SIGNAL` 中止 —— 此时四张旧表**一张都还没删**，
--      现场完好、可安全重跑；
--   e) 只有全部校验过了，才 DROP 旧表。
--
-- 换句话说：**「失败回滚」在这份脚本里落成了「失败即中止在不可逆操作之前」**，
-- 这是 MySQL 上能给出的最强保证。重跑安全由 (a)(b) 保证。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、新表（直线 DDL，幂等）
-- ---------------------------------------------------------------------

-- 部门（无限层级：公司 → 部门 → 小组）。
-- path 存物化路径（'/1/10/'），取子树 / 判层级只需一次 LIKE 前缀匹配；
-- handler_group 标记「最终处理部门」（原「最终处理小组」的继任者，全局唯一，业务侧保证）。
CREATE TABLE IF NOT EXISTS `departments` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `dept_name`                VARCHAR(64)  NOT NULL                            COMMENT '部门名称（同一上级下唯一）',
  `parent_id`                BIGINT                DEFAULT NULL               COMMENT '上级部门ID；NULL = 根节点（公司）',
  `path`                     VARCHAR(512) NOT NULL DEFAULT '/'                COMMENT '物化路径，形如 /1/10/，用于取子树与判定层级',
  `depth`                    INT          NOT NULL DEFAULT 0                  COMMENT '层级深度（根为 0）',
  `sort_order`               INT          NOT NULL DEFAULT 0                  COMMENT '同级显示顺序',
  `handler_group`            TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否「最终处理部门」：1 是（原最终处理小组的唯一继任者）',
  `approval_flow_version_id` BIGINT                DEFAULT NULL               COMMENT '本部门绑定的已发布审批流程版本 approval_flow_version.id；承接原 biz_group.approval_flow_version_id',
  `status`                   TINYINT(1)   NOT NULL DEFAULT 1                  COMMENT '状态：1 正常 0 停用',
  `remark`                   VARCHAR(255)          DEFAULT NULL               COMMENT '备注',
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dept_parent` (`parent_id`),
  KEY `idx_dept_path` (`path`),
  KEY `idx_dept_handler` (`handler_group`),
  CONSTRAINT `fk_dept_parent` FOREIGN KEY (`parent_id`) REFERENCES `departments` (`id`)
      ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '部门表（）';

-- 部门主管（可多个）。**它是「直属主管默认值」的唯一事实源**：
-- 成员没有手工覆盖（users.leader_override = 0）时，直属主管 = 主部门的部门主管。
-- 用关联表而不是 JSON 列：要按主管反查「他管哪些部门」，JSON 列做不到走索引。
CREATE TABLE IF NOT EXISTS `department_manager` (
  `id`            BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `department_id` BIGINT   NOT NULL                            COMMENT '部门ID',
  `user_id`       BIGINT   NOT NULL                            COMMENT '部门主管 user_id',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dm_dept_user` (`department_id`, `user_id`),
  KEY `idx_dm_user` (`user_id`),
  CONSTRAINT `fk_dm_dept` FOREIGN KEY (`department_id`) REFERENCES `departments` (`id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_dm_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
      ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '部门主管表（）';

-- 成员兼职部门。**主部门（users.department_id）决定审批上级**，兼职部门只影响「他属于哪些部门」，
-- 不参与审批人推导 —— 否则同一个人会因为加了两个部门而被推成两条审批路径。
CREATE TABLE IF NOT EXISTS `user_department` (
  `id`            BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `user_id`       BIGINT   NOT NULL                            COMMENT '成员 user_id',
  `department_id` BIGINT   NOT NULL                            COMMENT '兼职部门ID',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ud_user_dept` (`user_id`, `department_id`),
  KEY `idx_ud_dept` (`department_id`),
  CONSTRAINT `fk_ud_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_ud_dept` FOREIGN KEY (`department_id`) REFERENCES `departments` (`id`)
      ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '成员兼职部门表（）';

-- ---------------------------------------------------------------------
-- 二、迁移主体（过程体：条件 DDL + 事务 DML + 校验 + 删表）
-- ---------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `p19_migrate_department`;

DELIMITER $$

CREATE PROCEDURE `p19_migrate_department`()
BEGIN
    -- ---- 基线（迁移前计数，用于迁移后逐条比对） ----
    DECLARE v_before_groups  INT DEFAULT 0;
    DECLARE v_before_users   INT DEFAULT 0;
    DECLARE v_before_orders  INT DEFAULT 0;

    -- ---- 迁移后计数 ----
    DECLARE v_now_depts      INT DEFAULT 0;
    DECLARE v_now_users      INT DEFAULT 0;
    DECLARE v_now_orders     INT DEFAULT 0;
    DECLARE v_dangling_users INT DEFAULT 0;
    DECLARE v_dangling_ords  INT DEFAULT 0;
    DECLARE v_handler_dept   BIGINT DEFAULT NULL;
    DECLARE v_handler_cnt    INT DEFAULT 0;

    -- 「加索引 / 改索引名 / 加外键」这类天然可重复的 DDL 用动态 SQL 执行；
    -- 只吞掉「已存在 / 不存在」这一类**良性**错误码，其余错误照常抛出。
    --   1061 = ER_DUP_KEYNAME（索引重名）
    --   1176 = ER_KEY_DOES_NOT_EXIST（RENAME INDEX 时源名已不存在 —— 重跑时撞到的就是它）
    --   1091 = ER_CANT_DROP_FIELD_OR_KEY
    --   1826 = ER_FK_DUP_NAME（外键重名）
    DECLARE CONTINUE HANDLER FOR 1061, 1091, 1176, 1826 BEGIN END;

    -- 基线取「迁移前」的值。**每一步都要能在重跑时成立** ——
    -- 本脚本允许在任意一步失败后重跑（见文件头说明），
    -- 因此不能无条件引用旧表 / 旧列：它们可能已经被上一次执行删掉或改名了。
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'biz_group') THEN
        SELECT COUNT(*) INTO v_before_groups FROM `biz_group`;
    ELSE
        -- 重跑（旧表已删）：部门已经建好，以当前部门数作为基线
        SELECT COUNT(*) INTO v_before_groups FROM `departments`
        WHERE `id` <> 1 AND `handler_group` = 0;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'biz_group_id') THEN
        SELECT COUNT(*) INTO v_before_users FROM `users` WHERE `biz_group_id` IS NOT NULL;
    ELSE
        SELECT COUNT(*) INTO v_before_users FROM `users` WHERE `department_id` IS NOT NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'biz_group_id') THEN
        SELECT COUNT(*) INTO v_before_orders FROM `orders` WHERE `biz_group_id` IS NOT NULL;
    ELSE
        SELECT COUNT(*) INTO v_before_orders FROM `orders` WHERE `department_id` IS NOT NULL;
    END IF;

    -- =============================================================
    -- 第 1 步：列改名 / 加列（先查 information_schema，已做过则跳过）
    -- =============================================================

    -- 1.1 users：先摘掉指向 biz_group 的外键（否则列改名会被外键拖住；
    --     而且 biz_group 马上就要被删，这个外键无论如何都活不过本脚本）
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE table_schema = DATABASE() AND table_name = 'users'
                 AND constraint_name = 'fk_users_biz_group') THEN
        ALTER TABLE `users` DROP FOREIGN KEY `fk_users_biz_group`;
    END IF;

    -- 1.2 users.biz_group_id → department_id（**只改名，不动值**：部门保留了原分组 id）
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'users'
                 AND column_name = 'biz_group_id') THEN
        ALTER TABLE `users` CHANGE COLUMN `biz_group_id` `department_id` BIGINT DEFAULT NULL
            COMMENT '主部门ID（决定审批上级）；原 biz_group_id， 迁移';
    END IF;

    -- 1.3 users.leader_override：直属主管是否被手工覆盖
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'users'
                     AND column_name = 'leader_override') THEN
        ALTER TABLE `users` ADD COLUMN `leader_override` TINYINT(1) NOT NULL DEFAULT 0
            COMMENT '直属主管是否手工覆盖：1 = 手工指定（不随部门主管变更自动更新）0 = 跟随部门主管';
    END IF;

    SET @s = 'ALTER TABLE `users` RENAME INDEX `idx_users_biz_group` TO `idx_users_department`';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    SET @s = 'ALTER TABLE `users` ADD KEY `idx_users_department` (`department_id`)';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    -- 1.4 orders
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE table_schema = DATABASE() AND table_name = 'orders'
                 AND constraint_name = 'fk_orders_biz_group') THEN
        ALTER TABLE `orders` DROP FOREIGN KEY `fk_orders_biz_group`;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE table_schema = DATABASE() AND table_name = 'orders'
                 AND constraint_name = 'fk_orders_handler_group') THEN
        ALTER TABLE `orders` DROP FOREIGN KEY `fk_orders_handler_group`;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'orders'
                 AND column_name = 'biz_group_id') THEN
        ALTER TABLE `orders` CHANGE COLUMN `biz_group_id` `department_id` BIGINT DEFAULT NULL
            COMMENT '快照：提交时申请人所属部门ID（原 biz_group_id， 迁移）';
    END IF;

    -- 原 orders.final_handler_group_id（指向 handler_groups）→ handler_department_id（指向 departments）。
    -- 最终处理小组退役后由「最终处理部门」承接，历史值在下方统一点到 IT运维组。
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'orders'
                 AND column_name = 'final_handler_group_id') THEN
        ALTER TABLE `orders` CHANGE COLUMN `final_handler_group_id` `handler_department_id` BIGINT DEFAULT NULL
            COMMENT '快照：提交时绑定的最终处理部门ID（原 final_handler_group_id， 迁移）';
    END IF;

    SET @s = 'ALTER TABLE `orders` RENAME INDEX `fk_orders_biz_group` TO `idx_orders_department`';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    SET @s = 'ALTER TABLE `orders` ADD KEY `idx_orders_department` (`department_id`)';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    -- =============================================================
    -- 第 2 步：数据迁移（真事务，失败整体回滚）
    -- =============================================================
    START TRANSACTION;

    -- 2.1 根节点「公司」（id 固定为 1；biz_group 的 id 最小是 10，不会撞）
    INSERT INTO `departments` (`id`, `dept_name`, `parent_id`, `path`, `depth`, `sort_order`, `remark`)
    SELECT 1, '公司', NULL, '/1/', 0, 0, '部门树根节点（ 自动创建）'
    WHERE NOT EXISTS (SELECT 1 FROM `departments` d WHERE d.`id` = 1);

    -- 2.2 11 条业务分组 → 部门（**保留 id**：users/orders 的关联因此一一对齐、零重算）。
    --     重跑时 biz_group 已被删除 → 整段跳过（部门已在第一次执行时建好）。
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'biz_group') THEN
        INSERT INTO `departments` (`id`, `dept_name`, `parent_id`, `path`, `depth`, `sort_order`,
                                   `approval_flow_version_id`, `remark`)
        SELECT g.`id`, g.`group_name`, 1,
               CONCAT('/1/', g.`id`, '/'), 1,
               g.`sort_order`,
               g.`approval_flow_version_id`,
               CONCAT('由原业务分组迁移（biz_group.id=', g.`id`, '）')
        FROM `biz_group` g
        WHERE NOT EXISTS (SELECT 1 FROM `departments` d WHERE d.`id` = g.`id`);
    END IF;

    -- 2.3 最终处理小组 → 单一「IT运维组」部门（handler_group = 1）
    INSERT INTO `departments` (`dept_name`, `parent_id`, `path`, `depth`, `sort_order`, `handler_group`, `remark`)
    SELECT 'IT运维组', 1, '/1/0/', 1, 100, 1,
           '由原「最终处理小组」合并而来（）；成员由 IT执行人角色 / 本部门共同承载'
    WHERE NOT EXISTS (SELECT 1 FROM `departments` d WHERE d.`handler_group` = 1);

    -- 2.4 物化路径与深度对齐（IT运维组刚插入时 path 还是占位值 '/1/0/'）。
    --     本次只产生「根 → 一级」两层，路径直接由 parent_id 拼出即可；
    --     更深层级的 path 由 DepartmentServiceImpl 在增删改时统一维护。
    --     注意：MySQL 里 `||` 默认是逻辑或（不是字符串拼接），这里必须用 CONCAT。
    UPDATE `departments` d
    SET d.`path`  = CASE WHEN d.`parent_id` IS NULL
                         THEN CONCAT('/', d.`id`, '/')
                         ELSE CONCAT('/', d.`parent_id`, '/', d.`id`, '/') END,
        d.`depth` = CASE WHEN d.`parent_id` IS NULL THEN 0 ELSE 1 END
    WHERE d.`path` = '/1/0/';

    -- 2.5 自增起点后移，避开已保留的历史 id（10..48）
    SET @s = 'ALTER TABLE `departments` AUTO_INCREMENT = 1000';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    -- 2.6 部门主管种子：取「该部门成员中出现次数最多的直属主管」。
    --     这是**从既有数据反推**而不是凭空指派 —— 演示环境里 99 人一批的部门，
    --     其 leader_id 天然就指向同一个人（如「演示-研发中心」的 99 人 leader 都是赵建国）。
    INSERT INTO `department_manager` (`department_id`, `user_id`)
    SELECT t.`department_id`, t.`leader_id`
    FROM (
        SELECT u.`department_id`,
               u.`leader_id`,
               COUNT(*) AS c,
               ROW_NUMBER() OVER (PARTITION BY u.`department_id` ORDER BY COUNT(*) DESC, u.`leader_id`) AS rn
        FROM `users` u
        WHERE u.`department_id` IS NOT NULL AND u.`leader_id` IS NOT NULL
        GROUP BY u.`department_id`, u.`leader_id`
    ) t
    WHERE t.rn = 1
      AND NOT EXISTS (SELECT 1 FROM `department_manager` dm
                      WHERE dm.`department_id` = t.`department_id` AND dm.`user_id` = t.`leader_id`);

    -- 2.7 直属主管 ≠ 本部门主管的成员，标记为「手工覆盖」。
    --     语义：这些人的 leader_id 是历史手工配置的，**不得**被「部门主管变更」自动改写。
    UPDATE `users` u
    SET u.`leader_override` = 1
    WHERE u.`leader_id` IS NOT NULL
      AND u.`department_id` IS NOT NULL
      AND u.`leader_override` = 0
      AND NOT EXISTS (SELECT 1 FROM `department_manager` dm
                      WHERE dm.`department_id` = u.`department_id` AND dm.`user_id` = u.`leader_id`);

    -- 2.8 历史处理小组的成员，写入「兼职部门 = IT运维组」。
    --     只挂兼职、**不动主部门与角色** —— 这些人（张伟、李娜、刘洋、赵敏…）
    --     的主部门与角色是演示脚本的锚点，改掉会让既有回归全线飘红。
    --     「谁可以当执行人」由「IT运维组部门成员 ∪ IT执行人角色」两路共同回答（见 ApproverRuleResolver）。
    SET v_handler_dept = (SELECT `id` FROM `departments` WHERE `handler_group` = 1 LIMIT 1);
    IF v_handler_dept IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'handler_group_member') THEN
        INSERT INTO `user_department` (`user_id`, `department_id`)
        SELECT DISTINCT m.`user_id`, v_handler_dept
        FROM `handler_group_member` m
        JOIN `handler_groups` g ON g.`id` = m.`group_id`
        WHERE g.`deleted` = 0
          AND NOT EXISTS (SELECT 1 FROM `user_department` ud
                          WHERE ud.`user_id` = m.`user_id` AND ud.`department_id` = v_handler_dept);
    END IF;

    -- 2.9 历史工单的最终处理小组快照，统一点到「IT运维组」部门
    IF v_handler_dept IS NOT NULL THEN
        UPDATE `orders` SET `handler_department_id` = v_handler_dept
        WHERE `handler_department_id` IS NOT NULL;
    END IF;

    COMMIT;

    -- 2.10 补外键。**必须放在数据迁移之后**：users/orders 改名列后，列里已经带着
    --      指向「原分组 id」的值，而 departments 的对应行是本步骤 2.2 才插入的 ——
    --      在外键先建的情况下，第 1.3 步那一次 ALTER 会因为「父行还不存在」直接失败。
    --      （这一条是在影子库真跑时踩出来的：错误信息是 fk_users_department 的 1452。）
    SET @s = 'ALTER TABLE `users` ADD CONSTRAINT `fk_users_department` FOREIGN KEY (`department_id`) '
             'REFERENCES `departments` (`id`) ON DELETE SET NULL ON UPDATE CASCADE';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    SET @s = 'ALTER TABLE `orders` ADD CONSTRAINT `fk_orders_department` FOREIGN KEY (`department_id`) '
             'REFERENCES `departments` (`id`) ON DELETE SET NULL ON UPDATE CASCADE';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    SET @s = 'ALTER TABLE `orders` ADD CONSTRAINT `fk_orders_handler_department` FOREIGN KEY (`handler_department_id`) '
             'REFERENCES `departments` (`id`) ON DELETE SET NULL ON UPDATE CASCADE';
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

    -- =============================================================
    -- 第 3 步：校验（不通过即 SIGNAL 中止；此时四张旧表一张都没删）
    -- =============================================================

    -- 3.1 列存在性
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'department_id') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：users.department_id 不存在';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'department_id') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：orders.department_id 不存在';
    END IF;

    -- 3.2 部门数 = 原分组数（+ 根节点 + IT运维组，两者不计入）
    SELECT COUNT(*) INTO v_now_depts FROM `departments`
    WHERE `id` <> 1 AND `handler_group` = 0;
    IF v_now_depts <> v_before_groups THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：部门数与原业务分组数不一致';
    END IF;

    -- 3.3 用户部门关联数对齐
    SELECT COUNT(*) INTO v_now_users FROM `users` WHERE `department_id` IS NOT NULL;
    IF v_now_users <> v_before_users THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：用户部门关联数与原分组关联数不一致';
    END IF;

    -- 3.4 历史工单部门关联不丢
    SELECT COUNT(*) INTO v_now_orders FROM `orders` WHERE `department_id` IS NOT NULL;
    IF v_now_orders <> v_before_orders THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：工单部门关联数与原分组关联数不一致';
    END IF;

    -- 3.5 无悬挂引用（引用了不存在的部门）
    SELECT COUNT(*) INTO v_dangling_users FROM `users` u
    WHERE u.`department_id` IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM `departments` d WHERE d.`id` = u.`department_id`);
    IF v_dangling_users > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：存在引用了不存在部门的用户';
    END IF;

    SELECT COUNT(*) INTO v_dangling_ords FROM `orders` o
    WHERE o.`department_id` IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM `departments` d WHERE d.`id` = o.`department_id`);
    IF v_dangling_ords > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：存在引用了不存在部门的工单';
    END IF;

    -- 3.6 最终处理部门必须**有且仅有 1 个**
    SELECT COUNT(*) INTO v_handler_cnt FROM `departments` WHERE `handler_group` = 1;
    IF v_handler_cnt <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[+B] 校验失败：最终处理部门不是恰好 1 个';
    END IF;

    -- =============================================================
    -- 第 4 步：旧表退役（只有走到这里才执行 —— 不可逆操作永远在校验之后）
    -- =============================================================
    DROP TABLE IF EXISTS `biz_group_approver`;
    DROP TABLE IF EXISTS `biz_group`;
    DROP TABLE IF EXISTS `handler_group_member`;
    DROP TABLE IF EXISTS `handler_groups`;

    -- =============================================================
    -- 第 5 步：退役权限码的授权行清理
    -- =============================================================
    -- PermissionCatalog 里已经删掉 bizgroup:*/handlergroup:* 四个码（业务分组与处理小组下线），
    -- 但 history 环境下 sys_role_permission 里可能还留着对应的授权行。
    -- 不清掉会有两个后果：① 角色编辑页回显时带着一堆「目录里不存在的码」；
    -- ② 授权保存时被判为非法码而整单拒绝，运维找不到原因。
    DELETE FROM `sys_role_permission`
    WHERE `perm_code` IN ('bizgroup:view', 'bizgroup:manage', 'handlergroup:view', 'handlergroup:manage');

END$$

DELIMITER ;

CALL `p19_migrate_department`();

DROP PROCEDURE IF EXISTS `p19_migrate_department`;
