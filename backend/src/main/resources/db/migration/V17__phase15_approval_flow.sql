-- =====================================================================
--  · 动态审批流程（自定义申请第二期）
-- V17__phase15_approval_flow.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-20 确认）：
--   一 审批流程模板独立管理（系统管理 → 审批流程模板），支持 CRUD / 草稿 / 发布 / 版本冻结
--   二 申请类型关联「已发布的流程版本」，一个流程模板可被多个类型引用
--   三 流程可含：审批节点（节点名 / 审批人来源 / 会签或签）、条件分支（按表单字段）、结束节点
--   四 修改流程发布新版本：审批中的工单走旧版本，新提交走新版本
--   五 权限：approval_flow:view（admin 只读）、approval_flow:manage（仅超管）
--
-- 规范依据：
--   第 13.2 章 快照语义（配置变更不影响历史工单）
--   第 6 章    角色与权限｜第 20 章 操作审计
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **与一期「表单模板 + 版本」完全同构**（V16 的 form_template / form_template_version）。
--     理由不是"照抄省事"，而是这一类需求的共性：流程定义是**会被反复修改的配置**，
--     而工单是**不可回改的历史**。二者一旦耦合，改一次流程就会改写所有历史工单的审批口径。
--     因此同样是「模板（可改）」+「版本（发布即冻结）」两层，工单只引用**版本 id**。
--
--  B. **流程定义整体存 JSON（definition_json）而不是拆成"节点表 + 连线表"**。
--     权衡：拆表能靠外键约束连线，但流程定义是**一次性整体读写**的（设计器里整棵画布一起保存），
--     拆表会带来"保存时要 diff 增删改三类行"的复杂度，且连线合法性（可达/无环/穷尽分支）
--     本就必须由**服务层校验器**判定，外键给不了这些保证。
--     结论：整体 JSON + 发布时严格校验，是复杂性与正确性的更优点。
--     校验产物（node_count）冗余成一列，供列表展示与"发布前必须至少有 1 个审批节点"快速判断。
--
--  C. **apply_type.approval_flow_version_id 可空**，与一期 form_template_version_id 的"必填"
--     刻意不同：一期的表单是自定义申请的**必要**组成（没有表单就无从填写），
--     而流程是**可选**的——一期已交付的 NONE（无审批）/ GROUP（走分组审批）必须继续可用，
--     新建类型也可以继续选这两种。用 NULL 表达"不使用独立审批流程"，语义自洽且向后兼容。
--
--  D. **orders.approval_flow_json 是订单级流程快照**。工单表已通过
--     order_approval_nodes 固化了"实际走到的节点"，但那只是**执行结果**；
--     要回答"当初这套流程长什么样、为什么走了这条分支"，需要把**定义**也冻一份在订单上。
--     与 order_form_data 固化"当初那一版表单"是同一思路（快照语义，）。
--
--  E. **order_approval_nodes 只加列、不改既有语义**。
--     新增 node_key / node_name / condition_desc 三列，全部可空：
--     借用单与 GROUP 模式的自定义单这三列恒为 NULL，其读写路径**一行都不用改**，
--     从而保证一期与更早的借用流程零回归。
--     特别说明：**没有**新增 stage 列。执行序仍由 step_order 承载——
--     原因是提交时已把"条件求值后的命中路径"展开成**线性序列**，
--     路径上每个节点分配唯一的 step_order；同一流程节点解析出多人时共享同一个 step_order
--     （这正是既有 ANY_SIGN/ALL_SIGN 的语义）。未命中分支的节点同样落库（标 SKIPPED），
--     但因其状态非 PENDING，不会干扰 currentStepOrder（"最小含 PENDING 的 step"）的判定。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、approval_flow —— 审批流程模板
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `approval_flow` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `flow_code`   VARCHAR(32)  NOT NULL                            COMMENT '流程编码（唯一，字母开头，如 PURCHASE_FLOW）',
  `flow_name`   VARCHAR(64)  NOT NULL                            COMMENT '流程名称',
  `description` VARCHAR(255)          DEFAULT NULL               COMMENT '流程说明',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'            COMMENT '状态：DRAFT 草稿 / PUBLISHED 已发布 / DISABLED 已停用',
  `created_by`  BIGINT                DEFAULT NULL               COMMENT '创建人 user_id',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_flow_code` (`flow_code`),
  KEY `idx_approval_flow_status` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '审批流程模板（）';

-- ---------------------------------------------------------------------
-- 二、approval_flow_version —— 流程版本（发布后不可修改）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `approval_flow_version` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `flow_id`         BIGINT       NOT NULL                            COMMENT '所属流程 approval_flow.id',
  `version_no`      INT          NOT NULL                            COMMENT '版本号（同一流程内从 1 递增）',
  `definition_json` TEXT         NOT NULL                            COMMENT '流程定义 JSON：{start, nodes:[{key,type,name,...}]}',
  `node_count`      INT          NOT NULL DEFAULT 0                  COMMENT '审批节点数量（发布校验产物，供列表展示）',
  `published_at`    DATETIME              DEFAULT NULL               COMMENT '发布时间（NULL = 草稿）',
  `published_by`    BIGINT                DEFAULT NULL               COMMENT '发布人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_afv_flow_version` (`flow_id`, `version_no`),
  KEY `idx_afv_flow` (`flow_id`, `version_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '审批流程版本（发布后冻结，）';

-- ---------------------------------------------------------------------
-- 三、apply_type 增量：绑定审批流程版本（可空）
-- ---------------------------------------------------------------------
ALTER TABLE `apply_type`
  ADD COLUMN `approval_flow_version_id` BIGINT DEFAULT NULL
      COMMENT '绑定的已发布审批流程版本 approval_flow_version.id；NULL = 不使用独立流程（走 NONE / GROUP）'
      AFTER `approval_mode`;

CREATE INDEX `idx_apply_type_afv` ON `apply_type` (`approval_flow_version_id`);

-- approval_mode 取值扩展：新增 FLOW（使用独立审批流程模板）
ALTER TABLE `apply_type`
  MODIFY COLUMN `approval_mode` VARCHAR(16) NOT NULL DEFAULT 'GROUP'
      COMMENT '审批方式：NONE 无审批 / GROUP 走分组审批流 / FLOW 使用独立审批流程模板';

-- ---------------------------------------------------------------------
-- 四、orders 增量：流程定义快照（仅 FLOW 模式有值）
-- ---------------------------------------------------------------------
ALTER TABLE `orders`
  ADD COLUMN `approval_flow_json` LONGTEXT DEFAULT NULL
      COMMENT 'FLOW 模式：提交时冻结的审批流程定义 JSON（快照，保证历史可解释）'
      AFTER `apply_type_id`;

-- ---------------------------------------------------------------------
-- 五、order_approval_nodes 增量：流程节点信息（全部可空，借用/GROUP 恒为 NULL）
-- ---------------------------------------------------------------------
ALTER TABLE `order_approval_nodes`
  ADD COLUMN `node_key`       VARCHAR(64)  DEFAULT NULL COMMENT '流程节点稳定标识（仅 FLOW 模式有值）' AFTER `step_order`,
  ADD COLUMN `node_name`      VARCHAR(64)  DEFAULT NULL COMMENT '流程节点名（展示用，仅 FLOW 模式有值）' AFTER `node_key`,
  ADD COLUMN `condition_desc` VARCHAR(255) DEFAULT NULL COMMENT '分支说明：为何走到/跳过本节点（如「走『金额大于5000』分支（金额 = 8000）」）' AFTER `node_name`;

CREATE INDEX `idx_oan_node_key` ON `order_approval_nodes` (`order_id`, `node_key`);

-- ---------------------------------------------------------------------
-- 六、RBAC：为内置 admin 角色补授 approval_flow:view（幂等）
--
-- 需求明确「超管才能管理流程模板，admin 只读」——只授 view，manage 恒归超管
-- （super_admin 由 PermissionGuard 恒定全量短路放行，无需授权行）。
-- 与 V16 同理：rbac_seeded 是一次性标记，代码里改默认集合不会回灌存量环境，
-- 故把增量授权落成迁移，新装与升级两种环境结果一致。
-- `FROM DUAL` 不可省：MySQL 的「无表 SELECT」不允许直接跟 WHERE（否则 1064）。
-- ---------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'admin', 'approval_flow:view' FROM DUAL
WHERE EXISTS (SELECT 1 FROM `sys_role` WHERE `role_code` = 'admin')
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission`
      WHERE `role_code` = 'admin' AND `perm_code` = 'approval_flow:view'
  );
