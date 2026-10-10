package com.enterprise.ticket.module.flowmonitor.dto.vo;

import lombok.Data;

/**
 * 流程监控 · 模板维度汇总（ · M7）
 *
 * <p>一行 = 一个流程模板（{@code approval_flow}），回答「这个模板一共跑了多少单、平均审批多久、
 * 卡在哪个节点」。设计稿  的三个指标全在这里。
 *
 * <h2>「未归属」也是一个合法的行</h2>
 * <p>{@code flowId = }{@value com.enterprise.ticket.module.flowmonitor.service.FlowMonitorService#UNATTRIBUTED_FLOW_ID}
 * 表示这一行不是某个现存模板，而是"归不到任何模板"的工单集合：走分组固定审批人表 / 借用单内置流程的单
 * （本来就没有模板），以及本期上线前的存量单与模板已删除的历史单。
 *
 * <p>为什么把它们摆成一行而不是过滤掉：这套统计是给管理员看"流程跑得怎么样"的，
 * <b>被静默丢掉的数据会让人低估总量</b>；而摆成一行并明确标注「未归属」，
 * 管理员一眼就知道"还有 N 单没进模板统计"，需要时能去查为什么。
 *
 * <h2>为什么瓶颈给两个而不是一个</h2>
 * <p>设计稿明确「平均耗时最大」与「超时率最高」都要返回、由前端切换。二者经常不是同一个节点：
 * 一个节点可能单次都很快但偶尔严重超时（超时率高、平均不高），另一个可能每次都要等半天
 * 但从不越线（平均高、超时率为 0）。不同的问题要用不同的名字去问，
 * 所以后端不做取舍、把两个答案都端出来。
 */
@Data
public class FlowMonitorFlowVO {

    /**
     * 流程模板 id；{@code 0} 表示「未归属」桶（见类注释）。
     *
     * <p>用 0 而不是 null：前端要拿它去调
     * {@code GET /api/flow-monitor/flows/{flowId}/nodes} 下钻节点明细，
     * 路径里放不下 null。0 在 MySQL 自增主键里不可能出现，作为哨兵是安全的。
     */
    private Long flowId;

    /** 模板名；未归属桶或名字快照缺失时为服务层给的兜底文案 */
    private String flowName;

    /** 是否「未归属」桶（= {@code flowId == 0}）；给前端决定是否加标注与弱化样式 */
    private boolean unattributed;

    /**
     * 工单量 = 该模板下「冻结过流程定义」的工单数（去重到工单）。
     *
     * <p>口径按设计稿  决策 B：统计**所有** {@code approval_flow_json} 非空的单，
     * 含 M1 之后走自定义流程的借用单 —— 只统计 {@code order_type = 'CUSTOM'}
     * 会把借用单整块漏掉。
     */
    private long orderCount;

    /**
     * 已完成审批的工单数（= 平均审批时长的样本数）。
     *
     * <p>它与 {@link #orderCount} 的差额就是「还在审批中」的单。单独给出来是为了让
     * {@link #avgApprovalHours} 可被解读：样本 1 单的平均值与样本 50 单的平均值，
     * 可信度完全不是一回事，界面必须能显示分母。
     */
    private long approvedOrderCount;

    /**
     * 平均审批时长（小时，保留 2 位小数）；无样本时为 {@code null}。
     *
     * <p>口径：起点 = 工单提交时刻（{@code borrow_order.created_at}），
     * 终点 = 该工单**最后一个终态审批节点**（APPROVED / REJECTED）的操作时间。
     * 取"最后一个"而不是"第一个通过"，是因为用户感知的等待覆盖到审批真正结束为止；
     * 把 REJECTED 也算作终点，是因为被驳回的单同样走完了审批，排除掉会低估实际耗时。
     */
    private Double avgApprovalHours;

    /** 瓶颈节点（按平均耗时最大）。无任何可用节点样本时为 {@code null} */
    private FlowMonitorNodeVO bottleneckByDuration;

    /** 瓶颈节点（按超时率最高）。所有节点都没有时限、分不出超时率时为 {@code null} */
    private FlowMonitorNodeVO bottleneckByOverdue;

    /**
     * 组成本行的版本标签（形如 {@code "v1, v2"}）；未归属桶或版本行已删除时为 {@code null}。
     *
     * <p>存在的理由：同一个模板可以有多版，而节点聚合是**跨版本**做的
     * （见 {@link FlowMonitorNodeVO} 的说明）。管理员看到"模板 A 有 40 单"时，
     * 需要能立刻知道这 40 单其实是 v1 与 v2 两拨不同定义的工单，
     * 否则会把两版混在一起的平均值当成"当前这版的现状"来读。
     */
    private String versionLabels;
}
