package com.enterprise.ticket.module.dashboard.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.dashboard.dto.DashboardQuery;
import com.enterprise.ticket.module.dashboard.dto.vo.DashboardSummaryVO;
import com.enterprise.ticket.module.dashboard.mapper.DashboardMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 仪表盘服务层单测（Phase 16 Wave 3 · M5）
 *
 * <h2>锁的是「展示层判断规则」，不是 SQL</h2>
 * <p>数值由 {@code DashboardMapper} 的聚合 SQL 算出，这里全部以桩数据喂入。因此本类回答的是：
 * <ul>
 *   <li><b>区间换算</b>：用户给的「到某天」必须变成「到某天次日零点」，否则当天数据会漏掉；</li>
 *   <li><b>默认值</b>：不给任何参数时是「近 30 天 + 按天」，且生效区间要能回显给前端；</li>
 *   <li><b>非法输入要报错</b>：粒度拼错、区间倒置都拒绝 —— 静默纠正会让「为什么筛选没生效」
 *       变成一个只能靠猜的问题；</li>
 *   <li><b>{@code null} ≠ {@code 0}</b>：没有样本时平均时长与超时率必须保持 null（界面显示「—」），
 *       而计数归零，让每个「—」都有据可查；</li>
 *   <li><b>文案只有一处</b>：枚举中文名在服务层补，未知编码给「未知」而不是 null。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    @Mock
    private DashboardMapper dashboardMapper;

    @InjectMocks
    private DashboardServiceImpl service;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private DashboardQuery query(LocalDate from, LocalDate to, String granularity) {
        DashboardQuery q = new DashboardQuery();
        q.setFrom(from);
        q.setTo(to);
        q.setGranularity(granularity);
        return q;
    }

    private DashboardSummaryVO.Bucket bucket(String code, long count) {
        DashboardSummaryVO.Bucket row = new DashboardSummaryVO.Bucket();
        row.setCode(code);
        row.setCount(count);
        return row;
    }

    private DashboardSummaryVO.GroupBucket group(Long groupId, String name, long count) {
        DashboardSummaryVO.GroupBucket row = new DashboardSummaryVO.GroupBucket();
        row.setGroupId(groupId);
        row.setGroupName(name);
        row.setCount(count);
        return row;
    }

    private DashboardSummaryVO.ApprovalStats approvalStats(Long count, Double hours) {
        DashboardSummaryVO.ApprovalStats stats = new DashboardSummaryVO.ApprovalStats();
        stats.setApprovedOrderCount(count);
        stats.setAvgApprovalHours(hours);
        return stats;
    }

    private DashboardSummaryVO.OverdueStats overdueStats(Long overdue, Long withDeadline, Double rate) {
        DashboardSummaryVO.OverdueStats stats = new DashboardSummaryVO.OverdueStats();
        stats.setOverdueNodeCount(overdue);
        stats.setWithDeadlineNodeCount(withDeadline);
        stats.setOverdueRate(rate);
        return stats;
    }

    // ------------------------------------------------------------------
    // 一、区间换算与默认值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认区间：不传任何参数 → 含今天在内的近 30 天，粒度按天")
    void defaultsToLastThirtyDays() {
        DashboardSummaryVO vo = service.summary(null);

        LocalDate today = LocalDate.now();
        assertEquals(today, vo.getTo());
        assertEquals(today.minusDays(29), vo.getFrom(), "近 30 天含今天，故起点是今天往前 29 天");
        assertEquals(DashboardQuery.GRANULARITY_DAY, vo.getGranularity());
    }

    @Test
    @DisplayName("只给结束日 → 起点往前推 30 天（而不是不限起点）")
    void onlyToGiven() {
        DashboardSummaryVO vo = service.summary(query(null, LocalDate.of(2026, 9, 30), null));

        assertEquals(LocalDate.of(2026, 9, 30), vo.getTo());
        assertEquals(LocalDate.of(2026, 9, 1), vo.getFrom());
    }

    @Test
    @DisplayName("只给开始日 → 终点取今天")
    void onlyFromGiven() {
        DashboardSummaryVO vo = service.summary(query(LocalDate.of(2026, 9, 1), null, null));

        assertEquals(LocalDate.of(2026, 9, 1), vo.getFrom());
        assertEquals(LocalDate.now(), vo.getTo());
    }

    /**
     * 「选到 9 月 30 日」必须包含 9 月 30 日全天。
     *
     * <p>所以下推给 SQL 的上界是 10 月 1 日 00:00（左闭右开）。若这里退化成「9 月 30 日 00:00」，
     * 用户会发现「选到今天却查不到今天提交的单」，而且这种错误不会报错、只是少数据。
     */
    @Test
    @DisplayName("区间右开：结束日下推为次日 00:00，保证当天全天被包含")
    void toIsExclusiveNextDay() {
        service.summary(query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(dashboardMapper).countOrders(from.capture(), to.capture());

        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), from.getValue());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), to.getValue());
    }

    @Test
    @DisplayName("粒度按月：SQL 用 %Y-%m，且生效粒度回显为 MONTH")
    void monthlyGranularityUsesMonthPattern() {
        DashboardSummaryVO vo = service.summary(query(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), "month"));

        assertEquals(DashboardQuery.GRANULARITY_MONTH, vo.getGranularity());
        org.mockito.Mockito.verify(dashboardMapper)
                .countByTime(any(), any(), eq("%Y-%m"));
    }

    @Test
    @DisplayName("粒度按天：SQL 用 %Y-%m-%d")
    void dailyGranularityUsesDayPattern() {
        service.summary(query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "DAY"));

        org.mockito.Mockito.verify(dashboardMapper).countByTime(any(), any(), eq("%Y-%m-%d"));
    }

    // ------------------------------------------------------------------
    // 二、非法输入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("粒度拼错（MONTHLY）→ 报 PARAM_INVALID，不静默按天")
    void unknownGranularityIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.summary(query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "MONTHLY")));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("区间倒置（开始晚于结束）→ 报 INVALID_TIME_RANGE，不静默对调")
    void reversedRangeIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.summary(query(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), null)));

        assertEquals(ErrorCode.INVALID_TIME_RANGE, ex.getErrorCode());
    }

    // ------------------------------------------------------------------
    // 三、文案补全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("类型分布：中文名取自 OrderType 枚举；未知编码回落「未知」而不是 null")
    void typeLabelsComeFromEnum() {
        when(dashboardMapper.countByType(any(), any())).thenReturn(List.of(
                bucket("BORROW", 12),
                bucket("CUSTOM", 3),
                bucket("LEGACY_X", 1)));

        DashboardSummaryVO vo = service.summary(query(null, null, null));

        assertEquals("借用申请", vo.getByType().get(0).getLabel());
        assertEquals("自定义申请", vo.getByType().get(1).getLabel());
        assertEquals("未知", vo.getByType().get(2).getLabel());
    }

    @Test
    @DisplayName("状态分布：中文名取自 OrderStatus 枚举（含「使用中」这类与编码不同名的文案）")
    void statusLabelsComeFromEnum() {
        when(dashboardMapper.countByStatus(any(), any())).thenReturn(List.of(
                bucket("BORROWED", 5),
                bucket("PENDING_APPROVAL", 2)));

        DashboardSummaryVO vo = service.summary(query(null, null, null));

        assertEquals("使用中", vo.getByStatus().get(0).getLabel());
        assertEquals("审批中", vo.getByStatus().get(1).getLabel());
    }

    @Test
    @DisplayName("部门分布：未分组桶 id 归一为 0、文案「未分组」、且恒排最后")
    void ungroupedBucketIsLabelledAndSortedLast() {
        when(dashboardMapper.countByGroup(any(), any())).thenReturn(List.of(
                group(5L, "营销部", 10),
                group(null, null, 3),
                group(7L, null, 1)));

        List<DashboardSummaryVO.GroupBucket> rows = service.summary(query(null, null, null)).getByGroup();

        assertEquals(3, rows.size());
        assertEquals(5L, rows.get(0).getGroupId());
        assertEquals("营销部", rows.get(0).getGroupName());
        // 有 id 但分组行取不到名字：不能标成「未分组」（它确实分过组），也不能是 null
        assertEquals(7L, rows.get(1).getGroupId());
        assertEquals("未知分组", rows.get(1).getGroupName());
        // 未分组恒最后 —— 它是"剩下的"，摆在真实分组之间会打断阅读顺序
        assertEquals(0L, rows.get(2).getGroupId());
        assertEquals("未分组", rows.get(2).getGroupName());
    }

    // ------------------------------------------------------------------
    // 四、null ≠ 0
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无样本：平均时长与超时率保持 null（界面显示「—」），计数归零")
    void noSamplesKeepsMetricsNull() {
        when(dashboardMapper.approvalStats(any(), any())).thenReturn(approvalStats(null, null));
        when(dashboardMapper.overdueStats(any(), any())).thenReturn(overdueStats(null, null, null));

        DashboardSummaryVO vo = service.summary(query(null, null, null));

        assertNull(vo.getAvgApprovalHours(), "一张审批完成的单都没有 → 平均值「无法计算」，不是 0 小时");
        assertNull(vo.getOverdueRate(), "没有一个节点设过时限 → 超时率「无法判定」，不是 0%");
        assertEquals(0L, vo.getApprovedOrderCount());
        assertEquals(0L, vo.getOverdueNodeCount());
        assertEquals(0L, vo.getWithDeadlineNodeCount());
    }

    @Test
    @DisplayName("Mapper 整体返回 null（无行）时也要给出合法的空视图，不能 NPE")
    void nullMapperResultsAreTolerated() {
        DashboardSummaryVO vo = service.summary(query(null, null, null));

        assertEquals(0L, vo.getTotalOrders());
        assertTrue(vo.getByType().isEmpty());
        assertTrue(vo.getByStatus().isEmpty());
        assertTrue(vo.getByTime().isEmpty());
        assertTrue(vo.getByGroup().isEmpty());
        assertNull(vo.getAvgApprovalHours());
        assertNull(vo.getOverdueRate());
    }

    @Test
    @DisplayName("有样本：两个指标的分子/分母与比值原样透传（含「15 个有时限里超 2 个」）")
    void samplesArePassedThrough() {
        when(dashboardMapper.countOrders(any(), any())).thenReturn(42L);
        when(dashboardMapper.approvalStats(any(), any())).thenReturn(approvalStats(18L, 6.35));
        when(dashboardMapper.overdueStats(any(), any())).thenReturn(overdueStats(2L, 15L, 13.33));

        DashboardSummaryVO vo = service.summary(query(null, null, null));

        assertEquals(42L, vo.getTotalOrders());
        assertEquals(18L, vo.getApprovedOrderCount());
        assertEquals(6.35, vo.getAvgApprovalHours());
        assertEquals(2L, vo.getOverdueNodeCount());
        assertEquals(15L, vo.getWithDeadlineNodeCount());
        assertEquals(13.33, vo.getOverdueRate());
    }
}
