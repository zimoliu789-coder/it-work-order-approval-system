package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 条件校验（ ·  · W4-A1，自 {@link FlowDefinitionValidator} 拆出）。
 *
 * <h2>职责</h2>
 * <p>校验分支上的 {@link FlowCondition}：组合逻辑、条件列表非空、逐项规则（字段 / 运算符 / 比较值）、
 * 嵌套条件组的结构约束，以及字段与运算符的**可比性**（数值比较不能用在文本字段上等）。
 *
 * <p>它是校验器里唯一<b>递归</b>的一段（M3-A 起支持三层嵌套），也是唯一需要
 * {@code where} 路径前缀逐层累加的一段 —— 报错必须能指出"第几个分支的第几个条件项的第几层组"，
 * 否则配置者面对一个四层嵌套的 JSON 无从定位。
 *
 * <h2>为什么递归与深度判定放在同一处</h2>
 * <p>{@code MAX_DEPTH} 既是这里的硬约束，也是前端设计器禁用"再加一层"按钮的阈值。
 * 若把深度判定抽到别处，就会出现"设计器允许加、后端拒绝发布"的组合。见
 * {@link FlowCondition#MAX_DEPTH}。
 */
final class FlowConditionValidator {

    private FlowConditionValidator() {
    }

    static void validate(FlowCondition condition, String where,
                         FormSchema schema, FlowProblemCollector collector) {
        validate(condition, where, schema, collector, 1);
    }

    /**
     * 条件校验（ · M3-A：递归 + 深度上限 + 组结构约束）。
     *
     * @param depth 当前层号，**最外层条件组算第 1 层**
     *
     * <h2>为什么深度超限时"立即 return"而不是继续往下递归</h2>
     * <p>继续递归会让一个 4 层嵌套报出 3 条同样的话（第 2/3/4 层各一条），
     * 而真正要告诉配置者的只有一条："你的嵌套太深了"。收集全部问题是为了**看到全部不同的
     * 问题**，不是为了把同一个问题按层数复述一遍。
     */
    static void validate(FlowCondition condition, String where, FormSchema schema,
                         FlowProblemCollector collector, int depth) {
        if (condition == null) {
            collector.add(where + "未配置条件");
            return;
        }
        if (depth > FlowCondition.MAX_DEPTH) {
            collector.add(where + "的条件嵌套层数超过上限（最多 " + FlowCondition.MAX_DEPTH
                    + " 层，当前至少 " + depth + " 层）");
            return;
        }
        if (!FlowCondition.LOGIC_AND.equalsIgnoreCase(condition.getLogic())
                && !FlowCondition.LOGIC_OR.equalsIgnoreCase(condition.getLogic())) {
            collector.add(where + "的组合逻辑不合法：" + condition.getLogic());
        }
        if (condition.getRules().isEmpty()) {
            collector.add(where + "的条件列表为空");
        }
        List<ConditionRule> items = condition.getRules();
        for (int index = 0; index < items.size(); index++) {
            ConditionRule rule = items.get(index);
            String itemWhere = where + "的第 " + (index + 1) + " 个条件项";
            if (rule == null) {
                collector.add(itemWhere + "为空");
                continue;
            }
            if (rule.isGroup()) {
                validateGroup(rule, itemWhere, schema, collector, depth);
                continue;
            }
            // kind 写了个不认识的取值时**必须报错**，而不是当 RULE 用：
            // 若静默降级，一个本想配置为组的项会因为 field 为 null 被报成"未选择字段"，
            // 配置者会去检查字段选择，而真正的问题在 kind 上。
            if (StringUtils.hasText(rule.getKind())
                    && !ConditionRule.KIND_RULE.equalsIgnoreCase(rule.getKind().trim())) {
                collector.add(itemWhere + "的种类不合法：" + rule.getKind()
                        + "（只能是 " + ConditionRule.KIND_RULE + " 或 " + ConditionRule.KIND_GROUP + "）");
                continue;
            }
            if (!StringUtils.hasText(rule.getField())) {
                collector.add(itemWhere + "未选择字段");
                continue;
            }
            FlowOperator operator = FlowOperator.of(rule.getOp());
            if (operator == null) {
                collector.add(itemWhere + "的运算符不合法：" + rule.getOp());
                continue;
            }
            if (operator.isRequiresValue() && rule.getValue() == null) {
                collector.add(itemWhere + "的「" + operator.getLabel() + "」未填写比较值");
            }
            validateField(rule.getField(), operator, itemWhere, schema, collector);
        }
    }

    /**
     * 嵌套条件组自身的约束（M3-A）。
     *
     * <p>三条，都是"前端不该写出来、但后端不能信任前端"的半残对象：
     * <ol>
     *   <li><b>组不能为空</b> —— 空组求值恒 false（见 {@code FlowPathResolver#evaluate} 的空判），
     *       配置者会以为"这一项没起作用"而不是"我配漏了"；</li>
     *   <li><b>组不得携带 field/op/value</b> —— 这三个字段在组上没有意义。
     *       若前端切换过类型（先当规则填了字段再切成组），残留值会一起提交上来。
     *       后端虽不读它们，但 JSON 里留着脏值与 M1 的一条既有教训同源：脏值迟早被某个
     *       未来版本的代码读到；</li>
     *   <li>递归校验组内的子条件（含再下一层的深度）。</li>
     * </ol>
     */
    private static void validateGroup(ConditionRule rule, String where, FormSchema schema,
                                      FlowProblemCollector collector, int depth) {
        if (StringUtils.hasText(rule.getField()) || StringUtils.hasText(rule.getOp())
                || rule.getValue() != null) {
            collector.add(where + "是嵌套条件组，不应携带字段 / 运算符 / 比较值");
        }
        FlowCondition inner = rule.getCondition();
        if (inner == null) {
            collector.add(where + "是空的嵌套条件组（未配置任何子条件）");
            return;
        }
        if (inner.getRules().isEmpty()) {
            collector.add(where + "的嵌套条件组为空");
            return;
        }
        validate(inner, where + "的嵌套条件组", schema, collector, depth + 1);
    }

    private static void validateField(String fieldKey, FlowOperator operator, String where,
                                      FormSchema schema, FlowProblemCollector collector) {
        // M2：运行期字段（process.*）走内置白名单，**不能**落到下面的表单 schema 查找 ——
        // 那样会报出「引用的表单字段不存在这种误导性的错，用户会去表单设计器里找一个
        // 根本不在表单里的字段，永远找不到。
        if (ProcessFieldCatalog.isProcessField(fieldKey)) {
            if (!ProcessFieldCatalog.isKnown(fieldKey)) {
                collector.add(where + "引用了未知的运行期字段「" + fieldKey + "」，可用字段："
                        + String.join("、", ProcessFieldCatalog.labels().keySet()));
                return;
            }
            FormField processField = ProcessFieldCatalog.schema().getFields().stream()
                    .filter(f -> fieldKey.equals(f.getKey()))
                    .findFirst()
                    .orElse(null);
            if (processField == null) {
                return;
            }
            FormFieldType processType = FormFieldType.of(processField.getType());
            FormFieldType.ValueKind kind = processType == null
                    ? FormFieldType.ValueKind.NONE : processType.getValueKind();
            // 与表单字段**同一套**可比性规则（这正是把运行期字段也适配成 FormField 的价值：
            // 不必在这里长出第二份类型判定，两份迟早会漂移）
            if (operator.isNumericComparison() && kind != FormFieldType.ValueKind.NUMBER) {
                collector.add(where + "的「" + operator.getLabel() + "」只能用于数值字段，"
                        + "而运行期字段「" + ProcessFieldCatalog.labelOf(fieldKey) + "」是"
                        + (processType == null ? "未知类型" : processType.getLabel()));
            }
            return;
        }
        if (schema == null) {
            return;
        }
        FormField field = schema.getFields().stream()
                .filter(f -> fieldKey.equals(f.getKey()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            collector.add(where + "引用的表单字段「" + fieldKey + "」不存在");
            return;
        }
        FormFieldType fieldType = FormFieldType.of(field.getType());
        if (fieldType == null || fieldType.getValueKind() == FormFieldType.ValueKind.NONE) {
            collector.add(where + "引用的字段「" + field.getLabel() + "」是布局元素，不能作为条件");
            return;
        }
        FormFieldType.ValueKind kind = fieldType.getValueKind();
        if (operator.isNumericComparison()
                && kind != FormFieldType.ValueKind.NUMBER
                && kind != FormFieldType.ValueKind.DATE
                && kind != FormFieldType.ValueKind.DATETIME) {
            collector.add(where + "的「" + operator.getLabel() + "」只能用于数字或日期字段，"
                    + "而「" + field.getLabel() + "」是" + fieldType.getLabel());
        }
        if ((operator == FlowOperator.CONTAINS || operator == FlowOperator.NOT_CONTAINS)
                && kind != FormFieldType.ValueKind.TEXT
                && kind != FormFieldType.ValueKind.OPTION
                && kind != FormFieldType.ValueKind.OPTION_MULTI) {
            collector.add(where + "的「" + operator.getLabel() + "」只能用于文本或选项字段，"
                    + "而「" + field.getLabel() + "」是" + fieldType.getLabel());
        }
    }
}
