package com.enterprise.ticket.module.applytype.support;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.ConditionRule;
import com.enterprise.ticket.common.flow.FlowBranch;
import com.enterprise.ticket.common.flow.FlowCondition;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowOperator;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.permission.PermissionCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 预置申请类型目录
 *
 * <h2>为什么是代码生成而不是 Flyway SQL</h2>
 * <p>表单 schema 与流程定义都是**嵌套 JSON**。手写进 SQL 有两个硬伤：
 * ① 字段名写错（如 {@code approverRules} 写成 {@code approverRule}）不会报错，
 * 要等到用户提交工单、路径求值时才炸，报错离现场极远；
 * ② 流程定义必须过 {@code FlowDefinitionValidator} / {@code FlowGraphValidator} 才合法，
 * SQL 里没有这道闸门。这里用与 {@code BorrowFlowCatalog} 相同的做法：
 * 代码构造对象 → 由 {@code FlowDefinitionCodec} 序列化 → 结构天然合法。
 *
 * <h2>审批链的口径</h2>
 * <p>五类预置的默认链（需求方已拍板）：
 * <ul>
 *   <li>加班 / 请假 / 系统权限申请 —— 一级：{'部门主管'}</li>
 *   <li>用章 —— 两级：{'部门主管 → 上级部门主管'}</li>
 *   <li>采购 —— 两级带条件：{'部门主管 →（金额>5000）上级部门主管'}</li>
 * </ul>
 * 首节点用 {@link ApproverRuleType#BIZ_GROUP_APPROVERS}（部门主管）而**不是** {@code LEADER}：
 * 既有的借用预置流程（{@code BorrowFlowCatalog}）首节点用的就是它，是这套系统里**已被验证可解析**的规则；
 * {@code LEADER} 依赖每个员工都维护了 leader_id，在一线数据里不可靠，
 * 用它会让新类型在「提交」这一步就报「审批人解析为空」。
 *
 * <h2>「系统权限申请」的审批链为什么只有一级</h2>
 * <p>需求是「普通权限主管批了就开通；高危权限自动多走一级超管」。
 * 那**不是**一条静态流程能表达的 —— 它取决于申请人在表单里勾了哪些权限项。
 * 因此这里只落一级，超管节点由  在**提交时按所选权限的风险等级动态插入**。
 * 若在这里用条件分支写，条件字段（权限风险）根本不在表单 schema 里，
 * 过不了 {@code validateAgainstForm} —— 那是个死路。
 */
public final class PresetApplyCatalog {

    private PresetApplyCatalog() {
    }

    /** 预置类型的审批时限（小时）：与借用预置流程保持一致 */
    public static final int TIME_LIMIT_HOURS = 24;

    /** 采购金额分档阈值（元）：与借用预置的默认阈值同源（系统参数 borrow 组里可调） */
    private static final String PURCHASE_AMOUNT_THRESHOLD = "5000";

    /** 一个预置申请类型 */
    public record Preset(String typeCode, String typeName, String icon, String description,
                         int sortOrder, String orderPrefix, FormSchema formSchema,
                         FlowDefinition flow) {
    }

    /**
     * 五类预置。
     *
     * <p>顺序即提交页的卡片顺序（sortOrder 100 起步，给管理员后续自建的类型留出插队空间）。
     */
    public static List<Preset> presets() {
        return List.of(overtime(), leave(), seal(), purchase(), permissionApply());
    }

    // ==================================================================
    // 1. 加班申请
    // ==================================================================

    private static Preset overtime() {
        FormSchema schema = schema(
                required("overtimeDate", "加班日期", FormFieldType.DATE, 1),
                number("overtimeHours", "加班时长", "小时", 1, 0.5, 24, 1, true, 1),
                textarea("reason", "加班事由", 500, true, 2)
        );
        // 单级：部门主管
        FlowDefinition flow = linear(
                "部门主管审批", ApproverRuleType.BIZ_GROUP_APPROVERS);
        return new Preset("OVERTIME", "加班申请", "Timer", "填报加班日期、时长与事由", 100, "JB", schema, flow);
    }

    // ==================================================================
    // 2. 请假申请
    // ==================================================================

    private static Preset leave() {
        FormSchema schema = schema(
                select("leaveType", "请假类型", 1,
                        option("PERSONAL", "事假"), option("SICK", "病假"), option("ANNUAL", "年假")),
                required("leaveStart", "开始时间", FormFieldType.DATETIME, 1),
                required("leaveEnd", "结束时间", FormFieldType.DATETIME, 1),
                textarea("reason", "请假事由", 500, true, 2)
        );
        FlowDefinition flow = linear("部门主管审批", ApproverRuleType.BIZ_GROUP_APPROVERS);
        return new Preset("LEAVE", "请假申请", "Calendar", "事假 / 病假 / 年假，含起止时间与事由", 110, "QJ", schema, flow);
    }

    // ==================================================================
    // 3. 用章申请
    // ==================================================================

    private static Preset seal() {
        FormSchema schema = schema(
                select("sealType", "印章类型", 1, option("OFFICIAL", "公章"), option("CONTRACT", "合同章")),
                textarea("purpose", "用途", 500, true, 2),
                number("copies", "份数", "份", 1, 1, 99, 0, true, 1)
        );
        // 两级：部门主管 → 上级部门主管
        FlowDefinition flow = twoLevel("部门主管审批", ApproverRuleType.BIZ_GROUP_APPROVERS,
                "上级部门主管审批", ApproverRuleType.PARENT_DEPT_APPROVERS);
        return new Preset("SEAL", "用章申请", "Postcard", "公章 / 合同章，含用途与份数", 120, "YZ", schema, flow);
    }

    // ==================================================================
    // 4. 采购申请
    // ==================================================================

    private static Preset purchase() {
        FormSchema schema = schema(
                text("itemName", "物品名称", true, 1, "如：联想 ThinkPad X1"),
                number("quantity", "数量", "件", 1, 1, 9999, 0, true, 1),
                number("amount", "预估金额", "元", 1, 0, 10000000, 2, true, 1),
                textarea("purpose", "用途", 500, true, 2)
        );
        // 两级带条件：部门主管 →（amount > 5000）上级部门主管
        FlowDefinition flow = withAmountSplit(
                "部门主管审批", ApproverRuleType.BIZ_GROUP_APPROVERS,
                "上级部门主管审批", ApproverRuleType.PARENT_DEPT_APPROVERS,
                "amount", PURCHASE_AMOUNT_THRESHOLD);
        return new Preset("PURCHASE", "采购申请", "ShoppingCart", "办公用品、软件许可等采购申请", 130, "CG", schema, flow);
    }

    // ==================================================================
    // 5. 系统权限申请
    // ==================================================================

    /**
     * 可申请的权限码。
     *
     * <p>排除需求方点名的四类**提权类**权限（角色管理 / AD 管理 / 在线升级 / 系统参数）——
     * 拿到「角色与权限管理」就能绕过本流程自行提权，等于流程形同虚设。
     *
     * <p>选项文案取 {@link PermissionCatalog#nameOf(String)}，因此权限目录改了名字这里自动跟随，
     * 不需要在两处维护同一份中文名。
     *
     * <h2>⚠️  修正的一处真实缺陷（提权口子）</h2>
     * <p>原实现把黑名单**硬编码在本类**，且写的是 {@code system:upgrade:manage} ——
     * 而目录里**根本没有这个码**（真实的码是 {@code system:upgrade:view} /
     * {@code system:upgrade:execute}）。{@code Set.contains} 对不存在的码恒为 false，
     * 于是「在线升级」的两个码**一直是可申请的**。
     *
     * <p>这是个**静默**的提权口子：过滤器不报错，只是少排除了两项，
     * 而少排除的恰恰是「能替换服务器可执行文件」那一个（等价于代码执行）。
     *
     * <p>修法有两步，缺一不可：
     * <ol>
     *   <li>黑名单的**事实源**改为 {@link PermissionCatalog#defaultNonApplicableCodes()}
     *       —— 不再在本类另写一份（两份必然漂移，本缺陷就是这么来的）；</li>
     *   <li>可申请性判断改走 {@link PermissionCatalog#applicableByDefault(String)}，
     *       它内部会先校验「码存在于目录」，写错的码不可能再悄悄放行。</li>
     * </ol>
     */
    private static Preset permissionApply() {
        List<FormOption> options = new ArrayList<>();
        for (String code : PermissionCatalog.allCodeList()) {
            if (PermissionCatalog.applicableByDefault(code)) {
                options.add(option(code, PermissionCatalog.nameOf(code)));
            }
        }
        FormField permissionCodes = new FormField();
        permissionCodes.setKey("permissionCodes");
        permissionCodes.setLabel("申请开通的权限");
        permissionCodes.setType(FormFieldType.MULTI_SELECT.name());
        permissionCodes.setRequired(true);
        permissionCodes.setWidth(2);
        permissionCodes.setHelp("可多选。含高危权限时会自动多走一级超管审批。");
        permissionCodes.setOptions(options);

        FormSchema schema = schema(permissionCodes, textarea("reason", "申请理由", 500, true, 2));

        // 只落一级；「高危 ⇒ 多走一级超管」由  在提交时按所选权限的风险等级动态插入节点
        FlowDefinition flow = linear("部门主管审批", ApproverRuleType.BIZ_GROUP_APPROVERS);
        return new Preset("PERMISSION_APPLY", "系统权限申请", "Key",
                "申请开通系统权限，审批通过后自动生效", 140, "QX", schema, flow);
    }

    // ==================================================================
    // 构造助手
    // ==================================================================

    private static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    private static FormField field(String key, String label, FormFieldType type, boolean required, int width) {
        FormField f = new FormField();
        f.setKey(key);
        f.setLabel(label);
        f.setType(type.name());
        f.setRequired(required);
        f.setWidth(width);
        return f;
    }

    private static FormField required(String key, String label, FormFieldType type, int width) {
        return field(key, label, type, true, width);
    }

    /** 单行文本（带输入提示） */
    private static FormField text(String key, String label, boolean required, int width, String placeholder) {
        FormField f = field(key, label, FormFieldType.TEXT, required, width);
        f.setPlaceholder(placeholder);
        return f;
    }

    private static FormField textarea(String key, String label, int maxLength, boolean required, int width) {
        FormField f = field(key, label, FormFieldType.TEXTAREA, required, width);
        f.setMaxLength(maxLength);
        return f;
    }

    private static FormField select(String key, String label, int width, FormOption... options) {
        FormField f = field(key, label, FormFieldType.SELECT, true, width);
        f.setOptions(new ArrayList<>(List.of(options)));
        return f;
    }

    private static FormField number(String key, String label, String unit, int width,
                                    double min, double max, int precision, boolean required, int... ignored) {
        FormField f = field(key, label, FormFieldType.NUMBER, required, width);
        f.setUnit(unit);
        f.setMin(min);
        f.setMax(max);
        f.setPrecision(precision);
        return f;
    }

    private static FormOption option(String value, String label) {
        FormOption o = new FormOption();
        o.setValue(value);
        o.setLabel(label);
        return o;
    }

    /** 线性流程：开始 → 一个审批节点 → 结束 */
    private static FlowDefinition linear(String nodeName, ApproverRuleType ruleType) {
        return twoLevel(nodeName, ruleType, null, null);
    }

    /**
     * 一或两级线性流程。
     *
     * @param secondName 为 {@code null} 表示单级
     */
    private static FlowDefinition twoLevel(String firstName, ApproverRuleType firstType,
                                          String secondName, ApproverRuleType secondType) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");

        List<FlowNode> nodes = new ArrayList<>();
        if (secondName == null) {
            nodes.add(approval("n1", firstName, firstType, "end"));
        } else {
            nodes.add(approval("n1", firstName, firstType, "n2"));
            nodes.add(approval("n2", secondName, secondType, "end"));
        }
        nodes.add(endNode("end"));
        definition.setNodes(nodes);
        return definition;
    }

    /** 两级带金额分档：部门主管 →（金额超阈值）上级部门主管，未超阈值直接结束 */
    private static FlowDefinition withAmountSplit(String firstName, ApproverRuleType firstType,
                                                  String secondName, ApproverRuleType secondType,
                                                  String amountField, String threshold) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");

        FlowBranch over = new FlowBranch();
        over.setKey("over_threshold");
        over.setName("金额超过 " + threshold + " 元");
        over.setCondition(numericGt(amountField, threshold));
        over.setNext("n2");

        FlowBranch normal = new FlowBranch();
        normal.setKey("within_threshold");
        // 刻意写成「不超过或未录入」：金额为 null 时数值比较不成立，会落到这条默认出口
        normal.setName("金额不超过 " + threshold + " 元或未录入");
        normal.setElseBranch(true);
        normal.setNext("end");

        FlowNode split = new FlowNode();
        split.setKey("amount_split");
        split.setType(FlowNodeType.CONDITION.name());
        split.setName("金额分档");
        split.setBranches(new ArrayList<>(List.of(over, normal)));

        List<FlowNode> nodes = new ArrayList<>();
        nodes.add(approval("n1", firstName, firstType, "amount_split"));
        nodes.add(split);
        nodes.add(approval("n2", secondName, secondType, "end"));
        nodes.add(endNode("end"));
        definition.setNodes(nodes);
        return definition;
    }

    private static FlowCondition numericGt(String field, String value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOp(FlowOperator.GT.name());
        // 比较值用字符串承载：求值器统一经 BigDecimal 归一，两种写法等价，
        // 而字符串在 JSON 里永远序列化成 "5000"，不会出现科学计数法。
        rule.setValue(value);
        FlowCondition condition = new FlowCondition();
        condition.setLogic(FlowCondition.LOGIC_AND);
        condition.setRules(new ArrayList<>(List.of(rule)));
        return condition;
    }

    private static FlowNode approval(String key, String name, ApproverRuleType ruleType, String next) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName(name);
        node.setSignType(SignType.ANY_SIGN);
        ApproverRule rule = new ApproverRule();
        rule.setType(ruleType.name());
        node.setApproverRules(new ArrayList<>(List.of(rule)));
        node.setNext(next);
        node.setTimeLimitHours(TIME_LIMIT_HOURS);
        return node;
    }

    private static FlowNode endNode(String key) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.END.name());
        node.setName("结束");
        return node;
    }
}
