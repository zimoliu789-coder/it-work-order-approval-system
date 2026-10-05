package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 借用单内置字段域（ / M1）。
 *
 * <h2>它解决什么问题</h2>
 * <p>借用单没有动态表单，但接入流程后同样需要「条件分支」。条件分支必须能引用字段
 * （如「预计借用天数 &gt; 30 就走部门经理审批」），因此借用域需要一份**可信的字段清单**：
 * 校验器据此判断「条件里引用的字段存不存在」「这个字段能不能做数值比较」。
 *
 * <h2>为什么以 {@link FormSchema} 的形态暴露，而不是另建一套结构</h2>
 * <p>校验器里「字段存在性 + 是否布局元素 + 数值/文本可比性」这套判定已经写在
 * {@code FlowDefinitionValidator#validateConditionField}。若借用域另造一种字段结构，
 * 就必须在那段逻辑里长出第二个分支 —— 也就等于把同一套类型规则写两遍，
 * 正是 M4a 刚刚消除的漂移隐患。
 *
 * <p>因此这里把借用内置字段**适配成与动态表单同构的 {@link FormField}**：
 * 校验器、前端字段选择器、条件求值器都能直接复用，无需知道字段来自哪里。
 * 新增一个借用内置字段时，只改本文件一处。
 *
 * <h2>字段值从哪来</h2>
 * <p>{@link #formData} 负责把提交入参（借用类型 / 期望归还日期 / 用途 / 设备金额）
 * 与设备信息转成条件求值器要的扁平 map。二者的 key 必须**逐字一致**，
 * 因此所有 key 都以常量形式集中在本类，避免「选择器里写 borrow.useType、求值时写成
 * useType」这类静默失配（求值失败不会报错，只会让条件永远不命中）。
 */
public final class BorrowFieldCatalog {

    /** 借用类型（SHORT_TERM 短期借用 / LONG_TERM 长期领用） */
    public static final String USE_TYPE = "borrow.useType";
    /** 预计借用天数（由期望归还日期与今天换算；长期领用无固定归还日期 → 不参与比较） */
    public static final String EXPECTED_DAYS = "borrow.expectedDays";
    /** 申请设备的主分类 id */
    public static final String DEVICE_CATEGORY_ID = "borrow.deviceCategoryId";
    /** 用途（原「借用原因」； 起改为选填） */
    public static final String REASON = "borrow.reason";

    /**
     * 设备金额（元， 新增）—— 取自资产台账 {@code device.amount}，**由系统自动带出**。
     *
     * <h2>它是金额分档条件的唯一依据</h2>
     * <p>：「设备金额 ≤ 5000 元走三级审批；&gt; 5000 元加一级上级部门主管」。
     * 金额不作为员工可填字段（已明确「金额（自动带）」），因此它的来源只能是资产。
     *
     * <h2>金额未录入时</h2>
     * <p>{@link #formData} 在金额为 null 时**不放入 map**（而不是放 0）。这不是偷懒，
     * 而正是需求要的口径：求值器取到空值 → 数值比较不成立 → 走默认（三级）分支。
     * 若放 0，则「金额 ≥ 0」这类条件会把所有未录入金额的设备误判成命中大额分支；
     * 若放一个极大值，则相反地让小额设备错走四级。两种都不可接受。
     */
    public static final String DEVICE_AMOUNT = "borrow.deviceAmount";

    private BorrowFieldCatalog() {
    }

    /**
     * 借用域字段清单。
     *
     * <p>字段类型刻意选「能表达值的语义」的那一种，因为校验器据它推断可比性：
     * <ul>
     *   <li>{@code borrow.useType} 用 SELECT（值域 = UseType 枚举），使「等于 / 不等于」可用，
     *       也使前端选择器能直接列出可选值；</li>
     *   <li>{@code borrow.expectedDays} / {@code borrow.deviceCategoryId} / {@code borrow.deviceAmount}
     *       用 NUMBER，从而允许 {@code > >= < <=} 这类数值比较；</li>
     *   <li>{@code borrow.reason} 用 TEXTAREA，允许「包含 / 不包含」。</li>
     * </ul>
     */
    public static FormSchema schema() {
        FormSchema schema = new FormSchema();
        List<FormField> fields = new ArrayList<>();
        fields.add(field(USE_TYPE, "借用类型", FormFieldType.SELECT, useTypeOptions()));
        fields.add(field(EXPECTED_DAYS, "预计借用天数", FormFieldType.NUMBER, null));
        fields.add(field(DEVICE_CATEGORY_ID, "设备主分类", FormFieldType.NUMBER, null));
        fields.add(field(REASON, "用途", FormFieldType.TEXTAREA, null));
        fields.add(field(DEVICE_AMOUNT, "设备金额(元)", FormFieldType.NUMBER, null));
        schema.setFields(fields);
        return schema;
    }

    /**
     * 把借用单的提交上下文转成条件求值器要的扁平 map。
     *
     * <p>求值器（{@code FlowPathResolver#matches}）直接以 {@code rule.getField()} 为 key 取值比较，
     * 因此这里的 key 必须与 {@link #schema()} 中字段的 key 完全一致。
     *
     * <h2>关于「长期领用」没有天数</h2>
     * <p>长期领用（LONG_TERM）没有固定归还日期，{@code expectedDays} 取不到值 ——
     * 此时**不放入 map**（而不是放 0）。这利用了求值器的既有语义：
     * 取到 null 时数值比较自然不成立，与前端「空值不满足条件」的表现一致；
     * 若放 0，则「expectedDays &gt; 0」这类条件会把长期领用错误地判为命中。
     *
     * <p>{@code deviceAmount} 同理：未录入金额时不放入，走默认分支。
     *
     * @param useType           借用类型枚举名（SHORT_TERM / LONG_TERM）
     * @param expectedDays      预计借用天数；长期领用传 null
     * @param deviceCategoryId  设备主分类 id；可为 null
     * @param reason            用途（原「借用原因」）
     * @param deviceAmount      设备金额（元）；资产未录入时为 null
     */
    public static Map<String, Object> formData(String useType, Integer expectedDays,
                                               Long deviceCategoryId, String reason,
                                               BigDecimal deviceAmount) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfPresent(data, USE_TYPE, useType);
        putIfPresent(data, EXPECTED_DAYS, expectedDays);
        putIfPresent(data, DEVICE_CATEGORY_ID, deviceCategoryId);
        putIfPresent(data, REASON, reason);
        putIfPresent(data, DEVICE_AMOUNT, deviceAmount);
        return data;
    }

    /**
     * 由「今天 → 期望归还日期」换算预计借用天数。
     *
     * @param expectedReturnDate 期望归还日期；长期领用为 null
     * @return 天数（&gt;= 0）；无法换算时返回 null
     */
    public static Integer expectedDays(LocalDate today, LocalDate expectedReturnDate) {
        if (today == null || expectedReturnDate == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, expectedReturnDate);
        // 需求已保证期望归还日期不早于今天；此处仍取 max(0) 以防脏数据把负数喂给条件求值
        return (int) Math.max(0, days);
    }

    /** 借用类型可选值：直接复用 UseType 枚举，避免与提交侧的值域漂移 */
    private static List<FormOption> useTypeOptions() {
        List<FormOption> options = new ArrayList<>();
        for (com.enterprise.ticket.common.constant.UseType type
                : com.enterprise.ticket.common.constant.UseType.values()) {
            FormOption option = new FormOption();
            option.setValue(type.name());
            option.setLabel(type.getLabel());
            options.add(option);
        }
        return options;
    }

    private static FormField field(String key, String label, FormFieldType type, List<FormOption> options) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type.name());
        field.setOptions(options);
        return field;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        data.put(key, value);
    }
}
