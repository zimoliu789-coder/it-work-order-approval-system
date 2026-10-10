package com.enterprise.ticket.common.flow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 预置借用审批流程（ · ）。
 *
 * <h2>它解决什么问题</h2>
 * <p>改造前「借用单要走什么审批」由管理员在「部门 → 绑定审批流程」里挑一份自己画的流程决定；
 * 没绑的部门则退回旧的「部门主管单级」快照路径（{@code OrderServiceImpl#buildSnapshot}）。
 * 要求「出厂即可用、管理员不用画流程图」，因此这里给出**唯一一份内置三级流程**：
 *
 * <pre>
 * 直属主管审批（申请人所在部门的部门主管）
 *   → 设备金额分档
 *       ├─ 金额 &gt; 阈值（默认 5000 元）→ 上级部门主管审批
 *       └─ 否则（含金额未录入）
 *   → IT主管审批
 *   → IT执行人处理（由 IT主管通过时指定的人）
 * </pre>
 *
 * <h2>为什么用「代码定义」而不是「Flyway 里塞一份 JSON」</h2>
 * <p>需求同时要求「金额阈值在系统参数里改一个数字即可生效」。若把定义 JSON 静态写进迁移脚本，
 * 阈值就冻结在那一份 JSON 里 —— 改参数不会改流程，两者从此各自演化，而且**不会报错**：
 * 管理员看到参数变了、行为没变，是最难排查的一类问题。
 *
 * <p>因此结构（有哪几级、谁审批）在这里以代码定义，只有**阈值这一个是输入**，
 * 在物化时从 {@code SystemConfigService} 现读。这与 {@link BorrowFieldCatalog}
 * 「借用域字段清单写在代码里」同一思路：借用单是**内置业务**，它的字段域与审批骨架
 * 都是产品的组成部分，不是用户数据。
 *
 * <p>更关键的是：「已提交工单走提交时的流程快照」这条既有的审计语义被完整保留 ——
 * 每次提交都把本类产出的定义整份写进 {@code borrow_order.approval_flow_json}，
 * 之后管理员再怎么改阈值，历史工单的审批口径一字不变。
 *
 * <h2>为什么没有 {@code locked} 标记</h2>
 * <p>需求文档写的是「预置流程 locked=1」，但 {@code approval_flow} 表没有这一列，
 * 而更重要的是：一份**不存在于 {@code approval_flow} 表里的流程**本身就是锁定的 ——
 * 它不出现在设计器的模板列表里，没有任何接口能改到它。用"表里没有"代替一个需要
 * 被所有写入路径遵守的布尔标记，是更强的保证：没有可以忘记检查的字段。
 *
 * <h2>与「最终处理部门」的关系</h2>
 * <p>第 3 级用的是 {@link ApproverRuleType#PREV_ASSIGN}（上一节点指定），
 * 可选范围限定为 {@link ApproverRuleType.AssignScope#IT_EXECUTOR} ——
 * 对应需求「IT主管批准时从 IT执行人角色 / IT运维组里选」。
 * 被指定的人会被 {@code OrderServiceImpl#assignFinalHandler} 采纳为**实际执行人**
 * （即最终把设备交出去的人），因此「IT主管决定谁发设备」是真的生效，
 * 而不是在随机分配之后再被系统改掉。
 */
public final class BorrowFlowCatalog {

    /**
     * 预置流程的展示名（写进 {@code borrow_order.approval_flow_name} 作为归属快照）。
     *
     * <p>带「（系统预置）」后缀是刻意的：流程监控页会把没有模板归属的工单归入
     * 「未归属」分组，这一列是那一组里唯一的辨识依据。叫「设备借用审批流程」会让人
     * 以为去设计器里能搜到它。
     */
    public static final String PRESET_FLOW_NAME = "设备借用审批流程（系统预置）";

    /** 第 1 级：直属主管（= 申请人所在部门的部门主管） */
    public static final String NODE_DIRECT_MANAGER = "direct_manager_approval";
    /** 金额分档条件节点 */
    public static final String NODE_AMOUNT_SPLIT = "device_amount_split";
    /** 大额时追加的一级：上级部门主管 */
    public static final String NODE_PARENT_DEPT_MANAGER = "parent_dept_manager_approval";
    /** IT主管审批 */
    public static final String NODE_IT_MANAGER = "it_manager_approval";
    /** IT执行人处理（由上一节点指定） */
    public static final String NODE_IT_EXECUTOR = "it_executor_handle";
    /** 结束节点 */
    public static final String NODE_END = "end";

    /** 各级审批时限（小时）—— ：自定义流程每级限时 24 小时，超时自动提醒 */
    public static final int TIME_LIMIT_HOURS = 24;

    /** 默认金额阈值（元）：的 5000。与 {@code SystemConfigCatalog} 的默认值必须一致 */
    public static final BigDecimal DEFAULT_AMOUNT_THRESHOLD = new BigDecimal("5000");

    /**
     * {@code PREV_ASSIGN} 的目标人数 —— 第 3 级是「指定一位执行人」，恒为 1。
     *
     * <p>写成常量而不是内联 1：这个值同时决定前端让 IT主管 选几个人，
     * 两处硬编码 1 迟早会漂移。
     */
    public static final int EXECUTOR_ASSIGN_COUNT = 1;

    private BorrowFlowCatalog() {
    }

    /**
     * 用默认阈值构造预置流程定义。
     *
     * <p>供预览的两参重载与单测使用；生产路径一律走
     * {@link #preset(BigDecimal)} 并传入系统参数里的当前阈值。
     */
    public static FlowDefinition preset() {
        return preset(DEFAULT_AMOUNT_THRESHOLD);
    }

    /**
     * 用给定金额阈值构造预置流程定义。
     *
     * @param amountThreshold 金额阈值（元）。{@code null} 或负数时回落到
     *                        {@link #DEFAULT_AMOUNT_THRESHOLD} ——
     *                        阈值是「分档的边界」，缺了它定义就不完整；
     *                        宁可用出厂值，也不要造出一份条件分支恒不命中的流程
     *                        （那表现为"金额条件配了却没生效"）。
     */
    public static FlowDefinition preset(BigDecimal amountThreshold) {
        BigDecimal threshold = normalizeThreshold(amountThreshold);

        FlowDefinition definition = new FlowDefinition();
        definition.setStart(NODE_DIRECT_MANAGER);

        List<FlowNode> nodes = new ArrayList<>();
        nodes.add(approval(NODE_DIRECT_MANAGER, "直属主管审批",
                approverRule(ApproverRuleType.BIZ_GROUP_APPROVERS), NODE_AMOUNT_SPLIT));
        nodes.add(amountSplit(threshold));
        nodes.add(approval(NODE_PARENT_DEPT_MANAGER, "上级部门主管审批",
                approverRule(ApproverRuleType.PARENT_DEPT_APPROVERS), NODE_IT_MANAGER));
        nodes.add(approval(NODE_IT_MANAGER, "IT主管审批",
                roleRule(), NODE_IT_EXECUTOR));
        nodes.add(prevAssignNode());
        nodes.add(endNode());
        definition.setNodes(nodes);
        return definition;
    }

    /**
     * 预置流程里「已被指定的执行人」所在的节点 key —— 判据取自定义本身，
     * 而不是硬编码节点 key，因此将来预置结构变化时不会失配。
     *
     * <p>实现就在 {@link FlowDefinition#prevAssignNodeKeys()}：详情页的
     * 「由上一节点指定」标记与审批完结时的执行人采纳读的是同一份反查逻辑。
     */
    public static Set<String> prevAssignNodeKeys(FlowDefinition definition) {
        return definition == null ? Set.of() : definition.prevAssignNodeKeys();
    }

    // ------------------------------------------------------------------
    // 组装细节
    // ------------------------------------------------------------------

    private static FlowNode amountSplit(BigDecimal threshold) {
        String thresholdText = threshold.toPlainString();

        FlowNode node = new FlowNode();
        node.setKey(NODE_AMOUNT_SPLIT);
        node.setType(FlowNodeType.CONDITION.name());
        node.setName("设备金额分档");

        FlowBranch over = new FlowBranch();
        over.setKey("over_threshold");
        over.setName("设备金额超过 " + thresholdText + " 元");
        over.setCondition(numericGt(BorrowFieldCatalog.DEVICE_AMOUNT, thresholdText));
        over.setNext(NODE_PARENT_DEPT_MANAGER);

        FlowBranch normal = new FlowBranch();
        normal.setKey("within_threshold");
        // 刻意写成「不超过」而不是「小于」：金额未录入时为 null，数值比较不成立，
        // 会落到这条默认出口 —— 需求明确「未录入金额按 ≤5000 走三级」，文案要与该口径一致。
        normal.setName("设备金额不超过 " + thresholdText + " 元或未录入");
        normal.setElseBranch(true);
        normal.setNext(NODE_IT_MANAGER);

        node.setBranches(new ArrayList<>(List.of(over, normal)));
        return node;
    }

    private static FlowCondition numericGt(String field, String value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOp(FlowOperator.GT.name());
        // 比较值用**字符串**承载：求值器统一经 BigDecimal 归一，两种写法等价，
        // 而字符串在 JSON 里永远序列化成 "5000"，不会出现 BigDecimal 的 5E+3 科学计数法。
        rule.setValue(value);
        FlowCondition condition = new FlowCondition();
        condition.setLogic(FlowCondition.LOGIC_AND);
        condition.setRules(new ArrayList<>(List.of(rule)));
        return condition;
    }

    private static FlowNode approval(String key, String name, ApproverRule rule, String next) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName(name);
        node.setSignType(com.enterprise.ticket.common.constant.SignType.ANY_SIGN);
        node.setApproverRules(new ArrayList<>(List.of(rule)));
        node.setNext(next);
        node.setTimeLimitHours(TIME_LIMIT_HOURS);
        return node;
    }

    private static FlowNode prevAssignNode() {
        FlowNode node = new FlowNode();
        node.setKey(NODE_IT_EXECUTOR);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName("IT执行人处理");
        // 硬约束（FlowNodeValidator）：PREV_ASSIGN 必须是节点上的唯一规则、只支持 ANY_SIGN
        node.setSignType(com.enterprise.ticket.common.constant.SignType.ANY_SIGN);
        node.setApproverRules(new ArrayList<>(List.of(prevAssignRule())));
        node.setNext(NODE_END);
        node.setTimeLimitHours(TIME_LIMIT_HOURS);
        return node;
    }

    private static FlowNode endNode() {
        FlowNode node = new FlowNode();
        node.setKey(NODE_END);
        node.setType(FlowNodeType.END.name());
        node.setName("结束");
        return node;
    }

    private static ApproverRule approverRule(ApproverRuleType type) {
        ApproverRule rule = new ApproverRule();
        rule.setType(type.name());
        return rule;
    }

    /** 第 2 级：角色 = IT主管（角色管理里维护，组织变动时不用改流程） */
    private static ApproverRule roleRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.ROLE.name());
        rule.setRoleCode(com.enterprise.ticket.common.constant.RoleCode.IT_MANAGER);
        return rule;
    }

    private static ApproverRule prevAssignRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.PREV_ASSIGN.name());
        rule.setAssignCount(EXECUTOR_ASSIGN_COUNT);
        rule.setAssignScope(ApproverRuleType.AssignScope.IT_EXECUTOR.name());
        return rule;
    }

    /**
     * 阈值归一：null / 负数一律回落出厂值。
     *
     * <p>负数在业务上不可达（{@code ConfigRules} 已把键限制在 0 ~ 1000000），
     * 这里兜底是为了让「人手改库改脏了」不至于把预置流程变成大额分支恒不命中。
     */
    private static BigDecimal normalizeThreshold(BigDecimal amountThreshold) {
        if (amountThreshold == null || amountThreshold.signum() < 0) {
            return DEFAULT_AMOUNT_THRESHOLD;
        }
        return amountThreshold;
    }
}
