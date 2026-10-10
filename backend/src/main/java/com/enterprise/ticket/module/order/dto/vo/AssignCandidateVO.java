package com.enterprise.ticket.module.order.dto.vo;

import com.enterprise.ticket.module.user.dto.UserOptionVO;
import lombok.Data;

import java.util.List;

/**
 * 「上一节点指定审批人」的候选项与原因说明。
 *
 * <h2>为什么要有这个接口，而不是继续用 {@code GET /api/users/options}</h2>
 * <p>改造前审批页拉候选用的是员工下拉接口。那个接口挂的是 {@code staff:view} 权限 ——
 * 也就是说<b>除了管理员，谁都拉不到候选</b>：直属主管、IT主管点「通过」时，
 * 请求被 403，前端 {@code catch} 后把候选置空，于是「必须指定 1 人才能通过」
 * 变成一句永远无法满足的前置条件，工单静默卡死。
 *
 * <p>本接口把三件事一次说清，避免前端东拼西凑：
 * <ol>
 *   <li><b>要指定几个人</b>（{@link #requiredCount}）；</li>
 *   <li><b>能从哪些人里指</b>（{@link #candidates}）；</li>
 *   <li><b>为什么只有这些人</b>（{@link #assignScopeLabel}）—— 一份没有解释的短名单
 *       会被当成"系统坏了，人少了"，而不是"这个节点就是只允许 IT执行人"。</li>
 * </ol>
 *
 * <h2>与详情接口的关系</h2>
 * <p>{@code OrderDetailVO.nodes} 已经带 {@code pendingAssign} 与 {@code assignScope} 标记
 * （见 {@code OrderViewAssembler#toNodeView}），本 VO 里的范围字段与它同源
 * （都取 {@code ApproverRuleType.AssignScope}），只是把「候选名单」这部分补全。
 * 前端因此可以只用这一个接口完成指派 UI，不必先拉详情再拉候选人。
 *
 * <p>权限：与工单详情同口径（申请人 / 审批人 / 执行人 / super_admin / admin）。
 * 候选名单本身不构成额外信息泄露 —— 它的上限就是「IT执行人角色 ∪ IT运维组成员」，
 * 而真正的写入校验在 {@code OrderServiceImpl#applyNextStepAssignment}（服务端权威）。
 */
@Data
public class AssignCandidateVO {

    /**
     * 需要指定的人数；{@code 0} 表示本单当前步骤之后<b>没有</b>待指派节点。
     *
     * <p>为 0 时前端必须<b>隐藏</b>选择器（而不是渲染一个空的必填项）。
     */
    private Integer requiredCount;

    /** 待指派节点的流程 key（{@code order_approval_node.node_key}）；无待指派时为 null */
    private String nodeKey;

    /** 待指派节点的名称（如「IT执行人处理」）；无待指派时为 null */
    private String nodeName;

    /** 该节点所在的步骤号（{@code step_order}）；无待指派时为 null */
    private Integer stepOrder;

    /**
     * 指派范围代码（{@code ALL} / {@code IT_EXECUTOR}）；无待指派时为 null。
     *
     * <p>取值来自该节点 {@code PREV_ASSIGN} 规则的 {@code assignScope} 参数。
     */
    private String assignScope;

    /**
     * 指派范围的中文说明，直接用于界面提示。
     *
     * <p>与 {@code ApprovalNodeView.assignScopeLabel} 同源（都取枚举自身标签），
     * 避免详情里写「不限制」而选择器里写「全部员工」这类两套说法。
     */
    private String assignScopeLabel;

    /**
     * 是否限定了范围。
     *
     * <p>{@code false} 表示"不限制"（存量流程与缺省参数的情形），此时
     * {@link #candidates} 是全部在职启用员工。单列一个布尔量而不是让前端
     * 去比 {@code assignScope == 'ALL'}：约定"缺省即 ALL"这件事只应有一处判据。
     */
    private Boolean restricted;

    /**
     * 候选人员（在职启用，<b>已排除申请人本人</b>）。
     *
     * <p>排除申请人是把服务端已有的「自审回避」提前到选择阶段 ——
     * 否则用户会选到一个提交时必被拒的人。真正的拦截仍在服务端。
     */
    private List<UserOptionVO> candidates;
}
