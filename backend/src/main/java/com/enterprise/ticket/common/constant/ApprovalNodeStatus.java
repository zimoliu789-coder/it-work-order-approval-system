package com.enterprise.ticket.common.constant;

/**
 * 审批快照节点状态
 *
 * <p>取值与规范完全一致：{@code PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED}。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #PENDING} —— 待审批，等待该节点审批人处理；</li>
 *   <li>{@link #APPROVED} —— 该审批人已通过；</li>
 *   <li>{@link #REJECTED} —— 该审批人驳回（会签/或签任一节点驳回即整单驳回，）；</li>
 *   <li>{@link #SKIPPED} —— 被跳过，两种来源：① 审批人 = 申请人且存在其他审批人；
 *       ② 或签模式下同一步骤的其他节点已有人先通过（「第一个通过生效」）；</li>
 *   <li>{@link #CANCELLED} —— 整单驳回/撤回后，其余未完成节点自动作废；</li>
 *   <li>{@link #CC_NOTIFIED} —— 抄送节点的终态，见其枚举值说明；</li>
 *   <li>{@link #INACTIVE} —— 未激活，见其枚举值说明。</li>
 * </ul>
 */
public enum ApprovalNodeStatus {

    PENDING("待审批"),
    APPROVED("已通过"),
    REJECTED("已驳回"),
    SKIPPED("已跳过"),
    CANCELLED("已作废"),

    /**
     * 已抄送：抄送节点的终态。
     *
     * <p>{@link #isFinished()} 天然为 true，因此抄送节点不会进入
     * 「最小含 PENDING 的步骤」判定 —— 这正是抄送「不阻塞、不参与推进」的实现方式，
     * 引擎不需要为此加任何分支。
     */
    CC_NOTIFIED("已抄送"),

    /**
     * 未激活：骨架里有它，但**尚未判定是否走**。
     *
     * <h2>与 SKIPPED 的本质区别（不可合并）</h2>
     * <ul>
     *   <li>{@link #SKIPPED} 是<b>结论</b>：已经确定不会走（条件未命中 / 或签他人先通过 /
     *       审批人=申请人）。它不会再变成 PENDING。</li>
     *   <li>{@link #INACTIVE} 是<b>待定</b>：条件引用了审批过程中才产生的数据
     *       （{@code process.prevNodeResult} 等），提交时判定不了，将来可能被激活为 PENDING。</li>
     * </ul>
     * <p>把两者合成一个状态是不可行的：详情页要能解释「这步为什么没走」，
     * 而"还不知道"和"确定不走"是两种不同的解释，合并后这个解释就永久丢失了。
     *
     * <h2>它为什么不算终态</h2>
     * <p>{@link #isFinished()} 对 INACTIVE 返回 <b>false</b>：一个还没判定的节点
     * 显然不能算"已完成"。这正是「全 INACTIVE」不会被误判为「流程走完」的原因 ——
     * 结合 {@code OrderServiceImpl} 的三段式终态判定，
     * 「无 PENDING 但有 INACTIVE」会先触发一次重算，而不是直接进终态。
     *
     * <h2>零回归的关键</h2>
     * <p>总开关 {@code flow_runtime_condition_enabled} 默认关闭时，
     * <b>任何代码路径都不会写入此取值</b>。在 INACTIVE 永不出现的前提下，
     * {@code this != PENDING && this != INACTIVE} 与 {@code this != PENDING}
     * 的取值完全相同 —— 因此既有回归在开关关闭时必然全绿。
     * 这不是"希望它绿"，而是可枚举的不变式。
     */
    INACTIVE("未激活");

    private final String label;

    ApprovalNodeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否已完成（终态），用于「是否还有未处理节点」的判定。
     *
     * <p> 起，{@link #INACTIVE} 同样<b>不算完成</b>：
     * 它只是"提交时判定不了"的占位，运行期可能被激活为 PENDING。
     * 若把它算作已完成，「无 PENDING 但有 INACTIVE」就会被误判为"流程走完"，
     * 工单会在还有节点可能要走的情况下直接进终态。
     *
     * <p>注意这是本波<b>唯一触碰既有语义</b>的一行；开关关闭时 INACTIVE 不会出现，
     * 两种写法的取值完全相同（见 {@link #INACTIVE} 的零回归论证）。
     */
    public boolean isFinished() {
        return this != PENDING && this != INACTIVE;
    }

    /** 是否「尚有可能需要处理」（待审批或待激活）：终态判定用 */
    public boolean isOpen() {
        return this == PENDING || this == INACTIVE;
    }

    public static ApprovalNodeStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (ApprovalNodeStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ApprovalNodeStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
