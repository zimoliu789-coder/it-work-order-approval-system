-- ============================================================================
--  —— 清理演示申请配置数据（申请类型 / 表单模板 / 审批流程 / 演示自定义工单）
-- V41__p4b_cleanup_demo_apply_config.sql
-- 企业内部设备借用工单系统
-- ----------------------------------------------------------------------------
-- 说明：「删掉所有演示数据（演示借用申请、演示自定义申请、M6 导出自测类型等）」
--
-- 本脚本**只做删除**，不建任何新数据。7 个预置申请类型由 Java 侧的
-- `PresetApplyInitializer` 播种（见下方「为什么预置数据不写在这里」）。
--
-- ⚠️ 本脚本是**破坏性**的。执行前已把待删数据导出到：
--      .docs/_p4b-backup/pre-v41-demo-config.sql      （申请类型/模板/流程 全量）
--      .docs/_p4b-backup/pre-v41-custom-orders.sql    （35 条 CUSTOM 工单）
--      .docs/_p4b-backup/pre-v41-order-form-data.sql  （35 条表单数据）
--
-- ⚠️ 一律用 DELETE 而不是 TRUNCATE（项目既定约定）：
--    TRUNCATE 会重置自增并隐式提交、且无法按条件删；这里必须按条件精确删。
--
-- ⚠️ 保留的东西（刻意不删）：
--    · orders 中 order_type='BORROW' 的 668 条借用工单 —— P0~P3 的冒烟与取证都依赖它；
--    · 设备台账、故障记录、盘点、消息中心等与申请配置无关的数据。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- ① 演示自定义工单的从属数据
-- ----------------------------------------------------------------------------
-- 删除顺序由**外键删除规则**决定，先查过 information_schema：
--   order_approval_nodes    CASCADE   → 会随 orders 自动删，不必手写
--   messages                SET NULL  → 不手写会留下「订单号为空」的演示消息，属噪音，故显式删
--   device_fault            SET NULL  → 涉及的 0 行；故障记录本身属于设备，不该跟着工单走，故不删
--   order_extend / order_force_operation / order_handler_transfer / order_urge
--                           RESTRICT  → 涉及 0 行，否则这里的 DELETE FROM orders 会被拒
--
-- 附件：CUSTOM 工单的附件为 0 条，写出来是为了口径完整（将来有附件时也删干净）
DELETE FROM attachments
WHERE biz_type = 'ORDER'
  AND biz_id IN (SELECT id FROM orders WHERE order_type = 'CUSTOM');

DELETE FROM messages
WHERE order_id IN (SELECT id FROM orders WHERE order_type = 'CUSTOM');

DELETE FROM order_form_data
WHERE order_id IN (SELECT id FROM orders WHERE order_type = 'CUSTOM');

DELETE FROM orders WHERE order_type = 'CUSTOM';


-- ----------------------------------------------------------------------------
-- ② 演示申请类型（6 条：全部是要清掉的演示 / 自测 / 待重建的样例）
-- ----------------------------------------------------------------------------
-- PURCHASE       —— 采购申请（表单是演示数据，将由预置初始化器按新表单重建）
-- PURCHASE_FLOW  —— 演示自定义审批流程
-- FTDEMOBORROW   —— 演示借用申请
-- FTDEMOFORMCOND —— 演示自定义申请-条件分支
-- FTDEMOFORMCC   —— 演示自定义申请-抄送时限
-- W3DFORM        —— M6 导出自测类型
--
-- ⚠️ 设备借用 / 设备报修**不在这张表里** —— 它们是代码内置的两类（要锁设备、要建故障记录），
--    见 `OrderServiceImpl` 的 `orderType` 分派。本脚本与它们无关。
DELETE FROM apply_type
WHERE type_code IN ('PURCHASE', 'PURCHASE_FLOW', 'FTDEMOBORROW', 'FTDEMOFORMCOND',
                    'FTDEMOFORMCC', 'W3DFORM');


-- ----------------------------------------------------------------------------
-- ③ 演示表单模板与版本
-- ----------------------------------------------------------------------------
-- 顺序：先版本后模板（虽然没有外键约束，但反过来万一将来加了约束就会失败）
-- 「1234321234」是一个 DRAFT 状态的垃圾草稿（创建于 2026-10-04，无版本被引用）
DELETE FROM form_template_version
WHERE template_id IN (SELECT id FROM form_template
                      WHERE template_name IN ('采购申请', '演示-自定义申请单', '1234321234'));

DELETE FROM form_template
WHERE template_name IN ('采购申请', '演示-自定义申请单', '1234321234');


-- ----------------------------------------------------------------------------
-- ④ 演示审批流程与版本
-- ----------------------------------------------------------------------------
-- sgdfh4fgdfzhgd / l234565432l —— 明显是手敲的垃圾流程（编码乱码、无业务含义）
-- DEMO_PURCHASE_FLOW —— 演示：嵌套条件 / 抄送 / 时限 / 直属领导 / 上一节点指定
-- FTDEMO_SIMPLE / FTDEMO_COND / FTDEMO_CC —— 演示流程三件套
-- F8_SIGN50 —— F8 压测用的「50 人会签」夹具流程
DELETE FROM approval_flow_version
WHERE flow_id IN (SELECT id FROM approval_flow
                  WHERE flow_code IN ('sgdfh4fgdfzhgd', 'l234565432l', 'DEMO_PURCHASE_FLOW',
                                      'FTDEMO_SIMPLE', 'FTDEMO_COND', 'FTDEMO_CC', 'F8_SIGN50'));

DELETE FROM approval_flow
WHERE flow_code IN ('sgdfh4fgdfzhgd', 'l234565432l', 'DEMO_PURCHASE_FLOW',
                    'FTDEMO_SIMPLE', 'FTDEMO_COND', 'FTDEMO_CC', 'F8_SIGN50');


-- ============================================================================
-- 为什么 7 个预置申请类型不写在这里
-- ----------------------------------------------------------------------------
-- 「预置数据 = Flyway SQL」在本项目里**不是**通用规则，而是分情况的：
--   · 纯静态字典（权限码、角色、系统参数）→ 走 Flyway，因为它们没有结构；
--   · **带结构的配置**（表单 schema / 流程定义）→ 走代码播种。
-- 表单 schema 与流程定义都是**嵌套 JSON**，手写在 SQL 里有两个硬伤：
--   ① 一个字段名写错（如 `approvalRules` 写成 `approverRule`）不会报错，
--      要等到用户提交工单、路径求值时才炸，且报错离现场很远；
--   ② 流程定义必须过 `FlowDefinitionValidator` / `FlowGraphValidator` 才算合法，
--      SQL 里没有这道闸门。
-- 因此预置的 5 类（系统权限申请 / 加班 / 请假 / 用章 / 采购）由
-- `PresetApplyCatalog`（构造 schema 与流程定义，复用 `FlowDefinitionCodec` 序列化）
-- + `PresetApplyInitializer`（幂等播种）负责 —— 与既有的 `BorrowFlowCatalog`
-- 「预置借用流程 = 代码生成」是同一取向。
-- ============================================================================
