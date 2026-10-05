-- =====================================================================
--  · 动态审批流程 （抄送 / 审批时限 / 直属领导 / 上一节点指派）
-- V19__phase15_wave2.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-21 确认）：
--   一 抄送节点 CC：提交时即解析即发送、可只读查看工单、不阻塞推进
--   二 审批时限 timeLimitHours：节点级时限 → 提交时快照 deadline_at；超时提醒（审批人+申请人，每天一次）
--   三 申请人直属领导 LEADER：users.leader_id + 员工管理维护 + Excel 导入列
--   四 上一节点审批人指定 PREV_ASSIGN：节点 approver_id 提交时留空（PENDING），上一节点通过时回填
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **只加列、不加状态机、不加新表**。 已经把「审批」抽象成
--     order_approval_nodes 上的一行， 的四项能力都能用「已有行的新列/新取值」表达：
--       - 抄送 = node_type 多一个取值 CC + 状态 CC_NOTIFIED（isFinished 天然为 true，不干扰推进）；
--       - 时限 = 节点上的 deadline_at 快照；
--       - 待指派 = approver_id 为 NULL 的 PENDING 行（V18 已放开可空）。
--     因此**不新增 pending_assign 列**：`status='PENDING' AND approver_id IS NULL` 已是无歧义表达，
--     再加一个需要与它保持同步的布尔列，只是多一处可能不一致的真相。
--
--  B. **不新增 stage 列**。 的 FlowPathResolver 已用 DFS 给每个审批节点分配唯一 step_order
--     （同一节点解析出多人时共享同一 step_order），step_order 本身就是展示/排序的序。
--     加一个没有消费者的 stage 只会带来"两个序要保持同步"的隐患。
--
--  C. **node_type / deadline_at 全部可空**：借用单与 GROUP 模式自定义单这两列恒为 NULL，
--     其读写路径一行不改 —— 继续保证一期与更早流程零回归。
--
--  D. **users.leader_id 不加外键**：与 apply_type.approval_flow_version_id、
--     apply_type.form_template_version_id 保持一致（本项目对"配置型引用"一律用应用层校验，
--     不用数据库外键，避免删除/停用被外键卡住，也便于历史留痕）。
--     领导离职时**不清空下属的 leader_id**（保留历史），提交工单时解析到离职领导 → 超管兜底。
--
--  E. **超时提醒的幂等位沿用 last_remind_at**（V6 已有），不新增列； 只把
--     「按 deadline_at 优先、无则回落全局阈值」的判定与「每天一次」的窗口写进查询条件。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、users 增量：直属领导（LEADER 规则的数据来源）
-- ---------------------------------------------------------------------
ALTER TABLE `users`
  ADD COLUMN `leader_id` BIGINT DEFAULT NULL
      COMMENT '直属领导 user_id（申请人直属领导审批规则用；可为空=未配置）'
      AFTER `biz_group_id`;

CREATE INDEX `idx_users_leader` ON `users` (`leader_id`);

-- ---------------------------------------------------------------------
-- 二、order_approval_nodes 增量：节点类型 + 审批时限快照
-- ---------------------------------------------------------------------
ALTER TABLE `order_approval_nodes`
  ADD COLUMN `node_type` VARCHAR(16) DEFAULT NULL
      COMMENT '流程节点类型：APPROVAL 审批 / CC 抄送（仅 FLOW 模式有值；借用单与 GROUP 单为 NULL）'
      AFTER `node_name`,
  ADD COLUMN `deadline_at` DATETIME DEFAULT NULL
      COMMENT '审批时限截止时间快照（提交时=提交时刻+timeLimitHours；NULL=不限时）'
      AFTER `condition_desc`;

-- 超时扫描：按「状态 + 截止时间」过滤已超时的待审节点
CREATE INDEX `idx_oan_deadline` ON `order_approval_nodes` (`status`, `deadline_at`);

-- 「抄送我的」列表：按抄送人 + 节点类型定位工单
CREATE INDEX `idx_oan_approver_type` ON `order_approval_nodes` (`approver_id`, `node_type`);
