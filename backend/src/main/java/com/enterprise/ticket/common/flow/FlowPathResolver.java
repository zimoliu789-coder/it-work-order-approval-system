package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程路径求值器（； 扩展运行期判定）—— 本期最关键的一步。
 *
 * <h2>它做了什么</h2>
 * 把流程 DAG 按条件求值走一遍，为**每个**审批 / 抄送节点给出一个激活态
 * （{@link NodeActivation}：会走 / 还没定 / 确定不走），并按遍历顺序分配 {@code stepOrder}。
 *
 * <h2>第二期的前提与它在本期被打破的地方</h2>
 * 第二期的条件只允许引用**表单字段**，而表单在提交时就已固定 —— 走哪条分支在提交那一刻
 * 就是<b>确定</b>的。因此第二期只有「命中(SKIPPED 与否)」两态，且提前求值带来
 * 「审批引擎零改动」（落库后就是一条线性步骤序列）与「确定可复现」两大好处。
 *
 * <p>M2 打破了「提交即确定」：条件可以引用 {@code process.*}（上一节点结果 / 已耗时 / 驳回次数），
 * 这些值在提交时不存在。处理方式不是引入一套新的运行时求值器，而是：
 * <ol>
 *   <li>提交时把这类分支的下游物化为 {@link NodeActivation#INACTIVE}（不下结论）；</li>
 *   <li>运行期由 {@code FlowActivationService#recompute} <b>用同一份代码、喂上运行期上下文</b>
 *       再算一次，把 INACTIVE 收敛为 ACTIVE 或 SKIPPED。</li>
 * </ol>
 * 也就是说：{@link FlowOperator} 的运算符、{@link #matches} 的比较逻辑、DAG 遍历逻辑
 * 全都<b>没有第二次实现</b> —— 这与 M1「把借用字段适配成同构 FormSchema」是同一手法。
 *
 * <h2>stepOrder 的分配（ · W4-C.5 起改为「拓扑距离」口径）</h2>
 * 对**全部**审批与抄送节点分配**唯一**的 stepOrder；同一个流程节点解析出多人时共享同一个
 * stepOrder（这正是 ANY_SIGN/ALL_SIGN 的既有语义）。
 *
 * <p>分配顺序 = <b>「从起点出发的最长路径长度（层号）升序，同层按定义书写顺序」</b>，
 * 再逐个编号 1..N。
 *
 * <p><b>为什么不是 DFS 前序枚举</b>（W4-C.5 修掉的缺陷）：DFS 会先把首条分支的整条链
 * 编完 —— 包括它在汇合点上的那一个节点 —— 再回头编 else 链。于是走 else 时，
 * 「汇合点」的编号小于 else 链上节点的编号，与设计器上画的先后<b>相反</b>，
 * 而推进判定正是「最小含 PENDING 的 step」，结果就是"当前步骤/当前审批人"错位。
 *
 * <p><b>为什么层号口径就够</b>：任意一条边 {@code X→Y} 都满足 {@code depth(Y) > depth(X)}
 * （条件节点也占一层，只会把差距拉得更大），而排序键只有层号，因此
 * <b>任意一条路径上的 stepOrder 必然严格递增</b> —— 这就是"运行时路径顺序"的形式化表述，
 * 也是 {@code FlowActivationService} 敢用 {@code 当前节点 + 1} 作为动态插入点的前提。
 *
 * <p><b>为什么取「最长」而不是「最短」</b>：一个节点可能同时位于一长一短两条路径上，
 * 取最短会让长路径中段节点的编号被短路径上的后继"插队"（长路径上出现 stepOrder 回退）。
 * 取最长（对 DAG 按拓扑序做一次 DP 求最长距离）则天然单调。
 *
 * <p>被跳过 / 未激活的节点一样有 stepOrder（分层时就占位），但它们落库状态非 PENDING，
 * 因此不会干扰"最小含 PENDING 的 step"这一推进判定；反过来，某个节点占了号却走了别的分支，
 * 命中链的 stepOrder 出现"跳跃"（1 → 3）是正常的，不是缺号。
 *
 * <h2>口径变更的适用范围</h2>
 * 只影响<b>新提交</b>的工单 —— stepOrder 在提交时随节点行落库，历史工单的
 * {@code order_approval_node.step_order} <b>不做迁移</b>：它们的审批已结束，
 * step_order 不再参与推进，改号只是无谓的数据变更。
 *
 * <h2>汇聚（多条分支合流）</h2>
 * 分类采用 <b>ACTIVE &gt; INACTIVE &gt; SKIPPED</b> 的择优规则（见 {@link NodeActivation#stronger}）：
 * 只要有一条"会走"的入边，节点就会走；否则只要有一条"还没定"的入边，它就还没定。
 */
public final class FlowPathResolver {

    private FlowPathResolver() {
    }

    /** 求值结果中的单个节点 */
    public record ResolvedNode(String nodeKey,
                               String nodeName,
                               String signType,
                               List<ApproverRule> approverRules,
                               int stepOrder,
                               NodeActivation activation,
                               String conditionDesc,
                               String nodeType,
                               Integer timeLimitHours,
                               RejectAction onReject,
                               TimeoutAction onTimeout) {

        /** 是否在命中路径上（ 语义的向后兼容访问器） */
        public boolean onPath() {
            return activation == NodeActivation.ACTIVE;
        }

        /** 是否审批节点 */
        public boolean isApproval() {
            return FlowNodeType.APPROVAL.name().equals(nodeType);
        }

        /** 是否抄送节点 */
        public boolean isCc() {
            return FlowNodeType.CC.name().equals(nodeType);
        }
    }

    /** 求值结果 */
    public record Result(List<ResolvedNode> nodes) {

        /** 命中路径上的节点（按 stepOrder 升序） */
        public List<ResolvedNode> onPathNodes() {
            return nodes.stream().filter(node -> node.activation() == NodeActivation.ACTIVE).toList();
        }

        /** 确定不走的节点 */
        public List<ResolvedNode> skippedNodes() {
            return nodes.stream().filter(node -> node.activation() == NodeActivation.SKIPPED).toList();
        }

        /**
         * 尚未判定（运行期条件 / 位于未完成节点之后）的节点。
         *
         * <p>第二期流程下<b>恒为空</b> —— 这正是"开关关闭时行为与第二期逐行一致"的又一处体现：
         * 没有运行期特性就不会产生 INACTIVE，旧的两分法（命中 / 跳过）自动成立。
         */
        public List<ResolvedNode> inactiveNodes() {
            return nodes.stream().filter(node -> node.activation() == NodeActivation.INACTIVE).toList();
        }
    }

    /**
     * 按表单数据求值（提交时刻口径）。
     *
     * <p>等价于「运行期上下文为空、不阻塞任何节点」：引用 {@code process.*} 的分支判不了 →
     * 下游 INACTIVE；其余全部按表单数据一次判定完。既有调用方（预览接口、第二期提交流程）
     * 继续用这个签名，行为与改造前一致。
     */
    public static Result resolve(FlowDefinition definition, FormSchema schema, Map<String, Object> formData) {
        return resolve(definition, schema, formData, null, null, null);
    }

    /**
     * 运行期求值。
     *
     * <p>与三参版本的差别只有两处，且都是"喂不同的输入"而非"换一套逻辑"：
     * <ol>
     *   <li>{@code context} 非空时，{@code process.*} 有值 → 依赖它的条件<b>可以下结论</b>；</li>
     *   <li>{@code blockedNodeKeys} 里的节点视为"尚未完成" → 其下游一律不透传 ACTIVE，
     *       保持 INACTIVE。这保证了"运行期条件只在其<b>前置节点已完成</b>时才被求值" ——
     *       否则一次 recompute 会把排在后续审批节点之后的条件一起算掉，
     *       造出"当前节点还没审，下游节点已经待办"的错序。</li>
     * </ol>
     *
     * @param context        运行期上下文；null = 提交时刻（process.* 一律判为"不可判定"）
     * @param blockedNodeKeys 尚未完成的节点 key（recompute 时=存在 PENDING 行的节点 ∪ 本次将被激活的节点）；
     *                        null = 全部视为已完成
     */
    public static Result resolve(FlowDefinition definition, FormSchema schema, Map<String, Object> formData,
                                 RuntimeContext context, Set<String> blockedNodeKeys) {
        return resolve(definition, schema, formData, context, blockedNodeKeys, null);
    }

    /**
     * 运行期求值 + 改道。
     *
     * @param forceActiveKeys 强制判定为"会走"的节点 key。
     *        <p>用途只有一个：<b>驳回改道</b>（{@code onReject=GOTO}）。
     *        改道意味着"本节点不走，改走另一支"，而那一支在提交时可能已被判定为
     *        跳过或未判定 —— 这个信息无法从表单数据或运行期上下文推导出来
     *        （它是"发生过一次驳回"这个事件的结果），因此必须由调用方显式喂进来。
     *        <p>实现上它只做<b>单向升级</b>（{@link NodeActivation#stronger} 意义上的 ACTIVE），
     *        不会把任何节点降级，因此对既有路径零影响。
     */
    public static Result resolve(FlowDefinition definition, FormSchema schema, Map<String, Object> formData,
                                 RuntimeContext context, Set<String> blockedNodeKeys,
                                 Set<String> forceActiveKeys) {
        Map<String, FlowNode> index = definition.indexByKey();
        Map<String, String> labels = mergedLabels(schema);
        Map<String, Object> input = RuntimeContext.merge(formData, context);
        boolean runtimeReady = context != null;
        Set<String> blocked = blockedNodeKeys == null ? Set.of() : blockedNodeKeys;
        Set<String> forced = forceActiveKeys == null ? Set.of() : forceActiveKeys;

        // 1) 拓扑传播：为每个节点定出激活态与说明
        Map<String, NodeActivation> state = new LinkedHashMap<>();
        Map<String, String> explain = new LinkedHashMap<>();
        if (StringUtils.hasText(definition.getStart())) {
            state.put(definition.getStart(), NodeActivation.ACTIVE);
        }
        for (String key : topoOrder(definition, index)) {
            FlowNode node = index.get(key);
            NodeActivation current = state.get(key);
            if (node == null || current == null) {
                continue;
            }
            if (node.typeEnum() == FlowNodeType.END) {
                continue;
            }
            if (node.typeEnum() == FlowNodeType.CONDITION) {
                propagateCondition(node, current, input, labels, runtimeReady, forced, state, explain);
            } else {
                NodeActivation edge = current == NodeActivation.ACTIVE && !blocked.contains(key)
                        ? NodeActivation.ACTIVE
                        : (current == NodeActivation.ACTIVE ? NodeActivation.INACTIVE : current);
                // 说明沿链传递（与第二期「最近一次条件分支的说明向下延续」语义一致）
                String desc = explain.get(key);
                for (String next : outgoing(node)) {
                    apply(state, explain, next, edge, desc, forced);
                }
            }
        }

        // 2) 按「拓扑距离（最长路径层号）」给每个审批 / 抄送节点分配唯一 stepOrder。
        //    这里刻意不是 DFS 前序枚举：见类注释「stepOrder 的分配」。
        return new Result(assignStepOrders(definition, index, state, explain));
    }

    // ------------------------------------------------------------------
    // 拓扑传播
    // ------------------------------------------------------------------

    /** 条件节点：按分支逐个定态（顺序敏感 —— 首条命中的分支生效，与第二期 pickBranch 一致） */
    private static void propagateCondition(FlowNode node, NodeActivation current,
                                           Map<String, Object> input, Map<String, String> labels,
                                           boolean runtimeReady, Set<String> forced,
                                           Map<String, NodeActivation> state, Map<String, String> explain) {
        List<FlowBranch> branches = node.getBranches();
        FlowBranch elseBranch = null;
        FlowBranch taken = null;
        // 一旦某条前置分支"判不了"，它后面的分支（含默认出口）就都判不了：
        // 因为那条前置分支将来若命中，后面的分支根本不会被选中，此刻的"不命中"结论没有意义。
        boolean deferred = false;

        if (current == NodeActivation.ACTIVE) {
            // 落到这里的条件说明：本条件位于运行期注入的改道分支上时，直接用 formData/labels 求值
            for (FlowBranch branch : branches) {
                if (branch == null) {
                    continue;
                }
                if (branch.isElse()) {
                    elseBranch = branch;
                    continue;
                }
                if (deferred) {
                    apply(state, explain, branch.getNext(), NodeActivation.INACTIVE,
                            describeDeferred(node, branch), forced);
                    continue;
                }
                if (!runtimeReady && ProcessFieldCatalog.isRuntimeDependent(branch.getCondition())) {
                    deferred = true;
                    apply(state, explain, branch.getNext(), NodeActivation.INACTIVE,
                            describeDeferred(node, branch), forced);
                    continue;
                }
                if (taken == null && evaluate(branch.getCondition(), input)) {
                    taken = branch;
                    apply(state, explain, branch.getNext(), NodeActivation.ACTIVE,
                            describeTaken(node, branch, input, labels), forced);
                } else {
                    apply(state, explain, branch.getNext(), NodeActivation.SKIPPED,
                            describeSkipped(node, branch, input, labels), forced);
                }
            }

            if (elseBranch != null) {
                if (deferred) {
                    apply(state, explain, elseBranch.getNext(), NodeActivation.INACTIVE,
                            describeDeferred(node, elseBranch), forced);
                } else if (taken != null) {
                    apply(state, explain, elseBranch.getNext(), NodeActivation.SKIPPED,
                            describeSkipped(node, elseBranch, input, labels), forced);
                } else {
                    apply(state, explain, elseBranch.getNext(), NodeActivation.ACTIVE,
                            describeTaken(node, elseBranch, input, labels), forced);
                }
            }
            return;
        }

        // 前置已经不走 / 还没定 → 本条件的全部分支继承同一状态，不做任何求值
        String desc = explain.get(node.getKey());
        for (FlowBranch branch : branches) {
            if (branch != null) {
                apply(state, explain, branch.getNext(), current, desc, forced);
            }
        }
    }

    /**
     * 定态：只允许"增强"（ACTIVE &gt; INACTIVE &gt; SKIPPED），同级取先到者。
     *
     * <p>这就是汇聚语义的落点：同一节点被多条边指向时，只要有任意一条边是 ACTIVE 就走；
     * 否则只要有任意一条是 INACTIVE 就还没定；全部 SKIPPED 才算跳过。
     *
     * <p>{@code forced} 里的节点（改道注入）在此处被<b>单向上调</b>为 ACTIVE ——
     * 无论它本来被判成什么，"改道到它"这个事实优先。
     */
    private static void apply(Map<String, NodeActivation> state, Map<String, String> explain,
                              String key, NodeActivation activation, String desc, Set<String> forced) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        NodeActivation effective = forced.contains(key) ? NodeActivation.ACTIVE : activation;
        NodeActivation existing = state.get(key);
        if (existing == null) {
            state.put(key, effective);
            if (desc != null) {
                explain.put(key, desc);
            }
            return;
        }
        NodeActivation merged = existing.stronger(effective);
        if (merged != existing) {
            state.put(key, merged);
            explain.remove(key);
            if (desc != null) {
                explain.put(key, desc);
            }
        }
    }

    /** 按拓扑序返回节点 key（校验器已保证无环；此处是纵深防御，环上残余节点按定义顺序并到末尾） */
    private static List<String> topoOrder(FlowDefinition definition, Map<String, FlowNode> index) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String key : index.keySet()) {
            indegree.put(key, 0);
        }
        for (FlowNode node : index.values()) {
            for (String next : outgoing(node)) {
                if (indegree.containsKey(next)) {
                    indegree.put(next, indegree.get(next) + 1);
                }
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        List<String> order = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String key = queue.poll();
            order.add(key);
            for (String next : outgoing(index.get(key))) {
                Integer remain = indegree.get(next);
                if (remain == null) {
                    continue;
                }
                indegree.put(next, remain - 1);
                if (remain - 1 == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() < index.size()) {
            for (String key : index.keySet()) {
                if (!order.contains(key)) {
                    order.add(key);
                }
            }
        }
        return order;
    }

    // ------------------------------------------------------------------
    // 条件求值（对外也暴露，供单测与预览接口复用）
    // ------------------------------------------------------------------

    /**
     * 条件是否成立（ · M3-A：条件树支持嵌套，逐项递归求值）。
     *
     * <p>递归本身不引入第二套逻辑：无论第几层，"组内按 {@code logic} 折叠、叶子交给
     * {@link #matches}" 都是同一段代码。因此三层嵌套与单层条件走的是**同一条**求值路径 ——
     * 这也是"存量单层条件行为逐字节不变"的结构性保证，而不是靠额外加分支兜住。
     */
    public static boolean evaluate(FlowCondition condition, Map<String, Object> formData) {
        if (condition == null || condition.getRules().isEmpty()) {
            return false;
        }
        boolean or = condition.isOr();
        for (ConditionRule rule : condition.getRules()) {
            boolean hit = evaluateItem(rule, formData);
            if (or && hit) {
                return true;
            }
            if (!or && !hit) {
                return false;
            }
        }
        return !or;
    }

    /**
     * 求值单个条件项：{@code kind=GROUP} 递归到子条件组，其余按单条规则比较。
     *
     * <p><b>这是求值路径上唯一一处"判 kind 后决定怎么读 rule"的地方。</b>
     * 若将来有人绕过它直接调 {@link #matches}，嵌套组会因为 {@code field} 为 null
     * 而恒判 false —— 表现为"配了嵌套条件的分支永远不走"，且不会有任何报错。
     */
    private static boolean evaluateItem(ConditionRule rule, Map<String, Object> formData) {
        if (rule == null) {
            return false;
        }
        if (rule.isGroup()) {
            return evaluate(rule.getCondition(), formData);
        }
        return matches(rule, formData);
    }

    /**
     * 条件树的最大层数（最外层条件组算第 1 层）。
     *
     * <p>与 {@code FlowCondition.MAX_DEPTH} 的关系刻意冗余但**语义不同**：常量是"允许多少"，
     * 本方法是"实际多少"。校验器拿两者比较；求值器不关心深度（多深都能算），
     * 因此深度超限的定义若因人工改库流入运行期，只会照常求值，而不是崩在某个递归里。
     */
    public static int depthOf(FlowCondition condition) {
        if (condition == null) {
            return 0;
        }
        int deepest = 0;
        for (ConditionRule rule : condition.getRules()) {
            if (rule != null && rule.isGroup()) {
                deepest = Math.max(deepest, depthOf(rule.getCondition()));
            }
        }
        return deepest + 1;
    }

    /** 条件的中文说明（用于详情页"因 X 走了 Y 分支"） */
    public static String describeCondition(FlowCondition condition, Map<String, Object> formData,
                                           Map<String, String> labels) {
        if (condition == null || condition.getRules().isEmpty()) {
            return "默认分支";
        }
        String joiner = condition.isOr() ? " 或 " : " 且 ";
        List<String> parts = new ArrayList<>();
        for (ConditionRule rule : condition.getRules()) {
            parts.add(describeRule(rule, formData, labels));
        }
        return String.join(joiner, parts);
    }

    private static String describeTaken(FlowNode node, FlowBranch branch,
                                        Map<String, Object> formData, Map<String, String> labels) {
        if (branch == null) {
            return null;
        }
        String reason = branch.isElse()
                ? "以上条件均未命中，走默认分支「" + safe(branch.getName()) + "」"
                : "因 " + describeCondition(branch.getCondition(), formData, labels)
                        + "，走「" + safe(branch.getName()) + "」分支";
        return "「" + safe(node.getName()) + "」" + reason;
    }

    /**
     * 未命中分支的说明（与第二期文案<b>逐字一致</b>）。
     *
     * <p>刻意不改这段文案：既有回归与金样例都断言了它，而这段文案本身也没有因为 M2 变得不准确。
     */
    private static String describeSkipped(FlowNode node, FlowBranch branch,
                                          Map<String, Object> formData, Map<String, String> labels) {
        if (branch == null) {
            return null;
        }
        return "未命中「" + safe(branch.getName()) + "」分支（"
                + describeCondition(branch.getCondition(), formData, labels)
                + "），本节点已跳过";
    }

    /**
     * 待判定分支的说明（M2 新增）。
     *
     * <p>它回答的是与"已跳过"完全不同的问题：不是"不会走"，而是"现在还判不了"。
     * 因此文案必须点明<b>依赖了哪个运行期字段</b>——只说"待判定"会让配置者无从下手。
     */
    private static String describeDeferred(FlowNode node, FlowBranch branch) {
        if (branch == null) {
            return null;
        }
        String fields = runtimeFieldsOf(branch.getCondition());
        return "「" + safe(node.getName()) + "」分支「" + safe(branch.getName()) + "」依赖运行期数据"
                + (fields.isEmpty() ? "" : "（" + fields + "）")
                + "，将在前置节点完成后自动判定";
    }

    /** 条件里引用到的运行期字段名（中文），用于"待判定"说明；条件树任意深度的引用都要收集到 */
    private static String runtimeFieldsOf(FlowCondition condition) {
        Set<String> names = new LinkedHashSet<>();
        collectRuntimeFields(condition, names);
        return String.join("、", names);
    }

    /**
     * 递归收集运行期字段名（M3-A）。
     *
     * <p>文案里必须把**嵌套组里**引用的运行期字段也列出来：否则一个三层条件给出的
     * "依赖运行期数据，将在前置节点完成后自动判定" 会不带字段名，配置者只能一层层点开找。
     * 用 {@link LinkedHashSet} 保持出现顺序并去重 —— 同一字段在多个分支里被引用时
     * 重复列出会比不列出更让人困惑。
     */
    private static void collectRuntimeFields(FlowCondition condition, Set<String> out) {
        if (condition == null) {
            return;
        }
        for (ConditionRule rule : condition.getRules()) {
            if (rule == null) {
                continue;
            }
            if (rule.isGroup()) {
                collectRuntimeFields(rule.getCondition(), out);
                continue;
            }
            if (ProcessFieldCatalog.isProcessField(rule.getField())) {
                out.add(ProcessFieldCatalog.labelOf(rule.getField()));
            }
        }
    }

    private static String describeRule(ConditionRule rule, Map<String, Object> formData,
                                       Map<String, String> labels) {
        if (rule == null) {
            return "";
        }
        // 嵌套组：递归出组内文案后**加括号**。括号不是装饰 ——
        // "A 且 B 或 C" 与 "A 且 (B 或 C)" 是两份完全不同的配置，
        // 而说明文案是配置者核对"我配的到底是不是我想的"的唯一入口。
        // 这里用全角括号：文案整体是中文，「」也已在用，全角与正文排版一致。
        if (rule.isGroup()) {
            return "（" + describeCondition(rule.getCondition(), formData, labels) + "）";
        }
        String fieldLabel = labels.getOrDefault(rule.getField(), rule.getField());
        FlowOperator operator = FlowOperator.of(rule.getOp());
        String opLabel = operator == null ? String.valueOf(rule.getOp()) : operator.getLabel();
        Object actual = formData.get(rule.getField());
        if (operator == null) {
            return fieldLabel;
        }
        if (!operator.isRequiresValue()) {
            return fieldLabel + " " + opLabel;
        }
        return fieldLabel + " " + opLabel + " " + display(rule.getValue())
                + "（实际 " + display(actual) + "）";
    }

    private static boolean matches(ConditionRule rule, Map<String, Object> formData) {
        if (rule == null) {
            return false;
        }
        FlowOperator operator = FlowOperator.of(rule.getOp());
        if (operator == null) {
            return false;
        }
        Object actual = formData.get(rule.getField());
        if (!operator.isRequiresValue()) {
            boolean empty = isEmpty(actual);
            return operator == FlowOperator.IS_EMPTY ? empty : !empty;
        }
        Object expected = rule.getValue();
        if (operator.isNumericComparison()) {
            BigDecimal left = toDecimal(actual);
            BigDecimal right = toDecimal(expected);
            if (left == null || right == null) {
                return false;
            }
            int cmp = left.compareTo(right);
            return switch (operator) {
                case GT -> cmp > 0;
                case GTE -> cmp >= 0;
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
                default -> false;
            };
        }
        if (operator == FlowOperator.CONTAINS || operator == FlowOperator.NOT_CONTAINS) {
            boolean contains = containsValue(actual, expected);
            return operator == FlowOperator.CONTAINS ? contains : !contains;
        }
        boolean equal = equalsValue(actual, expected);
        return operator == FlowOperator.EQ ? equal : !equal;
    }

    // ------------------------------------------------------------------
    // 值比较的小工具（保守：类型不明时判为"不命中"，宁可少走分支不可错走）
    // ------------------------------------------------------------------

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean equalsValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        BigDecimal left = toDecimal(actual);
        BigDecimal right = toDecimal(expected);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        if (actual instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> String.valueOf(item).equals(String.valueOf(expected)));
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private static boolean containsValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String needle = String.valueOf(expected);
        if (actual instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> String.valueOf(item).equals(needle));
        }
        return String.valueOf(actual).contains(needle);
    }

    private static String display(Object value) {
        if (value == null) {
            return "（空）";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("（空）");
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // 遍历
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // stepOrder 的分配（拓扑距离口径， · W4-C.5）
    // ------------------------------------------------------------------

    /**
     * 为每个审批 / 抄送节点分配唯一的 stepOrder。
     *
     * <p>三步：① 按拓扑序做一次 DP，求出每个节点「从起点出发的最长路径长度」（层号）；
     * ② 把审批 / 抄送节点按「层号升序、同层按定义书写顺序」稳定排序；
     * ③ 顺序编号 1..N。
     *
     * <p>起点为空（定义残缺）时返回空列表 —— 与改造前 DFS 的行为一致：没有起点就没有路径。
     * 只有「从起点可达」的节点参与编号：不可达节点本就不在任何一条路径上，
     * 给它编号反而会凭空多出一行永不推进的节点（发布校验会先拒掉这种定义，这里是纵深防御）。
     */
    private static List<ResolvedNode> assignStepOrders(FlowDefinition definition, Map<String, FlowNode> index,
                                                       Map<String, NodeActivation> state,
                                                       Map<String, String> explain) {
        List<ResolvedNode> resolved = new ArrayList<>();
        if (!StringUtils.hasText(definition.getStart())) {
            return resolved;
        }
        Map<String, Integer> depth = longestDepthFrom(definition, index);
        List<FlowNode> ordered = new ArrayList<>();
        for (FlowNode node : definition.getNodes()) {
            if (node == null || !depth.containsKey(node.getKey())) {
                continue;
            }
            FlowNodeType type = node.typeEnum();
            if (type == FlowNodeType.APPROVAL || type == FlowNodeType.CC) {
                ordered.add(node);
            }
        }
        // 稳定排序 = 同层节点保持定义的书写顺序：它们互相不可达、先后本身无语义，
        // 但保持确定性才能让「同一份定义永远算出同一份编号」。
        ordered.sort(Comparator.comparingInt(node -> depth.get(node.getKey())));

        int step = 0;
        for (FlowNode node : ordered) {
            FlowNodeType type = node.typeEnum();
            resolved.add(new ResolvedNode(
                    node.getKey(),
                    node.getName(),
                    SignType.normalize(node.getSignType()),
                    node.getApproverRules(),
                    ++step,
                    state.getOrDefault(node.getKey(), NodeActivation.SKIPPED),
                    explain.get(node.getKey()),
                    type.name(),
                    type == FlowNodeType.APPROVAL ? node.getTimeLimitHours() : null,
                    node.getOnReject(),
                    node.getOnTimeout()));
        }
        return resolved;
    }

    /**
     * 每个节点「从起点出发的最长路径长度」（起点为 0）。
     *
     * <p>在拓扑序上做一次单遍 DP：处理到某个节点时它的全部前驱都已处理过，
     * 因此此刻的层号已是最终值。用<b>最长</b>而不是最短，理由见类注释。
     *
     * <p>只返回「从起点可达」的节点；不可达节点不进 map，调用方据此把它们排除在编号之外。
     * 起点为空或不在图中时返回空 map。环（发布校验已拒）属纵深防御场景：
     * {@link #topoOrder} 会把环上残余节点并到末尾，它们仍能拿到较大层号，不会抛异常也不会死循环。
     */
    private static Map<String, Integer> longestDepthFrom(FlowDefinition definition, Map<String, FlowNode> index) {
        Map<String, Integer> depth = new LinkedHashMap<>();
        String start = definition.getStart();
        if (!StringUtils.hasText(start) || !index.containsKey(start)) {
            return depth;
        }
        depth.put(start, 0);
        for (String key : topoOrder(definition, index)) {
            Integer current = depth.get(key);
            if (current == null) {
                continue;
            }
            FlowNode node = index.get(key);
            if (node == null) {
                continue;
            }
            for (String next : outgoing(node)) {
                if (!index.containsKey(next)) {
                    continue;
                }
                Integer existing = depth.get(next);
                if (existing == null || existing < current + 1) {
                    depth.put(next, current + 1);
                }
            }
        }
        return depth;
    }

    private static List<String> outgoing(FlowNode node) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        FlowNodeType type = node.typeEnum();
        if ((type == FlowNodeType.APPROVAL || type == FlowNodeType.CC)
                && StringUtils.hasText(node.getNext())) {
            result.add(node.getNext());
        }
        if (type == FlowNodeType.CONDITION) {
            for (FlowBranch branch : node.getBranches()) {
                if (branch != null && StringUtils.hasText(branch.getNext())) {
                    result.add(branch.getNext());
                }
            }
        }
        return result;
    }

    private static Map<String, String> fieldLabels(FormSchema schema) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (schema == null) {
            return labels;
        }
        for (FormField field : schema.getFields()) {
            if (field != null && StringUtils.hasText(field.getKey())) {
                labels.put(field.getKey(), StringUtils.hasText(field.getLabel())
                        ? field.getLabel() : field.getKey());
            }
        }
        return labels;
    }

    /**
     * 表单字段标签 + 运行期字段标签。
     *
     * <p>运行期字段必须并进来，否则条件说明里会出现 {@code process.prevNodeResult} 这种
     * 只有开发能看懂的原始 key。合并顺序把运行期字段放前面再由表单覆盖 ——
     * 虽然二者 key 空间本不重叠（表单字段禁带点号），但一旦重叠，让用户实际填的字段名生效更符合直觉。
     */
    private static Map<String, String> mergedLabels(FormSchema schema) {
        Map<String, String> labels = new LinkedHashMap<>(ProcessFieldCatalog.labels());
        labels.putAll(fieldLabels(schema));
        return labels;
    }

    /** 供外部（预览接口）复用：把字段 key 映射成中文名 */
    public static Map<String, String> labelsOf(FormSchema schema) {
        return mergedLabels(schema);
    }

    /** 表单字段类型是否可作为条件字段（供预览/设计器端做前端提示的一致性依据） */
    public static boolean conditionable(FormField field) {
        if (field == null) {
            return false;
        }
        FormFieldType type = FormFieldType.of(field.getType());
        return type != null && type.getValueKind() != FormFieldType.ValueKind.NONE;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
