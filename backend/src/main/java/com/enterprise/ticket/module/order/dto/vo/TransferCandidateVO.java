package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

/**
 * 工单转交候选对象（ / 「转交弹窗：普通组员下拉只显示同小组其他在职成员」）
 *
 * <p>{@code inFlightCount} 是该成员<b>名下在办工单数</b>，仅作为选择时的参考信息展示
 * （避免反复转给同一个人）。它不是硬约束 —— 规范只要求「同组在职」，不限制负载。
 */
@Data
public class TransferCandidateVO {

    private Long userId;

    private String displayName;

    /** 名下在办工单数（待交付 / 使用中 / 待收回） */
    private Integer inFlightCount;
}
