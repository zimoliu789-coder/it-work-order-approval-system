package com.enterprise.ticket.module.approvalflow.service.impl;

import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowDefinitionValidator;
import com.enterprise.ticket.common.flow.FlowScope;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 流程定义校验端点的服务层单测（Phase 16 Wave 2 · M2 修正）。
 *
 * <h2>本类锁定的那个缺陷</h2>
 * <p>M4a 建立的契约是「{@code publish()} 抛出的首条 message == {@code validate} 端点返回的首项」，
 * 前端据此做发布前预检。M2 把「运行期特性 + 总开关关闭 ⇒ 拒绝发布」这道闸门加在了
 * {@code publish()} 里（它要读开关，属于环境状态），却**忘了把它算进 validate 端点** ——
 * 于是出现"M1 已经踩过一次"的那个坑：预检全绿 → 用户点发布 → 被拒，
 * 而拒绝理由在预检结果里从未出现过。用户只会认为系统自相矛盾。
 *
 * <h2>为什么这些断言必须存在（而不是靠集成回归兜底）</h2>
 * <p>集成回归只能证明"当前这一版是对的"；这里锁定的是**顺序语义**：
 * 闸门必须排在所有校验器问题之<b>前</b>（因为 publish 也是先过闸门再跑结构校验），
 * 并且开关开启 / 非运行期定义时列表内容与改造前<b>逐项一致</b>（零回归）。
 *
 * <p>纯 Mockito，不启动 Spring / 数据库；校验器是无 Spring 依赖的纯函数，可直接调用比对。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalFlowServiceImplTest {

    /** 含运行期特性：条件引用了 process.prevNodeResult（提交时判不了） */
    private static final String RUNTIME_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"c1",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"c1","type":"CONDITION","name":"上一节点结果判定","branches":[
                {"key":"b1","name":"上一节点已通过","next":"n2",
                 "condition":{"logic":"AND","rules":[{"field":"process.prevNodeResult","op":"EQ","value":"APPROVED"}]}},
                {"key":"b2","name":"其它情况","next":"n3","else":true}]},
              {"key":"n2","type":"APPROVAL","name":"经理复核","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"n3","type":"APPROVAL","name":"归档确认","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    /** 含运行期特性 **且** 结构也有问题（缺 else 默认分支）—— 用于断言闸门排在校验器问题之前 */
    private static final String RUNTIME_BROKEN_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"c1",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"c1","type":"CONDITION","name":"上一节点结果判定","branches":[
                {"key":"b1","name":"已通过","next":"n2",
                 "condition":{"logic":"AND","rules":[{"field":"process.elapsedHours","op":"GT","value":"24"}]}}]},
              {"key":"n2","type":"APPROVAL","name":"经理复核","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    /** 第二期定义：无任何运行期特性（全部存量定义都是这一类） */
    private static final String PLAIN_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    @Mock private ApprovalFlowMapper flowMapper;
    @Mock private ApprovalFlowVersionMapper versionMapper;
    @Mock private ApplyTypeMapper applyTypeMapper;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks private ApprovalFlowServiceImpl service;

    private static FlowDefinition def(String json) {
        return FlowDefinitionCodec.read(json);
    }

    // ------------------------------------------------------------------ 闸门前置

    @Test
    @DisplayName("开关关闭 + 运行期定义 → validate 首项即发布闸门文案（与 publish 同源）")
    void validateDefinition_runtimeFlow_switchOff_prependsGateMessage() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(false);
        FlowDefinition definition = def(RUNTIME_JSON);

        List<String> problems = service.validateDefinition(definition, null);

        List<String> validatorOnly = FlowDefinitionValidator.collectProblems(definition, null, null);
        // 闸门是唯一新增项，且被前置 —— 这正是 publish() 的判定顺序
        assertEquals(validatorOnly.size() + 1, problems.size(),
                "开关关闭时运行期定义应比纯校验器问题多出且仅多出「闸门」这一项");
        assertEquals(ApprovalFlowServiceImpl.RUNTIME_GATE_MESSAGE, problems.get(0),
                "首项必须是闸门文案：publish 先抛的就是它（M4a 同源契约）");
        assertEquals(validatorOnly, problems.subList(1, problems.size()),
                "闸门之后的问题清单必须与纯校验器逐项一致（顺序也不得变）");
    }

    @Test
    @DisplayName("闸门排在校验器问题之前：定义同时有结构错误时，首项仍是闸门（因为 publish 先过闸门）")
    void validateDefinition_runtimeFlowWithStructuralError_gateStillFirst() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(false);
        FlowDefinition definition = def(RUNTIME_BROKEN_JSON);

        List<String> problems = service.validateDefinition(definition, null);

        assertFalse(problems.isEmpty(), "这份定义同时缺 else 分支，问题清单不应为空");
        assertEquals(ApprovalFlowServiceImpl.RUNTIME_GATE_MESSAGE, problems.get(0),
                "闸门必须前置：publish 是先判闸门再跑结构校验的，顺序反过来就是不同源的误导");
        assertTrue(problems.size() > 1, "结构问题应排在闸门之后一并返回（一次性给出全部问题）");
    }

    @Test
    @DisplayName("开关关闭 + 运行期定义 + BORROW 域（三参重载）→ 同样被闸门拦住")
    void validateDefinition_scopeOverload_alsoGated() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(false);

        List<String> problems = service.validateDefinition(def(RUNTIME_JSON), null, FlowScope.BORROW);

        assertEquals(ApprovalFlowServiceImpl.RUNTIME_GATE_MESSAGE, problems.get(0),
                "借用域同样要经过发布闸门 —— 域只影响字段域与禁用规则集，不影响环境闸门");
    }

    // ------------------------------------------------------------------ 零回归

    @Test
    @DisplayName("开关开启 + 运行期定义 → 不加闸门，清单与纯校验器逐项一致")
    void validateDefinition_runtimeFlow_switchOn_identicalToValidator() {
        when(systemConfigService.flowRuntimeConditionEnabled()).thenReturn(true);
        FlowDefinition definition = def(RUNTIME_JSON);

        assertEquals(FlowDefinitionValidator.collectProblems(definition, null, null),
                service.validateDefinition(definition, null),
                "开关开启时运行期定义是合法输入，不应产生任何额外问题");
    }

    @Test
    @DisplayName("零回归：开关关闭 + 普通定义 → 清单与纯校验器逐项一致（存量流程不受影响）")
    void validateDefinition_plainFlow_switchOff_identicalToValidator() {
        // 刻意不桩开关：不含运行期特性时 `hasRuntimeFeature()` 为 false，闸门连开关都不该去读
        //（短路本身就是一条契约 —— 存量流程的校验路径不得新增任何一次配置查询）。
        FlowDefinition definition = def(PLAIN_JSON);

        List<String> problems = service.validateDefinition(definition, null);

        assertEquals(FlowDefinitionValidator.collectProblems(definition, null, null), problems,
                "不含运行期特性的定义在开关关闭时必须与引入闸门之前逐项一致");
        assertTrue(problems.isEmpty(), "这份普通定义是合法的，问题清单应为空");
    }

    @Test
    @DisplayName("零回归：两参重载与三参（scope=null）等价，且都对 null 定义安全")
    void validateDefinition_nullDefinition_isSafe() {
        // 注意：这里刻意不桩 systemConfigService —— 闸门在 definition 为 null 时必须短路，
        // 连开关都不该去读（否则会触发 Mockito 的 UnnecessaryStubbing 之外的真实空指针风险）。
        List<String> problems = service.validateDefinition(null, null);

        assertEquals(List.of("流程定义为空"), problems,
                "null 定义只应返回校验器的那一条问题，闸门不得叠加噪音");
    }
}
