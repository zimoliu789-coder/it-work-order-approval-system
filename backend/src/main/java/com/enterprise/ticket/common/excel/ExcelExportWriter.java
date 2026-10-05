package com.enterprise.ticket.common.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Excel 导出写出器（ 导入导出）
 *
 * <p>与 {@link ExcelImportSupport} 的分工：后者负责「把用户上传的文件安全地读进来」，
 * 本类负责「把系统数据安全地写出去」。两者共用 {@link ExcelStyles}，保证模板、失败明细、
 * 导出文件三者表头样式一致。
 *
 * <h2>为什么用 SXSSF 而不是 XSSF</h2>
 * <p> 要求「导出超过 10000 条时异步生成文件」—— 这类任务的行数量级可达十万。
 * {@code XSSFWorkbook} 会把<b>全部行对象常驻堆内存</b>，20 万行 × 十余列足以把 JVM 撑爆；
 * {@code SXSSFWorkbook} 是 POI 的流式实现：只保留最近 N 行在内存，更早的行写进临时文件，
 * 内存占用与行数基本无关。代价是需要 {@code dispose()} 清理临时文件（见 {@code finally}）。
 * 小数据量下两者的输出格式一致，故不做「小用 XSSF 大用 SXSSF」的分支 —— 少一条分支少一处风险。
 *
 * <h2>公式注入防护（写出的第一原则）</h2>
 * <p>用户可控的文本一律走 {@code setCellValue(String)} —— POI 只有 {@code setCellFormula}
 * 才会生成 {@code <f>} 节点，因此 {@code =HYPERLINK(...)}、{@code =cmd|...} 这类内容
 * 在 Excel 中只会按文本显示，不会被当作公式求值。本类的 {@code writeCell} 是唯一出口，
 * 修改时务必保持「String 只写字符串单元格」。
 *
 * <p><b>为什么数值列写真数字而不是字符串</b>：数值不可能构成公式（公式必须是字符串以
 * {@code =} 开头），写真实数值单元格既不降低安全性，又能让 Excel 正确排序 / 求和 /
 * 画图；若一律写成文本，用户打开报表会看到「数字左对齐 + 绿色小三角」的警告提示。
 */
public final class ExcelExportWriter {

    /** 未指定列宽时的默认宽度（1/256 个字符宽） */
    private static final int DEFAULT_COLUMN_WIDTH = 18;

    /** SXSSF 内存中保留的行数：越大内存占用越高、临时文件读写越少 */
    private static final int ROW_ACCESS_WINDOW = 500;

    /** Excel 单元格文本长度上限（32767），超出会被 POI 拒绝，故提前截断 */
    private static final int MAX_CELL_TEXT = 32000;

    private ExcelExportWriter() {
    }

    /**
     * 一个工作表的完整内容。
     *
     * @param name    工作表名
     * @param headers 表头（列顺序即数据列顺序）
     * @param widths  各列宽度（单位 1/256 字符宽），长度不足时按默认宽度补齐；可为 null
     * @param rows    数据行；每行元素按列顺序排列，元素类型决定写入方式（见 {@code writeCell}）
     */
    public record SheetSpec(String name, String[] headers, int[] widths, List<Object[]> rows) {
    }

    /**
     * 写出多工作表 xlsx。
     *
     * @throws BusinessException 写出失败（{@code EXPORT_SAVE_FAILED}）
     */
    public static byte[] writeWorkbook(List<SheetSpec> sheets) {
        if (sheets == null || sheets.isEmpty()) {
            throw new BusinessException(ErrorCode.EXPORT_SAVE_FAILED, "导出内容为空，已取消生成");
        }
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 关闭压缩，避免 POI 在写出时再缓存一份完整字节流（大文件下内存翻倍）
            workbook.setCompressTempFiles(false);
            CellStyle headerStyle = ExcelStyles.headerStyle(workbook);
            CellStyle bodyStyle = ExcelStyles.textStyle(workbook, false);
            for (SheetSpec spec : sheets) {
                writeSheet(workbook, spec, headerStyle, bodyStyle);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.EXPORT_SAVE_FAILED);
        } finally {
            // 必须 dispose：删除 SXSSF 在 java.io.tmpdir 下产生的临时分片文件
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
                // 关流失败不影响已生成的结果
            }
        }
    }

    private static void writeSheet(Workbook workbook, SheetSpec spec, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(safeSheetName(spec.name()));
        String[] headers = spec.headers() == null ? new String[0] : spec.headers();

        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(nullToEmpty(headers[i]));
            cell.setCellStyle(headerStyle);
        }
        for (int i = 0; i < headers.length; i++) {
            int width = spec.widths() != null && i < spec.widths().length ? spec.widths()[i] : DEFAULT_COLUMN_WIDTH;
            sheet.setColumnWidth(i, width * 256);
        }
        // 冻结首行：报表行数多时滚动仍能看到表头
        sheet.createFreezePane(0, 1);

        List<Object[]> rows = spec.rows() == null ? List.of() : spec.rows();
        int rowIndex = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = row.createCell(i);
                writeCell(cell, i < values.length ? values[i] : null);
                cell.setCellStyle(bodyStyle);
            }
        }
    }

    /**
     * 单元格写入的唯一出口（公式注入防护点）。
     *
     * <ul>
     *   <li>{@code null} → 空字符串（不留 {@code null} 单元格，避免下游读取时空指针）；</li>
     *   <li>{@link Number} → 数值单元格（安全，数值无法构成公式；且 Excel 可排序/求和）；</li>
     *   <li>{@link Boolean} → 布尔单元格；</li>
     *   <li>其它（含所有用户可控文本）→ <b>字符串单元格</b>，以 {@code =} 开头也不会被求值。</li>
     * </ul>
     */
    private static void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(truncate(value.toString()));
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CELL_TEXT ? text : text.substring(0, MAX_CELL_TEXT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 工作表名规整：去掉 Excel 禁止的字符（{@code \ / ? * [ ] :}），并限制 31 字符。
     *
     * <p>表名由后端常量提供，理论上不会命中；此处仍做一次，避免日后有人把用户输入
     * （例如部门名）直接当表名导致 POI 抛 {@code IllegalArgumentException}。
     */
    private static String safeSheetName(String name) {
        if (name == null || name.isBlank()) {
            return "Sheet1";
        }
        String cleaned = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() <= 31 ? cleaned : cleaned.substring(0, 31);
    }
}
