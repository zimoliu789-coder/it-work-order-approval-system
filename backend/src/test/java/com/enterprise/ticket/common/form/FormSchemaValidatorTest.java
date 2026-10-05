package com.enterprise.ticket.common.form;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表单定义（schema）发布校验单元测试（Phase 14）
 *
 * <p>纯静态方法，无 Mock、无 Spring 上下文。
 *
 * <p>为什么这些用例重要：{@link FormSchemaValidator#validateAndNormalize} 是「草稿 → 发布」
 * 的那道闸门。闸门一旦漏，被放行的 schema 会被申请类型引用、被每一次提交当作校验依据 ——
 * 例如一个「没有选项的下拉框」会让用户永远选不出合法值，一个非法正则会让每次提交都抛异常。
 * 因此这里把「该拦的必须拦住」与「该归一化的正确归一化」都固化成断言。
 */
class FormSchemaValidatorTest {

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private static FormField field(String type, String key, String label) {
        FormField field = new FormField();
        field.setType(type);
        field.setKey(key);
        field.setLabel(label);
        field.setRequired(false);
        field.setWidth(1);
        return field;
    }

    private static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    private static FormOption option(String value, String label) {
        FormOption option = new FormOption();
        option.setValue(value);
        option.setLabel(label);
        return option;
    }

    private static ErrorCode codeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    private static String messageOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getMessage();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    // ------------------------------------------------------------------
    // 整体结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空 schema / 空字段列表 → FORM_SCHEMA_INVALID（至少 1 个字段）")
    void rejectsEmptyForm() {
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID, codeOf(() -> FormSchemaValidator.validateAndNormalize(null)));

        FormSchema empty = new FormSchema();
        empty.setFields(new ArrayList<>());
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(empty)));
    }

    @Test
    @DisplayName("字段数量超过上限 50 → 拒绝（封住无界写入）")
    void rejectsTooManyFields() {
        List<FormField> fields = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            fields.add(field(FormFieldType.TEXT.name(), "f" + i, "字段" + i));
        }
        FormSchema schema = new FormSchema();
        schema.setFields(fields);

        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema)));
    }

    @Test
    @DisplayName("未知字段类型 → 拒绝（前端能拖、后端不认的缺陷在此被拦住）")
    void rejectsUnknownFieldType() {
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field("NOT_A_TYPE", "a", "甲")))));
    }

    @Test
    @DisplayName("宽度不是 1/2 → 拒绝；缺省宽度归一化为 1")
    void normalizesWidth() {
        FormField bad = field(FormFieldType.TEXT.name(), "a", "甲");
        bad.setWidth(3);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(bad))));

        FormField noWidth = field(FormFieldType.TEXT.name(), "a", "甲");
        noWidth.setWidth(null);
        FormSchema schema = schema(noWidth);
        FormSchemaValidator.validateAndNormalize(schema);
        assertEquals(1, schema.getFields().get(0).getWidth());
    }

    // ------------------------------------------------------------------
    // 字段 key / label
    // ------------------------------------------------------------------

    @Test
    @DisplayName("字段 key 非法 / 重复 / 为空 分别拒绝")
    void rejectsBadKeys() {
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field(FormFieldType.TEXT.name(), "1bad", "甲")))));

        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field(FormFieldType.TEXT.name(), "same", "甲"),
                                field(FormFieldType.TEXT.name(), "same", "乙")))));

        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(
                schema(field(FormFieldType.TEXT.name(), "  ", "甲")))).contains("不能为空"));
    }

    @Test
    @DisplayName("显示名称为空 / 超长 → 拒绝")
    void rejectsBadLabel() {
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field(FormFieldType.TEXT.name(), "a", "  ")))));

        String longLabel = "字".repeat(65);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field(FormFieldType.TEXT.name(), "a", longLabel)))));
    }

    // ------------------------------------------------------------------
    // 选项类
    // ------------------------------------------------------------------

    @Test
    @DisplayName("选项类字段无选项 / 选项值重复 / 选项值为空 → 拒绝")
    void rejectsBadOptions() {
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(
                        schema(field(FormFieldType.SELECT.name(), "s", "类别")))));

        FormField dup = field(FormFieldType.SELECT.name(), "s", "类别");
        dup.setOptions(List.of(option("A", "甲"), option("A", "乙")));
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(dup))).contains("重复"));

        FormField blank = field(FormFieldType.SELECT.name(), "s", "类别");
        blank.setOptions(List.of(option("", "甲")));
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(blank))));
    }

    @Test
    @DisplayName("非选项类字段携带的 options 被清空（与类型无关的残留配置归一化）")
    void clearsOptionsForNonOptionField() {
        FormField text = field(FormFieldType.TEXT.name(), "t", "文本");
        text.setOptions(List.of(option("A", "甲")));
        FormSchema schema = schema(text);

        FormSchemaValidator.validateAndNormalize(schema);

        assertNull(schema.getFields().get(0).getOptions());
    }

    // ------------------------------------------------------------------
    // 数字 / 文本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数字：min>max / 小数位越界 → 拒绝")
    void rejectsBadNumber() {
        FormField reversed = field(FormFieldType.NUMBER.name(), "n", "数量");
        reversed.setMin(10.0);
        reversed.setMax(1.0);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(reversed))));

        FormField precision = field(FormFieldType.NUMBER.name(), "n", "数量");
        precision.setPrecision(5);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(precision))));
    }

    @Test
    @DisplayName("文本：最小长度负数 / 最大长度越界 / min>max / 非法正则 → 拒绝")
    void rejectsBadText() {
        FormField negativeMin = field(FormFieldType.TEXT.name(), "t", "文本");
        negativeMin.setMinLength(-1);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(negativeMin))));

        FormField hugeMax = field(FormFieldType.TEXT.name(), "t", "文本");
        hugeMax.setMaxLength(3000);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(hugeMax))));

        FormField reversed = field(FormFieldType.TEXT.name(), "t", "文本");
        reversed.setMinLength(10);
        reversed.setMaxLength(2);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(reversed))));

        FormField badPattern = field(FormFieldType.TEXT.name(), "t", "文本");
        badPattern.setPattern("[unclosed");
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(badPattern)))
                .contains("正则表达式不合法"));
    }

    @Test
    @DisplayName("文本：合法正则通过；非文本字段的文本配置被清空")
    void normalizesText() {
        FormField ok = field(FormFieldType.TEXT.name(), "t", "手机号");
        ok.setPattern("^\\d{11}$");
        ok.setMaxLength(11);
        FormSchemaValidator.validateAndNormalize(schema(ok));

        FormField number = field(FormFieldType.NUMBER.name(), "n", "数量");
        number.setMaxLength(10);
        number.setPattern("\\d+");
        FormSchema schema = schema(number);
        FormSchemaValidator.validateAndNormalize(schema);
        assertNull(schema.getFields().get(0).getMaxLength());
        assertNull(schema.getFields().get(0).getPattern());
    }

    // ------------------------------------------------------------------
    // 日期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日期：非法 dateLimit / CUSTOM 缺上下界 / 起始晚于截止 → 拒绝")
    void rejectsBadDate() {
        FormField badLimit = field(FormFieldType.DATE.name(), "d", "日期");
        badLimit.setDateLimit("SOMETIME");
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(badLimit))));

        FormField noBound = field(FormFieldType.DATE.name(), "d", "日期");
        noBound.setDateLimit("CUSTOM");
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(noBound)))
                .contains("至少填写起始或截止日期"));

        FormField reversed = field(FormFieldType.DATE.name(), "d", "日期");
        reversed.setDateLimit("CUSTOM");
        reversed.setDateMin("2026-12-31");
        reversed.setDateMax("2026-01-01");
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(reversed)))
                .contains("不能晚于截止日期"));
    }

    @Test
    @DisplayName("日期：缺省 dateLimit 归一化为 NONE，且非 CUSTOM 时清空上下界")
    void normalizesDateLimit() {
        FormField field = field(FormFieldType.DATE.name(), "d", "日期");
        field.setDateLimit(null);
        field.setDateMin("2026-01-01");
        field.setDateMax("2026-12-31");
        FormSchema schema = schema(field);

        FormSchemaValidator.validateAndNormalize(schema);

        assertEquals("NONE", schema.getFields().get(0).getDateLimit());
        assertNull(schema.getFields().get(0).getDateMin());
        assertNull(schema.getFields().get(0).getDateMax());
    }

    // ------------------------------------------------------------------
    // 附件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("附件：数量/大小上限越界、高风险扩展名（svg/html/js…）→ 拒绝")
    void rejectsBadAttachment() {
        FormField manyFiles = field(FormFieldType.FILE.name(), "f", "附件");
        manyFiles.setMaxCount(11);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(manyFiles))));

        FormField hugeFile = field(FormFieldType.FILE.name(), "f", "附件");
        hugeFile.setMaxSizeMb(51);
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(hugeFile))));

        FormField svg = field(FormFieldType.FILE.name(), "f", "附件");
        svg.setFileTypes("pdf,svg");
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(svg)))
                .contains("高风险文件类型：svg"));

        FormField badExt = field(FormFieldType.FILE.name(), "f", "附件");
        badExt.setFileTypes("p.d.f");
        assertEquals(ErrorCode.FORM_SCHEMA_INVALID,
                codeOf(() -> FormSchemaValidator.validateAndNormalize(schema(badExt))));
    }

    @Test
    @DisplayName("附件：缺省上限归一化为 maxCount=1、maxSizeMb=10")
    void normalizesAttachmentDefaults() {
        FormField field = field(FormFieldType.FILE.name(), "f", "附件");
        FormSchema schema = schema(field);

        FormSchemaValidator.validateAndNormalize(schema);

        assertEquals(1, schema.getFields().get(0).getMaxCount());
        assertEquals(10, schema.getFields().get(0).getMaxSizeMb());
        assertNull(schema.getFields().get(0).getFileTypes());
    }

    // ------------------------------------------------------------------
    // 布局类
    // ------------------------------------------------------------------

    @Test
    @DisplayName("说明文字内容为空 → 拒绝；分隔线无需 key/label")
    void layoutFields() {
        FormField description = field(FormFieldType.DESCRIPTION.name(), null, "说明");
        description.setContent("  ");
        assertTrue(messageOf(() -> FormSchemaValidator.validateAndNormalize(schema(description)))
                .contains("内容不能为空"));

        // 分隔线：无 key、无 label 也合法
        FormField divider = field(FormFieldType.DIVIDER.name(), null, null);
        FormSchema schema = schema(divider);
        FormSchemaValidator.validateAndNormalize(schema);
        assertNull(schema.getFields().get(0).getKey());
    }

    @Test
    @DisplayName("布局类字段的「数据配置」被清空（key/required/options 等）")
    void clearsLayoutDataConfig() {
        FormField divider = field(FormFieldType.DIVIDER.name(), "leftover", "分隔");
        divider.setRequired(true);
        divider.setOptions(List.of(option("A", "甲")));
        divider.setMaxLength(10);
        FormSchema schema = schema(divider);

        FormSchemaValidator.validateAndNormalize(schema);

        FormField saved = schema.getFields().get(0);
        assertNull(saved.getKey());
        assertFalse(Boolean.TRUE.equals(saved.getRequired()));
        assertNull(saved.getOptions());
        assertNull(saved.getMaxLength());
    }

    // ------------------------------------------------------------------
    // 类型编码 / 工单前缀
    // ------------------------------------------------------------------

    @Test
    @DisplayName("类型编码校验：2–20 位、字母开头")
    void validatesTypeCode() {
        FormSchemaValidator.validateTypeCode("purchase");
        FormSchemaValidator.validateTypeCode("a1");

        assertEquals(ErrorCode.APPLY_TYPE_CODE_INVALID,
                codeOf(() -> FormSchemaValidator.validateTypeCode("a")));
        assertEquals(ErrorCode.APPLY_TYPE_CODE_INVALID,
                codeOf(() -> FormSchemaValidator.validateTypeCode("1abc")));
        assertEquals(ErrorCode.APPLY_TYPE_CODE_INVALID,
                codeOf(() -> FormSchemaValidator.validateTypeCode(null)));
    }

    @Test
    @DisplayName("工单前缀校验：空值放行（用系统默认），非法值拒绝")
    void validatesOrderPrefix() {
        FormSchemaValidator.validateOrderPrefix(null);
        FormSchemaValidator.validateOrderPrefix("  ");
        FormSchemaValidator.validateOrderPrefix("PRCH");

        assertEquals(ErrorCode.APPLY_TYPE_PREFIX_INVALID,
                codeOf(() -> FormSchemaValidator.validateOrderPrefix("P")));
        assertEquals(ErrorCode.APPLY_TYPE_PREFIX_INVALID,
                codeOf(() -> FormSchemaValidator.validateOrderPrefix("P_R")));
        assertEquals(ErrorCode.APPLY_TYPE_PREFIX_INVALID,
                codeOf(() -> FormSchemaValidator.validateOrderPrefix("1ABC")));
    }

    // ------------------------------------------------------------------
    // 枚举对齐
    // ------------------------------------------------------------------

    @Test
    @DisplayName("supportedTypes 与 FormFieldType 枚举完全一致（防止测试与实现各写一份）")
    void supportedTypesMirrorEnum() {
        List<String> types = FormSchemaValidator.supportedTypes();
        assertEquals(FormFieldType.values().length, types.size());
        assertTrue(types.containsAll(List.of("TEXT", "SELECT", "FILE", "USER", "DESCRIPTION", "DIVIDER")));
        // 典型可渲染类型均在列
        for (FormFieldType type : FormFieldType.values()) {
            assertNotNull(FormFieldType.of(type.name()));
        }
    }

    @Test
    @DisplayName("合法表单通过且保持字段顺序（顺序即渲染顺序，不能被归一化打乱）")
    void acceptsValidSchemaAndKeepsOrder() {
        FormField first = field(FormFieldType.TEXT.name(), "title", "标题");
        first.setRequired(true);
        FormField second = field(FormFieldType.SELECT.name(), "category", "类别");
        second.setOptions(List.of(option("A", "甲"), option("B", "乙")));
        FormField third = field(FormFieldType.NUMBER.name(), "amount", "金额");
        third.setPrecision(2);

        FormSchema schema = schema(first, second, third);
        FormSchemaValidator.validateAndNormalize(schema);

        assertEquals(List.of("title", "category", "amount"),
                schema.getFields().stream().map(FormField::getKey).toList());
    }
}
