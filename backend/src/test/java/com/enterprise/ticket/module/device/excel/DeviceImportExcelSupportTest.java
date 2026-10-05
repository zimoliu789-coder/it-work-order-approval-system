package com.enterprise.ticket.module.device.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceImportRow;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportRowVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 设备批量导入 Excel 读写支持单元测试（规范 §8）
 *
 * <p>这里用<b>真实 POI</b>跑真实的 .xlsx 字节流，覆盖的都是「靠读代码看不出来、只有真解析一次
 * 才知道」的行为：
 * <ul>
 *   <li>公式单元格到底读出了什么（POI 的 DataFormatter 在无 FormulaEvaluator 时返回公式文本）；</li>
 *   <li>内嵌 DOCTYPE / 外部实体的 xlsx 是否被拒（XXE 防护）；</li>
 *   <li>解压体积预检与行数护栏；</li>
 *   <li>失败明细导出是否只写字符串单元格（防公式注入）。</li>
 * </ul>
 */
class DeviceImportExcelSupportTest {

    private static final int MAX_ROWS = 500;

    private final DeviceImportExcelSupport support = new DeviceImportExcelSupport();

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private List<DeviceImportRow> parse(byte[] xlsx) {
        return support.parse(new ByteArrayInputStream(xlsx), MAX_ROWS);
    }

    /** 用 POI 造一个「表头 + 若干数据行」的 xlsx；每行是一个单元格值数组 */
    private byte[] xlsxWithRows(Object[]... dataRows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("设备导入模板");
            Row header = sheet.createRow(0);
            for (int i = 0; i < DeviceImportExcelSupport.HEADERS.length; i++) {
                header.createCell(i).setCellValue(DeviceImportExcelSupport.HEADERS[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                Object[] values = dataRows[r];
                for (int c = 0; c < values.length; c++) {
                    setValue(row.createCell(c), values[c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void setValue(Cell cell, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof LocalDate date) {
            // 必须显式套日期格式：用户在 Excel 里输入的日期，存盘时就是「带日期格式的数字单元格」。
            // 不设格式的话 POI 只会写成一个裸数字（46037.0），读出来自然也不是日期。
            Workbook workbook = cell.getSheet().getWorkbook();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(dateStyle);
            cell.setCellValue(date);
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /** 直接拼一个 zip（xlsx 容器），用于构造 POI 自己写不出来的畸形/恶意文件 */
    private byte[] craftedXlsx(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * STORED 方式打包：把解压体积写进<b>本地头</b>，便于验证体积预检。
     * （DEFLATED 条目走数据描述符，本地头里读不到体积，预检会跳过 —— 那种情况由 POI 的
     * {@code ZipSecureFile} 上限在实际解压时兜住。）
     */
    private byte[] zipWithDeclaredSizes(Map<String, byte[]> payloads) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> payload : payloads.entrySet()) {
                CRC32 crc = new CRC32();
                crc.update(payload.getValue());
                ZipEntry entry = new ZipEntry(payload.getKey());
                entry.setSize(payload.getValue().length);
                entry.setCompressedSize(payload.getValue().length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(payload.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 基本解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("解析：跳过表头、空白行与示例行，只返回真实数据行")
    void parse_skipsHeaderBlankAndExampleRows() throws IOException {
        byte[] xlsx = xlsxWithRows(
                new Object[]{"设备A", "IMP-A", "电脑", "笔记本"},
                new Object[]{null, null, null, null},
                new Object[]{"示例", "IT-2026-9999", "电脑", "笔记本", null, null, null, null, null,
                        DeviceImportExcelSupport.EXAMPLE_MARKER},
                new Object[]{"设备B", "IMP-B", "电脑", null});

        List<DeviceImportRow> rows = parse(xlsx);

        assertEquals(2, rows.size());
        assertEquals("IMP-A", rows.get(0).getAssetNo());
        // 行号按「含表头从 1 开始」编号，便于用户回原文件定位
        assertEquals(2, rows.get(0).getRowNo());
        assertEquals("IMP-B", rows.get(1).getAssetNo());
        assertEquals(5, rows.get(1).getRowNo());
    }

    @Test
    @DisplayName("解析：整数型资产编号不出现科学计数法")
    void parse_keepsIntegerAssetNoOutOfScientificNotation() throws IOException {
        byte[] xlsx = xlsxWithRows(new Object[]{"设备A", 20260001, "电脑", null});

        assertEquals("20260001", parse(xlsx).get(0).getAssetNo());
    }

    @Test
    @DisplayName("解析：日期单元格按 yyyy-MM-dd 输出")
    void parse_readsDateCellAsIsoDate() throws IOException {
        byte[] xlsx = xlsxWithRows(
                new Object[]{"设备A", "IMP-A", "电脑", null, null, null, null, null, LocalDate.of(2026, 1, 15)});

        assertEquals("2026-01-15", parse(xlsx).get(0).getPurchaseDate());
    }

    @Test
    @DisplayName("模板本身：只剩表头与示例行，解析时应报「没有可导入的数据行」")
    void template_containsOnlyExampleRow() {
        assertEquals(ErrorCode.DEVICE_IMPORT_EMPTY,
                assertThrows(BusinessException.class, () -> parse(support.buildTemplate())).getErrorCode());
    }

    // ------------------------------------------------------------------
    // 公式单元格
    // ------------------------------------------------------------------

    @Test
    @DisplayName("公式单元格：读取缓存结果（用户在 Excel 里看到的值）")
    void parse_readsCachedFormulaResult() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("s");
            Row header = sheet.createRow(0);
            for (int i = 0; i < DeviceImportExcelSupport.HEADERS.length; i++) {
                header.createCell(i).setCellValue(DeviceImportExcelSupport.HEADERS[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellFormula("CONCATENATE(\"资产\",\"X\")");
            row.createCell(1).setCellValue("FMLA-1");
            row.createCell(2).setCellValue("电脑");
            // 等价于「用户在 Excel 中保存过」：Excel 会把计算结果一并写进文件
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        assertEquals("资产X", parse(xlsx).get(0).getDeviceName());
    }

    @Test
    @DisplayName("公式单元格：任何情况下都不得把公式本体当作业务数据导入")
    void parse_neverImportsFormulaText() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("s");
            Row header = sheet.createRow(0);
            for (int i = 0; i < DeviceImportExcelSupport.HEADERS.length; i++) {
                header.createCell(i).setCellValue(DeviceImportExcelSupport.HEADERS[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellFormula("CONCATENATE(\"无缓存\",\"Y\")");
            row.createCell(1).setCellValue("FMLA-2");
            row.createCell(2).setCellValue("电脑");
            // 刻意不 evaluate：模拟「公式没有缓存结果」的文件
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        String imported;
        try {
            imported = parse(xlsx).get(0).getDeviceName();
        } catch (BusinessException e) {
            // 拿不到缓存结果时直接拒绝文件 —— 比导入一行错误数据更安全
            assertEquals(ErrorCode.DEVICE_IMPORT_PARSE_FAILED, e.getErrorCode());
            return;
        }
        assertNotEquals("CONCATENATE(\"无缓存\",\"Y\")", imported, "不得把公式本体当业务数据");
    }

    // ------------------------------------------------------------------
    // XXE 防护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("XXE：sharedStrings 内嵌 DOCTYPE + 外部实体 → 解析失败，不泄漏本地文件")
    void parse_rejectsDoctypeWithExternalEntity() throws IOException {
        String sharedStrings = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE sst [ <!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\"> ]>\n"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"1\" uniqueCount=\"1\">"
                + "<si><t>&xxe;</t></si></sst>";
        byte[] xlsx = craftedXlsx(xlsxParts(sharedStrings));

        BusinessException e = assertThrows(BusinessException.class, () -> parse(xlsx));
        assertEquals(ErrorCode.DEVICE_IMPORT_PARSE_FAILED, e.getErrorCode());
    }

    @Test
    @DisplayName("XXE：引用外部 DTD → 解析失败（不发起外网请求）")
    void parse_rejectsExternalDtd() throws IOException {
        String sharedStrings = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE sst SYSTEM \"http://127.0.0.1:1/evil.dtd\">\n"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"1\" uniqueCount=\"1\">"
                + "<si><t>x</t></si></sst>";
        byte[] xlsx = craftedXlsx(xlsxParts(sharedStrings));

        assertEquals(ErrorCode.DEVICE_IMPORT_PARSE_FAILED,
                assertThrows(BusinessException.class, () -> parse(xlsx)).getErrorCode());
    }

    /** 组装一个最小可用的 xlsx 容器（sharedStrings 可替换为带 DOCTYPE 的版本） */
    private Map<String, String> xlsxParts(String sharedStrings) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                        + "</Types>");
        parts.put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "</Relationships>");
        parts.put("xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                        + "</Relationships>");
        parts.put("xl/sharedStrings.xml", sharedStrings);
        parts.put("xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>设备名称</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B2\" t=\"inlineStr\"><is><t>XXE-1</t></is></c>"
                        + "<c r=\"C2\" t=\"inlineStr\"><is><t>电脑</t></is></c></row>"
                        + "</sheetData></worksheet>");
        return parts;
    }

    // ------------------------------------------------------------------
    // 资源防护：解压体积 / 行数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("体积预检：单条目解压体积超限 → FILE_TOO_LARGE")
    void assertExpandedSize_rejectsHugeEntry() throws IOException {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put("xl/worksheets/sheet1.xml", new byte[2 * 1024 * 1024]);
        byte[] zip = zipWithDeclaredSizes(payloads);

        BusinessException e = assertThrows(BusinessException.class,
                () -> support.assertExpandedSize(new ByteArrayInputStream(zip), 1024 * 1024, 8 * 1024 * 1024));
        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE, e.getErrorCode());
    }

    @Test
    @DisplayName("体积预检：逐个条目都不超限、但总体积超限 → FILE_TOO_LARGE")
    void assertExpandedSize_rejectsOversizeTotal() throws IOException {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put("part1.bin", new byte[700 * 1024]);
        payloads.put("part2.bin", new byte[700 * 1024]);
        byte[] zip = zipWithDeclaredSizes(payloads);

        // 单条目上限 1MB（700KB 未超），总体上限 1MB（实际 1.4MB 已超）
        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE,
                assertThrows(BusinessException.class,
                        () -> support.assertExpandedSize(new ByteArrayInputStream(zip), 1024 * 1024, 1024 * 1024))
                        .getErrorCode());
    }

    @Test
    @DisplayName("体积预检：合法 xlsx 通过预检（不误伤正常文件）")
    void assertExpandedSize_acceptsNormalWorkbook() throws IOException {
        support.assertExpandedSize(new ByteArrayInputStream(xlsxWithRows(new Object[]{"设备A", "OK-1", "电脑"})));
    }

    @Test
    @DisplayName("伪装文件：非 zip 内容在预检阶段取不到条目，仍由解析阶段报「解析失败」")
    void nonZipFile_isRejectedByParse() {
        byte[] notZip = "this is not an excel file".getBytes(StandardCharsets.UTF_8);

        // 预检只负责体积，不该对非 zip 报错（ZipInputStream 对非 zip 只是取不到条目）
        support.assertExpandedSize(new ByteArrayInputStream(notZip));
        // 真正的拒绝发生在解析阶段，且必须是可读的规范错误码而不是 500
        assertEquals(ErrorCode.DEVICE_IMPORT_PARSE_FAILED,
                assertThrows(BusinessException.class, () -> parse(notZip)).getErrorCode());
    }

    @Test
    @DisplayName("解析：数据行超过 500 → ROW_LIMIT_EXCEEDED")
    void parse_rejectsTooManyDataRows() throws IOException {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < MAX_ROWS + 1; i++) {
            rows.add(new Object[]{"设备" + i, "OVER-" + i, "电脑", null});
        }
        byte[] xlsx = xlsxWithRows(rows.toArray(new Object[0][]));

        assertEquals(ErrorCode.DEVICE_IMPORT_ROW_LIMIT_EXCEEDED,
                assertThrows(BusinessException.class, () -> parse(xlsx)).getErrorCode());
    }

    @Test
    @DisplayName("解析：整个文件没有数据行 → DEVICE_IMPORT_EMPTY")
    void parse_rejectsFileWithoutDataRows() throws IOException {
        assertEquals(ErrorCode.DEVICE_IMPORT_EMPTY,
                assertThrows(BusinessException.class, () -> parse(xlsxWithRows())).getErrorCode());
    }

    // ------------------------------------------------------------------
    // 失败明细导出（公式注入）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("失败明细导出：以 = + - @ 开头的内容写成字符串单元格，绝不生成公式节点")
    void buildFailureReport_writesStringsNotFormulas() throws IOException {
        DeviceImportRowVO failure = new DeviceImportRowVO();
        failure.setRowNo(2);
        failure.setDeviceName("=1+1");
        failure.setAssetNo("=SUM(1+1)*cmd|calc");
        failure.setRemark("=HYPERLINK(\"http://evil.example\",\"click\")");
        failure.setReason("=恶意原因");

        byte[] xlsx = support.buildFailureReport(List.of(failure));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            for (int i = 0; i <= DeviceImportExcelSupport.HEADERS.length; i++) {
                Cell cell = row.getCell(i);
                if (cell == null) {
                    continue;
                }
                assertEquals(CellType.STRING, cell.getCellType(),
                        "第 " + (i + 1) + " 列必须是字符串单元格，不得是公式");
            }
            assertEquals("=1+1", row.getCell(0).getStringCellValue());
            assertEquals("=恶意原因", row.getCell(DeviceImportExcelSupport.HEADERS.length).getStringCellValue());
        }
    }

    @Test
    @DisplayName("失败明细导出：与模板列一致（可改完直接重导）")
    void buildFailureReport_keepsTemplateHeader() throws IOException {
        DeviceImportRowVO failure = new DeviceImportRowVO();
        failure.setRowNo(2);
        failure.setReason("资产编号已存在");

        byte[] xlsx = support.buildFailureReport(List.of(failure));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < DeviceImportExcelSupport.HEADERS.length; i++) {
                assertEquals(DeviceImportExcelSupport.HEADERS[i], header.getCell(i).getStringCellValue());
            }
            // 末列为后端追加的「失败原因」，用户据此逐行修正
            assertEquals("失败原因", header.getCell(DeviceImportExcelSupport.HEADERS.length).getStringCellValue());
            assertNotNull(workbook.getSheetAt(0).getRow(1).getCell(DeviceImportExcelSupport.HEADERS.length));
        }
    }
}
