package com.enterprise.ticket.module.report.service.impl;

import com.enterprise.ticket.module.report.dto.ReportQuery;
import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DashboardOverviewVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;
import com.enterprise.ticket.module.report.mapper.ReportMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计报表服务单测（规范 §26.2）
 *
 * <p>报表的风险不在「算错」而在「口径漂移」：一旦页面上显示的耗时与导出文件里的不一致、
 * 或分类被删后单元格空白，用户对报表的信任就崩了。因此测试固化三类口径：
 * <ul>
 *   <li>时间区间换算（ReportQuery 的年/月 → 左闭右开 {@code [from, to)}）；</li>
 *   <li>审批耗时的「小时」换算与是否超时的判定必须来自同一个值；</li>
 *   <li>分类缺失兜底「未分类」、聚合列为 null 兜底 0，避免展示层出现空白 / NPE。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock
    private ReportMapper reportMapper;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private ReportServiceImpl service;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 9, 0);

    // ------------------------------------------------------------------
    // 一、设备借用频次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("借用频次：汇总数字与按设备/按分类列表原样返回，空白分类兜底「未分类」")
    void deviceUsage_summaryAndCategoryFallback() {
        DeviceUsageReportVO.DeviceItem withCategory = new DeviceUsageReportVO.DeviceItem();
        withCategory.setDeviceId(1L);
        withCategory.setDeviceName("笔记本");
        withCategory.setPrimaryCategoryName("IT 设备");
        withCategory.setBorrowCount(8L);

        DeviceUsageReportVO.DeviceItem blankCategory = new DeviceUsageReportVO.DeviceItem();
        blankCategory.setDeviceId(2L);
        blankCategory.setDeviceName("投影仪");
        blankCategory.setPrimaryCategoryName("   ");

        when(reportMapper.countBorrowOrders(any(), any())).thenReturn(10L);
        when(reportMapper.countBorrowDevices(any(), any())).thenReturn(4L);
        when(reportMapper.borrowByDevice(any(), any())).thenReturn(List.of(withCategory, blankCategory));
        when(reportMapper.borrowByCategory(any(), any())).thenReturn(List.of());

        var vo = service.deviceUsage(new ReportQuery());

        assertEquals(10L, vo.getTotalOrders());
        assertEquals(4L, vo.getDeviceCount());
        assertEquals(2, vo.getByDevice().size());
        assertEquals("IT 设备", vo.getByDevice().get(0).getPrimaryCategoryName());
        assertEquals("未分类", vo.getByDevice().get(1).getPrimaryCategoryName(),
                "分类缺失必须兜底为「未分类」，否则页面会出现空白单元格");
    }

    @Test
    @DisplayName("借用频次：不给年/月时不限时间，区间取最大范围（1970 ~ 2999）")
    void deviceUsage_noFilter_usesWidestRange() {
        when(reportMapper.borrowByDevice(any(), any())).thenReturn(List.of());
        when(reportMapper.borrowByCategory(any(), any())).thenReturn(List.of());

        service.deviceUsage(null);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(reportMapper).countBorrowOrders(from.capture(), to.capture());
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0), from.getValue());
        assertEquals(LocalDateTime.of(2999, 12, 31, 23, 59, 59), to.getValue());
    }

    @Test
    @DisplayName("借用频次：指定 2026 年 9 月 → 区间为 [2026-09-01, 2026-10-01)（左闭右开）")
    void deviceUsage_yearMonth_range() {
        when(reportMapper.borrowByDevice(any(), any())).thenReturn(List.of());
        when(reportMapper.borrowByCategory(any(), any())).thenReturn(List.of());

        ReportQuery query = new ReportQuery();
        query.setYear(2026);
        query.setMonth(9);
        service.deviceUsage(query);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(reportMapper).countBorrowOrders(from.capture(), to.capture());
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), from.getValue());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), to.getValue());
    }

    // ------------------------------------------------------------------
    // 二、工单审批时效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审批时效：平均耗时分钟 → 小时（保留 1 位小数），阈值读系统配置")
    void approvalEfficiency_avgHoursAndThreshold() {
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(24);
        when(reportMapper.avgApprovalMinutes(any(), any())).thenReturn(90d);
        when(reportMapper.countSubmittedOrders(any(), any())).thenReturn(12L);
        when(reportMapper.countApprovedOrders(any(), any())).thenReturn(10L);
        when(reportMapper.countOvertimeOrders(any(), any(), anyLong())).thenReturn(3L);
        when(reportMapper.countPendingNodes()).thenReturn(5L);
        when(reportMapper.countPendingOverdueNodes(anyInt())).thenReturn(1L);
        when(reportMapper.approvalItems(any(), any(), anyInt())).thenReturn(List.of());

        var vo = service.approvalEfficiency(new ReportQuery());

        assertEquals(24, vo.getTimeoutThresholdHours(), "阈值必须来自配置，不得硬编码");
        assertEquals(1.5d, vo.getAvgApprovalHours(), 1e-9, "90 分钟应换算为 1.5 小时");
        assertEquals(12L, vo.getSubmittedOrders());
        assertEquals(10L, vo.getApprovedOrders());
        assertEquals(3L, vo.getOvertimeOrders());
        assertEquals(5L, vo.getPendingNodes());
        assertEquals(1L, vo.getPendingOverdueNodes());
    }

    @Test
    @DisplayName("审批时效：无已完成工单（平均为 null）→ 平均耗时为 0，不抛 NPE")
    void approvalEfficiency_nullAvg_zero() {
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(24);
        when(reportMapper.avgApprovalMinutes(any(), any())).thenReturn(null);
        when(reportMapper.approvalItems(any(), any(), anyInt())).thenReturn(null);

        var vo = service.approvalEfficiency(new ReportQuery());

        assertEquals(0d, vo.getAvgApprovalHours(), 1e-9);
        assertTrue(vo.getItems().isEmpty(), "明细为 null 时应回落空列表，而不是 null");
    }

    @Test
    @DisplayName("审批时效明细：耗时与超时判定同源，按耗时降序、未完成沉底")
    void approvalEfficiency_itemsSortedAndOvertime() {
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(24);
        when(reportMapper.approvalItems(any(), any(), anyInt())).thenReturn(List.of(
                item("A-快", BASE, BASE.plusHours(1)),
                item("B-未完成", BASE, null),
                item("C-慢", BASE, BASE.plusHours(30))));

        var vo = service.approvalEfficiency(new ReportQuery());

        var items = vo.getItems();
        assertEquals(3, items.size());
        // 降序：C(30h) → A(1h) → B(未完成，null 沉底)
        assertEquals("C-慢", items.get(0).getOrderNo());
        assertEquals(30d, items.get(0).getHours(), 1e-9);
        assertTrue(items.get(0).isOvertime(), "30 小时 > 24 小时阈值，应标记超时");

        assertEquals("A-快", items.get(1).getOrderNo());
        assertEquals(1d, items.get(1).getHours(), 1e-9);
        assertFalse(items.get(1).isOvertime());

        assertEquals("B-未完成", items.get(2).getOrderNo());
        assertNull(items.get(2).getHours(), "未完成审批的耗时应为 null（前端显示「审批中」）");
        assertFalse(items.get(2).isOvertime());
    }

    private ApprovalEfficiencyReportVO.Item item(String orderNo, LocalDateTime submitted, LocalDateTime approved) {
        ApprovalEfficiencyReportVO.Item it = new ApprovalEfficiencyReportVO.Item();
        it.setOrderNo(orderNo);
        it.setSubmittedAt(submitted);
        it.setApprovedAt(approved);
        return it;
    }

    // ------------------------------------------------------------------
    // 三、设备故障统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("故障统计：按状态汇总 + 分类兜底 + pendingCount 为 null 兜底 0")
    void deviceFault_summaryAndFallbacks() {
        DeviceFaultReportVO.DeviceItem blank = new DeviceFaultReportVO.DeviceItem();
        blank.setDeviceId(1L);
        blank.setPrimaryCategoryName(null);
        blank.setFaultCount(3L);
        blank.setPendingCount(null);

        when(reportMapper.countFaults(any(), any())).thenReturn(7L);
        when(reportMapper.countFaultsByStatus(any(), any(), eq("PENDING_REPAIR"))).thenReturn(2L);
        when(reportMapper.countFaultsByStatus(any(), any(), eq("REPAIRED"))).thenReturn(4L);
        when(reportMapper.countFaultsByStatus(any(), any(), eq("SCRAPPED"))).thenReturn(1L);
        when(reportMapper.faultsByDevice(any(), any(), anyInt())).thenReturn(List.of(blank));
        when(reportMapper.faultsByMonth(any(), any())).thenReturn(List.of());

        var vo = service.deviceFault(new ReportQuery());

        assertEquals(7L, vo.getTotalFaults());
        assertEquals(2L, vo.getPendingRepair());
        assertEquals(4L, vo.getRepaired());
        assertEquals(1L, vo.getScrapped());
        assertEquals("未分类", vo.getByDevice().get(0).getPrimaryCategoryName());
        assertEquals(0L, vo.getByDevice().get(0).getPendingCount(), "聚合列 null 应兜底为 0");
    }

    // ------------------------------------------------------------------
    // 四、管理概览（P3 管理层数据看板）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("概览：未选年月时区间默认为「当前自然月」（卡面文案是「本月借出」，给全部历史会自相矛盾）")
    void overview_defaultsToCurrentMonth() {
        stubOverview(defaultStatusCounts());

        service.overview(null);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportMapper).borrowTrend(fromCaptor.capture(), toCaptor.capture(), any());

        LocalDate today = LocalDate.now();
        LocalDateTime monthStart = LocalDateTime.of(today.getYear(), today.getMonthValue(), 1, 0, 0);
        assertEquals(monthStart, fromCaptor.getValue(), "区间下界应为当月 1 日 0 点");
        assertEquals(monthStart.plusMonths(1), toCaptor.getValue(),
                "区间上界应为次月 1 日 0 点（左闭右开，避免漏掉月底最后一秒之后的数据）");
    }

    @Test
    @DisplayName("概览：显式选定年月时区间与文案以所选为准")
    void overview_explicitRange() {
        stubOverview(defaultStatusCounts());
        ReportQuery query = new ReportQuery();
        query.setYear(2026);
        query.setMonth(9);

        var vo = service.overview(query);

        assertEquals("2026-09", vo.getRangeLabel());
        assertEquals("2026-09-01", vo.getFrom());
        assertEquals("2026-10-01", vo.getTo(), "9 月的上界是 10 月 1 日，不是 9 月 30 日");
    }

    @Test
    @DisplayName("概览：只给年份时文案为「2026」、区间为整年、趋势按月分桶")
    void overview_yearOnly() {
        stubOverview(defaultStatusCounts());
        ReportQuery query = new ReportQuery();
        query.setYear(2026);

        var vo = service.overview(query);

        assertEquals("2026", vo.getRangeLabel());
        assertEquals("2026-01-01", vo.getFrom());
        assertEquals("2027-01-01", vo.getTo());
        verify(reportMapper).borrowTrend(any(), any(), eq("%Y-%m"));
    }

    @Test
    @DisplayName("概览：区间落到月时趋势按天分桶（看当月细节）")
    void overview_trendByDayWithinMonth() {
        stubOverview(defaultStatusCounts());
        ReportQuery query = new ReportQuery();
        query.setYear(2026);
        query.setMonth(10);

        service.overview(query);

        verify(reportMapper).borrowTrend(any(), any(), eq("%Y-%m-%d"));
    }

    @Test
    @DisplayName("概览：利用率 = (在用 + 审批中) / (总数 − 报废)，并回显分子分母供页面标注口径")
    void overview_utilizationFormula() {
        stubOverview(defaultStatusCounts());

        var vo = service.overview(new ReportQuery());

        assertEquals(1091L, vo.getDeviceTotal());
        assertEquals(16L + 373L, vo.getUtilizationNumerator(), "审批中的设备已被占用，应计入分子");
        assertEquals(1091L - 11L, vo.getUtilizationDenominator(), "报废设备不可调度，应从分母剔除");
        assertEquals(36.0, vo.getUtilizationRate(), 0.05);
        // 各状态台数一并回显 —— 只给百分号读者无法核对它是怎么算出来的
        assertEquals(681L, vo.getDeviceAvailableCount());
        assertEquals(10L, vo.getDeviceMaintenanceCount());
    }

    @Test
    @DisplayName("概览：分母为 0（空库 / 全部报废）时利用率为 0 而非 NaN")
    void overview_utilizationZeroDenominator() {
        DashboardOverviewVO.DeviceStatusCount allScrapped = new DashboardOverviewVO.DeviceStatusCount();
        allScrapped.setTotal(3);
        allScrapped.setScrapped(3);
        stubOverview(allScrapped);

        var vo = service.overview(new ReportQuery());

        assertEquals(0L, vo.getUtilizationDenominator());
        assertEquals(0d, vo.getUtilizationRate(), 0.0001);
        assertFalse(Double.isNaN(vo.getUtilizationRate()), "NaN 序列化出去前端无法渲染");
    }

    @Test
    @DisplayName("概览：逾期两个口径都取自 mapper，且副口径恒 ≥ 主口径")
    void overview_overdueBothScopes() {
        stubOverview(defaultStatusCounts());

        var vo = service.overview(new ReportQuery());

        assertEquals(14L, vo.getOverdueTimeoutCount(), "主口径 = borrow_timeout 标记（规范 §16.5）");
        assertEquals(15L, vo.getOverdueGraceCount(), "副口径 = 计划归还已过（含顺延宽限期内）");
        assertTrue(vo.getOverdueGraceCount() >= vo.getOverdueTimeoutCount(),
                "副口径含宽限期内工单，恒不小于主口径；若反了说明两个 SQL 的限定条件写错了");
    }

    @Test
    @DisplayName("概览：部门排行原样返回（含「未分配」兜底项，保证各部门之和 = 总借出数）")
    void overview_departmentRanking() {
        stubOverview(defaultStatusCounts());
        DashboardOverviewVO.DepartmentItem item = new DashboardOverviewVO.DepartmentItem();
        item.setDepartmentId(null);
        item.setDepartmentName("未分配");
        item.setBorrowCount(486L);
        when(reportMapper.borrowByDepartment(any(), any(), anyInt())).thenReturn(List.of(item));

        var vo = service.overview(new ReportQuery());

        assertEquals(1, vo.getDepartmentRanking().size());
        assertEquals("未分配", vo.getDepartmentRanking().get(0).getDepartmentName(),
                "无部门的账号（如超管）应归入「未分配」而不是从排行里消失");
        assertEquals(486L, vo.getDepartmentRanking().get(0).getBorrowCount());
    }

    // ------------------------------------------------------------------
    // 概览测试的桩
    // ------------------------------------------------------------------

    /** 默认设备状态分布：照抄演示库真实值，让利用率断言可以直接人工核对 */
    private DashboardOverviewVO.DeviceStatusCount defaultStatusCounts() {
        DashboardOverviewVO.DeviceStatusCount counts = new DashboardOverviewVO.DeviceStatusCount();
        counts.setTotal(1091);
        counts.setAvailable(681);
        counts.setInUse(16);
        counts.setInApproval(373);
        counts.setMaintenance(10);
        counts.setLost(0);
        counts.setScrapped(11);
        return counts;
    }

    private void stubOverview(DashboardOverviewVO.DeviceStatusCount counts) {
        when(reportMapper.countBorrowOrders(any(), any())).thenReturn(20L);
        when(reportMapper.countBorrowDevices(any(), any())).thenReturn(18L);
        when(reportMapper.borrowByDepartment(any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.borrowTrend(any(), any(), any())).thenReturn(List.of());
        when(reportMapper.countOverdueTimeout()).thenReturn(14L);
        when(reportMapper.countOverdueGrace()).thenReturn(15L);
        when(reportMapper.deviceStatusCounts()).thenReturn(counts);
    }
}
