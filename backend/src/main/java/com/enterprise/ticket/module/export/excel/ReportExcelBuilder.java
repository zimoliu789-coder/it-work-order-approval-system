package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 统计报表的 Excel 组装（「报表支持导出 Excel」）
 *
 * <p>每张报表都输出「汇总 + 明细」多工作表：
 * <ul>
 *   <li><b>汇总</b>——几个关键指标，适合直接贴进周报；</li>
 *   <li><b>明细</b>——构成这些指标的行级数据，便于追溯与二次分析。</li>
 * </ul>
 * 只给汇总会让用户「想核对却无从下手」，只给明细则无法一眼看结论，两者都不可省。
 *
 * <p>纯函数式组装（输入 VO、输出工作表），不依赖 Spring / 数据库，可单测列顺序与兜底文案。
 */
public final class ReportExcelBuilder {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ReportExcelBuilder() {
    }

    // ==================================================================
    // 一、设备借用频次
    // ==================================================================

    public static List<SheetSpec> usage(DeviceUsageReportVO vo) {
        List<Object[]> summaryRows = new ArrayList<>();
        summaryRows.add(new Object[]{"借用申请总数", vo.getTotalOrders()});
        summaryRows.add(new Object[]{"涉及设备数", vo.getDeviceCount()});

        List<Object[]> deviceRows = new ArrayList<>();
        for (DeviceUsageReportVO.DeviceItem item : safe(vo.getByDevice())) {
            deviceRows.add(new Object[]{
                    item.getDeviceName(), item.getAssetNo(), item.getPrimaryCategoryName(), item.getBorrowCount()
            });
        }

        List<Object[]> categoryRows = new ArrayList<>();
        for (DeviceUsageReportVO.CategoryItem item : safe(vo.getByCategory())) {
            categoryRows.add(new Object[]{
                    item.getCategoryName(), item.getBorrowCount(), item.getDeviceCount()
            });
        }

        return List.of(
                new SheetSpec("借用频次汇总",
                        new String[]{"指标", "数值"}, new int[]{24, 16}, summaryRows),
                new SheetSpec("按设备统计",
                        new String[]{"设备名称", "资产编号", "一级分类", "借用次数"}, new int[]{28, 18, 16, 12}, deviceRows),
                new SheetSpec("按分类统计",
                        new String[]{"一级分类", "借用次数", "涉及设备数"}, new int[]{16, 12, 14}, categoryRows));
    }

    // ==================================================================
    // 二、工单审批时效
    // ==================================================================

    public static List<SheetSpec> approvalEfficiency(ApprovalEfficiencyReportVO vo) {
        List<Object[]> summaryRows = new ArrayList<>();
        summaryRows.add(new Object[]{"提交工单数", vo.getSubmittedOrders()});
        summaryRows.add(new Object[]{"完成审批工单数", vo.getApprovedOrders()});
        summaryRows.add(new Object[]{"平均审批耗时（小时）", vo.getAvgApprovalHours()});
        summaryRows.add(new Object[]{"超时审批工单数", vo.getOvertimeOrders()});
        summaryRows.add(new Object[]{"当前待审节点数", vo.getPendingNodes()});
        summaryRows.add(new Object[]{"当前超时未审节点数", vo.getPendingOverdueNodes()});
        summaryRows.add(new Object[]{"超时判定阈值（小时）", vo.getTimeoutThresholdHours()});

        List<Object[]> detailRows = new ArrayList<>();
        for (ApprovalEfficiencyReportVO.Item item : safe(vo.getItems())) {
            detailRows.add(new Object[]{
                    item.getOrderNo(),
                    item.getApplicantName(),
                    item.getDeviceName(),
                    format(item.getSubmittedAt()),
                    format(item.getApprovedAt()),
                    // 未完成审批时耗时留空字符串，而不是 0 —— 0 会被误读成「瞬间通过」
                    item.getHours() == null ? "" : item.getHours(),
                    item.getApprovedAt() == null ? "审批中" : (item.isOvertime() ? "超时" : "正常")
            });
        }

        return List.of(
                new SheetSpec("审批时效汇总",
                        new String[]{"指标", "数值"}, new int[]{26, 16}, summaryRows),
                new SheetSpec("审批明细",
                        new String[]{"工单编号", "申请人", "设备", "提交时间", "审批完成时间", "耗时（小时）", "时效判定"},
                        new int[]{22, 12, 28, 20, 20, 14, 12}, detailRows));
    }

    // ==================================================================
    // 三、设备故障统计
    // ==================================================================

    public static List<SheetSpec> deviceFault(DeviceFaultReportVO vo) {
        List<Object[]> summaryRows = new ArrayList<>();
        summaryRows.add(new Object[]{"故障总数", vo.getTotalFaults()});
        summaryRows.add(new Object[]{"待维修", vo.getPendingRepair()});
        summaryRows.add(new Object[]{"维修完成", vo.getRepaired()});
        summaryRows.add(new Object[]{"已报废", vo.getScrapped()});

        List<Object[]> deviceRows = new ArrayList<>();
        for (DeviceFaultReportVO.DeviceItem item : safe(vo.getByDevice())) {
            deviceRows.add(new Object[]{
                    item.getDeviceName(), item.getAssetNo(), item.getPrimaryCategoryName(),
                    item.getFaultCount(), item.getPendingCount()
            });
        }

        List<Object[]> monthRows = new ArrayList<>();
        for (DeviceFaultReportVO.MonthItem item : safe(vo.getByMonth())) {
            monthRows.add(new Object[]{item.getMonth(), item.getFaultCount()});
        }

        return List.of(
                new SheetSpec("故障统计汇总",
                        new String[]{"指标", "数值"}, new int[]{20, 16}, summaryRows),
                new SheetSpec("故障设备分布",
                        new String[]{"设备名称", "资产编号", "一级分类", "故障次数", "其中待维修"},
                        new int[]{28, 18, 16, 12, 14}, deviceRows),
                new SheetSpec("故障月份分布",
                        new String[]{"月份", "故障数"}, new int[]{14, 12}, monthRows));
    }

    private static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATETIME.format(dateTime);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
