package com.enterprise.ticket.module.report.service.impl;

import com.enterprise.ticket.common.constant.FaultStatus;
import com.enterprise.ticket.module.report.dto.ReportQuery;
import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DashboardOverviewVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;
import com.enterprise.ticket.module.report.mapper.ReportMapper;
import com.enterprise.ticket.module.report.service.ReportService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统计报表实现
 *
 * <p>实现里刻意<b>不做二次聚合</b>：能由 SQL 给出的数（总数、平均、分组）一律在库里算，
 * 内存里只做「派生展示字段」的计算，例如把分钟换算成小时、把耗时与阈值比较得出是否超时。
 * 这样数据量增长时报表的内存占用是常数级。
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /**
     * 审批明细最多返回多少条。
     *
     * <p>明细是「给人看的清单兼导出源」，不是数据仓库；限制条数可以让页面与导出文件
     * 都保持可读。汇总数字（总数 / 平均 / 超时数）不受此限制，仍按全量计算 ——
     * 用户不会因为明细截断而看到失真的统计结论。
     */
    private static final int MAX_APPROVAL_ITEMS = 500;

    /** 故障设备分布最多返回多少台设备（Pareto 视角下，超过 200 台已无分析意义） */
    private static final int MAX_FAULT_DEVICES = 200;

    /** 部门借用排行取前几名（柱状图 Top N；再多的部门在图上已看不清，也失去对比意义） */
    private static final int MAX_DEPARTMENT_RANKING = 10;

    private static final String UNKNOWN_CATEGORY = "未分类";

    private final ReportMapper reportMapper;
    private final SystemConfigService systemConfigService;

    // ------------------------------------------------------------------
    // 一、设备借用频次
    // ------------------------------------------------------------------

    @Override
    public DeviceUsageReportVO deviceUsage(ReportQuery query) {
        ReportQuery effective = query == null ? new ReportQuery() : query;
        LocalDateTime from = effective.from();
        LocalDateTime to = effective.to();

        DeviceUsageReportVO vo = new DeviceUsageReportVO();
        vo.setTotalOrders(reportMapper.countBorrowOrders(from, to));
        vo.setDeviceCount(reportMapper.countBorrowDevices(from, to));
        vo.setByDevice(withCategoryFallback(reportMapper.borrowByDevice(from, to)));
        vo.setByCategory(reportMapper.borrowByCategory(from, to));
        return vo;
    }

    /** 分类名兜底：设备一级分类被删除或数据异常时统一显示「未分类」，避免页面出现空白单元格 */
    private List<DeviceUsageReportVO.DeviceItem> withCategoryFallback(List<DeviceUsageReportVO.DeviceItem> items) {
        List<DeviceUsageReportVO.DeviceItem> result = new ArrayList<>();
        for (DeviceUsageReportVO.DeviceItem item : items == null ? List.<DeviceUsageReportVO.DeviceItem>of() : items) {
            if (item.getPrimaryCategoryName() == null || item.getPrimaryCategoryName().isBlank()) {
                item.setPrimaryCategoryName(UNKNOWN_CATEGORY);
            }
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 二、工单审批时效
    // ------------------------------------------------------------------

    @Override
    public ApprovalEfficiencyReportVO approvalEfficiency(ReportQuery query) {
        ReportQuery effective = query == null ? new ReportQuery() : query;
        LocalDateTime from = effective.from();
        LocalDateTime to = effective.to();

        // 阈值读系统配置（ approval_timeout_remind_hours，默认 24 小时），不硬编码
        int thresholdHours = systemConfigService.approvalTimeoutRemindHours();
        long thresholdMinutes = (long) thresholdHours * 60;

        Double avgMinutes = reportMapper.avgApprovalMinutes(from, to);

        ApprovalEfficiencyReportVO vo = new ApprovalEfficiencyReportVO();
        vo.setTimeoutThresholdHours(thresholdHours);
        vo.setSubmittedOrders(reportMapper.countSubmittedOrders(from, to));
        vo.setApprovedOrders(reportMapper.countApprovedOrders(from, to));
        // 平均耗时保留 1 位小数（分钟 → 小时）；无已完成工单时为 0
        vo.setAvgApprovalHours(round1(avgMinutes == null ? 0d : avgMinutes / 60d));
        vo.setOvertimeOrders(reportMapper.countOvertimeOrders(from, to, thresholdMinutes));
        vo.setPendingNodes(reportMapper.countPendingNodes());
        vo.setPendingOverdueNodes(reportMapper.countPendingOverdueNodes(thresholdHours));
        vo.setItems(enrichApprovalItems(
                reportMapper.approvalItems(from, to, MAX_APPROVAL_ITEMS), thresholdHours));
        return vo;
    }

    /**
     * 明细补派生字段：耗时小时数 + 是否超时，并按耗时降序（未完成的排最后）
     *
     * <p>耗时在 Java 侧计算而不是 SQL 里 {@code TIMESTAMPDIFF}，是为了让「页面看到的耗时」
     * 与「判定是否超时用的耗时」严格来自同一个值 —— 两处各算一次，迟早因为取整方式不同
     * 出现「标着未超时但耗时写着 24.1 小时」这类自相矛盾的展示。
     */
    private List<ApprovalEfficiencyReportVO.Item> enrichApprovalItems(
            List<ApprovalEfficiencyReportVO.Item> items, int thresholdHours) {
        List<ApprovalEfficiencyReportVO.Item> result = new ArrayList<>();
        for (ApprovalEfficiencyReportVO.Item item : items == null ? List.<ApprovalEfficiencyReportVO.Item>of() : items) {
            if (item.getApprovedAt() != null && item.getSubmittedAt() != null) {
                double hours = Duration.between(item.getSubmittedAt(), item.getApprovedAt()).toMinutes() / 60d;
                item.setHours(round1(hours));
                item.setOvertime(hours > thresholdHours);
            } else {
                item.setHours(null);
                item.setOvertime(false);
            }
            result.add(item);
        }
        // 已完成的按耗时降序排前面，未完成的（hours == null）沉底
        result.sort(Comparator.comparing(ApprovalEfficiencyReportVO.Item::getHours,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    // ------------------------------------------------------------------
    // 三、设备故障统计
    // ------------------------------------------------------------------

    @Override
    public DeviceFaultReportVO deviceFault(ReportQuery query) {
        ReportQuery effective = query == null ? new ReportQuery() : query;
        LocalDateTime from = effective.from();
        LocalDateTime to = effective.to();

        DeviceFaultReportVO vo = new DeviceFaultReportVO();
        vo.setTotalFaults(reportMapper.countFaults(from, to));
        vo.setPendingRepair(reportMapper.countFaultsByStatus(from, to, FaultStatus.PENDING_REPAIR.name()));
        vo.setRepaired(reportMapper.countFaultsByStatus(from, to, FaultStatus.REPAIRED.name()));
        vo.setScrapped(reportMapper.countFaultsByStatus(from, to, FaultStatus.SCRAPPED.name()));
        vo.setByDevice(withFaultCategoryFallback(
                reportMapper.faultsByDevice(from, to, MAX_FAULT_DEVICES)));
        vo.setByMonth(reportMapper.faultsByMonth(from, to));
        return vo;
    }

    private List<DeviceFaultReportVO.DeviceItem> withFaultCategoryFallback(
            List<DeviceFaultReportVO.DeviceItem> items) {
        List<DeviceFaultReportVO.DeviceItem> result = new ArrayList<>();
        for (DeviceFaultReportVO.DeviceItem item : items == null ? List.<DeviceFaultReportVO.DeviceItem>of() : items) {
            if (item.getPrimaryCategoryName() == null || item.getPrimaryCategoryName().isBlank()) {
                item.setPrimaryCategoryName(UNKNOWN_CATEGORY);
            }
            // SUM(CASE WHEN ...) 在无匹配行时可能为 null（理论上不会，因为分组内至少一行）
            if (item.getPendingCount() == null) {
                item.setPendingCount(0L);
            }
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 四、管理概览（P3 管理层数据看板）
    // ------------------------------------------------------------------

    @Override
    public DashboardOverviewVO overview(ReportQuery query) {
        // 「随区间」的三项用 effective；「当前状态」的两项与区间无关，故不传区间
        ReportQuery effective = effectiveMonthly(query);
        LocalDateTime from = effective.from();
        LocalDateTime to = effective.to();

        DashboardOverviewVO vo = new DashboardOverviewVO();
        vo.setFrom(from.toLocalDate().toString());
        vo.setTo(to.toLocalDate().toString());
        vo.setRangeLabel(rangeLabelOf(effective));

        // ① 借出（随区间；未选年月时区间即当前月 ⇒ 卡面文案「本月借出」成立）
        vo.setMonthlyBorrowCount(reportMapper.countBorrowOrders(from, to));
        vo.setMonthlyBorrowDeviceCount(reportMapper.countBorrowDevices(from, to));
        vo.setDepartmentRanking(reportMapper.borrowByDepartment(from, to, MAX_DEPARTMENT_RANKING));
        vo.setTrend(reportMapper.borrowTrend(from, to, trendFormatOf(effective)));

        // ② 逾期（当前状态）
        vo.setOverdueTimeoutCount(reportMapper.countOverdueTimeout());
        vo.setOverdueGraceCount(reportMapper.countOverdueGrace());

        // ③ 设备利用率（当前状态）
        DashboardOverviewVO.DeviceStatusCount counts = reportMapper.deviceStatusCounts();
        vo.setDeviceTotal(counts.getTotal());
        vo.setDeviceAvailableCount(counts.getAvailable());
        vo.setDeviceInUseCount(counts.getInUse());
        vo.setDeviceInApprovalCount(counts.getInApproval());
        vo.setDeviceMaintenanceCount(counts.getMaintenance());
        vo.setDeviceLostCount(counts.getLost());
        vo.setDeviceScrappedCount(counts.getScrapped());

        long numerator = counts.getInUse() + counts.getInApproval();
        long denominator = counts.getTotal() - counts.getScrapped();
        vo.setUtilizationNumerator(numerator);
        vo.setUtilizationDenominator(denominator);
        // 分母为 0（空库或全部报废）时给 0 而不是 NaN —— NaN 序列化出去是个前端无法渲染的值
        vo.setUtilizationRate(denominator <= 0 ? 0d : round1(numerator * 100d / denominator));
        return vo;
    }

    /**
     * 概览的默认区间 = 当前自然月。
     *
     * <p>与三张明细报表「不选年月 = 全部历史」刻意不同：概览第一张卡的文案是「本月借出」，
     * 默认给全部历史会让数字与文案自相矛盾。用户显式选了年月则以所选为准。
     */
    private ReportQuery effectiveMonthly(ReportQuery query) {
        if (query != null && query.getYear() != null) {
            return query;
        }
        LocalDate now = LocalDate.now();
        ReportQuery fallback = new ReportQuery();
        fallback.setYear(now.getYear());
        fallback.setMonth(now.getMonthValue());
        return fallback;
    }

    /** 区间文案：{@code 2026-10}（月）/ {@code 2026}（整年） */
    private String rangeLabelOf(ReportQuery effective) {
        if (effective.getMonth() == null) {
            return String.valueOf(effective.getYear());
        }
        return String.format("%d-%02d", effective.getYear(), effective.getMonth());
    }

    /** 趋势分桶：区间落到月 ⇒ 按天看细节；只给年 ⇒ 按月看全年走势 */
    private String trendFormatOf(ReportQuery effective) {
        return effective.getMonth() == null ? "%Y-%m" : "%Y-%m-%d";
    }

    /** 保留 1 位小数（避免前端出现 3.3333333333333335 这类浮点噪声） */
    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
