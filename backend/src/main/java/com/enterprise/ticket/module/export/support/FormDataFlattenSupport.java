package com.enterprise.ticket.module.export.support;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义表单数据「摊平成表格」支撑（ · M6）
 *
 * <h2>两个必须分开回答的问题</h2>
 * <ol>
 *   <li><b>表头是什么</b>（{@link #resolveColumns}）—— 由「本批工单<b>实际引用过</b>的
 *       表单版本」的字段并集决定，而不是「申请类型当前绑定的版本」。
 *       申请类型换绑表单后，历史工单用的是旧版本；只看当前版本会让
 *       <b>历史工单的字段整列消失</b>，那不叫「完整导出」。
 *       顺带这也自动覆盖了「换绑到另一个模板」的情形
 *       （{@code apply_type} 表里并没有 {@code template_id} 列，无从反查）。</li>
 *   <li><b>单元格是什么</b>（{@link #rowOf}）—— 用<b>该工单自己那一版</b>的字段定义格式化。
 *       同一个 key 在不同版本里可能是不同类型（v1 是文本、v2 是数字），
 *       用别的版本的定义去格式化必然出错 —— 值是按它自己那版的规则填的。</li>
 * </ol>
 *
 * <h2>为什么是纯函数</h2>
 * <p>不依赖 Spring、不碰数据库：版本定义与引用名称映射都由调用方（{@code ExportSheetBuilder}）
 * 批量查好后传入。这样「列顺序 / 同 key 合并 / 缺列留空 / 引用回落」这些最容易漂移的规则
 * 可以在单测里逐条钉死，而不必拖起数据库。
 *
 * <h2>与详情页的展示口径对齐</h2>
 * <p>格式化规则逐条对照前端 {@code FormRenderer.displayText} / {@code refLabel}：
 * 选项输出 label、人员输出「姓名（离职）」（离职标记由调用方在名称里带上）、
 * 设备输出「名称（资产编号）」、分组输出分组名、数字追加单位、查不到回落 {@code #id}。
 * 导出的表与详情页对同一个字段显示同样的文字，用户不会在两处看到两种说法。
 */
public final class FormDataFlattenSupport {

    /** 附件字段的占位符：附件不在 {@code form_data_json} 里，这一列结构上就没有值 */
    public static final String FILE_PLACEHOLDER = "-";

    /**
     * 一个表单版本的定义（版本号 + 解析后的 schema）
     *
     * <p>{@code versionNo} 用于给列排序（**版本号升序**，即「先出现的先成列」）。
     * 允许为 null（极端情况下版本行缺号），排序时排到最后而不是抛异常 ——
     * 列顺序退化只影响可读性，不该让整次导出失败。
     */
    public record VersionSchema(Long versionId, Integer versionNo, FormSchema schema) {
    }

    /** 一列：{@code key} 是定位数据的键，{@code label} 是表头 */
    public record Column(String key, String label) {
    }

    /**
     * 引用类字段的名称映射（全部由调用方一次 {@code IN} 查好，本类不做任何查询）
     *
     * <p>三类 map 的值都是<b>最终展示文本</b>：
     * 人员已含离职标记、设备已含资产编号。把「组合展示文本」放在装载侧，
     * 是为了让本类的格式化逻辑保持在「查表 + 回落」这一层，可穷举、可断言。
     */
    public record References(Map<Long, String> userLabels,
                            Map<Long, String> deviceLabels,
                            Map<Long, String> groupLabels) {

        public static References empty() {
            return new References(Map.of(), Map.of(), Map.of());
        }

        private static String lookup(Map<Long, String> map, Object raw) {
            Long id = idOf(raw);
            if (id == null) {
                // 值不是 id（人工改库等脏数据）：原样输出，不抹掉信息
                return stringOf(raw);
            }
            String label = map == null ? null : map.get(id);
            return label == null ? "#" + id : label;
        }

        public String user(Object raw) {
            return lookup(userLabels, raw);
        }

        public String device(Object raw) {
            return lookup(deviceLabels, raw);
        }

        public String group(Object raw) {
            return lookup(groupLabels, raw);
        }
    }

    private FormDataFlattenSupport() {
    }

    // ------------------------------------------------------------------
    // 表头：列并集
    // ------------------------------------------------------------------

    /**
     * 求列并集：**版本号升序 → 各版本内 schema 字段顺序**。
     *
     * <ul>
     *   <li>同一 {@code key} 在多个版本中复用同一列，{@code label} 取<b>最早出现</b>的版本 ——
     *       历史列名稳定，不会因为某次改文案就让表头跟着变；</li>
     *   <li>布局类字段（说明文字 / 分隔线）<b>不进列</b>：它们没有值，进列就是一片空白列；</li>
     *   <li>{@code key} 为空（字段被人工改坏）的跳过，不造出无法定位数据的列。</li>
     * </ul>
     */
    public static List<Column> resolveColumns(List<VersionSchema> versions) {
        List<VersionSchema> ordered = new ArrayList<>(versions == null ? List.of() : versions);
        // 版本号升序；版本号相同时按 versionId 兜底，保证列顺序在任何数据下都确定
        ordered.sort(Comparator
                .comparing((VersionSchema v) -> v.versionNo() == null ? Integer.MAX_VALUE : v.versionNo())
                .thenComparing(v -> v.versionId() == null ? Long.MAX_VALUE : v.versionId()));

        Map<String, String> labelByKey = new LinkedHashMap<>();
        for (VersionSchema version : ordered) {
            if (version.schema() == null) {
                continue;
            }
            for (FormField field : version.schema().getFields()) {
                String key = field.getKey();
                if (key == null || key.isBlank() || !isDataField(field)) {
                    continue;
                }
                // 只在首次出现时登记 label：后出现的版本改文案不影响既有列名
                labelByKey.putIfAbsent(key, labelOf(field, key));
            }
        }
        List<Column> columns = new ArrayList<>(labelByKey.size());
        labelByKey.forEach((key, label) -> columns.add(new Column(key, label)));
        return columns;
    }

    /**
     * 按「值的种类」归类字段 key
     *
     * <p>用途：引用类字段（人员 / 设备 / 分组）的值是 id，导出时必须换成名称。
     * 调用方先拿到「哪些 key 是引用类」，再遍历本批数据把 id 收集起来，
     * 最后对每类各发<b>一次</b> {@code IN} 查询 —— 这正是「不做 N+1」的前提。
     *
     * <p>返回的 key 集合是<b>所有版本</b>的并集：某一行的数据里只会有它自己那版的 key，
     * 取不到的键自然为 null，不会误伤。
     */
    public static Map<FormFieldType.ValueKind, Set<String>> keysByValueKind(List<VersionSchema> versions) {
        Map<FormFieldType.ValueKind, Set<String>> keys = new LinkedHashMap<>();
        for (VersionSchema version : versions == null ? List.<VersionSchema>of() : versions) {
            if (version.schema() == null) {
                continue;
            }
            for (FormField field : version.schema().getFields()) {
                String key = field.getKey();
                if (key == null || key.isBlank() || !isDataField(field)) {
                    continue;
                }
                FormFieldType type = FormFieldType.of(field.getType());
                if (type == null) {
                    continue;
                }
                keys.computeIfAbsent(type.getValueKind(), kind -> new LinkedHashSet<>()).add(key);
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // 单元格
    // ------------------------------------------------------------------

    /**
     * 取一行数据：对每个列 key 用<b>本行自己那一版</b>的字段定义格式化
     *
     * <p>本行版本里没有的 key（= 该列来自别的版本）输出空字符串 ——
     * 「这一单当时没这个字段」在 Excel 里的正确表达是空单元格，不是 {@code -}：
     * {@code -} 会被当成值参与筛选与透视，而空白明确表示「无」。
     */
    public static Object[] rowOf(FormSchema rowSchema, Map<String, Object> data,
                                 List<Column> columns, References references) {
        Map<String, FormField> fieldByKey = fieldByKey(rowSchema);
        Map<String, Object> values = data == null ? Map.of() : data;
        References refs = references == null ? References.empty() : references;

        List<Object> cells = new ArrayList<>(columns.size());
        for (Column column : columns) {
            FormField field = fieldByKey.get(column.key());
            if (field == null) {
                cells.add("");
                continue;
            }
            cells.add(formatValue(field, values.get(column.key()), refs));
        }
        return cells.toArray();
    }

    /**
     * 单值格式化（导出为文本；数值列也输出文本，因为同一个 key 在不同版本里
     * 可能是数字也可能是文本，Excel 列一旦被定义为数值就再也装不下文本）
     */
    public static String formatValue(FormField field, Object raw, References references) {
        if (field == null) {
            return "";
        }
        FormFieldType type = FormFieldType.of(field.getType());
        if (type == null) {
            // 未登记的字段类型：原样输出，不丢信息也不猜语义
            return stringOf(raw);
        }
        return switch (type.getValueKind()) {
            case NONE -> "";
            case NUMBER -> numberText(raw, field.getUnit());
            case OPTION -> optionLabel(field, raw);
            case OPTION_MULTI -> multiOptionLabel(field, raw);
            case USER_REF -> references.user(raw);
            case DEVICE_REF -> references.device(raw);
            case GROUP_REF -> references.group(raw);
            case FILE -> fileText(raw);
            // TEXT / DATE / DATETIME：值本身就是可读文本（日期由前端按 yyyy-MM-dd 落库）
            default -> stringOf(raw);
        };
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Map<String, FormField> fieldByKey(FormSchema schema) {
        Map<String, FormField> byKey = new LinkedHashMap<>();
        if (schema == null) {
            return byKey;
        }
        for (FormField field : schema.getFields()) {
            String key = field.getKey();
            if (key != null && !key.isBlank()) {
                byKey.putIfAbsent(key, field);
            }
        }
        return byKey;
    }

    /** 字段类型缺失 / 非法时按「有值的数据字段」处理，避免因为一条脏定义丢掉整列 */
    private static boolean isDataField(FormField field) {
        FormFieldType type = FormFieldType.of(field.getType());
        return type == null || type.isDataField();
    }

    private static String labelOf(FormField field, String fallback) {
        String label = field.getLabel();
        return label == null || label.isBlank() ? fallback : label;
    }

    /** 数字 + 单位（「8000元」）。单位为空则只输出数字 */
    private static String numberText(Object raw, String unit) {
        String text = numericText(raw);
        if (unit == null || unit.isBlank() || text.isEmpty()) {
            return text;
        }
        return text + unit;
    }

    /**
     * 数字的文本化：整数不带小数点（「8000」而不是「8000.0」）
     *
     * <p>JSON 里的 {@code 8000} 会被 Jackson 解析成 Integer、{@code 8000.5} 解析成 Double，
     * 而 {@code 8000.00} 这类带小数位的值（若将来改用 BigDecimal）需要去掉尾随零 ——
     * 否则同一列里会同时出现「8000」与「8000.00」，Excel 无法按数值排序。
     */
    private static String numericText(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof BigDecimal decimal) {
            // toPlainString：不做科学计数（stripTrailingZeros 会把 8000 变成 8E+3）
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value == Math.rint(value)
                    && Math.abs(value) < 9.007199254740992E15) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
        return stringOf(raw);
    }

    /** 单选项 → label；命中不到回落原值（选项被删 / 改名后仍看得见当时选了哪个值） */
    private static String optionLabel(FormField field, Object raw) {
        String value = stringOf(raw);
        if (value.isEmpty()) {
            return "";
        }
        String label = optionLabelOf(field, value);
        return label == null ? value : label;
    }

    /** 多选项 → label 以「、」连接；非数组值（脏数据）按单值处理 */
    private static String multiOptionLabel(FormField field, Object raw) {
        if (raw == null) {
            return "";
        }
        Collection<?> items = raw instanceof Collection<?> collection
                ? collection
                : List.of(raw);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : items) {
            String value = stringOf(item);
            if (value.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('、');
            }
            String label = optionLabelOf(field, value);
            builder.append(label == null ? value : label);
        }
        return builder.toString();
    }

    private static String optionLabelOf(FormField field, String value) {
        List<FormOption> options = field.getOptions();
        if (options == null) {
            return null;
        }
        for (FormOption option : options) {
            if (option != null && value.equals(option.getValue())) {
                return option.getLabel();
            }
        }
        return null;
    }

    /**
     * 附件字段的文本：恒为 {@value #FILE_PLACEHOLDER}
     *
     * <p>附件<b>不写进 {@code form_data_json}</b>（上传需要工单 id，而工单 id 在提交成功后才存在），
     * 因此这一列的值在数据模型上就不存在。写 {@code -} 而不是留空白，
     * 是为了明确表达「这一列已处理，只是结构上没有值」——
     * 空白会让用户以为导出漏了内容。附件明细见同文件的「附件清单」工作表。
     *
     * <p>若将来值真的落了库（非空数组），按详情页的口径输出「已上传 N 个附件」，
     * 而不是继续谎报 {@code -}。
     */
    private static String fileText(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.isEmpty() ? FILE_PLACEHOLDER : "已上传 " + collection.size() + " 个附件";
        }
        String text = stringOf(raw);
        return text.isEmpty() ? FILE_PLACEHOLDER : text;
    }

    /**
     * id 的宽松解析：JSON 里可能是数字也可能是字符串，两种都要认
     *
     * <p>公开供调用方复用：收集引用类 id 时要用同一套解析规则，
     * 否则会出现「格式化认这个值、收集 id 时不认」导致名称解析不到的错位。
     */
    public static Long idOf(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String stringOf(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
