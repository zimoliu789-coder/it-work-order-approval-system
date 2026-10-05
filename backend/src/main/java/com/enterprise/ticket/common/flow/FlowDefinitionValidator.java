package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;

import java.util.List;
import java.util.Map;

/**
 * 流程定义校验器（ / M4a）——**发布时的唯一严格校验点**。
 *
 * <p>检查项（缺一不可）：
 * <ol>
 *   <li>节点标识唯一、格式合法；类型合法；名称非空；</li>
 *   <li>{@code start} 已设置且存在；</li>
 *   <li>每个 APPROVAL 节点：有审批人规则、有 next、签署方式合法、时限（如有）在 1..720；
 *       <b>若含 {@code PREV_ASSIGN}</b>：必须是唯一规则、不得与会签组合、且该节点前面必须已存在审批节点；</li>
 *   <li>每个 CC 节点：有抄送对象规则、有 next、不得配分支/签署方式/时限，
 *       且**不允许** {@code APPLICANT_CHOOSE} / {@code PREV_ASSIGN}（抄送对象必须提交时即可确定）；</li>
 *   <li>每个 CONDITION 节点：至少一个分支、**恰有一个默认出口**、各分支均有去向；</li>
 *   <li>条件规则：字段存在于所绑表单版本、类型与运算符匹配、需要比较值时已填值；</li>
 *   <li><b>无环</b>（DAG）；</li>
 *   <li>从 start 出发**所有节点可达**（无孤岛）；</li>
 *   <li>所有节点**都能到达 END**（无死路）；</li>
 *   <li>至少一个审批节点（全是条件/抄送/结束的流程没有意义）。</li>
 * </ol>
 *
 * <p>为什么第 7/8/9 项必须在这里做：流程定义是整体 JSON，数据库给不了"无环""可达""穷尽"
 * 这三类保证。一旦放过，运行时可能出现"走到一个永远到不了 END 的分支"——
 * 表现为工单卡在"待审批"再也推不动，且没有任何报错指向流程本身。
 * 发布时一次静态校验，就把这类问题挡在了产生数据之前。
 *
 * <h3>M4a：从「首错即抛」到「全量收集 + 兼容抛错」</h3>
 * <p>本类原先只在发现**第一个**问题时抛 {@link BusinessException}；前端镜像
 * （{@code validateFlowForPublish}）却返回**全部**问题数组，两侧对同一份定义给出的
 * 结论口径不一致（后端第 2 个问题前端可能永远看不到）——这正是前后端校验器漂移的根因。
 *
 * <p>M4a 的做法是：内核改为「收集**全部**问题」（{@link #collectProblems}），
 * 对外保留两个入口：
 * <ul>
 *   <li>{@link #validate}：收集后若有问题则抛**第一条** —— 发布路径调用它，
 *       消息与旧实现逐字一致，行为零回归；</li>
 *   <li>{@link #collectProblems}：直接返回问题清单 —— 供「校验」端点返回给前端，
 *       前端发布时以后端结果为准，本地镜像降级为「即时预检」。</li>
 * </ul>
 *
 * <p><b>短路语义</b>：某些检查依赖前序检查已通过（如节点索引已建好、图结构自洽），
 * 一旦前置失败就无法安全继续，此时收集器会带上"致命"标记提前收口，
 * 只返回已完成阶段的问题，避免出现级联噪音或 NPE。
 *
 * <h3>W4-A1：本类退化为「门面 + 编排」，规则实现按段拆到四个子校验器</h3>
 * <p>拆分前这里是 916 行、把三段检查与十几个私有方法混在一起。拆分后：
 * <ul>
 *   <li>{@link FlowStructureValidator} —— 第一段（定义级结构 + 节点索引）；</li>
 *   <li>{@link FlowNodeValidator} —— 第二段（逐节点的四类型规则 / 规则适配 / 域禁用 / 指派约束）；</li>
 *   <li>{@link FlowConditionValidator} —— 条件求值前的结构校验（含 M3-A 的递归与深度）；</li>
 *   <li>{@link FlowRuntimeActionValidator} —— 驳回改道 / 超时升级动作；</li>
 *   <li>{@link FlowGraphValidator} —— 第三段（环 / 可达 / 到 END / 两项前置 / 计数）。</li>
 * </ul>
 *
 * <p><b>本次拆分是纯搬迁，零行为变更。</b>这一点由三层证据共同保证：① 既有
 * {@code FlowDefinitionValidatorTest} / {@code ConditionNestingTest} / {@code FlowTest} /
 * {@code FlowRuntimeActionTest} / {@code BorrowFlowTest} 全部原样通过；②
 * {@code FlowGoldenSampleTest} 与前端共享的金样例继续一致；③ 新增
 * {@code FlowValidatorProblemOrderTest} 把 {@link #collectProblems} 的**完整清单与顺序逐字钉死**
 * —— 前三层只断言"某关键字有没有出现"，只有第三层能发现"两条消息换了位置"。
 *
 * <p>编排顺序（结构 → 逐节点 → 图）是本类唯一的自有逻辑，也是不可调换的：
 * 结构段失败必须整体收口，否则第二/三段会在脏索引上产生平方级的级联噪音。
 */
public final class FlowDefinitionValidator {

    private FlowDefinitionValidator() {
    }

    /**
     * 发布校验（{@link FlowScope#CUSTOM} 业务域）：不通过则抛
     * {@link ErrorCode#FLOW_DEFINITION_INVALID}，message 带定位信息。
     *
     * <p>保留不带 scope 的旧签名，等价于 {@code validate(definition, schema, FlowScope.CUSTOM)} ——
     * 让  /  的既有调用点与逐字断言测试零改动。
     */
    public static void validate(FlowDefinition definition, FormSchema schema) {
        validate(definition, schema, FlowScope.CUSTOM);
    }

    /** 发布校验（指定业务域，M1）：不通过则抛 {@link ErrorCode#FLOW_DEFINITION_INVALID} */
    public static void validate(FlowDefinition definition, FormSchema schema, FlowScope scope) {
        List<String> problems = collectProblems(definition, schema, scope);
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.FLOW_DEFINITION_INVALID, problems.get(0));
        }
    }

    /**
     * 收集**全部**校验问题（M4a）。
     *
     * <p>与 {@link #validate} 同源同序：返回列表的第一项 == {@code validate} 抛出时的 message。
     * 返回空列表代表校验通过。
     *
     * <p>实现上按「结构 → 单节点 → 图」三段推进，任一段出现致命错误即停止后续段
     * （后续段的前置条件已不成立，继续跑只会产生噪音）。
     */
    public static List<String> collectProblems(FlowDefinition definition, FormSchema schema) {
        return collectProblems(definition, schema, FlowScope.CUSTOM);
    }

    /**
     * 收集**全部**校验问题（M4a），指定业务域（M1）。
     *
     * <p>与 {@link #validate} 同源同序：返回列表的第一项 == {@code validate} 抛出时的 message。
     * 返回空列表代表校验通过。
     *
     * <p>{@code scope} 只影响两件事，其余规则完全共用：字段域来源
     * （{@link FlowScope#CUSTOM} 用传入的 {@code schema}，{@link FlowScope#BORROW} 用
     * {@link BorrowFieldCatalog#schema()}），以及业务域特有的**禁用规则集**。
     *
     * <p>实现上按「结构 → 单节点 → 图」三段推进，任一段出现致命错误即停止后续段
     * （后续段的前置条件已不成立，继续跑只会产生噪音）。
     */
    public static List<String> collectProblems(FlowDefinition definition, FormSchema schema, FlowScope scope) {
        FlowScope effectiveScope = scope == null ? FlowScope.CUSTOM : scope;
        // 字段域：借用域用内置字段清单，自定义域用传入的表单 schema（可为 null = 无表单上下文）
        FormSchema fieldSchema = effectiveScope == FlowScope.BORROW ? BorrowFieldCatalog.schema() : schema;
        FlowProblemCollector collector = new FlowProblemCollector();

        // ---- 第一段：定义级结构（失败则整体不可继续） ----
        FlowStructureValidator.Result structure = FlowStructureValidator.validate(definition, collector);
        if (!structure.proceed()) {
            return collector.problems();
        }
        Map<String, FlowNode> index = structure.index();

        // ---- 第二段：逐节点（可收集多条） ----
        // 是否含运行期特性会改变两条规则（申请人自选禁用、改道/加签动作校验），
        // 因此把它作为上下文一并传入，而不是在节点级重新判断一遍 —— 两次判断的
        // 结果必须永远一致，交给同一个方法产出就没有漂移的可能。
        boolean runtimeFlow = definition.hasRuntimeFeature();
        for (FlowNode node : definition.getNodes()) {
            FlowNodeValidator.validate(node, index, fieldSchema, collector, effectiveScope, runtimeFlow);
        }

        // ---- 第三段：图结构（依赖节点字段自洽；此处即便有问题也继续收集，谓词本身null安全） ----
        FlowGraphValidator.validate(definition, index, collector);
        return collector.problems();
    }

    /** 审批节点数量（发布校验产物，写入 approval_flow_version.node_count） */
    public static int countApprovalNodes(FlowDefinition definition) {
        return FlowGraphValidator.countApprovalNodes(definition);
    }
}
