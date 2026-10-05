package com.enterprise.ticket.module.report.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单审批时效统计（「平均审批耗时、超时审批工单」）
 *
 * <p><b>「审批耗时」的口径</b>：以工单<b>提交时间</b>为起点、以该工单<b>最后一个审批节点通过的时间</b>
 * 为终点。理由是用户感知的等待就是这两点之间 —— 中间经过几步、每步停留多久，
 * 都只是同一段等待的内部构成。
 *
 * <p><b>「超时审批工单」的口径</b>：上述耗时超过配置项
 * {@code approval_timeout_remind_hours}（，默认 24 小时）。
 * 阈值读配置而不是硬编码，现场可调。
 *
 * <p>另外单独给出 {@code pendingOverdueNodes}：「此刻仍挂着的、已超过阈值的待审节点」。
 * 它与 {@code overtimeOrders} 不是同一个数 —— 前者是<b>当前积压</b>（可立即催办），
 * 后者是<b>历史已发生的慢审批</b>（用于复盘）。两个数混在一起会得出错误的运营结论。
 */
@Data
public class ApprovalEfficiencyReportVO {

    /** 区间内提交的工单数（分母） */
    private long submittedOrders;

    /** 区间内已完成全部审批的工单数 */
    private long approvedOrders;

    /**
     * 平均审批耗时（小时，保留 1 位小数）
     *
     * <p>仅统计已完成审批的工单；未完成的工单没有终点，纳入平均会把数字拉低（幸存者偏差）。
     */
    private double avgApprovalHours;

    /** 审批耗时超过阈值的工单数（历史） */
    private long overtimeOrders;

    /** 当前仍待处理的审批节点数（不受区间限制，反映此刻积压） */
    private long pendingNodes;

    /** 当前待处理且已超过阈值的审批节点数（此刻积压中的超时部分） */
    private long pendingOverdueNodes;

    /** 判定超时所用的阈值（小时） */
    private int timeoutThresholdHours;

    /** 审批明细（按耗时降序，最多 {@code MAX_ITEMS} 条，供页面表格与导出使用） */
    private List<Item> items;

    /** 单笔工单的审批时效明细 */
    @Data
    public static class Item {
        private Long orderId;
        private String orderNo;
        private String applicantName;
        private String deviceName;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submittedAt;

        /** 最后一个审批节点通过的时间；未完成审批时为空 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime approvedAt;

        /** 审批耗时（小时）；未完成审批时为空 */
        private Double hours;

        /** 是否超过阈值（未完成审批时为 false） */
        private boolean overtime;
    }
}
