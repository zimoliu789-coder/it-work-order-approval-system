package com.enterprise.ticket.module.report.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.report.dto.ReportQuery;
import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DashboardOverviewVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;
import com.enterprise.ticket.module.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计报表接口
 *
 * <p>权限：规范明确「报表仅 super_admin、admin 可见」，因此三个端点统一
 * {@code @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")} —— 前端菜单隐藏只是体验，
 * 直调接口必须被拦。
 *
 * <p>审计：报表包含全量经营数据，<b>谁看过</b>本身值得留痕，故三个端点都记
 * {@code REPORT_VIEW}（NORMAL 级）。报表访问频次低（仅管理员），不会淹没日志。
 *
 * <p>导出：本控制器只提供 JSON 数据。报表的 Excel 导出走统一的导出入口
 * （{@code POST /api/exports}，type 为 {@code REPORT_*}），这样可以复用
 * 「落任务 + 鉴权下载 + 过期清理 + 审计」这一整套既有机制，不必再写一条下载通道。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /** 设备借用频次（按设备、按分类） */
    @GetMapping("/device-usage")
    @PreAuthorize("@perm.has('asset:report:view')")
    @AuditLog(module = "REPORT", action = "REPORT_VIEW", risk = RiskLevel.NORMAL, description = "查看借用频次报表")
    public ApiResponse<DeviceUsageReportVO> deviceUsage(@RequestParam(required = false) Integer year,
                                                        @RequestParam(required = false) Integer month) {
        return ApiResponse.success(reportService.deviceUsage(queryOf(year, month)));
    }

    /** 工单审批时效（平均审批耗时、超时审批工单） */
    @GetMapping("/approval-efficiency")
    @PreAuthorize("@perm.has('asset:report:view')")
    @AuditLog(module = "REPORT", action = "REPORT_VIEW", risk = RiskLevel.NORMAL, description = "查看审批时效报表")
    public ApiResponse<ApprovalEfficiencyReportVO> approvalEfficiency(@RequestParam(required = false) Integer year,
                                                                     @RequestParam(required = false) Integer month) {
        return ApiResponse.success(reportService.approvalEfficiency(queryOf(year, month)));
    }

    /** 设备故障统计（故障数量、故障设备分布） */
    @GetMapping("/device-fault")
    @PreAuthorize("@perm.has('asset:report:view')")
    @AuditLog(module = "REPORT", action = "REPORT_VIEW", risk = RiskLevel.NORMAL, description = "查看故障统计报表")
    public ApiResponse<DeviceFaultReportVO> deviceFault(@RequestParam(required = false) Integer year,
                                                        @RequestParam(required = false) Integer month) {
        return ApiResponse.success(reportService.deviceFault(queryOf(year, month)));
    }

    /** 管理概览（P3 管理层数据看板）：借出 / 逾期 / 设备利用率 / 部门借用排行 */
    @GetMapping("/overview")
    @PreAuthorize("@perm.has('asset:report:view')")
    @AuditLog(module = "REPORT", action = "REPORT_VIEW", risk = RiskLevel.NORMAL, description = "查看管理概览")
    public ApiResponse<DashboardOverviewVO> overview(@RequestParam(required = false) Integer year,
                                                      @RequestParam(required = false) Integer month) {
        return ApiResponse.success(reportService.overview(queryOf(year, month)));
    }

    private ReportQuery queryOf(Integer year, Integer month) {
        ReportQuery query = new ReportQuery();
        query.setYear(year);
        query.setMonth(month);
        return query;
    }
}
