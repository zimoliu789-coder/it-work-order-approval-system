package com.enterprise.ticket.common.flow;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一段：定义级结构校验（ ·  · W4-A1，自 {@link FlowDefinitionValidator} 拆出）。
 *
 * <h2>职责</h2>
 * <p>检查"定义作为一个整体"的最基本前提，并据此建立 {@code key → 节点} 索引：
 * <ol>
 *   <li>定义不为空、节点列表非空、节点数不超上限；</li>
 *   <li>逐节点检查标识（非空 / 格式 / 唯一）与类型合法性；</li>
 *   <li>{@code start} 已设置且存在。</li>
 * </ol>
 *
 * <h2>为什么它必须先跑，且失败后整段收口</h2>
 * <p>第二段（逐节点）与第三段（图）都以 {@code index} 为前提：图遍历要找邻居、
 * 分支要验证去向节点存在。若 index 里有重复 key、空 key 或未识别类型，
 * 后续两段的每个检查都会各自报一次"找不到节点"——<b>错误数量会按节点数平方级膨胀</b>，
 * 而真正的病根（一行结构错误）反被淹没。
 *
 * <p>因此本段返回一个 {@link Result}：不能安全继续时 {@code proceed=false}，
 * 由门面直接收口。这不是"少报了几个问题"，而是刻意把级联噪音挡在门外。
 *
 * <h2>三个 return 点的顺序不可调换</h2>
 * <p>{@code start} 的两项检查排在 {@code structureOk} 之前：一个既结构错乱、又没设起始节点的
 * 定义，应当先被告知"未设置起始节点"（这是一眼可见的手误），再回头处理结构问题。
 * 顺序锁由 {@code FlowValidatorProblemOrderTest} 覆盖。
 */
final class FlowStructureValidator {

    private FlowStructureValidator() {
    }

    /**
     * 结构段结论。
     *
     * @param proceed 是否足以支撑「逐节点」「图」两段检查
     * @param index   节点索引（仅 {@code proceed=true} 时非 null 且可信）
     */
    record Result(boolean proceed, Map<String, FlowNode> index) {

        static Result stop() {
            return new Result(false, null);
        }
    }

    static Result validate(FlowDefinition definition, FlowProblemCollector collector) {
        if (definition == null) {
            collector.add("流程定义为空");
            return Result.stop();
        }
        List<FlowNode> nodes = definition.getNodes();
        if (nodes.isEmpty()) {
            collector.add("流程至少要有一个节点");
            return Result.stop();
        }
        if (nodes.size() > FlowValidationLimits.MAX_NODES) {
            collector.add("节点数量（" + nodes.size() + "）超过上限 "
                    + FlowValidationLimits.MAX_NODES);
            return Result.stop();
        }

        Map<String, FlowNode> index = new LinkedHashMap<>();
        boolean structureOk = true;
        for (FlowNode node : nodes) {
            if (node == null) {
                collector.add("存在空节点");
                structureOk = false;
                continue;
            }
            String key = node.getKey();
            if (!StringUtils.hasText(key)) {
                collector.add("存在未设置标识（key）的节点");
                structureOk = false;
                continue;
            }
            if (!FlowValidationLimits.KEY_PATTERN.matcher(key).matches()) {
                collector.add("节点标识不合法：" + key + "（字母开头，仅字母/数字/下划线，最长 64）");
                structureOk = false;
                continue;
            }
            if (index.putIfAbsent(key, node) != null) {
                collector.add("节点标识重复：" + key);
                structureOk = false;
                continue;
            }
            if (FlowNodeType.of(node.getType()) == null) {
                collector.add("节点「" + key + "」的类型不合法：" + node.getType());
                structureOk = false;
            }
        }

        if (!StringUtils.hasText(definition.getStart())) {
            collector.add("未设置起始节点");
            return Result.stop();
        }
        if (!index.containsKey(definition.getStart())) {
            collector.add("起始节点不存在：" + definition.getStart());
            return Result.stop();
        }
        if (!structureOk) {
            // 节点索引不可信，图级检查无法安全执行
            return Result.stop();
        }
        return new Result(true, index);
    }
}
