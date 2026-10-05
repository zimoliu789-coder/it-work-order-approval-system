package com.enterprise.ticket.common.excel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Excel 单元格样式工厂（导入模板  / 导出文件  共用）
 *
 * <p>抽出来的原因是<b>视觉一致性</b>：用户拿到的「导入模板」「导入失败明细」「导出文件」
 * 是同一套业务里的三种表格，表头样式若各写一份，迟早出现有的加粗有的不加粗、
 * 有的灰底有的白底。样式定义收敛到一处，改一处即全站一致。
 *
 * <p>只做「无状态样式创建」这一件事，不持有 Workbook，也不缓存 CellStyle ——
 * POI 的 {@code CellStyle} 归属于具体的 {@code Workbook} 实例，
 * 跨 workbook 复用会抛异常，因此每次由调用方传入 workbook 现场创建。
 */
public final class ExcelStyles {

    private ExcelStyles() {
    }

    /** 表头样式：加粗 + 灰底 + 居中 + 细边框 */
    public static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = textStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** 数据单元格样式：细边框；{@code wrapText=true} 时自动换行（长文本如失败原因用） */
    public static CellStyle textStyle(Workbook workbook, boolean wrapText) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setWrapText(wrapText);
        return style;
    }
}
