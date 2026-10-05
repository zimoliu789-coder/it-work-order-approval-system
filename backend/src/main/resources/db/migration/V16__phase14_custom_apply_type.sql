-- =====================================================================
--  · 自定义申请类型 + 动态表单（第一期）
-- V16__phase14_custom_apply_type.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方本轮指示·第一期）：
--   二  数据库设计：form_template / form_template_version / apply_type / order_form_data
--   三 Step 4  orders 表加 apply_type_id，order_type 增加 CUSTOM 取值
--   三 Step 5  权限码 form_template:view|manage、apply_type:view|manage
--   六.6 演示数据（1 个示例表单模板 + 1 个启用的申请类型）由 dev 初始化器写入，见 DemoDataInitializer
--
-- 规范依据：
--   第 6 章  角色与权限｜第 11/12 章 申请与工单状态机
--   第 13.2 章 快照语义（配置变更不影响历史工单）｜第 20 章 操作审计｜第 25 章 附件上传
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **模板版本与实例分离**。表单定义存在 `form_template_version.schema_json`，
--     发布后**不可修改**——要改只能发新版本。这样「历史工单按什么表单填的」永远可追溯：
--     工单只引用 `form_template_version_id`，模板后续怎么改都不会改写历史工单的展示口径。
--     这与项目既有的「审批快照」思路完全一致（）。
--
--  B. **申请类型指向「已发布版本」而不是模板**。`apply_type.form_template_version_id`
--     直接指向某个具体版本，避免出现「模板改了版本号，申请类型跟着漂移」的二次解析。
--     服务层在创建/修改时校验该版本确实处于已发布态。
--
--  C. **order_form_data 与 orders 一对一**且 `order_id` 唯一。表单数据是工单的附属信息，
--     不并入 orders 宽表：① 字段随模板变化，宽表无法预知列；② 列表查询不需要该数据，
--     放独立表可避免大字段拖慢列表（ 列表只返回必要字段）。
--
--  D. **orders.apply_type_id 可空**。现有三种类型（短期借用 / 长期领用 / 故障报修）
--     该列恒为 NULL——用 NULL 表达「非自定义」，比塞 0 或空串更符合语义，
--     也让「按申请类型筛选」天然只命中自定义工单。
--
--  E. **权限码只补一条授权行**。权限目录是代码资产（PermissionCatalog），
--     但 `rbac_seeded` 是一次性初始化标记（V12 引入），重启不会回灌——
--     因此这里显式把 `apply_type:view` 补给内置 admin 角色（ Step 5「admin 默认给
--     apply_type:view，能看不能改」），并用「不存在才插入」保证幂等。
--     超管不受影响：PermissionGuard 对 super_admin 恒定短路放行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、form_template —— 表单模板
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `form_template` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `template_name` VARCHAR(64)  NOT NULL                            COMMENT '模板名称',
  `description`   VARCHAR(255)          DEFAULT NULL               COMMENT '模板说明',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'            COMMENT '模板状态：DRAFT 草稿 / PUBLISHED 已发布 / DISABLED 已停用',
  `created_by`    BIGINT                DEFAULT NULL               COMMENT '创建人 user_id',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_form_tpl_status` (`status`, `id`),
  KEY `idx_form_tpl_name` (`template_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '自定义表单模板（）';

-- ---------------------------------------------------------------------
-- 二、form_template_version —— 模板版本（发布后不可修改）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `form_template_version` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `template_id`  BIGINT       NOT NULL                            COMMENT '所属模板 form_template.id',
  `version_no`   INT          NOT NULL                            COMMENT '版本号（同一模板内从 1 递增）',
  `schema_json`  TEXT         NOT NULL                            COMMENT '表单字段定义 JSON：{"fields":[{key,label,type,...}]}',
  `published_at` DATETIME              DEFAULT NULL               COMMENT '发布时间',
  `published_by` BIGINT                DEFAULT NULL               COMMENT '发布人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ftv_template_version` (`template_id`, `version_no`),
  KEY `idx_ftv_template` (`template_id`, `version_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '表单模板版本（发布后冻结，）';

-- ---------------------------------------------------------------------
-- 三、apply_type —— 申请类型
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `apply_type` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `type_code`                VARCHAR(32)  NOT NULL                            COMMENT '类型编码（唯一，如 CGBX）',
  `type_name`                VARCHAR(64)  NOT NULL                            COMMENT '类型名称',
  `icon`                     VARCHAR(64)           DEFAULT NULL               COMMENT '图标（Element Plus 图标组件名）',
  `description`              VARCHAR(255)          DEFAULT NULL               COMMENT '类型说明',
  `sort_order`               INT          NOT NULL DEFAULT 100                COMMENT '排序号（升序）',
  `status`                   VARCHAR(16)  NOT NULL DEFAULT 'ENABLED'          COMMENT '状态：ENABLED 启用 / DISABLED 停用',
  `form_template_version_id` BIGINT       NOT NULL                            COMMENT '关联的表单模板版本（必须为已发布版本）',
  `order_prefix`             VARCHAR(10)           DEFAULT NULL               COMMENT '工单编号前缀（字母开头 2-10 位，可空）',
  `approval_mode`            VARCHAR(16)  NOT NULL DEFAULT 'GROUP'            COMMENT '审批方式：NONE 无审批 / GROUP 走分组审批流',
  `submit_permission_type`   VARCHAR(16)  NOT NULL DEFAULT 'ALL'              COMMENT '提交权限类型：ALL 全部 / ROLE 指定角色 / GROUP 指定分组',
  `submit_permission_value`  TEXT                  DEFAULT NULL               COMMENT '提交权限值 JSON：角色码数组或分组 id 数组（ALL 时存 []）',
  `created_by`               BIGINT                DEFAULT NULL               COMMENT '创建人 user_id',
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apply_type_code` (`type_code`),
  KEY `idx_apply_type_status_sort` (`status`, `sort_order`, `id`),
  KEY `idx_apply_type_ftv` (`form_template_version_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '自定义申请类型（）';

-- ---------------------------------------------------------------------
-- 四、order_form_data —— 工单自定义表单数据（与 orders 一对一）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_form_data` (
  `id`                       BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`                 BIGINT   NOT NULL                            COMMENT '关联 orders.id（唯一）',
  `form_template_version_id` BIGINT   NOT NULL                            COMMENT '填写时使用的表单模板版本',
  `form_data_json`           TEXT     NOT NULL                            COMMENT '用户填写的表单值 JSON：{字段key: 值}',
  `created_at`               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ofd_order` (`order_id`),
  KEY `idx_ofd_ftv` (`form_template_version_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单自定义表单数据（）';

-- ---------------------------------------------------------------------
-- 五、orders 表增量：apply_type_id + order_type 取值扩展
--
-- 只加列，不改任何既有列：现有三种类型的工单该列保持 NULL，语义即「非自定义申请」。
-- order_type 是 VARCHAR(16)，新增取值 CUSTOM 属数据层面变化，无需 DDL；
-- 这里更新列注释，让「表结构自解释」（运维直接看表也知道有哪些取值）。
-- ---------------------------------------------------------------------
ALTER TABLE `orders`
  ADD COLUMN `apply_type_id` BIGINT DEFAULT NULL
      COMMENT '自定义申请类型 apply_type.id（仅 order_type=CUSTOM 时有值，其余类型为 NULL）' AFTER `order_type`;

CREATE INDEX `idx_orders_apply_type` ON `orders` (`apply_type_id`, `id`);

ALTER TABLE `orders`
  MODIFY COLUMN `order_type` VARCHAR(16) NOT NULL DEFAULT 'BORROW'
      COMMENT '工单类型：BORROW 借用申请 / RETURN 归还单 / REPAIR 维修单 / EXCHANGE 换货单 / CUSTOM 自定义申请';

-- ---------------------------------------------------------------------
-- 五之二、把「借用流程专属」的列放开为可空
--
-- 背景： 建 orders 表时，只有「借用申请」一种单据形态，于是这些列被定义成
--       NOT NULL（甚至挂了外键）。 引入的自定义申请（采购申请、外出登记……）
--       既没有设备、也不需要业务分组与处理小组，更谈不上「使用地点」与「借用类型」——
--       若不放宽，插入自定义工单会直接违反 NOT NULL 约束，功能根本不可用。
--
-- 为什么是「放宽为可空」而不是「填占位值」：
--   给 device_id 填 0、给 use_place 填 ''，都会让「这单到底有没有设备」这件事
--   变得依赖于一个魔法值。而列表、报表、对账任务只要有一处忘了排除占位值，
--   就会出现「设备 #0 借出过 37 次」这类脏统计。用 NULL 表达「不适用」是唯一自洽的选择：
--   SQL 的 `IS NULL` 天然把「不适用」与「有值」区分开，所有既有统计（如使用记录的
--   `JOIN device ON d.id = o.device_id`）也会自动把无设备工单排除在外，无需改动任何一处。
--
-- 外键保持不变：MySQL 的外键对 NULL 值不做校验（NULL 视为「无引用」），
-- 因此 device_id / biz_group_id / final_handler_group_id 放宽后，原有 FK 约束依然正确 ——
-- 有值时照样保证引用完整性，无值时自然放行。
--
-- 业务含义说明（写入侧约定，见 OrderServiceImpl#submitCustomOrder）：
--   · device_id             = NULL（自定义申请不占用设备）
--   · use_type / use_place  = NULL（借用流程专属概念，对自定义申请不适用）
--   · reason                = NULL（自定义申请的内容在 order_form_data，不重复塞进 reason）
--   · biz_group_id          = 走分组审批时为申请人所属分组；无审批模式为 NULL
--   · final_handler_group_id= 同上（仅用于审批通过后 best-effort 分配办理人）
-- ---------------------------------------------------------------------
ALTER TABLE `orders`
  MODIFY COLUMN `device_id`              BIGINT       DEFAULT NULL COMMENT '申请设备 device.id（自定义申请为空）',
  MODIFY COLUMN `biz_group_id`           BIGINT       DEFAULT NULL COMMENT '快照：提交时申请人所属业务分组ID（自定义申请无审批模式时为空）',
  MODIFY COLUMN `use_type`               VARCHAR(16)  DEFAULT NULL COMMENT '借用类型：SHORT_TERM / LONG_TERM（自定义申请为空）',
  MODIFY COLUMN `reason`                 VARCHAR(500) DEFAULT NULL COMMENT '借用原因（自定义申请为空，内容见 order_form_data）',
  MODIFY COLUMN `use_place`              VARCHAR(128) DEFAULT NULL COMMENT '使用地点（自定义申请为空）',
  MODIFY COLUMN `final_handler_group_id` BIGINT       DEFAULT NULL COMMENT '快照：提交时绑定的最终处理小组ID（自定义申请无审批模式时为空）';

-- ---------------------------------------------------------------------
-- 六、RBAC：为内置 admin 角色补授 apply_type:view（幂等）
--
-- 为什么用 SQL 而不是改 RolePermissionInitializer 的默认集合：
--   初始化器受 `rbac_seeded` 一次性标记保护（V12 引入），在本系统已经跑过初始化的
--   环境里不会再次执行；只改代码默认集合，存量环境升级后 admin 会拿不到新菜单。
--   因此这里用「不存在才插入」把增量授权落成迁移，两种环境（新装 / 升级）结果一致。
-- 只授 view：需求明确「admin 能看不能改」，manage 恒归超管。
-- ---------------------------------------------------------------------
-- 注：`FROM DUAL` 不是装饰——MySQL 的「无表 SELECT」不允许直接跟 WHERE，
-- 缺了 DUAL 语句会在解析阶段失败（1064），迁移直接卡住。
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'apply_type:view' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission`
      WHERE `role_code` = 'admin' AND `perm_code` = 'apply_type:view'
  );
