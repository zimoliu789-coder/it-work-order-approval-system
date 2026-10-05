package com.enterprise.ticket.common.flow;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 流程节点。
 *
 * <p>用"节点 + 显式 next/branches"表达流程，而不是"节点表 + 独立连线表"：
 * 连线在 JSON 里就是 {@code next} 与 {@code branches[].next} 两个字段，
 * 校验器（可达 / 无环 / 穷尽 / 可到 END）一次遍历就能判定完。
 *
 * <p>节点类型见 {@link FlowNodeType}：
 * <ul>
 *   <li>{@code APPROVAL}：用 {@code signType} + {@code approverRules} + {@code next}
 *       + 可选 {@code timeLimitHours}；</li>
 *   <li>{@code CC}：用 {@code approverRules} + {@code next}（无 signType / 无 branches）；</li>
 *   <li>{@code CONDITION}：用 {@code branches}（每支含条件与 next），**不用** {@code next}；</li>
 *   <li>{@code END}：三者皆无。</li>
 * </ul>
 */
@Data
public class FlowNode {

    /** 节点稳定标识（同一流程内唯一，英文/数字/下划线） */
    private String key;

    /** 节点类型，取值见 {@link FlowNodeType} */
    private String type;

    /** 节点名称（展示用，如"财务审批"） */
    private String name;

    // ---------------- APPROVAL ----------------

    /** 签署方式：ANY_SIGN 或签 / ALL_SIGN 会签，归一化见 {@code SignType.normalize} */
    private String signType;

    /** 审批人来源规则（多条取并集；抄送节点同样使用本字段） */
    private List<ApproverRule> approverRules = new ArrayList<>();

    /** 下一节点 key（APPROVAL / CC 必填） */
    private String next;

    /**
     * 审批时限（小时，）：null = 不限时。
     *
     * <p>提交时算出 {@code deadline_at} 快照到节点，超时提醒据此判定；
     * 只有 APPROVAL 节点有此语义，CC / CONDITION / END 配了也会被发布校验拒绝。
     */
    private Integer timeLimitHours;

    // ---------------- CONDITION ----------------

    /** 分支出口（CONDITION 必填，且恰有一个 else） */
    private List<FlowBranch> branches = new ArrayList<>();

    // ---------------- 运行期动作（，仅 APPROVAL 节点有意义） ----------------

    /**
     * 驳回动作。null 或未配置 → 默认 TERMINATE（与第二期一致）。
     *
     * <p>配置 GOTO 时需同时启用运行时条件总开关才能发布（见发布侧闸门）。
     */
    private RejectAction onReject;

    /**
     * 超时动作。null 或未配置 → 仅提醒（与第二期一致）。
     *
     * <p>配置 ADD_SIGN / GOTO 时需同时启用运行时条件总开关才能发布。
     */
    private TimeoutAction onTimeout;

    public List<ApproverRule> getApproverRules() {
        return approverRules == null ? new ArrayList<>() : approverRules;
    }

    public List<FlowBranch> getBranches() {
        return branches == null ? new ArrayList<>() : branches;
    }

    /**
     * 本节点是否携带「运行期特性」。
     *
     * <p>三类特性：驳回改道（onReject=GOTO）、超时升级（onTimeout=ADD_SIGN/GOTO）、
     * 条件引用 {@code process.*} 字段（在 {@link FlowDefinition#hasRuntimeFeature()} 里统一汇总）。
     *
     * <p>它被三处共用，且三处必须给出一致答案：发布闸门（开关关闭时拒绝发布）、
     * 提交物化（决定走"初始激活集"还是"一次性定格"）、校验器（决定用严格规则还是运行期规则）。
     * 把这个判定散成三份实现，就会出现"发布时算运行期、物化时算非运行期"这种最难查的裂缝。
     */
    public boolean hasRuntimeAction() {
        return (onReject != null && onReject.isGoto())
                || (onTimeout != null && (onTimeout.isAddSign() || onTimeout.isGoto()));
    }

    public FlowNodeType typeEnum() {
        return FlowNodeType.of(type);
    }
}
