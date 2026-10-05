package com.enterprise.ticket.common.flow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 条件树的**单一节点信封**：既可以是「单条规则」也可以是「嵌套条件组」（； · M3-A 扩展）。
 *
 * <h2>为什么是"同一个类承担两种角色"，而不是新建 ConditionNode</h2>
 * <p>设计稿给的 JSON 里，{@code RULE} 与 {@code GROUP} 本来就是**同一个信封**：
 * <pre>
 * { "kind": "RULE",  "field": "amount", "op": "GT", "value": 5000 }
 * { "kind": "GROUP", "condition": { "logic": "OR", "rules": [ ... ] } }
 * </pre>
 * 扩展现有类即忠实落地；若新建类型，{@code FlowCondition.rules} 的元素类型就要从
 * {@code List<ConditionRule>} 改成 {@code List<ConditionNode>}，波及十余个文件的类型引用 ——
 * 那是为洁癖付出的高风险改动，收益为零。
 *
 * <h2>代价（读这份代码时务必知道）</h2>
 * <p>{@code kind = GROUP} 时，{@code field} / {@code op} / {@code value} <b>三个字段没有意义</b>。
 * 因此**任何遍历 {@code FlowCondition.rules} 的地方都必须先判 {@link #isGroup()}**，
 * 不允许直接读 {@code rule.getField()} 而对 kind 视而不见。当前共有四处遍历，全部遵守此约定：
 * <ul>
 *   <li>{@link FlowPathResolver#evaluate}（求值）</li>
 *   <li>{@link FlowPathResolver#describeCondition}（中文说明）</li>
 *   <li>{@link FlowPathResolver#runtimeFieldsOf}（待判定说明里的运行期字段名）</li>
 *   <li>{@link ProcessFieldCatalog#isRuntimeDependent}（提交时能否判定）</li>
 * </ul>
 * 其中 {@code isRuntimeDependent} 漏判的后果最严重：嵌套组里引用了 {@code process.*}
 * 会被当成"提交时可判定"，直接把工单走到错误分支上，且**不报错**。
 *
 * <h2>向后兼容</h2>
 * <p>存量 JSON 里没有 {@code kind}：字段初始值即 {@link #KIND_RULE}，
 * 且 {@link #isGroup()} 对 null / 空白一律判 false → <b>缺省即 RULE</b>，
 * 与改造前的语义逐字节等价。
 */
@Data
public class ConditionRule {

    /** 单条规则：{字段} {运算符} {值} */
    public static final String KIND_RULE = "RULE";

    /** 嵌套条件组：内部再挂一个 {@link FlowCondition} */
    public static final String KIND_GROUP = "GROUP";

    /** 表单字段 key（必须存在于该类型所绑的表单版本中）；仅 {@code kind=RULE} 有意义 */
    private String field;

    /** 运算符，取值见 {@link FlowOperator}；仅 {@code kind=RULE} 有意义 */
    private String op;

    /**
     * 比较值；仅 {@code kind=RULE} 有意义。
     *
     * <p>用 {@link Object} 承载：数值比较时是数字、文本比较时是字符串、为空判断时为空。
     * 求值器会按运算符做类型归一，避免 JSON 里 {@code 5000} 与 {@code "5000"} 两种写法
     * 导致"同一份配置在不同接口上行为不一致"。
     */
    private Object value;

    /**
     * 节点种类：{@link #KIND_RULE}（缺省）/ {@link #KIND_GROUP}。
     *
     * <p>之所以给默认值而不是留 null：存量 JSON 反序列化后必须**直接**是 RULE，
     * 让"没写 kind 的旧条件"不需要任何额外兜底分支。
     */
    private String kind = KIND_RULE;

    /** 嵌套条件组的内容；仅 {@code kind=GROUP} 时被读取，其余情况恒为 null */
    private FlowCondition condition;

    /**
     * 是否为嵌套条件组。
     *
     * <p>{@code kind} 为 null / 空白 / 大小写不同时一律按**不是组**处理 ——
     * 与 {@code FlowCondition#isOr()} 同一取向：无法识别的取值退化成最保守的旧语义，
     * 而不是抛异常。真正"写得离谱"的 kind 由校验器报出来（见
     * {@code FlowDefinitionValidator#validateCondition}），校验与求值的职责不混。
     *
     * <p>{@link JsonIgnore} 是必须的：Lombok 之外的手写 {@code isXxx()} 会被 Jackson
     * 当成**只读属性**写进 JSON，给每条规则多加一个 {@code "group":false} ——
     * 那会让"重新保存一份旧定义"多出无意义的字段噪音。
     */
    @JsonIgnore
    public boolean isGroup() {
        return KIND_GROUP.equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 语义糖：非组即规则。让"分支到底走哪条读法"在调用处一眼可辨。 */
    @JsonIgnore
    public boolean isRule() {
        return !isGroup();
    }

    /** 构造一个嵌套组信封（供设计器/演示数据使用，避免手写三个字段） */
    public static ConditionRule group(FlowCondition condition) {
        ConditionRule rule = new ConditionRule();
        rule.setKind(KIND_GROUP);
        rule.setCondition(condition);
        return rule;
    }
}
