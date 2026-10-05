package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.export.excel.CustomFormExportExcel.AttachmentRow;
import com.enterprise.ticket.module.export.excel.CustomFormExportExcel.FormOrderRow;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.Column;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义表单数据导出单测（Phase 16 Wave 3 · M6）
 *
 * <p>覆盖三处「导出格式错了但不会报错」的规则：
 * <ol>
 *   <li><b>上下文列在前、动态列在后</b>——导出必须能回答「这是谁的哪一单」，否则一堆字段值无法归属；</li>
 *   <li><b>动态列宽有边界</b>——超长 label 不能撑出一屏放不下的列，过短 label 也不能窄到看不清；</li>
 *   <li><b>附件清单</b>——文件大小输出字节数（可排序 / 求和），时间格式统一，
 *       没有附件时仍产出表头（表头即「这里本该有东西」的说明）。</li>
 * </ol>
 */
class CustomFormExportExcelTest {

    private static FormField field(String key, String label, FormFieldType type) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type.name());
        return field;
    }

    private static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    private static FormOrderRow orderRow(FormSchema schema, Map<String, Object> data) {
        return new FormOrderRow("P16C20260927001", "采购申请", "张三", "审批中",
                LocalDateTime.of(2026, 9, 27, 10, 30, 0), schema, data);
    }

    @Test
    @DisplayName("表头 = 5 个工单上下文列 + 动态字段列（顺序与列定义一致）")
    void headers_contextThenDynamic() {
        List<Column> columns = List.of(new Column("title", "标题"), new Column("amount", "金额"));
        FormSchema schema = schema(field("title", "标题", FormFieldType.TEXT), field("amount", "金额", FormFieldType.NUMBER));

        SheetSpec spec = CustomFormExportExcel.buildFormData(columns,
                List.of(orderRow(schema, Map.of("title", "采购", "amount", 8000))), References.empty());

        assertEquals("表单数据", spec.name());
        assertEquals(List.of("工单编号", "申请类型", "申请人", "工单状态", "提交时间", "标题", "金额"),
                List.of(spec.headers()));
        assertEquals(spec.headers().length, spec.widths().length);
    }

    @Test
    @DisplayName("一行按「上下文 + 动态列」顺序映射，时间格式化为 yyyy-MM-dd HH:mm:ss")
    void row_mapsContextThenDynamic() {
        List<Column> columns = List.of(new Column("title", "标题"), new Column("amount", "金额"));
        FormSchema schema = schema(field("title", "标题", FormFieldType.TEXT), field("amount", "金额", FormFieldType.NUMBER));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "采购申请");
        data.put("amount", 8000);

        SheetSpec spec = CustomFormExportExcel.buildFormData(columns, List.of(orderRow(schema, data)),
                References.empty());
        Object[] cells = spec.rows().get(0);

        assertEquals("P16C20260927001", cells[0]);
        assertEquals("采购申请", cells[1]);
        assertEquals("张三", cells[2]);
        assertEquals("审批中", cells[3]);
        assertEquals("2026-09-27 10:30:00", cells[4]);
        assertEquals("采购申请", cells[5]);
        assertEquals("8000", cells[6]);
    }

    @Test
    @DisplayName("动态列宽按表头长度估算，并夹在 [10, 40] 之间")
    void widths_areClamped() {
        List<Column> columns = List.of(
                new Column("a", "A"),
                new Column("b", "附件（报价单 / 清单 / 合同扫描件）"));
        FormSchema schema = schema(field("a", "A", FormFieldType.TEXT), field("b", "b", FormFieldType.FILE));

        SheetSpec spec = CustomFormExportExcel.buildFormData(columns, List.of(orderRow(schema, Map.of())),
                References.empty());

        // 固定列宽 5 个 + 动态列宽 2 个
        assertEquals(7, spec.widths().length);
        assertTrue(spec.widths()[5] >= 10, "过短 label 的列也要保证最小可读宽度");
        assertTrue(spec.widths()[6] <= 40, "超长 label 不能让列宽无限膨胀");
    }

    @Test
    @DisplayName("没有动态列时只有上下文 5 列（不产出空表头的多余列）")
    void noColumns_onlyContext() {
        SheetSpec spec = CustomFormExportExcel.buildFormData(List.of(), List.of(), References.empty());

        assertEquals(5, spec.headers().length);
        assertTrue(spec.rows().isEmpty());
    }

    @Test
    @DisplayName("null 输入安全：columns / rows 为 null 时不抛错")
    void nullInput_isSafe() {
        assertNotNull(CustomFormExportExcel.buildFormData(null, null, null));
        assertNotNull(CustomFormExportExcel.buildAttachments(null));
    }

    @Test
    @DisplayName("附件清单：表头固定六列，大小输出字节数值、时间统一格式")
    void attachments_headersAndRow() {
        SheetSpec spec = CustomFormExportExcel.buildAttachments(List.of(new AttachmentRow(
                "P16C20260927001", "采购申请", "张三", "报价单.pdf", 204800L,
                LocalDateTime.of(2026, 9, 27, 11, 0, 0))));

        assertEquals("附件清单", spec.name());
        assertEquals(List.of("工单编号", "申请类型", "申请人", "文件名", "文件大小（字节）", "上传时间"),
                List.of(spec.headers()));
        Object[] cells = spec.rows().get(0);
        assertEquals("报价单.pdf", cells[3]);
        assertEquals(204800L, cells[4], "字节数保持数值：文本化的 1.2 MB 无法在 Excel 里排序求和");
        assertEquals("2026-09-27 11:00:00", cells[5]);
    }

    @Test
    @DisplayName("无附件时仍产出带表头的空表（表头即「这里本该有东西」的说明）")
    void attachments_emptyKeepsHeaders() {
        SheetSpec spec = CustomFormExportExcel.buildAttachments(List.of());

        assertTrue(spec.rows().isEmpty());
        assertEquals(6, spec.headers().length);
        assertEquals(6, spec.widths().length);
    }
}
