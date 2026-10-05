package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期上下文字段域。
 *
 * <h2>它解决什么问题</h2>
 * <p>第二期的条件只能引用**表单字段**，因为表单在提交那一刻就固定了 ——
 * 所以「走哪条分支」在提交时就能一次性算出来（这正是 {@link FlowPathResolver} 的设计前提）。
 * 但真实审批里有一类很自然的需求：「上一节点驳回过就走复核节点」「已耗时超过 48 小时就加签」，
 * 它们引用的是<b>审批过程中才产生</b>的数据，提交时根本不存在。
 *
 * <p>本类把这些数据定义成一组以 {@code process.} 为前缀的**伪字段**，
 * 让条件求值器与校验器可以完全无视它们的来源差异 —— 与 {@link BorrowFieldCatalog} 同一思路：
 * <b>适配成同构形态，而不是在求值器里长分支</b>。
 *
 * <h2>为什么全部以常量集中在此</h2>
 * <p>求值器以字符串为 key 取值，字段名拼错<b>不会报错</b>，只会让条件永远不命中 ——
 * 这是最难排查的一类静默失效。集中成常量后，设计器（前端）、校验器、求值器三处
 * 引用的是同一个字面量。
 *
 * <h2>为什么比较值必须是常量</h2>
 * <p>本期不支持「字段比字段」（如 {@code prevNodeHours > elapsedHours}）：它会让求值器
 * 需要知道每个 key 的类型才能解释右侧，而右侧现在既可能是常量也可能是字段名，
 * 解析歧义（值为字符串 "elapsedHours" 时到底算字段还是算文本？）没有廉价解法。
 * 校验器会显式拒绝这类配置，而不是留到运行期再猜。
 */
public final class ProcessFieldCatalog {

    /** 运行期字段统一前缀：设计器与校验器靠它一眼区分「表单字段」与「运行期字段」 */
    public static final String PREFIX = "process.";

    /** 上一审批节点的结果（APPROVED / REJECTED）；提交时无值 */
    public static final String PREV_NODE_RESULT = PREFIX + "prevNodeResult";
    /** 上一审批节点从「轮到它」到「处理完」的耗时（小时） */
    public static final String PREV_NODE_HOURS = PREFIX + "prevNodeHours";
    /** 工单从提交到现在的耗时（小时） */
    public static final String ELAPSED_HOURS = PREFIX + "elapsedHours";
    /** 是否发生过驳回（true / false） */
    public static final String ANY_REJECTED = PREFIX + "anyRejected";
    /** 驳回次数 */
    public static final String REJECT_COUNT = PREFIX + "rejectCount";
    /**
     * 已激活的审批节点数。
     *
     * <p>它不是给业务用的，而是<b>防环 / 防失控护栏</b>：改道与加签都可能在运行期
     * 新增或复活节点，用一个可比较的计数配合「单流程动态插入上限」，能把
     * 「配置写错导致节点被无限激活」从"跑满数据库"降级成"一条被拒的配置"。
     */
    public static final String ACTIVATED_COUNT = PREFIX + "activatedCount";

    /** 动态插入（加签 / 改道）单流程上限 —— 护栏，超出即拒绝并记 WARN */
    public static final int MAX_DYNAMIC_INSERT = 5;

    private ProcessFieldCatalog() {
    }

    /** 是否为运行期字段（以 {@code process.} 开头） */
    public static boolean isProcessField(String field) {
        return field != null && field.startsWith(PREFIX);
    }

    /**
     * 本字段是否为「已知的」运行期字段。
     *
     * <p>用于校验分流：{@code process.} 前缀但不在白名单里（如 {@code process.foo}）
     * 必须被<b>拒绝</b>，而不是当作普通表单字段去 schema 里找 —— 后者会报出
     * 「字段不存在于表单中」这种误导性的错（用户会去表单里找，永远找不到）。
     */
    public static boolean isKnown(String field) {
        return schemaKeys().contains(field);
    }

    /**
     * 条件是否「依赖运行期数据」。
     *
     * <h2>判定为什么是"看字段名"而不是"看当前取值"</h2>
     * <p>提交时 {@code process.rejectCount} 的取值是 0、{@code process.anyRejected} 是 false ——
     * 这些值当下是<b>可知</b>的。但若据此在提交时就下结论，就会把「将来可能变」的判定
     * 提前定死。因此这里只做<b>静态判定</b>：只要条件引用了 {@code process.} 字段，
     * 就认为它提交时无法判定，其下游节点物化为 {@code INACTIVE} 而非 SKIPPED。
     *
     * <p>这条规则简单且可枚举 —— 排查时只要看条件里有没有 {@code process.} 即可，
     * 不需要推算"当时那个值算不算有效"。
     *
     * <h2> · M3-A：判定必须递归（本批静默失效风险最高的一处）</h2>
     * <p>M3-A 之后 {@code rules} 里可能挂着嵌套组。若这里仍只扫**直接子级**，
     * 那么 "A 且 (上一节点已驳回 或 B)" 里的 {@code process.*} 就被漏掉：
     * 提交时会被判定为"可以下结论"，于是那条分支被<b>当场定成 SKIPPED 或 ACTIVE</b>，
     * 而不是落 {@code INACTIVE} 等运行期再算 —— 工单直接走错分支，
     * 且没有任何报错、没有任何日志，只有业务结果不对。
     *
     * <p>因此这里必须与 {@link FlowPathResolver#evaluate} 的遍历结构**同构**：
     * 求值能看到的字段，判定就一定要能看到。二者的递归深度由同一个
     * {@code MAX_DEPTH} 约束，不存在"能求值但判不出依赖"的配置。
     */
    public static boolean isRuntimeDependent(FlowCondition condition) {
        if (condition == null || condition.getRules().isEmpty()) {
            return false;
        }
        for (ConditionRule rule : condition.getRules()) {
            if (rule == null) {
                continue;
            }
            if (rule.isGroup()) {
                if (isRuntimeDependent(rule.getCondition())) {
                    return true;
                }
                continue;
            }
            if (isProcessField(rule.getField())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 运行期字段清单，以与动态表单同构的 {@link FormSchema} 暴露。
     *
     * <p>类型选择直接决定可用运算符（校验器据 {@code ValueKind} 推断可比性）：
     * <ul>
     *   <li>数值类（{@code prevNodeHours} / {@code elapsedHours} / {@code rejectCount} /
     *       {@code activatedCount}）用 NUMBER → 允许 {@code > >= < <=}；</li>
     *   <li>{@code prevNodeResult} / {@code anyRejected} 用 SELECT（值域即枚举）→
     *       允许 {@code 等于 / 不等于}，且前端选择器能直接列出可选值。</li>
     * </ul>
     */
    public static FormSchema schema() {
        FormSchema schema = new FormSchema();
        List<FormField> fields = new ArrayList<>();
        fields.add(field(PREV_NODE_RESULT, "上一节点结果", FormFieldType.SELECT, options(
                option("APPROVED", "已通过"), option("REJECTED", "已驳回"))));
        fields.add(field(PREV_NODE_HOURS, "上一节点耗时(小时)", FormFieldType.NUMBER, null));
        fields.add(field(ELAPSED_HOURS, "工单已耗时(小时)", FormFieldType.NUMBER, null));
        fields.add(field(ANY_REJECTED, "是否发生过驳回", FormFieldType.SELECT, options(
                option("true", "是"), option("false", "否"))));
        fields.add(field(REJECT_COUNT, "驳回次数", FormFieldType.NUMBER, null));
        fields.add(field(ACTIVATED_COUNT, "已激活节点数", FormFieldType.NUMBER, null));
        schema.setFields(fields);
        return schema;
    }

    /** 运行期字段的中文名映射（供求值器的说明文案拼装，与表单字段标签合并使用） */
    public static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PREV_NODE_RESULT, "上一节点结果");
        labels.put(PREV_NODE_HOURS, "上一节点耗时(小时)");
        labels.put(ELAPSED_HOURS, "工单已耗时(小时)");
        labels.put(ANY_REJECTED, "是否发生过驳回");
        labels.put(REJECT_COUNT, "驳回次数");
        labels.put(ACTIVATED_COUNT, "已激活节点数");
        return labels;
    }

    private static java.util.Set<String> schemaKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (FormField field : schema().getFields()) {
            keys.add(field.getKey());
        }
        return keys;
    }

    private static List<FormOption> options(FormOption... items) {
        List<FormOption> list = new ArrayList<>();
        for (FormOption item : items) {
            list.add(item);
        }
        return list;
    }

    private static FormOption option(String value, String label) {
        FormOption option = new FormOption();
        option.setValue(value);
        option.setLabel(label);
        return option;
    }

    private static FormField field(String key, String label, FormFieldType type, List<FormOption> options) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type.name());
        field.setOptions(options);
        return field;
    }

    /** 供文案使用：把 {@code process.xxx} 渲染成「上一节点结果」这类可读名 */
    public static String labelOf(String field) {
        if (!StringUtils.hasText(field)) {
            return "";
        }
        return labels().getOrDefault(field, field);
    }
}
