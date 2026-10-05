package com.enterprise.ticket.module.dashboard.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.dashboard.dto.DashboardQuery;
import com.enterprise.ticket.module.dashboard.dto.vo.DashboardSummaryVO;
import com.enterprise.ticket.module.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计仪表盘接口（ · M5）
 *
 * <h2>权限：独立的 {@code dashboard:view}</h2>
 * <p>与 {@code ReportController} / {@code FlowMonitorController} 同口径：分析类只读视图
 * 归业务管理员，且<b>直调接口必须被拦</b> —— 前端区块隐藏只是体验，不构成权限。
 *
 * <h2>审计</h2>
 * <p>记 {@code DASHBOARD_VIEW}（NORMAL 级）：仪表盘暴露的是全量经营数据，
 * 「谁看过」本身值得留痕，而访问频次低（仅管理员打开工作台时触发），不会淹没日志。
 * {@code recordArgs = false} 免掉一次参数序列化 —— 区间与粒度不构成敏感信息。
 *
 * <h2>工作台是一条「不新增接口也能用」的兼容路径</h2>
 * <p>改造前的工作台是前端用既有分页接口「取 size=1 读 total」拼出来的（见
 * {@code views/dashboard/index.vue}）。本接口是<b>增量</b>：前端只有在用户确实持有
 * {@code dashboard:view} 时才调用它，普通员工看到的页面与改造前逐像素相同。
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 仪表盘聚合摘要。
     *
     * @param from        区间下界（含），{@code yyyy-MM-dd}；为空则取「结束日往前 30 天」
     * @param to          区间上界（含），{@code yyyy-MM-dd}；为空则取今天
     * @param granularity 时间粒度 {@code DAY}（默认）/ {@code MONTH}
     */
    @GetMapping("/summary")
    @PreAuthorize("@perm.has('dashboard:view')")
    @AuditLog(module = "DASHBOARD", action = "DASHBOARD_VIEW", risk = RiskLevel.NORMAL,
            description = "查看统计仪表盘", recordArgs = false)
    public ApiResponse<DashboardSummaryVO> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity) {
        DashboardQuery query = new DashboardQuery();
        query.setFrom(from);
        query.setTo(to);
        query.setGranularity(granularity);
        return ApiResponse.success(dashboardService.summary(query));
    }
}
