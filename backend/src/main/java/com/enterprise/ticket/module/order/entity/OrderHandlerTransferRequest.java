package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单转单申请（V55 / D5.7）。
 *
 * <h2>与 {@link OrderHandlerTransfer} 的分工（不要合并）</h2>
 * <ul>
 *   <li>{@link OrderHandlerTransfer}：**已发生的事实**，只追加不修改，回答「谁在什么时候转给了谁」；</li>
 *   <li>本表：**申请单**，有状态可流转，回答「这单为什么卡在某个组里三天」——
 *       被拒绝的每一次都要留痕，而历史表只该记最终生效的那一次。</li>
 * </ul>
 *
 * <h2>为什么把「原小组 / 原执行人」抄一份而不是只存 order_id</h2>
 * <p>转单生效后 {@code borrow_order} 上的小组与执行人都变了，若本表只存外键，
 * 事后就再也答不出「这笔转单是从哪个组、哪个人转出来的」—— 而这正是审计要素。
 */
@Data
@TableName("order_transfer_request")
public class OrderHandlerTransferRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单 id */
    private Long orderId;

    /** 转单范围：SAME_GROUP / CROSS_GROUP，取值见 {@code TransferScope} */
    private String transferScope;

    /** 原处理小组 id（快照） */
    private Long fromDepartmentId;

    /** 目标处理小组 id；同组转单时等于 {@link #fromDepartmentId} */
    private Long toDepartmentId;

    /** 原实际执行人（生效时用它做条件 UPDATE，防止并发覆盖别人的转单结果） */
    private Long fromHandlerId;

    /** 目标执行人；跨组转单在「接收并分配」时写入 */
    private Long toHandlerId;

    /**
     * 待处理人。
     *
     * <p>同组转单 = 本组管理员（审批人）；跨组转单 = 目标组管理员（接收人）。
     * 把它落库而不是每次现算：管理员可能在申请挂起期间被换掉，
     * 而「当时该由谁批」必须按提交那一刻算，否则历史单据的待办会漂移到新人头上。
     */
    private Long toAdminId;

    /** 状态：PENDING / ACCEPTED / REJECTED / CANCELLED，取值见 {@code TransferRequestStatus} */
    private String status;

    /** 转单原因（必填，审计要素） */
    private String reason;

    /** 发起人 */
    private Long operatorId;

    /** 处理人（审批 / 接收 / 拒绝的人） */
    private Long decideBy;

    private LocalDateTime decideAt;

    /** 处理意见（拒绝时建议填写，说明为什么退回去） */
    private String decideComment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
