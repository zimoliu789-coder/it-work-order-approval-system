package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.form.FormSchema;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第二段：逐节点校验（ ·  · W4-A1，自 {@link FlowDefinitionValidator} 拆出）。
 *
 * <h2>职责</h2>
 * <p>按节点类型分派四套规则（APPROVAL / CC / CONDITION / END），并承载三类跨节点的节点级约束：
 * <ul>
 *   <li><b>审批人规则</b>：复用 {@link ApproverRuleValidator}（首错即抛）逐条调用并转成"收集"；</li>
 *   <li><b>业务域禁用规则</b>（M1）：借用域禁 {@code FORM_USER_FIELD} 与 {@code APPLICANT_CHOOSE}；</li>
 *   <li><b>上一节点指定的节点内硬约束</b>：唯一规则、不得与会签组合。</li>
 * </ul>
 *
 * <h2>为什么"复用 + 捕获"而不是改造 ApproverRuleValidator</h2>
 * <p>{@code ApproverRuleValidator#validate} 是首错即抛的既有实现（ 起就在用，
 * 发布路径依赖它）。这里用「逐条调用 + 捕获」的方式适配，避免为了收集模式去改动它，
 * 从而保证既有行为完全不变；代价是同一节点多条规则时需要一个字段一个字段地判。
 *
 * <h2>检查次序是本段的对外表现</h2>
 * <p>段内顺序为：名称 → 类型分派（分支 / 审批人 / 指派 / 域禁用 / 规则 / next / 签署 / 时限 /
 * 运行期特性 / 动作）。发布路径只抛第一条，所以次序一变，用户看到的提示就变 ——
 * 由 {@code FlowValidatorProblemOrderTest} 逐字锁定。
 */
final class FlowNodeValidator {

    private FlowNodeValidator() {
    }

    static void validate(FlowNode node, Map<String, FlowNode> index,
                         FormSchema schema, FlowProblemCollector collector, FlowScope scope,
                         boolean runtimeFlow) {
        if (node == null) {
            return;
        }
        FlowNodeType type = FlowNodeType.of(node.getType());
        if (type == null) {
            // 类型非法已在结构段记录，无法按类型分派
            return;
        }
        String where = "节点「" + node.getKey() + "」";
        if (!StringUtils.hasText(node.getName())) {
            collector.add(where + "未设置名称");
        } else if (node.getName().length() > FlowValidationLimits.MAX_NAME_LENGTH) {
            collector.add(where + "名称过长（最多 " + FlowValidationLimits.MAX_NAME_LENGTH + " 字）");
        }

        switch (type) {
            case APPROVAL -> {
                if (!node.getBranches().isEmpty()) {
                    collector.add(where + "是审批节点，不应配置分支");
                }
                if (node.getApproverRules().isEmpty()) {
                    collector.add(where + "未配置任何审批人");
                }
                long chooseCount = node.getApproverRules().stream()
                        .filter(rule -> rule != null
                                && ApproverRuleType.of(rule.getType()) == ApproverRuleType.APPLICANT_CHOOSE)
                        .count();
                if (chooseCount > 1) {
                    // 「申请人自选」的结果要按 nodeKey 回传给服务端，一个节点若有多条自选规则，
                    // 回传值就无法区分归属哪一条。与其在提交端发明一套合并语义（并集？人数相加？），
                    // 不如在设计端直接禁止 —— 合成一条规则即可表达同样的意图。
                    collector.add(where + "配置了 " + chooseCount + " 条「申请人自选」规则，最多只能有 1 条");
                }
                validatePrevAssign(node, where, collector);
                validateScopeForbiddenRules(node, where, scope, collector);
                int ruleIndex = 0;
                for (ApproverRule rule : node.getApproverRules()) {
                    collectRuleProblems(rule, ruleIndex++, node.getName(), schema, collector, scope);
                }
                requireNext(node, index, where, collector);
                if (StringUtils.hasText(node.getSignType()) && !SignType.isValid(node.getSignType())) {
                    collector.add(where + "的签署方式不合法：" + node.getSignType());
                }
                if (node.getTimeLimitHours() != null
                        && (node.getTimeLimitHours() < 1
                        || node.getTimeLimitHours() > FlowValidationLimits.MAX_TIME_LIMIT_HOURS)) {
                    collector.add(where + "的审批时限必须在 1.." + FlowValidationLimits.MAX_TIME_LIMIT_HOURS
                            + " 小时之间（当前 " + node.getTimeLimitHours() + "）");
                }
                if (runtimeFlow && chooseCount > 0) {
                    // 含运行期条件的流程里，节点可能在审批过程中才被激活（提交时是 INACTIVE），
                    // 而「申请人自选」的结果**不会随工单保存** —— 运行期激活时无从得知申请人当年选的是谁，
                    // 只能抛错或静默选错人。与其留一个运行期才炸的坑，不如在发布期就拦住。
                    collector.add(where + "配置了「申请人自选」——含运行期条件的流程不支持它："
                            + "自选结果不随工单保存，运行期激活该节点时无法复现申请人的选择");
                }
                FlowRuntimeActionValidator.validate(node, index, where, collector, runtimeFlow);
            }
            case CC -> {
                for (ApproverRule rule : node.getApproverRules()) {
                    ApproverRuleType ruleType = rule == null ? null : ApproverRuleType.of(rule.getType());
                    if (ruleType == ApproverRuleType.APPLICANT_CHOOSE
                            || ruleType == ApproverRuleType.PREV_ASSIGN) {
                        // 抄送是"提交即知会"，对象必须在提交那一刻就能算出来；
                        // 「申请人自选」与「上一节点指定」都把定人推到了提交之后，语义上矛盾。
                        collector.add(where + "是抄送节点，不支持「" + ruleType.getLabel()
                                + "」——抄送对象必须在提交时即可确定");
                    }
                }
                if (!node.getBranches().isEmpty()) {
                    collector.add(where + "是抄送节点，不应配置分支");
                }
                if (StringUtils.hasText(node.getSignType())) {
                    collector.add(where + "是抄送节点，不应配置签署方式");
                }
                if (node.getTimeLimitHours() != null) {
                    collector.add(where + "是抄送节点，不应配置审批时限");
                }
                if (node.getApproverRules().isEmpty()) {
                    collector.add(where + "未配置任何抄送对象");
                }
                int ruleIndex = 0;
                for (ApproverRule rule : node.getApproverRules()) {
                    collectRuleProblems(rule, ruleIndex++, node.getName(), schema, collector, scope);
                }
                requireNext(node, index, where, collector);
            }
            case CONDITION -> {
                if (StringUtils.hasText(node.getNext())) {
                    collector.add(where + "是条件分支节点，出口写在分支里，不应额外配置 next");
                }
                if (!node.getApproverRules().isEmpty()) {
                    collector.add(where + "是条件分支节点，不应配置审批人");
                }
                List<FlowBranch> branches = node.getBranches();
                if (branches.isEmpty()) {
                    collector.add(where + "未配置任何分支");
                }
                long elseCount = branches.stream().filter(b -> b != null && b.isElse()).count();
                if (elseCount != 1) {
                    collector.add(where + "必须且只能有一个默认出口（当前 " + elseCount + " 个）");
                }
                Set<String> branchKeys = new HashSet<>();
                int branchIndex = 0;
                for (FlowBranch branch : branches) {
                    String branchWhere = where + "的第 " + (++branchIndex) + " 个分支";
                    if (branch == null) {
                        collector.add(branchWhere + "为空");
                        continue;
                    }
                    if (!StringUtils.hasText(branch.getKey())) {
                        collector.add(branchWhere + "未设置标识（key）");
                    } else if (!branchKeys.add(branch.getKey())) {
                        collector.add(branchWhere + "标识重复：" + branch.getKey());
                    }
                    if (!StringUtils.hasText(branch.getName())) {
                        collector.add(branchWhere + "未设置名称");
                    }
                    if (!StringUtils.hasText(branch.getNext())) {
                        collector.add(branchWhere + "未指定去向节点");
                    } else if (!index.containsKey(branch.getNext())) {
                        collector.add(branchWhere + "指向的节点不存在：" + branch.getNext());
                    }
                    // 默认出口无需条件；带条件的出口必须校验条件
                    if (!branch.isElse()) {
                        FlowConditionValidator.validate(branch.getCondition(), branchWhere, schema, collector);
                    }
                }
            }
            case END -> {
                if (StringUtils.hasText(node.getNext())) {
                    collector.add(where + "是结束节点，不应配置 next");
                }
                if (!node.getBranches().isEmpty()) {
                    collector.add(where + "是结束节点，不应配置分支");
                }
                if (!node.getApproverRules().isEmpty()) {
                    collector.add(where + "是结束节点，不应配置审批人");
                }
            }
        }
    }

    /**
     * 复用 {@link ApproverRuleValidator} 的规则校验，但把「抛异常」转成「收集问题」。
     *
     * <p>理由见类注释。注意 null 规则也要走一遍：{@code ApproverRuleValidator} 内部对 null
     * 有明确分支（它知道该怎么描述"这里少了一条规则"），跳过它反而会让问题静默消失。
     */
    private static void collectRuleProblems(ApproverRule rule, int index, String nodeName,
                                            FormSchema schema, FlowProblemCollector collector, FlowScope scope) {
        try {
            ApproverRuleValidator.validate(rule, index, nodeName, schema, scope);
        } catch (com.enterprise.ticket.common.exception.BusinessException e) {
            collector.add(e.getMessage());
        }
    }

    /**
     * 业务域特有的禁用规则（M1）。
     *
     * <p>{@link FlowScope#BORROW} 下禁用两类审批人规则，理由是它们**都依赖提交端具备某种交互能力**，
     * 而借用提交页没有：
     * <ul>
     *   <li>{@code FORM_USER_FIELD}（表单人员字段）—— 借用单没有动态表单，字段无从引用；</li>
     *   <li>{@code APPLICANT_CHOOSE}（申请人自选审批人）—— 借用提交页没有选人器，
     *       配了申请人也无法指定，只会在提交时莫名失败或落到超管兜底，让用户困惑。</li>
     * </ul>
     *
     * <p>之所以在**发布时**就拦下而不是等提交时兜底：配置错误的代价不对称 ——
     * 发放时拦住只是让管理员改一下，放过则要等到有人提交时才发现，而那时流程已被引用、工单已产生。
     * 待将来借用提交页做了选人器，把这里对应的规则移除即可放开。
     *
     * <p>注意：本检查与 {@link ApproverRuleValidator} 的参数完备性校验**正交** ——
     * 那条规则本身是合法的（在自定义域下可用），只是在这个业务域下不被支持。
     */
    private static void validateScopeForbiddenRules(FlowNode node, String where,
                                                    FlowScope scope, FlowProblemCollector collector) {
        // 禁用集从 FlowScope 读（ · W4-D / C8）：此前这里内联硬编码了两个枚举，
        // 而设计器另有一份副本 —— 两处漂移会造出「设计器允许配、后端发布拒绝」最难排查的组合。
        // 现在校验与元数据接口读同一份声明，前端不再维护自己的副本。
        List<ApproverRuleType> forbidden = scope.forbiddenRuleTypes();
        if (forbidden.isEmpty()) {
            return;
        }
        for (ApproverRule rule : node.getApproverRules()) {
            ApproverRuleType ruleType = rule == null ? null : ApproverRuleType.of(rule.getType());
            if (ruleType != null && forbidden.contains(ruleType)) {
                collector.add(where + "是" + scope.getLabel() + "审批节点，不支持「" + ruleType.getLabel()
                        + "」——" + forbiddenHint(scope) + "，请改用其它审批人来源");
            }
        }
    }

    /**
     * 禁用规则的**补充说明文案**（只影响提示文案，不影响判定）。
     *
     * <p>文案与 {@code FlowScope#forbiddenRuleTypes()} 分开的理由：判定集是**事实**（必须单一来源），
     * 而这句话是**面向管理员的解释**（"为什么不能用"），随业务域语义变化。
     * 目前只有 {@link FlowScope#BORROW} 有禁用集，故默认分支实际不可达；
     * 将来若新增第二个有禁用集的域，只需在这里补一句更贴切的说明。
     */
    private static String forbiddenHint(FlowScope scope) {
        return scope == FlowScope.BORROW
                ? "借用提交页没有表单人员字段/选人器"
                : "该业务域不支持此类审批人来源";
    }

    /**
     * 「上一节点审批人指定」的节点内硬约束。
     *
     * <p>三条：① 必须是该节点**唯一**规则（人员在提交时未知，与其它规则取并集没有意义）；
     * ② **不得与会签组合**（语义是"指定若干人任一人审"，会签要求确定的一群人）；
     * ③ 「前面必须有审批节点」属于图性质，放在 {@link FlowGraphValidator} 里判。
     */
    private static void validatePrevAssign(FlowNode node, String where, FlowProblemCollector collector) {
        long prevAssignCount = node.getApproverRules().stream()
                .filter(rule -> rule != null
                        && ApproverRuleType.of(rule.getType()) == ApproverRuleType.PREV_ASSIGN)
                .count();
        if (prevAssignCount == 0) {
            return;
        }
        if (prevAssignCount > 1) {
            collector.add(where + "配置了 " + prevAssignCount + " 条「上一节点审批人指定」规则，最多只能有 1 条");
            return;
        }
        if (node.getApproverRules().size() > 1) {
            collector.add(where + "使用了「上一节点审批人指定」，它必须是该节点唯一的审批人规则"
                    + "（人员提交时未知，与其他规则取并集没有意义）");
            return;
        }
        if (SignType.ALL_SIGN.equals(SignType.normalize(node.getSignType()))) {
            collector.add(where + "使用了「上一节点审批人指定」，只支持或签，不能与会签组合");
        }
    }

    private static void requireNext(FlowNode node, Map<String, FlowNode> index,
                                    String where, FlowProblemCollector collector) {
        if (!StringUtils.hasText(node.getNext())) {
            collector.add(where + "未指定下一节点");
            return;
        }
        if (!index.containsKey(node.getNext())) {
            collector.add(where + "的下一节点不存在：" + node.getNext());
        }
    }
}
