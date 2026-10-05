package com.enterprise.ticket.module.order.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.ConditionRule;
import com.enterprise.ticket.common.flow.FlowBranch;
import com.enterprise.ticket.common.flow.FlowCondition;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.flow.NodeActivation;
import com.enterprise.ticket.common.flow.ProcessFieldCatalog;
import com.enterprise.ticket.common.flow.TimeoutAction;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderFlowActivationLogMapper;
import com.enterprise.ticket.module.order.support.FlowInputResolver;
import com.enterprise.ticket.module.order.support.FlowNodeMaterializer;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程节点激活服务单元测试（Phase 16 Wave 2 · M2 内核）。
 *
 * <p>本类锁定 M2 里最容易被"看起来对"欺骗的几处：
 * <ol>
 *   <li><b>开关关闭 = 第二期口径</b>：{@code resolveForSubmit} 在开关关闭时喂
 *       「已知但为空」的上下文，引用 {@code process.*} 的分支据此求值为"不命中"（SKIPPED），
 *       而不是"判不了"（INACTIVE）。这是"开关关闭时与第二期逐行等价"的直接断言；</li>
 *   <li><b>recompute 的守卫不看开关</b>：非运行期工单必须零成本短路（连节点表都不查）；</li>
 *   <li><b>超时动作的幂等与守卫</b>：只有仍在 PENDING 的节点才升级、同一节点只加签一次、
 *       解析不出人时静默跳过（提醒照发）。</li>
 * </ol>
 *
 * <p>纯 Mockito，不启动 Spring / 数据库。
 */
@ExtendWith(MockitoExtension.class)
class FlowActivationServiceTest {

    private static final Long ORDER_ID = 100L;
    private static final Long NODE_ID = 50L;
    private static final Long APPLICANT_ID = 2L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(OrderApprovalNode.class, Order.class, User.class);
    }

    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private OrderFlowActivationLogMapper activationLogMapper;
    @Mock
    private FlowInputResolver flowInputResolver;
    @Mock
    private FlowNodeMaterializer materializer;
    @Mock
    private ApproverRuleResolver approverRuleResolver;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FlowActivationService service;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 无任何运行期特性：第二期形态的定义（用来验证"开关关闭 = 逐行等价"的外壳） */
    private static FlowDefinition plainFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "end"),
                end("end", "结束")
        )));
        return definition;
    }

    /** 主管审批 →（上一节点结果=REJECTED 走复核、否则归档）→ 结束：含 process.* 条件的最简运行期流程 */
    private static FlowDefinition runtimeConditionFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "c1"),
                condition("c1", "上一节点结果判断",
                        branch("b1", "曾被驳回", "n2", cond(
                                ProcessFieldCatalog.PREV_NODE_RESULT, "EQ", "REJECTED")),
                        elseBranch("b2", "默认", "n3")),
                approval("n2", "复核", "end"),
                approval("n3", "归档", "end"),
                end("end", "结束")
        )));
        return definition;
    }

    /** 主管审批（超时加签给 9 号）→ 结束 */
    private static FlowDefinition addSignFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        FlowNode n1 = approval("n1", "主管审批", "end");
        TimeoutAction action = new TimeoutAction();
        action.setAction(TimeoutAction.ADD_SIGN);
        action.setApprovers(new ArrayList<>(List.of(specificUser(9L))));
        n1.setOnTimeout(action);
        definition.setNodes(new ArrayList<>(List.of(n1, end("end", "结束"))));
        return definition;
    }

    /** 主管审批 →（金额>5000 走财务、否则走兜底）→ 结束；n1 超时改道到「兜底审批」 */
    private static FlowDefinition timeoutGotoFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        FlowNode n1 = approval("n1", "主管审批", "c1");
        TimeoutAction action = new TimeoutAction();
        action.setAction(TimeoutAction.GOTO);
        action.setTarget("n3");
        n1.setOnTimeout(action);
        definition.setNodes(new ArrayList<>(List.of(
                n1,
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "n2", cond("amount", "GT", "5000")),
                        elseBranch("b2", "其它情况", "n3")),
                approval("n2", "财务复核", "end"),
                approval("n3", "超时兜底审批", "end"),
                end("end", "结束")
        )));
        return definition;
    }

    private Order order(FlowDefinition definition) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("GD202601010001");
        order.setApplicantId(APPLICANT_ID);
        order.setCreatedAt(LocalDateTime.now().minusHours(30));
        order.setApprovalFlowJson(definition == null ? null : FlowDefinitionCodec.write(definition));
        return order;
    }

    private OrderApprovalNode pendingNode(String nodeKey) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setId(NODE_ID);
        node.setOrderId(ORDER_ID);
        node.setNodeKey(nodeKey);
        node.setNodeName("主管审批");
        node.setNodeType(FlowNodeType.APPROVAL.name());
        node.setStepOrder(1);
        node.setApproverId(7L);
        node.setStatus(ApprovalNodeStatus.PENDING.name());
        return node;
    }

    private static FlowNode approval(String key, String name, String next) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName(name);
        node.setSignType("ANY_SIGN");
        node.setNext(next);
        node.setApproverRules(new ArrayList<>(List.of(roleRule("admin"))));
        return node;
    }

    private static FlowNode condition(String key, String name, FlowBranch... branches) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.CONDITION.name());
        node.setName(name);
        node.setBranches(new ArrayList<>(List.of(branches)));
        return node;
    }

    private static FlowNode end(String key, String name) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.END.name());
        node.setName(name);
        return node;
    }

    private static FlowBranch branch(String key, String name, String next, ConditionRule rule) {
        FlowBranch branch = new FlowBranch();
        branch.setKey(key);
        branch.setName(name);
        branch.setNext(next);
        branch.setElseBranch(false);
        FlowCondition condition = new FlowCondition();
        condition.setRules(new ArrayList<>(List.of(rule)));
        branch.setCondition(condition);
        return branch;
    }

    private static FlowBranch elseBranch(String key, String name, String next) {
        FlowBranch branch = new FlowBranch();
        branch.setKey(key);
        branch.setName(name);
        branch.setNext(next);
        branch.setElseBranch(true);
        return branch;
    }

    private static ConditionRule cond(String field, String op, Object value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOp(op);
        rule.setValue(value);
        return rule;
    }

    private static ApproverRule roleRule(String roleCode) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.ROLE.name());
        rule.setRoleCode(roleCode);
        return rule;
    }

    private static ApproverRule specificUser(Long... ids) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.SPECIFIC_USER.name());
        rule.setUserIds(new ArrayList<>(List.of(ids)));
        return rule;
    }

    private static NodeActivation activationOf(FlowPathResolver.Result result, String nodeKey) {
        return result.nodes().stream()
                .filter(node -> nodeKey.equals(node.nodeKey()))
                .findFirst()
                .orElseThrow()
                .activation();
    }

    // ------------------------------------------------------------------
    // 运行期工单判据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isRuntimeOrder：null / 无快照 / 不含运行期特性的定义 一律 false")
    void isRuntimeOrder_falseCases() {
        assertFalse(service.isRuntimeOrder(null));
        assertFalse(service.isRuntimeOrder(new Order()), "没有流程快照的工单（借用单 / 固定表模式）不是运行期单");
        assertFalse(service.isRuntimeOrder(order(plainFlow())),
                "普通流程定义不含运行期特性 → 走既有路径，一行不改（零回归判据）");
    }

    @Test
    @DisplayName("isRuntimeOrder：含 process.* 条件或改道/加签的定义判为运行期单")
    void isRuntimeOrder_trueCases() {
        assertTrue(service.isRuntimeOrder(order(runtimeConditionFlow())));
        assertTrue(service.isRuntimeOrder(order(addSignFlow())));
        assertTrue(service.isRuntimeOrder(order(timeoutGotoFlow())));
    }

    // ------------------------------------------------------------------
    // 开关关闭 = 第二期口径（零回归的物理落点）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交物化 · 开关关闭：process.* 分支按「取不到值 → 不命中」求值，落 SKIPPED 而非 INACTIVE")
    void resolveForSubmit_switchOff_behavesLikeSecondPhase() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(false);

        FlowPathResolver.Result result = service.resolveForSubmit(runtimeConditionFlow(), null,
                new LinkedHashMap<>(Map.of()));

        assertTrue(result.inactiveNodes().isEmpty(),
                "开关关闭时不得产生任何 INACTIVE —— 这正是「与第二期逐行等价」的判据");
        assertEquals(NodeActivation.SKIPPED, activationOf(result, "n2"),
                "process.prevNodeResult 在第二期口径下取不到值 → 条件不命中 → 该分支跳过");
        assertEquals(NodeActivation.ACTIVE, activationOf(result, "n3"), "默认分支照常走");
    }

    @Test
    @DisplayName("提交物化 · 开关开启：process.* 分支判不了 → 下游落 INACTIVE（留待运行期激活）")
    void resolveForSubmit_switchOn_defersProcessBranch() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(true);

        FlowPathResolver.Result result = service.resolveForSubmit(runtimeConditionFlow(), null,
                new LinkedHashMap<>(Map.of()));

        assertEquals(2, result.inactiveNodes().size(), "两条分支的下游都还没法定夺（含默认出口）");
        assertEquals(NodeActivation.INACTIVE, activationOf(result, "n2"));
        assertEquals(NodeActivation.INACTIVE, activationOf(result, "n3"));
        assertEquals(NodeActivation.ACTIVE, activationOf(result, "n1"), "起始审批节点不受影响");
    }

    // ------------------------------------------------------------------
    // recompute 守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recompute：非运行期工单零成本短路（连节点表都不查）")
    void recompute_nonRuntimeOrder_isNoop() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setApprovalFlowJson(null);

        FlowActivationService.RecomputeResult result = service.recompute(order);

        assertEquals(0, result.activated());
        assertEquals(0, result.skipped());
        assertFalse(result.changed());
        verify(nodeMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 超时动作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超时动作：非运行期工单直接返回 false，不查节点")
    void applyTimeoutAction_nonRuntimeOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);

        assertFalse(service.applyTimeoutAction(order, NODE_ID));
        verify(nodeMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("超时动作：节点已不是 PENDING（已被处理）→ 不升级")
    void applyTimeoutAction_nodeNotPending() {
        OrderApprovalNode node = pendingNode("n1");
        node.setStatus(ApprovalNodeStatus.APPROVED.name());
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);

        assertFalse(service.applyTimeoutAction(order(addSignFlow()), NODE_ID));
        verify(nodeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("超时动作 · 仅提醒（NOTIFY）：不动流程，返回 false（提醒由任务照发）")
    void applyTimeoutAction_notifyOnly() {
        // 让定义成为"运行期单"（n1 有加签），但对 n2 配置 NOTIFY —— 验证仅提醒分支
        FlowDefinition definition = addSignFlow();
        FlowNode n2 = approval("n2", "仅提醒节点", "end");
        TimeoutAction notify = new TimeoutAction();
        notify.setAction(TimeoutAction.NOTIFY);
        n2.setOnTimeout(notify);
        definition.getNodes().add(1, n2);

        when(nodeMapper.selectById(NODE_ID)).thenReturn(pendingNode("n2"));

        assertFalse(service.applyTimeoutAction(order(definition), NODE_ID));
        verify(nodeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("超时加签：显式加签人 → 插入新节点 + 写激活日志")
    void applyTimeoutAction_addSign_withExplicitApprovers() {
        OrderApprovalNode node = pendingNode("n1");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(flowInputResolver.of(any())).thenReturn(Map.of());
        when(approverRuleResolver.resolve(any(), any(), any())).thenReturn(List.of(9L));

        assertTrue(service.applyTimeoutAction(order(addSignFlow()), NODE_ID));

        verify(nodeMapper).insert(any(OrderApprovalNode.class));
        verify(activationLogMapper, atLeastOnce()).insert(any());
    }

    /**
     * step_order 位移的回归（Phase 16 Wave 4 · W4-C.5）。
     *
     * <p>加签的写法是「把 {@code step_order >= 当前节点+1} 的行<b>整体 +1</b>，再在
     * {@code 当前节点+1} 插入新行」。这套算术只在「stepOrder 沿实际路径严格递增」时才等价于
     * 「插到当前节点之后的第一个位置」—— 而旧口径（全图 DFS 前序枚举）在
     * 「≥2 分支 + 非首条分支有节点 + 汇合」的拓扑下会破坏这个前提。
     * 本用例把这条 SQL 契约钉住：若有人把它改成「先删后插」「按 id 排」或漏掉位移，
     * 三处断言会分别翻红。
     */
    @Test
    @DisplayName("超时加签 · step_order 位移：>= 插入点整体 +1，新行取「当前 + 1」")
    void addSign_insertsRightAfterCurrentStepAndShiftsLaterRows() {
        OrderApprovalNode node = pendingNode("n1");   // stepOrder = 1
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(flowInputResolver.of(any())).thenReturn(Map.of());
        when(approverRuleResolver.resolve(any(), any(), any())).thenReturn(List.of(9L));

        assertTrue(service.applyTimeoutAction(order(addSignFlow()), NODE_ID));

        // ① 位移：一条 UPDATE、判据 >=（插入点 = 当前节点 + 1）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<OrderApprovalNode>> shift =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(nodeMapper).update(any(), shift.capture());
        assertEquals("step_order = step_order + 1", shift.getValue().getSqlSet(),
                "位移必须是「整体 +1」的一条 SQL —— 拆成多条会留下半成品中间态");
        assertTrue(shift.getValue().getSqlSegment().contains("step_order >="),
                "位移判据必须是 >= 插入点，否则会顶掉当前节点自己：" + shift.getValue().getSqlSegment());
        assertTrue(shift.getValue().getParamNameValuePairs().containsValue(2),
                "插入点 = 当前节点 stepOrder(1) + 1：" + shift.getValue().getParamNameValuePairs());

        // ② 新行落在插入点上；nodeKey 带加签标记（幂等识别与监控折回父节点都依赖它）
        ArgumentCaptor<OrderApprovalNode> inserted = ArgumentCaptor.forClass(OrderApprovalNode.class);
        verify(nodeMapper).insert(inserted.capture());
        assertEquals(2, inserted.getValue().getStepOrder().intValue());
        assertTrue(inserted.getValue().getNodeKey()
                .startsWith("n1" + FlowActivationService.ADDSIGN_KEY_MARKER));
    }

    @Test
    @DisplayName("超时加签 · 幂等：同一节点已加过签则不再加（避免每个窗口重复加签）")
    void applyTimeoutAction_addSign_alreadyAdded() {
        OrderApprovalNode node = pendingNode("n1");
        OrderApprovalNode added = pendingNode("n1#addsign-123");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node, added));

        assertFalse(service.applyTimeoutAction(order(addSignFlow()), NODE_ID));
        verify(nodeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("超时加签 · 解析不出在职加签人 → 静默跳过（提醒不受影响）")
    void applyTimeoutAction_addSign_noApproverResolved() {
        OrderApprovalNode node = pendingNode("n1");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(flowInputResolver.of(any())).thenReturn(Map.of());
        when(approverRuleResolver.resolve(any(), any(), any())).thenReturn(List.of());

        assertFalse(service.applyTimeoutAction(order(addSignFlow()), NODE_ID));
        verify(nodeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("超时改道：作废超时节点并改走目标分支，返回 true 且留痕")
    void applyTimeoutAction_goto() {
        OrderApprovalNode node = pendingNode("n1");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(node);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(flowInputResolver.of(any())).thenReturn(Map.of());

        assertTrue(service.applyTimeoutAction(order(timeoutGotoFlow()), NODE_ID));

        // 作废自身 + 作废旁支 + 重开目标分支，至少各一次 UPDATE
        verify(nodeMapper, atLeastOnce()).update(any(), any());
        verify(activationLogMapper, atLeastOnce()).insert(any());
    }

    @Test
    @DisplayName("超时改道 · 目标不存在（配置被改脏）：不猜、不动流程，返回 false")
    void applyTimeoutAction_goto_missingTarget() {
        FlowDefinition definition = timeoutGotoFlow();
        definition.node("n1").getOnTimeout().setTarget("missing");
        when(nodeMapper.selectById(NODE_ID)).thenReturn(pendingNode("n1"));

        assertFalse(service.applyTimeoutAction(order(definition), NODE_ID));
    }

    // ------------------------------------------------------------------
    // 节点状态单一写库入口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelOpenNodes：转发到 mapper 的条件 UPDATE，返回受影响行数")
    void cancelOpenNodes_delegatesToMapper() {
        when(nodeMapper.update(any(), any())).thenReturn(3);
        assertEquals(3, service.cancelOpenNodes(ORDER_ID, null));
    }

    @Test
    @DisplayName("reassignStepApprover：转发到 mapper 的条件 UPDATE（节点仍 PENDING 才改）")
    void reassignStepApprover_delegatesToMapper() {
        when(nodeMapper.update(any(), any())).thenReturn(1);
        assertEquals(1, service.reassignStepApprover(ORDER_ID, 1, 9L));
        verify(nodeMapper).update(any(), any());
    }

    private static User applicant() {
        User user = new User();
        user.setId(APPLICANT_ID);
        user.setUsername("李娜");
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }
}
