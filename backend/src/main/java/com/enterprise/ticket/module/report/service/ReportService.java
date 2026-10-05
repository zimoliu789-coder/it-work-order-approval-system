package com.enterprise.ticket.module.report.service;

import com.enterprise.ticket.module.report.dto.ReportQuery;
import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DashboardOverviewVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;

/**
 * 统计报表服务
 *
 * <p>三张报表按规范原文一一对应：设备借用频次、工单审批时效、设备故障统计。
 * 权限（仅 {@code super_admin} / {@code admin} 可见）由 Controller 的
 * {@code @PreAuthorize} 与服务层双重把关：菜单隐藏只是体验，接口必须自己拦。
 */
public interface ReportService {

    /** 设备借用频次统计（按设备、按分类） */
    DeviceUsageReportVO deviceUsage(ReportQuery query);

    /** 工单审批时效统计（平均审批耗时、超时审批工单） */
    ApprovalEfficiencyReportVO approvalEfficiency(ReportQuery query);

    /** 设备故障统计（故障数量、故障设备分布） */
    DeviceFaultReportVO deviceFault(ReportQuery query);

    /**
     * 管理概览（P3 管理层数据看板）
     *
     * <p>与三张明细报表并列，但回答的是不同问题：三张报表回答「具体是谁/哪台/哪个月」，
     * 概览回答「现在什么情况」。因此它的「逾期 / 设备利用率」是<b>当前状态</b>，
     * 不受 {@code ReportQuery} 区间影响；「借出 / 部门排行 / 趋势」随区间变化。
     */
    DashboardOverviewVO overview(ReportQuery query);
}
