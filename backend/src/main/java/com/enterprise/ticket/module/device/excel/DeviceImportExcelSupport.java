package com.enterprise.ticket.module.device.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.excel.ExcelImportSupport;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceImportRow;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportRowVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 设备批量导入的 Excel 读写支持（，Apache POI）
 *
 * <p>Excel 侧的通用能力（解析安全三道防线、单元格取值、模板与明细样式、
 * 公式注入防护）全部继承自 {@link ExcelImportSupport}；本类只声明<b>设备列定义</b>
 * 与「设备行 ↔ 单元格」的映射，业务校验仍在 {@code DeviceImportServiceImpl} —
 * 与「单条新增设备」复用同一套规则，不在此处另立一份。
 *
 * <h2>「设备金额」为什么放在最后一列</h2>
 * <p>基类明确采用<b>按位置解析</b>（见 {@link ExcelImportSupport} 类注释：用户常改表头文案，
 * 按名匹配会让整列静默丢失）。因此插入一列会让「用旧模板填好的文件」把备注写进金额列、
 * 金额写进不存在的列 —— 不报错，只是数据错位。追加到末尾则旧文件仍然逐列正确，
 * 金额读不到就是 NULL（= 未录入），正是期望的降级行为。
 */
@Component
public class DeviceImportExcelSupport extends ExcelImportSupport {

    /** 模板/明细列顺序（解析与生成共用同一份定义）；新增列只能追加在末尾 */
    public static final String[] HEADERS = {
            "设备名称", "资产编号", "一级分类", "二级分类", "品牌",
            "型号", "序列号", "存放位置", "购置日期", "备注", "设备金额"
    };

    /** 各列建议宽度（单位：1/256 个字符宽） */
    private static final int[] COLUMN_WIDTHS = {
            22, 18, 14, 14, 12, 16, 20, 20, 16, 24, 14
    };

    /** 失败明细在模板列之后追加的列 */
    private static final String FAILURE_REASON_HEADER = "失败原因";

    private static final int COL_DEVICE_NAME = 0;
    private static final int COL_ASSET_NO = 1;
    private static final int COL_PRIMARY_CATEGORY = 2;
    private static final int COL_SECONDARY_CATEGORY = 3;
    private static final int COL_BRAND = 4;
    private static final int COL_MODEL = 5;
    private static final int COL_SERIAL_NO = 6;
    private static final int COL_STORAGE_LOCATION = 7;
    private static final int COL_PURCHASE_DATE = 8;
    private static final int COL_REMARK = 9;
    private static final int COL_AMOUNT = 10;

    @Override
    protected String[] headers() {
        return HEADERS;
    }

    @Override
    protected int[] columnWidths() {
        return COLUMN_WIDTHS;
    }

    // ------------------------------------------------------------------
    // 模板生成
    // ------------------------------------------------------------------

    /**
     * 生成导入模板：第 1 行表头，第 2 行示例数据（标注「导入前删除」，解析时自动跳过）
     */
    public byte[] buildTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("设备导入模板");
            writeHeaderRow(sheet, workbook, headerStyle(workbook));

            Row example = sheet.createRow(1);
            String[] sample = {
                    "ThinkPad X1 Carbon", "IT-2026-9999", "电脑", "笔记本", "联想",
                    "21CB0000CD", "PF3EXAMPLE", "A座3F研发区", "2026-01-15",
                    "示例数据，导入前请删除本行（" + EXAMPLE_MARKER + "）",
                    "6999"
            };
            for (int i = 0; i < sample.length; i++) {
                example.createCell(i).setCellValue(sample[i]);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成导入模板失败，请稍后重试");
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析上传的 .xlsx 为行数据（不做业务校验）
     *
     * <p>跳过第 1 行（表头）、完全空白行，以及含 {@link #EXAMPLE_MARKER} 的示例行。
     * 调用方应先用 {@link #assertExpandedSize(InputStream)} 做体积预检
     * （需要两个独立的输入流：本方法会消费完整个流）。
     *
     * @param maxRows 允许的最大数据行数，超出即抛 {@code DEVICE_IMPORT_ROW_LIMIT_EXCEEDED}
     */
    public List<DeviceImportRow> parse(InputStream inputStream, int maxRows) {
        return parseSheet(inputStream, maxRows, this::readRow,
                ErrorCode.DEVICE_IMPORT_ROW_LIMIT_EXCEEDED, ErrorCode.DEVICE_IMPORT_EMPTY);
    }

    /** 读取一行；整行为空（无任何非空单元格）时返回 null */
    private DeviceImportRow readRow(Row row, int rowNo, DataFormatter formatter) {
        String[] values = new String[HEADERS.length];
        boolean blank = true;
        for (int i = 0; i < HEADERS.length; i++) {
            values[i] = cellText(row.getCell(i), formatter);
            if (values[i] != null && !values[i].isEmpty()) {
                blank = false;
            }
        }
        if (blank) {
            return null;
        }
        DeviceImportRow result = new DeviceImportRow();
        result.setRowNo(rowNo);
        result.setDeviceName(values[COL_DEVICE_NAME]);
        result.setAssetNo(values[COL_ASSET_NO]);
        result.setPrimaryCategoryName(values[COL_PRIMARY_CATEGORY]);
        result.setSecondaryCategoryName(values[COL_SECONDARY_CATEGORY]);
        result.setBrand(values[COL_BRAND]);
        result.setModel(values[COL_MODEL]);
        result.setSerialNo(values[COL_SERIAL_NO]);
        result.setStorageLocation(values[COL_STORAGE_LOCATION]);
        result.setPurchaseDate(values[COL_PURCHASE_DATE]);
        result.setRemark(values[COL_REMARK]);
        // 旧模板（无此列）读不到值即为 null —— 与"未录入金额"同义，是可接受的降级
        result.setAmount(values[COL_AMOUNT]);
        return result;
    }

    // ------------------------------------------------------------------
    // 失败明细导出
    // ------------------------------------------------------------------

    /**
     * 生成失败明细 Excel：与模板同列（保证修好后可直接重新导入），末尾追加「失败原因」列
     *
     * <p>所有取值都用 {@code setCellValue(String)} 写入，得到的是<b>字符串单元格</b>而非公式
     * 单元格（POI 只有 {@code setCellFormula} 才会生成 {@code <f>} 节点）。因此用户数据里以
     * {@code = + - @} 开头的内容（例如 {@code =HYPERLINK(...)}、{@code =cmd|...}）在 Excel 中
     * 只会按文本显示，不会被当作公式求值 —— 这是防「公式注入（CSV/Excel injection）」的关键，
     * 修改本方法时务必保持用 setCellValue 写字符串。
     */
    public byte[] buildFailureReport(List<DeviceImportRowVO> failures) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("导入失败明细");
            CellStyle headerStyle = headerStyle(workbook);
            writeHeaderRow(sheet, workbook, headerStyle);
            CellStyle reasonStyle = textStyle(workbook, true);

            // 末列追加「失败原因」（writeHeaderRow 只写模板列，此处补一列）
            Cell reasonHeader = sheet.getRow(0).createCell(HEADERS.length);
            reasonHeader.setCellValue(FAILURE_REASON_HEADER);
            reasonHeader.setCellStyle(headerStyle);
            sheet.setColumnWidth(HEADERS.length, 44 * 256);

            int rowIndex = 1;
            for (DeviceImportRowVO failure : failures) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(COL_DEVICE_NAME).setCellValue(nullToEmpty(failure.getDeviceName()));
                row.createCell(COL_ASSET_NO).setCellValue(nullToEmpty(failure.getAssetNo()));
                row.createCell(COL_PRIMARY_CATEGORY).setCellValue(nullToEmpty(failure.getPrimaryCategoryName()));
                row.createCell(COL_SECONDARY_CATEGORY).setCellValue(nullToEmpty(failure.getSecondaryCategoryName()));
                row.createCell(COL_BRAND).setCellValue(nullToEmpty(failure.getBrand()));
                row.createCell(COL_MODEL).setCellValue(nullToEmpty(failure.getModel()));
                row.createCell(COL_SERIAL_NO).setCellValue(nullToEmpty(failure.getSerialNo()));
                row.createCell(COL_STORAGE_LOCATION).setCellValue(nullToEmpty(failure.getStorageLocation()));
                row.createCell(COL_PURCHASE_DATE).setCellValue(nullToEmpty(failure.getPurchaseDate()));
                row.createCell(COL_REMARK).setCellValue(nullToEmpty(failure.getRemark()));
                row.createCell(COL_AMOUNT).setCellValue(nullToEmpty(failure.getAmount()));
                // 失败原因由后端生成（非用户输入），同样以字符串写入
                Cell reasonCell = row.createCell(HEADERS.length);
                reasonCell.setCellValue(nullToEmpty(failure.getReason()));
                reasonCell.setCellStyle(reasonStyle);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成失败明细失败，请稍后重试");
        }
    }
}
