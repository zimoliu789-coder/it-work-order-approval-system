package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 审批操作请求（； 增补「指定下一节点审批人」）
 *
 * <p>驳回必须填写原因（「驳回必须填写原因」），该规则在后端强制校验，
 * 不依赖前端是否置灰按钮。
 */
@Data
public class OrderApproveRequest {

    /** true=通过，false=驳回 */
    @NotNull(message = "请选择审批结果")
    private Boolean approved;

    /** 审批意见；驳回时必填 */
    @Size(max = 500, message = "审批意见长度不能超过 500 个字符")
    private String comment;

    /**
     * 指定下一节点的审批人（，仅当下一审批节点使用「上一节点审批人指定」时必填）。
     *
     * <h2>为什么由上一节点传、而不是下游自选</h2>
     * <p>业务上「这一步该谁审」往往要由上一环节的人拍板（例如部门主管审完，指定由谁复核），
     * 而这个信息在提交那一刻并不存在，所以只能由上游在通过时给出。
     *
     * <h2>服务端必须强校验</h2>
     * <p>请求体完全由客户端可控，因此人数是否与节点配置（{@code PREV_ASSIGN.assignCount}）一致、
     * 被指定人是否在职启用、是否误指派了申请人本人，全部在服务端重新算一遍；
     * 违反任一 → 400。**若上游在没有待指派节点时也传了本字段，同样 400** ——
     * 静默忽略会让上游以为指派成功，而工单实际会卡在「无人可审」。
     */
    private List<Long> nextApproverIds;
}
