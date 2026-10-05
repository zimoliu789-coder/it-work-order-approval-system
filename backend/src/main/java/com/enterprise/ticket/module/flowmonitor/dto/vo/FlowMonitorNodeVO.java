package com.enterprise.ticket.module.flowmonitor.dto.vo;

import lombok.Data;

/**
 * 流程监控 · 节点维度明细（ · M7）
 *
 * <p>一行 = 某个模板下某个审批节点。同时被两处复用：
 * <ol>
 *   <li>{@code GET /api/flow-monitor/flows/{id}/nodes} 的明细表；</li>
 *   <li>模板汇总行里的 {@code bottleneckByDuration} / {@code bottleneckByOverdue} ——
 *       服务层直接从同一份明细里挑出来的 top1。两者共用同一个形状是有意的：
 *       前端翻到明细页时，看到的就是汇总页那一行的放大版，不需要为"瓶颈"单独写一套渲染。</li>
 * </ol>
 *
 * <h2>聚合口径（读数字前必须知道的三件事）</h2>
 * <ol>
 *   <li><b>按「审批人轮次」计数，不是按节点实例。</b>会签（ALL_SIGN）一个节点在一张单里
 *       会落多行，每行算一次样本。这是刻意的：会签的意义就是"每个人都得看一遍"，
 *       瓶颈常常正是某几个人的慢，按节点去重会把这件事抹平。</li>
 *   <li><b>跨版本聚合。</b>同一模板的 v1 / v2 会合并到同一个 nodeKey 上。若两版把某个 key
 *       指向了不同的节点，{@code nodeName} 会让它们分开成两行（名字是提交时的定义快照）；
 *       只有"key 与名字都相同"时才会真合并，那通常也正是管理员想看的同一个节点。</li>
 *   <li><b>只统计已决策的行</b>（APPROVED / REJECTED）。SKIPPED / INACTIVE / PENDING
 *       没有 {@code action_time}，天然不进来 —— 它们不是"耗时样本"。</li>
 * </ol>
 *
 * <h2>为什么耗时起点用 {@code COALESCE(activated_at, created_at)}</h2>
 * <p>{@code activated_at} 是 M2 引入的「被激活为 PENDING 的时刻」，它的存在意义正是让
 * <b>动态节点的耗时也能算</b>：加签节点、运行期才激活的分支节点，如果拿工单提交时间当起点，
 * 会把"排队等前一步"的时间也算成自己的耗时（甚至算出"提交前就完成"的负数）。
 * 存量行与第二期路径没有该值，回落到 {@code created_at} 是正确的 ——
 * 那些单的节点本来就是提交时一次性全部 PENDING 的，提交时刻就是它们的起跑线。
 */
@Data
public class FlowMonitorNodeVO {

    /** 所属流程模板 id（0 = 未归属桶，见 {@link FlowMonitorFlowVO}） */
    private Long flowId;

    /** 流程节点稳定标识；超时加签行已折回父节点（见 {@code ADDSIGN_KEY_MARKER}） */
    private String nodeKey;

    /** 节点名（提交时固化的定义快照，不是当前定义里的名字） */
    private String nodeName;

    /** 节点类型：APPROVAL / CC；传统分组审批路径为 null（历史行没有该列取值） */
    private String nodeType;

    /** 样本数 = 已决策的审批人轮次 */
    private long sampleCount;

    /** 平均耗时（小时，保留 2 位小数）；无样本时为 null */
    private Double avgHours;

    /** 超时轮次数（{@code action_time > deadline_at}） */
    private long overdueCount;

    /**
     * 超时率的分母：**有时限**的已决策轮次数。
     *
     * <p>与 {@link #sampleCount} 不是同一个数，必须分开返回：不限时的节点（时限列配置为空）
     * 无法"超时"，把它们算进分母会把所有超时率都稀释成接近 0，指标立刻失去意义。
     * 界面要能把「12 / 15 有时限」这样显示出来，数字才可被质疑、被解释。
     */
    private long withDeadlineCount;

    /**
     * 超时率（百分比，保留 2 位小数）；分母为 0 时为 {@code null}。
     *
     * <p>返回 null 而不是 0：0% 的意思是"从未超时"，null 的意思是"无法判定"。
     * 这两件事在管理上是相反的结论（前者可以放心，后者说明这个节点根本没设时限），
     * 合并成 0 会让人误以为流程很健康。
     */
    private Double overdueRate;

    /**
     * 运行期才被激活的轮次数（{@code activated_at} 明显晚于工单提交时刻）。
     *
     * <p>这是 M7 依赖 M2 的那个点：它把「静态骨架节点」与「运行期动态激活的节点」区分开，
     * 让"这个节点为什么会慢"有第二个可查的解释 —— 有些节点的耗时天然长，
     * 不是审批人慢，而是它本就要等前面的运行期条件判定完才被激活。
     */
    private long runtimeActivatedCount;
}
