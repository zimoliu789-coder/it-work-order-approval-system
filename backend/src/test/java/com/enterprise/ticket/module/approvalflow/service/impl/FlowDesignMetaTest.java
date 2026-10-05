package com.enterprise.ticket.module.approvalflow.service.impl;

import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowCondition;
import com.enterprise.ticket.common.flow.FlowScope;
import com.enterprise.ticket.module.approvalflow.dto.vo.FlowDesignMetaVO;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 设计器元数据（Wave 4 · W4-D / C8）的服务层契约测试。
 *
 * <h2>为什么一个"纯静态数据"的接口值得单测</h2>
 * <p>它返回的不是实现细节，而是**被前端依赖的契约**：前端启动时拉一次，
 * 用它覆盖本地兜底默认值（深度上限、各域禁用来源、各来源参数槽位）。
 * 之前这三份事实散落在前端三处硬编码，漂移的表现是用户可见的功能异常 ——
 * "设计器让配、后端不让发"。把它固化成可断言的契约，是这次整改的落点。
 *
 * <h2>两条互补的断言</h2>
 * <ul>
 *   <li><b>派生自枚举</b>：逐项等于 {@code FlowCondition.MAX_DEPTH} / {@code FlowScope} /
 *       {@code ApproverRuleType}。这防的是"有人在 service 里手写一份字面量" ——
 *       那等于把前端的副本搬到后端，第二份事实源依旧存在。</li>
 *   <li><b>等于共享金样例</b>：与前端 Vitest 加载**同一个**
 *       {@code test-fixtures/golden/flow-design-meta.json}。这防的是"后端改了枚举、前端兜底没跟上"
 *       这类跨语言漂移 —— 两端测试至少一侧会红。</li>
 * </ul>
 *
 * <p>纯 Mockito，不启动 Spring / 数据库。第 5 条用例反向锁住"它不该碰任何存储"。
 */
@ExtendWith(MockitoExtension.class)
class FlowDesignMetaTest {

    /** 与前端 Vitest 共用的金样例（相对 backend/ 工作目录），只含两端必须一致的部分 */
    private static final Path GOLDEN_FILE = Path.of("..", "test-fixtures", "golden", "flow-design-meta.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ApprovalFlowMapper flowMapper;
    @Mock
    private ApprovalFlowVersionMapper versionMapper;
    @Mock
    private ApplyTypeMapper applyTypeMapper;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private ApprovalFlowServiceImpl service;

    // ------------------------------------------------------------------
    // 1. 派生自枚举（防"手写第二份字面量"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("深度上限取自 FlowCondition.MAX_DEPTH，而不是 service 里的字面量")
    void conditionMaxDepthComesFromValidatorConstant() {
        assertEquals(FlowCondition.MAX_DEPTH, service.designMeta().getConditionMaxDepth(),
                "元数据的深度上限必须与发布校验读同一个常量，否则会出现"
                        + "「设计器允许加、后端拒绝发布」");
    }

    @Test
    @DisplayName("scopes 逐个对应 FlowScope，禁用集逐字等于枚举声明")
    void scopesMirrorFlowScopeDeclaration() {
        List<FlowDesignMetaVO.Scope> scopes = service.designMeta().getScopes();

        assertEquals(FlowScope.values().length, scopes.size(), "每个业务域都必须出现在元数据里");
        for (int i = 0; i < FlowScope.values().length; i++) {
            FlowScope expected = FlowScope.values()[i];
            FlowDesignMetaVO.Scope actual = scopes.get(i);

            assertEquals(expected.name(), actual.getCode());
            assertEquals(expected.getLabel(), actual.getLabel());
            assertEquals(expected.forbiddenRuleTypes().stream().map(ApproverRuleType::name).toList(),
                    actual.getForbiddenRuleTypes(),
                    "域 " + expected.name() + " 的禁用集与枚举不同源 —— 这正是 C8 要根除的漂移");
        }
    }

    @Test
    @DisplayName("ruleTypes 逐个对应 ApproverRuleType，参数槽位逐字等于枚举声明")
    void ruleTypesMirrorApproverRuleTypeDeclaration() {
        List<FlowDesignMetaVO.RuleType> ruleTypes = service.designMeta().getRuleTypes();

        assertEquals(ApproverRuleType.values().length, ruleTypes.size());
        for (int i = 0; i < ApproverRuleType.values().length; i++) {
            ApproverRuleType expected = ApproverRuleType.values()[i];
            FlowDesignMetaVO.RuleType actual = ruleTypes.get(i);

            assertEquals(expected.name(), actual.getCode());
            assertEquals(expected.getLabel(), actual.getLabel());
            assertEquals(expected.params(), actual.getParams());
        }
    }

    // ------------------------------------------------------------------
    // 2. 等于共享金样例（防跨语言漂移）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("响应等于共享金样例 flow-design-meta.json（前端兜底默认读同一份文件）")
    void matchesSharedGoldenContract() throws IOException {
        JsonNode golden = golden();
        FlowDesignMetaVO meta = service.designMeta();

        assertEquals(golden.path("conditionMaxDepth").asInt(), meta.getConditionMaxDepth());

        JsonNode goldenScopes = golden.path("scopes");
        assertEquals(goldenScopes.size(), meta.getScopes().size());
        for (int i = 0; i < goldenScopes.size(); i++) {
            JsonNode expected = goldenScopes.get(i);
            FlowDesignMetaVO.Scope actual = meta.getScopes().get(i);
            assertEquals(expected.path("code").asText(), actual.getCode());
            assertEquals(textList(expected.path("forbiddenRuleTypes")), actual.getForbiddenRuleTypes(),
                    "域 " + actual.getCode() + " 的禁用集与金样例不一致："
                            + "后端改了枚举，前端兜底与金样例必须同步更新");
        }

        // ruleTypes 按 code → params 比对，**不比顺序**：两端对下拉顺序有各自的编排
        // （前端把「申请人直属领导」编在来源列表末尾，后端按枚举声明顺序），
        // 这属于编辑器词汇，不是需要跨端一致的事实。要钉的是"每种来源要配哪些参数"。
        JsonNode goldenRuleTypes = golden.path("ruleTypes");
        Map<String, List<String>> expectedParams = toMap(goldenRuleTypes);
        Map<String, List<String>> actualParams = meta.getRuleTypes().stream()
                .collect(Collectors.toMap(FlowDesignMetaVO.RuleType::getCode,
                        FlowDesignMetaVO.RuleType::getParams));
        assertEquals(expectedParams, actualParams);

        assertEquals(goldenRuleTypes.size(), meta.getRuleTypes().size(), "不能有金样例未记录的新来源");
    }

    // ------------------------------------------------------------------
    // 3. 反向锁：它不该碰任何存储
    // ------------------------------------------------------------------

    @Test
    @DisplayName("元数据是纯静态派生：不读库、不读配置（因此无需缓存、无需审计）")
    void readsNoPersistence() {
        service.designMeta();

        verifyNoInteractions(flowMapper, versionMapper, applyTypeMapper, systemConfigService);
    }

    // ------------------------------------------------------------------
    // 装载金样例
    // ------------------------------------------------------------------

    private static JsonNode golden() throws IOException {
        if (!Files.exists(GOLDEN_FILE)) {
            throw new IllegalStateException("找不到金样例文件：" + GOLDEN_FILE.toAbsolutePath()
                    + "（它是前后端设计器约束一致性的唯一事实源，不能被删除）");
        }
        try (InputStream in = Files.newInputStream(GOLDEN_FILE)) {
            return MAPPER.readTree(in);
        }
    }

    private static List<String> textList(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static Map<String, List<String>> toMap(JsonNode ruleTypes) {
        return java.util.stream.StreamSupport.stream(ruleTypes.spliterator(), false)
                .collect(Collectors.toMap(node -> node.path("code").asText(),
                        node -> textList(node.path("params")),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }
}
