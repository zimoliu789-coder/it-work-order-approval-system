package com.enterprise.ticket.common.flow;

/**
 * 流程节点的激活态。
 *
 * <p>它与 {@code ApprovalNodeStatus} 不是一回事，必须分清：
 * <ul>
 *   <li>本枚举是<b>求值阶段的分类</b> —— 「这条分支会不会走」，是 {@link FlowPathResolver} 的输出；</li>
 *   <li>{@code ApprovalNodeStatus} 是<b>落库后的状态</b> —— 物化到 order_approval_nodes 上。</li>
 * </ul>
 * 两者的映射是：{@code ACTIVE → PENDING}、{@code SKIPPED → SKIPPED}、{@code INACTIVE → INACTIVE}。
 *
 * <h2>为什么 ACTIVE 不直接叫 PENDING</h2>
 * <p>因为求值阶段还没有"待审批"这个含义：一个 ACTIVE 的抄送节点物化后是
 * {@code CC_NOTIFIED}（已抄送，终态），而不是 PENDING。用 ACTIVE 表达"这条边走"，
 * 把"落库成什么状态"留给物化那一层决定，两个概念就不会互相污染。
 *
 * <h2>大小关系（本枚举的隐式优先级）</h2>
 * <p>汇聚（多条分支合流到同一节点）时，同一个节点可能从不同的边得到不同的分类，
 * 此时必须有一个确定的取值规则：<b>ACTIVE &gt; INACTIVE &gt; SKIPPED</b>。
 * 直觉上就是：只要"会走"就一定会走；否则只要"还没定"就还有机会；只有确定不走才算跳过。
 */
public enum NodeActivation {

    /** 会走（命中路径）：物化为 PENDING / CC_NOTIFIED */
    ACTIVE,

    /** 还没定：条件依赖运行期数据，或位于尚未完成的前置节点之后。物化为 INACTIVE */
    INACTIVE,

    /** 确定不走：条件未命中 / 或签他人先通过 / 审批人=申请人。物化为 SKIPPED */
    SKIPPED;

    /** 汇聚时的择优：ACTIVE 优先，其次 INACTIVE，最后 SKIPPED */
    public NodeActivation stronger(NodeActivation other) {
        if (other == null) {
            return this;
        }
        return this.ordinal() < other.ordinal() ? this : other;
    }
}
