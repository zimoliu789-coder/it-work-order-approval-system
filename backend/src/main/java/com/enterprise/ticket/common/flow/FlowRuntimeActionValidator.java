package com.enterprise.ticket.common.flow;

import java.util.Map;

/**
 * 运行期动作校验（ ·  · W4-A1，自 {@link FlowDefinitionValidator} 拆出）。
 *
 * <h2>职责</h2>
 * <p>校验节点的 {@code onReject}（驳回改道）与 {@code onTimeout}（超时升级：加签 / 改道）配置，
 * 包括动作类型合法性、改道目标节点存在且类型合适、加签阈值区间。
 *
 * <h2>为什么「不认识的 action」必须报错而不是当默认值</h2>
 * <p>把 {@code action} 写成 {@code "GOT"} 这类拼写错误若被静默当作 TERMINATE / NOTIFY，
 * 配置者会以为改道生效了 —— 而实际每笔驳回都在终止整单。这类"看起来在工作"的错配
 * 比直接报错危险得多，因此这里连拼写错误一起拦。
 *
 * <h2>目标节点为什么必须"能到 END"</h2>
 * <p>改道目标本身已在图段的 {@code assertAllLeadToEnd} 覆盖范围内（该检查遍历所有节点），
 * 这里额外确认目标<b>存在且是审批或抄送节点</b>就够 —— 指向 END 或 CONDITION
 * 会让工单在改道后立刻终止或再次分叉，都不是"改道"的语义。
 */
final class FlowRuntimeActionValidator {

    private FlowRuntimeActionValidator() {
    }

    static void validate(FlowNode node, Map<String, FlowNode> index, String where,
                         FlowProblemCollector collector, boolean runtimeFlow) {
        if (node.getOnReject() != null) {
            if (!node.getOnReject().isValid()) {
                collector.add(where + "的「驳回处理」配置不完整：改道（GOTO）必须指定已存在的目标节点");
            } else if (node.getOnReject().isGoto()) {
                checkTarget(node.getOnReject().getTarget(), index, where + "的「驳回改道」", collector);
            }
            if (node.getOnReject().isGoto() && !runtimeFlow) {
                // 兜底：理论上 hasRuntimeFeature() 已把 isGoto 计入，这里只是纵深防御
                collector.add(where + "配置了驳回改道，但流程未识别为运行期流程");
            }
        }
        if (node.getOnTimeout() != null) {
            if (!node.getOnTimeout().isValid()) {
                collector.add(where + "的「超时处理」配置不合法："
                        + "加签/改道需有效目标，超时阈值须为正数");
            } else if (node.getOnTimeout().isGoto()) {
                checkTarget(node.getOnTimeout().getTarget(), index, where + "的「超时改道」", collector);
            }
            if (node.getOnTimeout().isAddSign()) {
                if (node.getOnTimeout().getAfterHours() == null) {
                    collector.add(where + "的「超时加签」未设置超时阈值（小时）");
                }
                if (node.getOnTimeout().getAfterHours() != null
                        && node.getOnTimeout().getAfterHours() > FlowValidationLimits.MAX_TIME_LIMIT_HOURS) {
                    collector.add(where + "的「超时加签」阈值超出上限 "
                            + FlowValidationLimits.MAX_TIME_LIMIT_HOURS + " 小时");
                }
            }
            if (!node.getOnTimeout().isNotifyOnly() && !runtimeFlow) {
                collector.add(where + "配置了超时升级动作，但流程未识别为运行期流程");
            }
        }
    }

    /** 动作目标节点必须存在、且是审批或抄送节点 */
    private static void checkTarget(String target, Map<String, FlowNode> index,
                                    String what, FlowProblemCollector collector) {
        FlowNode targetNode = index.get(target);
        if (targetNode == null) {
            collector.add(what + "的目标节点不存在：" + target);
            return;
        }
        FlowNodeType type = targetNode.typeEnum();
        if (type != FlowNodeType.APPROVAL && type != FlowNodeType.CC) {
            collector.add(what + "的目标必须是审批或抄送节点，而「" + target + "」是"
                    + (type == null ? "未知类型" : type.getLabel()));
        }
    }
}
