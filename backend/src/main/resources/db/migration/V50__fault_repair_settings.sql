-- =====================================================================
-- V50__fault_repair_settings.sql
--
-- 故障报修「处理流程」：报修提交后通知谁（取代 V48 里那个「无审批」的死角）
--
-- <h2>它解决什么问题</h2>
-- 需求：「员工提交报修后，自动通知对应 IT 执行人（按部门或指定维修人）」，
-- 且「管理员能配置通知给谁（哪个角色 / 哪个人负责维修）」。
--
-- 改造前报修提交后**没有任何通知** —— 维修人只能靠自己不断刷新列表才发现有新单。
-- 这不是「少一个提醒」，而是整条处理流程没有起点：没人知道自己该干活。
--
-- <h2>为什么只有两项，而不是一份流程定义</h2>
-- 报修**不产生工单**，域模型里没有审批节点这一环（V48 的 flowEditable=false 说的就是这件事）。
-- 它需要的是处理流程：通知 → 接单 → 修完填结果。整条链路上唯一需要管理员配置的
-- 就是「通知给谁」，所以这里是两项标量配置，不是一张流程图。
--
-- <h2>默认值为什么是「按部门 + 留空」</h2>
-- 留空的 DEPARTMENT 表示「用系统里那个最终处理部门（IT运维组，handler_group = 1）」——
-- 与借用单交付走的是同一批人。这样装好就能用：什么都不配，报修也会通知到
-- 已经在维护设备的那批人。若默认成「不通知任何人」，表现为提交成功却没人收到，
-- 是一条不会报错的静默故障，最难发现。
--
-- <h2>键名契约</h2>
-- 与 FaultRepairSettings 逐字一致（Java 常量 / 本脚本 / 读取方三处）。
-- =====================================================================

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'fault_repair_assignee_type', 'DEPARTMENT', 'fault',
       '故障报修通知给谁：DEPARTMENT 按部门（默认，取最终处理部门 IT运维组）/ ROLE 按角色 / USER 指定人员。',
       1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'fault_repair_assignee_type');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'fault_repair_assignee_value', '', 'fault',
       '通知目标值：按部门填部门 id，按角色填角色码，指定人员填用户 id。留空表示用最终处理部门（IT运维组）。',
       1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'fault_repair_assignee_value');
