package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 工单详情视图（「工单详情页展示：最终处理部门、当前实际执行人、审批链路…」）
 *
 * <p> 展示审批快照链路；「转交历史、自动顺延次数、超时状态」随 /7 补充。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDetailVO extends OrderVO {

    /** 审批快照节点（按 stepOrder 升序） */
    private List<ApprovalNodeView> nodes;

    /** 当前登录者是否为本工单申请人 */
    private Boolean applicantSelf;

    /** 当前登录者是否为本工单实际执行人（交付确认权限判定） */
    private Boolean handlerSelf;

    /**
     * 当前登录者是否为本工单的<b>抄送人</b>。
     *
     * <p>抄送人是「可只读查看」的旁路角色：能看到完整详情与附件，但不能执行任何写操作。
     * 前端据此在详情顶部渲染只读提示，并隐藏全部操作按钮 ——
     * 这与后端「所有写接口对抄送人自然拒绝」是配套的：后端是事实源，前端只是提前不给入口。
     */
    private Boolean ccSelf;

    /**
     * 转交历史（ + 「工单详情页展示转交历史记录」）
     *
     * <p>按转交时间升序；申请人同样可见（需求方  ）。
     */
    private List<OrderTransferVO> transfers;

    /**
     * 催办记录（需求方  「时间线增加催办记录」）
     *
     * <p>按催办时间升序；两类催办合并在同一条时间线上，用 {@code urgeType} 区分。
     */
    private List<OrderUrgeVO> urges;

    /**
     * 强制干预记录（「时间线记录」）
     *
     * <p>按操作时间升序；只有 super_admin 操作过才会有内容。
     */
    private List<OrderForceOperationVO> forceOperations;
}
