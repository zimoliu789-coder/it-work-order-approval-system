package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.copyOf;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.find;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程定义 JSON 编解码（Phase 15 · Wave 1）—— {@link FlowDefinitionCodec} 回归测试。
 *
 * <p>重点固化的是一份**对外契约**：默认出口在 JSON 里的键是 {@code else}，
 * 而 Java 字段名只能是 {@code elseBranch}（{@code else} 是关键字）。这条映射靠
 * {@code @JsonProperty("else")} 实现 —— 一旦注解被误删，Java 侧一切正常、
 * 前端与接口回归却会静默失配（{@code else} 变成 {@code elseBranch}，
 * 校验器读到 null 就会判"没有默认出口"）。正因如此，这里直接断言**序列化文本**，
 * 而不是只断言反序列化后的对象。
 *
 * <p>另外固化"向后兼容"取向：未知属性一律忽略，旧存档不该因为新增字段就读不出来。
 *
 * <p>纯静态逻辑，不依赖 Spring / 数据库。
 */
class FlowDefinitionCodecTest {

    /** 与接口回归 {@code _p15-regression.sh} 完全一致的定义文本 */
    private static final String REGRESSION_JSON = """
            {"start":"n1","nodes":[
            {"key":"n1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","approverRules":[{"type":"ROLE","roleCode":"admin"}],"next":"c1"},
            {"key":"c1","type":"CONDITION","name":"金额判断","branches":[
              {"key":"b1","name":"金额大于5000","next":"n2","condition":{"logic":"AND","rules":[{"field":"amount","op":"GT","value":"5000"}]}},
              {"key":"b2","name":"其它情况","else":true,"next":"n3"}]},
            {"key":"n2","type":"APPROVAL","name":"财务复核","signType":"ALL_SIGN","approverRules":[{"type":"APPLICANT_CHOOSE","scope":"ALL","minCount":1,"maxCount":2}],"next":"n3"},
            {"key":"n3","type":"APPROVAL","name":"归档确认","signType":"ANY_SIGN","approverRules":[{"type":"FORM_USER_FIELD","fieldKey":"receiver"}],"next":"end"},
            {"key":"end","type":"END","name":"结束"}]}
            """;

    @Test
    @DisplayName("读取接口回归使用的定义文本：结构、分支、规则参数全部还原")
    void read_regressionJson() {
        FlowDefinition definition = FlowDefinitionCodec.read(REGRESSION_JSON);

        assertEquals("n1", definition.getStart());
        assertEquals(5, definition.getNodes().size());

        FlowNode condition = find(definition, "c1");
        assertEquals(FlowNodeType.CONDITION, condition.typeEnum());
        assertEquals(2, condition.getBranches().size());
        FlowBranch first = condition.getBranches().get(0);
        assertEquals("b1", first.getKey());
        assertFalse(first.isElse());
        assertEquals(FlowOperator.GT, FlowOperator.of(first.getCondition().getRules().get(0).getOp()));
        assertTrue(condition.getBranches().get(1).isElse(), "b2 应被识别为默认出口");

        ApproverRule choose = find(definition, "n2").getApproverRules().get(0);
        assertEquals(ApproverRuleType.APPLICANT_CHOOSE, ApproverRuleType.of(choose.getType()));
        assertEquals(ApproverRuleType.ChooseScope.ALL, ApproverRuleType.ChooseScope.of(choose.getScope()));
        assertEquals(1, choose.getMinCount());
        assertEquals(2, choose.getMaxCount());

        assertEquals("receiver", find(definition, "n3").getApproverRules().get(0).getFieldKey());
        assertEquals(FlowNodeType.END, find(definition, "end").typeEnum());
    }

    @Test
    @DisplayName("默认出口序列化为 JSON 键 else（而不是 Java 字段名 elseBranch）")
    void write_usesElseJsonKey() {
        String json = FlowDefinitionCodec.write(regressionFlow());
        assertTrue(json.contains("\"else\":true"), "对外契约是 else，实际：" + json);
        assertFalse(json.contains("elseBranch"), "不应泄露 Java 内部字段名：" + json);
    }

    @Test
    @DisplayName("写 → 读 往返保持等价（分支汇合、else 标记、规则参数均不丢失）")
    void roundTrip_isLossless() {
        FlowDefinition original = regressionFlow();
        FlowDefinition restored = FlowDefinitionCodec.read(FlowDefinitionCodec.write(original));

        assertEquals(original.getStart(), restored.getStart());
        assertEquals(original.getNodes().size(), restored.getNodes().size());
        for (FlowNode node : original.getNodes()) {
            FlowNode other = find(restored, node.getKey());
            assertEquals(node.getType(), other.getType());
            assertEquals(node.getName(), other.getName());
            assertEquals(node.getNext(), other.getNext());
            assertEquals(node.getBranches().size(), other.getBranches().size());
            assertEquals(node.getApproverRules().size(), other.getApproverRules().size());
        }
        // else 标记经 JSON 往返后仍然成立（这正是 @JsonProperty 的关键作用）
        assertTrue(find(restored, "c1").getBranches().get(1).isElse());
        // 汇合关系仍然成立：b2.next 与 n2.next 都指向 n3
        assertEquals("n3", find(restored, "c1").getBranches().get(1).getNext());
        assertEquals("n3", find(restored, "n2").getNext());
    }

    @Test
    @DisplayName("未知属性被忽略（旧存档不因新增字段而无法读取）")
    void read_toleratesUnknownProperties() {
        String withExtra = """
                {"start":"end","futureField":123,"nodes":[
                {"key":"end","type":"END","name":"结束","futureNested":{"a":1}}]}
                """;
        FlowDefinition definition = FlowDefinitionCodec.read(withExtra);
        assertEquals("end", definition.getStart());
        assertEquals(1, definition.getNodes().size());
    }

    @Test
    @DisplayName("空 / 格式非法：抛 FLOW_DEFINITION_INVALID（指向配置本身，而不是 INTERNAL_ERROR）")
    void read_invalidInput() {
        for (String bad : new String[]{null, "", "   "}) {
            BusinessException exception = assertThrows(BusinessException.class, () -> FlowDefinitionCodec.read(bad));
            assertEquals(ErrorCode.FLOW_DEFINITION_INVALID, exception.getErrorCode());
        }

        BusinessException malformed = assertThrows(BusinessException.class,
                () -> FlowDefinitionCodec.read("{ this is not json"));
        assertEquals(ErrorCode.FLOW_DEFINITION_INVALID, malformed.getErrorCode());
        assertTrue(malformed.getMessage().contains("无法解析"), malformed.getMessage());
    }

    @Test
    @DisplayName("indexByKey / node：按 key 定位节点，顺序保持定义顺序")
    void indexByKey_keepsDefinitionOrder() {
        FlowDefinition definition = copyOf(regressionFlow());
        assertEquals(java.util.List.of("n1", "c1", "n2", "n3", "end"),
                new java.util.ArrayList<>(definition.indexByKey().keySet()));
        assertEquals("金额判断", definition.node("c1").getName());
        assertEquals(null, definition.node("ghost"));
    }
}
