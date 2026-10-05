package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用记录导出（「形成完整使用记录」）
 *
 * <p><b>列与「使用记录」列表页逐列对齐</b>（导出文件即列表所见）：
 * 工单号 / 设备 / 资产编号 / 借用人 / 部门 / 借用类型 / 状态 /
 * 提交时间 / 计划归还 / 实际归还 / 顺延次数 / 是否超时。
 *
 * <p>列表把「顺延 / 超时」放在同一个单元格（「N 次」+ 超时标签）——
 * 那是<b>展示取舍</b>；导出到 Excel 时拆成「顺延次数（数值）」与「是否超时（是/否）」两列，
 * 这样在 Excel 里可以直接排序 / 筛选 / 透视，而无需先解析文本。
 * 底层字段一一对应，没有新增或丢失信息。
 *
 * <p>纯函数式构建：输入 {@code List<UsageRecordVO>} 输出 {@code SheetSpec}，
 * 不依赖 Spring、不碰数据库，因此可以单测「列顺序 / 长期领用占位 / 超时列 / null 处理」。
 */
public final class UsageExportExcel {

    public static final String SHEET_NAME = "使用记录";

    private static final String[] HEADERS = {
            "工单号", "设备", "资产编号", "借用人", "部门", "借用类型",
            "状态", "提交时间", "计划归还", "实际归还", "顺延次数", "是否超时"
    };

    /** 各列宽度（1/256 字符宽），与其它导出保持同一风格 */
    private static final int[] WIDTHS = {
            20, 24, 18, 12, 16, 12, 12, 20, 20, 20, 10, 10
    };

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UsageExportExcel() {
    }

    public static SheetSpec build(List<UsageRecordVO> records) {
        List<Object[]> rows = new ArrayList<>();
        for (UsageRecordVO record : records == null ? List.<UsageRecordVO>of() : records) {
            rows.add(new Object[]{
                    record.getOrderNo(),
                    record.getDeviceName(),
                    record.getAssetNo(),
                    record.getApplicantName(),
                    record.getDepartmentName(),
                    // 中文名优先用服务层已装配好的 label，兜底由枚举现算 —— 保证与列表页文案一致
                    labelOrCompute(record.getUseTypeLabel(), () -> UseType.labelOf(record.getUseType())),
                    labelOrCompute(record.getStatusLabel(), () -> OrderStatus.labelOf(record.getStatus())),
                    formatDateTime(record.getCreatedAt()),
                    plannedEnd(record),
                    formatDateTime(record.getActualEndTime()),
                    record.getAutoExtendCount() == null ? 0 : record.getAutoExtendCount(),
                    timeoutLabel(record.getBorrowTimeout())
            });
        }
        return new SheetSpec(SHEET_NAME, HEADERS, WIDTHS, rows);
    }

    /**
     * 计划归还为空即「长期领用」——与列表页单元格口径一致。
     *
     * <p>不导出空白：空白在 Excel 里既可能是「长期领用」也可能是「数据缺失」，
     * 运维/审计人员无法区分；写明「长期领用」才是可核对的结论。
     */
    private static String plannedEnd(UsageRecordVO record) {
        LocalDateTime planned = record.getPlannedEndTime();
        return planned == null ? "长期领用" : DATE_TIME.format(planned);
    }

    /** 是否超时：用「是 / 否」而不是空值，便于 Excel 直接筛选 */
    private static String timeoutLabel(Boolean borrowTimeout) {
        return Boolean.TRUE.equals(borrowTimeout) ? "是" : "否";
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
