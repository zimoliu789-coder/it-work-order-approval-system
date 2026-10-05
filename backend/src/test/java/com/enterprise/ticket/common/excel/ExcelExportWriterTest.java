package com.enterprise.ticket.common.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 导出写出器单测（规范 §26.1）
 *
 * <p>本类承载的核心安全不变量是<b>公式注入防护</b>：用户可控文本（设备名、工单号、
 * 驳回原因…）会原样写进导出文件，若被当作公式求值，打开文件即可能触发
 * {@code =HYPERLINK(...)} / {@code =cmd|...} 这类攻击。写出的唯一出口
 * {@code writeCell} 对所有非数值/布尔值一律写字符串单元格。
 *
 * <p>测试通过「生成 → 用 XSSF 读回 → 断言单元格类型」来固化这一行为：
 * 断言的是<b>单元格类型</b>而不只是文本内容 —— 即便内容对得上，
 * 若被写成 {@code FORMULA} 类型同样危险，只有类型是 {@code STRING} 才算安全。
 */
class ExcelExportWriterTest {

    private Workbook readBack(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("公式注入防护：以 = 开头的用户文本写成字符串单元格，绝不成为公式")
    void formulaInjection_isWrittenAsString() throws Exception {
        String malicious = "=HYPERLINK(\"http://evil.example\",\"点我\")";
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("设备台账", new String[]{"设备名称", "资产编号"}, new int[]{20, 20},
                        List.<Object[]>of(new Object[]{malicious, "IT-001"}))));

        try (Workbook wb = readBack(bytes)) {
            Sheet sheet = wb.getSheetAt(0);
            Cell cell = sheet.getRow(1).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType(),
                    "以 = 开头的文本必须是 STRING 单元格，否则打开文件会被 Excel 求值");
            assertEquals(malicious, cell.getStringCellValue());
        }
    }

    @Test
    @DisplayName("数值与布尔写成原生类型（便于排序/求和），而非文本")
    void numericAndBoolean_areNativeTypes() throws Exception {
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("统计", new String[]{"次数", "是否报废"}, null,
                        List.<Object[]>of(new Object[]{42, Boolean.TRUE}))));

        try (Workbook wb = readBack(bytes)) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertEquals(CellType.NUMERIC, row.getCell(0).getCellType());
            assertEquals(42d, row.getCell(0).getNumericCellValue());
            assertEquals(CellType.BOOLEAN, row.getCell(1).getCellType());
            assertTrue(row.getCell(1).getBooleanCellValue());
        }
    }

    @Test
    @DisplayName("表头写入第一行，null 值写成空串（不留 null 单元格，避免下游空指针）")
    void headerAndNullHandling() throws Exception {
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("工单记录", new String[]{"工单号", "驳回原因"}, null,
                        List.<Object[]>of(new Object[]{"BO-1", null}))));

        try (Workbook wb = readBack(bytes)) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("工单号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("驳回原因", sheet.getRow(0).getCell(1).getStringCellValue());
            Cell nullCell = sheet.getRow(1).getCell(1);
            assertEquals(CellType.STRING, nullCell.getCellType());
            assertEquals("", nullCell.getStringCellValue());
        }
    }

    @Test
    @DisplayName("多工作表：工单导出产出「工单记录 + 审批记录」两张表且顺序稳定")
    void multipleSheets() throws Exception {
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("工单记录", new String[]{"工单号"}, null,
                        List.<Object[]>of(new Object[]{"BO-1"})),
                new SheetSpec("审批记录", new String[]{"工单号", "审批人"}, null,
                        List.<Object[]>of(new Object[]{"BO-1", "张三"}))));

        try (Workbook wb = readBack(bytes)) {
            assertEquals(2, wb.getNumberOfSheets());
            assertEquals("工单记录", wb.getSheetAt(0).getSheetName());
            assertEquals("审批记录", wb.getSheetAt(1).getSheetName());
        }
    }

    @Test
    @DisplayName("工作表名含 Excel 非法字符时被规整（否则 POI 直接抛异常导致导出失败）")
    void illegalSheetName_isSanitized() throws Exception {
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("A/B:C*?", new String[]{"列"}, null,
                        List.<Object[]>of(new Object[]{"x"}))));

        try (Workbook wb = readBack(bytes)) {
            String name = wb.getSheetAt(0).getSheetName();
            assertTrue(name.indexOf('/') < 0 && name.indexOf(':') < 0 && name.indexOf('*') < 0,
                    "非法字符必须被替换，实际：" + name);
        }
    }

    @Test
    @DisplayName("空内容（无工作表）→ EXPORT_SAVE_FAILED，而不是生成一个空文件")
    void emptySheets_throws() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> ExcelExportWriter.writeWorkbook(List.of()));
        assertEquals(ErrorCode.EXPORT_SAVE_FAILED, e.getErrorCode());
    }

    @Test
    @DisplayName("超长单元格文本被截断到 32767 以内（超出会被 POI 拒绝）")
    void overlongCell_isTruncated() throws Exception {
        String longText = "x".repeat(40000);
        byte[] bytes = ExcelExportWriter.writeWorkbook(List.of(
                new SheetSpec("S", new String[]{"备注"}, null,
                        List.<Object[]>of(new Object[]{longText}))));

        try (Workbook wb = readBack(bytes)) {
            String value = wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue();
            assertTrue(value.length() <= 32767, "单元格文本必须被截断，实际长度：" + value.length());
        }
    }
}
