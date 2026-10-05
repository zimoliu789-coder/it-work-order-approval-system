package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.log.dto.vo.OperationLogVO;
import com.enterprise.ticket.module.log.support.OperationLogLabels;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志导出（P2）
 *
 * <h2>列与「操作日志」列表页对齐，但多带一列技术详情</h2>
 * <p>列表页默认展示的是「人类可读摘要」，原始技术详情要点开「查看详情」才看得到 ——
 * 那是<b>阅读取舍</b>（列表上塞 JSON 没人看得下去）。导出到 Excel 是给审计与排障用的，
 * 两份都要：摘要用于快速浏览，详情用于定位到具体请求。
 * 因此这里比列表**多一列 `技术详情`**，底层字段一一对应，没有新增或丢失语义。
 *
 * <h2>为什么详情列要截断</h2>
 * <p>`details` 里可能是较长的 JSON。Excel 单元格上限 32767 字符，
 * 超出会直接丢失且不报错；而单行超长还会把整张表的列宽撑坏。
 * 这里截断到 {@link #DETAILS_LIMIT} 并**显式加省略号**——让人看出「这段被截过」，
 * 而不是以为日志原文就到这里。
 *
 * <p>纯函数式构建：输入 {@code List<OperationLogVO>} 输出 {@code SheetSpec}，
 * 不依赖 Spring、不碰数据库，可以单测「列顺序 / 截断 / null 兜底 / 空列表」。
 */
public final class LogExportExcel {

    public static final String SHEET_NAME = "操作日志";

    /** 详情列截断长度（远小于 Excel 的 32767 上限，留给列宽与可读性） */
    public static final int DETAILS_LIMIT = 2000;

    private static final String[] HEADERS = {
            "操作时间", "操作人", "模块", "动作", "结果", "风险等级", "操作摘要", "技术详情", "IP", "traceId"
    };

    private static final int[] WIDTHS = {
            20, 14, 16, 20, 10, 10, 46, 60, 18, 34
    };

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LogExportExcel() {
    }

    public static SheetSpec build(List<OperationLogVO> logs) {
        List<Object[]> rows = new ArrayList<>();
        for (OperationLogVO log : logs == null ? List.<OperationLogVO>of() : logs) {
            rows.add(new Object[]{
                    formatDateTime(log.getOperationTime()),
                    log.getOperatorName(),
                    // 中文名优先用已装配好的 label，兜底由映射表现算 —— 保证与列表页文案一致
                    labelOrCompute(log.getModuleLabel(), () -> OperationLogLabels.moduleLabel(log.getModule())),
                    labelOrCompute(log.getActionLabel(), () -> OperationLogLabels.actionLabel(log.getAction())),
                    labelOrCompute(log.getResultLabel(), () -> OperationLogLabels.resultLabel(log.getResult())),
                    labelOrCompute(log.getRiskLabel(), () -> OperationLogLabels.riskLabel(log.getRiskLevel())),
                    log.getSummary(),
                    truncate(log.getDetails()),
                    log.getIp(),
                    log.getTraceId()
            });
        }
        return new SheetSpec(SHEET_NAME, HEADERS, WIDTHS, rows);
    }

    /** 超长详情截断并显式标注，避免「以为原文就到这里」 */
    private static String truncate(String details) {
        if (details == null) {
            return "";
        }
        if (details.length() <= DETAILS_LIMIT) {
            return details;
        }
        return details.substring(0, DETAILS_LIMIT) + "…（已截断，完整内容请在系统内查看）";
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    /** 已装配的 label 优先（非空白才算），否则调用兜底计算 —— 避免出现空字符串覆盖 */
    private static String labelOrCompute(String label, java.util.function.Supplier<String> fallback) {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return fallback.get();
    }
}
