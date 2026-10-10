package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.Column;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义表单数据导出（ · M6）
 *
 * <p>输出两个工作表（与既有「工单记录 = 工单 + 审批记录」同构）：
 * <ol>
 *   <li><b>「表单数据」</b>——一行一单：<b>工单上下文</b>（编号 / 申请类型 / 申请人 / 状态 / 提交时间）
 *       后接<b>动态字段列</b>（列 = 本批工单引用过的表单版本字段并集，由
 *       {@link FormDataFlattenSupport#resolveColumns} 决定）；</li>
 *   <li><b>「附件清单」</b>——一行一个附件：编号 / 申请类型 / 申请人 / 文件名 / 大小 / 上传时间。
 *       存在的理由见下。</li>
 * </ol>
 *
 * <h2>为什么附件要单独一张表，而不是塞进某个 FILE 列</h2>
 * <p>附件<b>不在 {@code form_data_json} 里</b>（上传需要工单 id，提交成功后才存在），
 * 而是按 {@code (biz_type='CUSTOM_ORDER', biz_id=orderId)} 独立关联到工单；
 * {@code attachment} 表也<b>没有字段级归属列</b>。也就是说「某个 FILE 字段对应哪几个文件」
 * 在数据模型上根本不存在 —— 硬填会让一个有 2 个附件字段的工单在<b>两个列里显示同一批文件名</b>，
 * 那是编造。因此 FILE 列输出 {@code -}（表示「结构上无值」，而非「漏导」），
 * 附件明细由本表承接：既一个不丢，又能追溯到具体文件。
 *
 * <h2>文件大小列输出字节数而不是「1.2 MB」</h2>
 * <p>文本化的单位会让 Excel 无法排序 / 求和 / 透视（只能按字符串比较，"9 KB" 会大于 "10 MB"）。
 * 单位写在表头里，单元格保持纯数值 —— 与既有报表导出的取舍一致。
 *
 * <p>纯函数式构建，不依赖 Spring / 数据库，可单测列顺序、动态列宽度估算与空值处理。
 */
public final class CustomFormExportExcel {

    /** 主表工作表名（也是 {@code ExportType.CUSTOM_FORM} 的表名） */
    public static final String SHEET_FORM_DATA = "表单数据";

    /** 附件清单工作表名 */
    public static final String SHEET_ATTACHMENT = "附件清单";

    /** 工单上下文的固定前缀列（导出必须能回答「这是谁的哪一单」） */
    private static final String[] CONTEXT_HEADERS = {
            "工单编号", "申请类型", "申请人", "工单状态", "提交时间"
    };

    private static final int[] CONTEXT_WIDTHS = {24, 16, 12, 14, 20};

    private static final String[] ATTACHMENT_HEADERS = {
            "工单编号", "申请类型", "申请人", "文件名", "文件大小（字节）", "上传时间"
    };

    private static final int[] ATTACHMENT_WIDTHS = {24, 16, 12, 40, 16, 20};

    /** 动态列宽的三条边界：太窄看不清、太宽一屏放不下两列 */
    private static final int DYNAMIC_WIDTH_MIN = 10;
    private static final int DYNAMIC_WIDTH_MAX = 40;
    private static final int DYNAMIC_WIDTH_EXTRA = 6;

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CustomFormExportExcel() {
    }

    /**
     * 一行「表单数据」：工单上下文 + 该单的表单数据
     *
     * <p>刻意把 {@code schema} 与 {@code data} 一起带进 record：格式化必须用
     * <b>该工单自己那一版</b>的字段定义（同一个 key 在不同版本里可能是不同类型），
     * 若把 schema 提到外面共用一份，跨版本工单就会按错的类型格式化。
     */
    public record FormOrderRow(String orderNo,
                               String applyTypeName,
                               String applicantName,
                               String statusLabel,
                               LocalDateTime submittedAt,
                               FormSchema schema,
                               Map<String, Object> data) {
    }

    /** 一行附件清单 */
    public record AttachmentRow(String orderNo,
                                String applyTypeName,
                                String applicantName,
                                String fileName,
                                Long fileSize,
                                LocalDateTime uploadedAt) {
    }

    // ------------------------------------------------------------------
    // 表单数据
    // ------------------------------------------------------------------

    /**
     * 构建「表单数据」工作表
     *
     * @param columns    动态列（并集，顺序已由 {@code resolveColumns} 确定）
     * @param rows       数据行（每行自带自己的 schema 与 values）
     * @param references 引用类字段的名称映射（人员 / 设备 / 分组）
     */
    public static SheetSpec buildFormData(List<Column> columns, List<FormOrderRow> rows, References references) {
        List<Column> safeColumns = columns == null ? List.of() : columns;
        List<FormOrderRow> safeRows = rows == null ? List.of() : rows;

        String[] headers = new String[CONTEXT_HEADERS.length + safeColumns.size()];
        System.arraycopy(CONTEXT_HEADERS, 0, headers, 0, CONTEXT_HEADERS.length);
        int[] widths = new int[headers.length];
        System.arraycopy(CONTEXT_WIDTHS, 0, widths, 0, CONTEXT_WIDTHS.length);
        for (int i = 0; i < safeColumns.size(); i++) {
            Column column = safeColumns.get(i);
            headers[CONTEXT_HEADERS.length + i] = column.label();
            widths[CONTEXT_HEADERS.length + i] = dynamicWidth(column.label());
        }

        List<Object[]> table = new ArrayList<>(safeRows.size());
        for (FormOrderRow row : safeRows) {
            Object[] cells = new Object[headers.length];
            cells[0] = text(row.orderNo());
            cells[1] = text(row.applyTypeName());
            cells[2] = text(row.applicantName());
            cells[3] = text(row.statusLabel());
            cells[4] = formatDateTime(row.submittedAt());
            Object[] formCells = FormDataFlattenSupport.rowOf(row.schema(), row.data(), safeColumns, references);
            System.arraycopy(formCells, 0, cells, CONTEXT_HEADERS.length, formCells.length);
            table.add(cells);
        }
        return new SheetSpec(SHEET_FORM_DATA, headers, widths, table);
    }

    // ------------------------------------------------------------------
    // 附件清单
    // ------------------------------------------------------------------

    /** 构建「附件清单」工作表；无附件时产出只有表头的空表（表头即「这里本该有东西」的说明） */
    public static SheetSpec buildAttachments(List<AttachmentRow> rows) {
        List<Object[]> table = new ArrayList<>();
        for (AttachmentRow row : rows == null ? List.<AttachmentRow>of() : rows) {
            table.add(new Object[]{
                    text(row.orderNo()),
                    text(row.applyTypeName()),
                    text(row.applicantName()),
                    text(row.fileName()),
                    row.fileSize() == null ? null : row.fileSize(),
                    formatDateTime(row.uploadedAt())
            });
        }
        return new SheetSpec(SHEET_ATTACHMENT, ATTACHMENT_HEADERS, ATTACHMENT_WIDTHS, table);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 动态列宽按表头长度估算：中文字符约占 2 个字符宽，故 {@code label.length() * 2}。
     *
     * <p>只按表头估而不扫全部数据，是因为列宽必须在写出表头时就确定
     * （{@code SXSSFWorkbook} 的列宽表不随流式行增长），扫数据会要求把整批行读两遍；
     * 而字段 label 的长度与内容长度本就大致相关。上限 40 防止超长 label 撑出一屏放不下的列。
     */
    private static int dynamicWidth(String label) {
        int estimated = (label == null ? 0 : label.length()) * 2 + DYNAMIC_WIDTH_EXTRA;
        return Math.min(Math.max(estimated, DYNAMIC_WIDTH_MIN), DYNAMIC_WIDTH_MAX);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : DATETIME.format(value);
    }
}
