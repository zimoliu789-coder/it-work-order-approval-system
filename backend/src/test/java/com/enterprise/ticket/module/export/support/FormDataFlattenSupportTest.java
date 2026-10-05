package com.enterprise.ticket.module.export.support;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.Column;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.VersionSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义表单摊平单测（Phase 16 Wave 3 · M6）
 *
 * <p>覆盖四类「不报错但会导出错内容」的规则：
 * <ol>
 *   <li><b>列并集与顺序</b>——版本号升序 → 版本内字段顺序；同 key 复用一列、label 取最早版本；</li>
 *   <li><b>按本行自己的版本格式化</b>——同 key 在不同版本里可能是不同类型，
 *       用别的版本的定义去格式化必然出错（这是最容易写错的一处）；</li>
 *   <li><b>缺列留空</b>——列来自别的版本的字段，在本行必须留空白而不是 {@code -}；</li>
 *   <li><b>引用与选项的可读化</b>——选项输出 label、引用输出名称、查不到回落 {@code #id}，
 *       与详情页逐字一致。</li>
 * </ol>
 */
class FormDataFlattenSupportTest {

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private static FormField field(String key, String label, FormFieldType type) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type.name());
        return field;
    }

    private static FormField layoutField(FormFieldType type, String content) {
        FormField field = new FormField();
        field.setKey(null);
        field.setLabel("说明");
        field.setType(type.name());
        field.setContent(content);
        return field;
    }

    private static FormField optionField(String key, String label, FormFieldType type, String... values) {
        FormField field = field(key, label, type);
        List<FormOption> options = new ArrayList<>();
        for (String value : values) {
            FormOption option = new FormOption();
            option.setValue(value);
            option.setLabel(value + "-中文");
            options.add(option);
        }
        field.setOptions(options);
        return field;
    }

    private static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    private static VersionSchema version(long id, Integer versionNo, FormSchema schema) {
        return new VersionSchema(id, versionNo, schema);
    }

    private static List<String> keysOf(List<Column> columns) {
        return columns.stream().map(Column::key).toList();
    }

    private static List<String> labelsOf(List<Column> columns) {
        return columns.stream().map(Column::label).toList();
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // 列并集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列 = 版本号升序 → 各版本内字段顺序；同 key 只出现一次")
    void resolveColumns_unionInVersionOrder() {
        FormSchema v1 = schema(
                field("title", "标题", FormFieldType.TEXT),
                field("amount", "金额", FormFieldType.NUMBER));
        FormSchema v2 = schema(
                field("title", "标题（改）", FormFieldType.TEXT),
                field("project", "项目号", FormFieldType.TEXT),
                field("quantity", "数量", FormFieldType.NUMBER));

        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(
                version(2L, 2, v2), version(1L, 1, v1)));

        assertEquals(List.of("title", "amount", "project", "quantity"), keysOf(columns));
    }

    @Test
    @DisplayName("跨版本同 key 复用一列，label 取最早出现的版本（改文案不影响既有列名）")
    void resolveColumns_sameKeyKeepsEarliestLabel() {
        FormSchema v1 = schema(field("amount", "预计金额", FormFieldType.NUMBER));
        FormSchema v2 = schema(field("amount", "预计金额（元）", FormFieldType.NUMBER));

        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(
                version(1L, 1, v1), version(2L, 2, v2)));

        assertEquals(List.of("amount"), keysOf(columns));
        assertEquals(List.of("预计金额"), labelsOf(columns));
    }

    @Test
    @DisplayName("布局字段（说明文字 / 分隔线）与无 key 字段不进列")
    void resolveColumns_skipsLayoutFields() {
        FormSchema schema = schema(
                layoutField(FormFieldType.DESCRIPTION, "请如实填写"),
                field(null, "无key", FormFieldType.TEXT),
                field("title", "标题", FormFieldType.TEXT));

        assertEquals(List.of("title"), keysOf(FormDataFlattenSupport.resolveColumns(List.of(version(1L, 1, schema)))));
    }

    @Test
    @DisplayName("版本号相同按 versionId 兜底；版本号为 null 排最后（列顺序必须确定）")
    void resolveColumns_isDeterministic() {
        FormSchema a = schema(field("a", "A", FormFieldType.TEXT));
        FormSchema b = schema(field("b", "B", FormFieldType.TEXT));
        FormSchema c = schema(field("c", "C", FormFieldType.TEXT));

        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(
                version(9L, null, c), version(3L, 1, b), version(2L, 1, a)));

        assertEquals(List.of("a", "b", "c"), keysOf(columns));
    }

    @Test
    @DisplayName("空的版本集合 / schema 为 null 都安全返回空列，不抛错")
    void resolveColumns_handlesEmptyInput() {
        assertTrue(FormDataFlattenSupport.resolveColumns(null).isEmpty());
        assertTrue(FormDataFlattenSupport.resolveColumns(List.of()).isEmpty());
        assertTrue(FormDataFlattenSupport.resolveColumns(List.of(version(1L, 1, null))).isEmpty());
    }

    @Test
    @DisplayName("字段未配 label 时用 key 当表头（不产出空表头）")
    void resolveColumns_fallsBackToKeyAsLabel() {
        FormSchema schema = schema(field("title", null, FormFieldType.TEXT));

        assertEquals(List.of("title"), labelsOf(FormDataFlattenSupport.resolveColumns(List.of(version(1L, 1, schema)))));
    }

    // ------------------------------------------------------------------
    // 单元格：按本行自己的版本格式化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一 key 在不同版本里类型不同时，各行按自己版本的类型格式化")
    void rowOf_usesRowOwnSchema() {
        FormField v1Field = field("code", "编码", FormFieldType.TEXT);
        FormField v2Field = optionField("code", "编码", FormFieldType.SELECT, "A");
        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(
                version(1L, 1, schema(v1Field)), version(2L, 2, schema(v2Field))));

        // v1 行：值是自由文本，原样输出
        Object[] v1Row = FormDataFlattenSupport.rowOf(schema(v1Field), values("code", "TEXT-1"),
                columns, References.empty());
        // v2 行：值命中选项，输出 label
        Object[] v2Row = FormDataFlattenSupport.rowOf(schema(v2Field), values("code", "A"),
                columns, References.empty());

        assertEquals("TEXT-1", v1Row[0]);
        assertEquals("A-中文", v2Row[0]);
    }

    @Test
    @DisplayName("本行版本里没有的列（列来自别的版本）留空白，而不是 -")
    void rowOf_missingColumnIsBlank() {
        FormField title = field("title", "标题", FormFieldType.TEXT);
        FormField extra = field("extra", "新增字段", FormFieldType.TEXT);
        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(
                version(1L, 1, schema(title)), version(2L, 2, schema(title, extra))));

        Object[] cells = FormDataFlattenSupport.rowOf(schema(title), values("title", "报销申请"),
                columns, References.empty());

        assertEquals("报销申请", cells[0]);
        assertEquals("", cells[1], "缺列必须是空白：- 会被当成值参与 Excel 筛选与透视");
    }

    @Test
    @DisplayName("null 值与空字符串都输出空串（不产出 null 单元格）")
    void rowOf_nullValuesAreBlank() {
        FormField title = field("title", "标题", FormFieldType.TEXT);
        FormField reason = field("reason", "事由", FormFieldType.TEXTAREA);
        List<Column> columns = FormDataFlattenSupport.resolveColumns(List.of(version(1L, 1, schema(title, reason))));

        Object[] cells = FormDataFlattenSupport.rowOf(schema(title, reason),
                values("title", null, "reason", ""), columns, References.empty());

        assertEquals("", cells[0]);
        assertEquals("", cells[1]);
    }

    @Test
    @DisplayName("选项字段输出 label；值不在选项集合里时回落原值（选项被删后仍看得见当时选了什么）")
    void formatValue_optionLabels() {
        FormField single = optionField("cat", "类别", FormFieldType.SELECT, "OFFICE");
        FormField multi = optionField("tags", "标签", FormFieldType.CHECKBOX, "A", "B");

        assertEquals("OFFICE-中文", FormDataFlattenSupport.formatValue(single, "OFFICE", References.empty()));
        assertEquals("已删除", FormDataFlattenSupport.formatValue(single, "已删除", References.empty()));
        assertEquals("A-中文、B-中文", FormDataFlattenSupport.formatValue(multi, List.of("A", "B"), References.empty()));
        // 多选里混入未知值时逐项回落，不整片丢失
        assertEquals("A-中文、X", FormDataFlattenSupport.formatValue(multi, List.of("A", "X"), References.empty()));
    }

    @Test
    @DisplayName("数字字段追加单位；整数不带小数点")
    void formatValue_numbers() {
        FormField amount = field("amount", "金额", FormFieldType.NUMBER);
        amount.setUnit("元");
        FormField quantity = field("quantity", "数量", FormFieldType.NUMBER);

        assertEquals("8000元", FormDataFlattenSupport.formatValue(amount, 8000, References.empty()));
        assertEquals("8000.5元", FormDataFlattenSupport.formatValue(amount, 8000.5, References.empty()));
        assertEquals("8000", FormDataFlattenSupport.formatValue(quantity, "8000", References.empty()));
        assertEquals("", FormDataFlattenSupport.formatValue(amount, null, References.empty()));
    }

    @Test
    @DisplayName("引用类字段输出名称；查不到 id 回落 #id（不抹掉信息）")
    void formatValue_references() {
        References refs = new References(
                Map.of(4L, "张三", 5L, "李四（离职）"),
                Map.of(9L, "ThinkPad X1（ZC-0001）"),
                Map.of(7L, "研发一组"));
        FormField user = field("receiver", "接收人", FormFieldType.USER);
        FormField device = field("device", "设备", FormFieldType.DEVICE);
        FormField group = field("group", "分组", FormFieldType.BIZ_GROUP);

        assertEquals("张三", FormDataFlattenSupport.formatValue(user, 4, refs));
        assertEquals("李四（离职）", FormDataFlattenSupport.formatValue(user, "5", refs),
                "字符串形态的 id 也要认（JSON 里可能是字符串）");
        assertEquals("#99", FormDataFlattenSupport.formatValue(user, 99, refs));
        assertEquals("ThinkPad X1（ZC-0001）", FormDataFlattenSupport.formatValue(device, 9L, refs));
        assertEquals("研发一组", FormDataFlattenSupport.formatValue(group, 7, refs));
    }

    @Test
    @DisplayName("附件字段输出 -（附件不在表单数据里，结构上就没有值）")
    void formatValue_fileIsPlaceholder() {
        FormField file = field("attachments", "附件", FormFieldType.FILE);
        FormField image = field("photos", "照片", FormFieldType.IMAGE);

        assertEquals("-", FormDataFlattenSupport.formatValue(file, null, References.empty()));
        assertEquals("-", FormDataFlattenSupport.formatValue(image, List.of(), References.empty()));
        // 若将来值真的落了库，按详情页口径输出「已上传 N 个附件」，而不是继续谎报 -
        assertEquals("已上传 2 个附件", FormDataFlattenSupport.formatValue(file, List.of("a", "b"), References.empty()));
    }

    @Test
    @DisplayName("日期 / 文本原样输出；未登记的字段类型也原样输出而不是丢值")
    void formatValue_plainAndUnknownTypes() {
        FormField date = field("expectedDate", "期望日期", FormFieldType.DATE);
        FormField broken = field("weird", "未知类型", FormFieldType.TEXT);
        broken.setType("NOT_A_REAL_TYPE");

        assertEquals("2027-01-01", FormDataFlattenSupport.formatValue(date, "2027-01-01", References.empty()));
        assertEquals("原样", FormDataFlattenSupport.formatValue(broken, "原样", References.empty()));
    }

    @Test
    @DisplayName("布局字段永远输出空串（不会把说明文字当成值填进单元格）")
    void formatValue_layoutFieldIsEmpty() {
        FormField description = layoutField(FormFieldType.DESCRIPTION, "请如实填写");

        assertEquals("", FormDataFlattenSupport.formatValue(description, "请如实填写", References.empty()));
    }

    // ------------------------------------------------------------------
    // 引用 id 的收集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keysByValueKind 只归类数据字段，且把全部版本的 key 取并集")
    void keysByValueKind_groupsReferenceKeys() {
        FormSchema v1 = schema(
                layoutField(FormFieldType.DESCRIPTION, "说明"),
                field("title", "标题", FormFieldType.TEXT),
                field("receiver", "接收人", FormFieldType.USER),
                field("device", "设备", FormFieldType.DEVICE));
        FormSchema v2 = schema(
                field("owner", "负责人", FormFieldType.USER),
                field("group", "分组", FormFieldType.BIZ_GROUP));

        Map<FormFieldType.ValueKind, Set<String>> keys = FormDataFlattenSupport.keysByValueKind(List.of(
                version(1L, 1, v1), version(2L, 2, v2)));

        assertEquals(Set.of("receiver", "owner"), keys.get(FormFieldType.ValueKind.USER_REF));
        assertEquals(Set.of("device"), keys.get(FormFieldType.ValueKind.DEVICE_REF));
        assertEquals(Set.of("group"), keys.get(FormFieldType.ValueKind.GROUP_REF));
        assertNull(keys.get(FormFieldType.ValueKind.NONE), "布局字段不该出现在任何引用类归类里");
        assertTrue(keys.getOrDefault(FormFieldType.ValueKind.TEXT, Set.of()).contains("title"));
    }

    @Test
    @DisplayName("keysByValueKind 对 null 输入安全")
    void keysByValueKind_nullSafe() {
        assertNotNull(FormDataFlattenSupport.keysByValueKind(null));
        assertTrue(FormDataFlattenSupport.keysByValueKind(null).isEmpty());
    }

    @Test
    @DisplayName("idOf 同时认数字与字符串，非数字与 null 返回 null")
    void idOf_parsesBothShapes() {
        assertEquals(4L, FormDataFlattenSupport.idOf(4));
        assertEquals(4L, FormDataFlattenSupport.idOf(4L));
        assertEquals(4L, FormDataFlattenSupport.idOf("4"));
        assertEquals(4L, FormDataFlattenSupport.idOf(" 4 "));
        assertNull(FormDataFlattenSupport.idOf("abc"));
        assertNull(FormDataFlattenSupport.idOf(null));
        assertNull(FormDataFlattenSupport.idOf(Map.of()));
    }
}
