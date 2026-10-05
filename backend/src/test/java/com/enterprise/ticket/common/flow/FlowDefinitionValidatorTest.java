package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.applicantChoose;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.copyOf;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.find;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.formUserField;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程定义发布校验（Phase 15 · Wave 1）—— {@link FlowDefinitionValidator} 回归测试。
 *
 * <p>存在的理由：发布校验器是**唯一**能拦住"结构不合法流程"的地方。它的检查项
 * （唯一/可达/无环/穷尽默认出口/所有节点可到 END/条件字段存在且类型可比）
 * 一旦漏掉一项，后果不是"测试变红"，而是**运行时工单卡在待审批再也推不动**，
 * 且没有任何报错指向流程本身。因此这里逐项覆盖每一个校验分支。
 *
 * <p>纯静态逻辑，不依赖 Spring / Mockito / 数据库。
 */
class FlowDefinitionValidatorTest {

    private static final FormSchema SCHEMA = schema(
            field("amount", "金额", FormFieldType.NUMBER.name()),
            field("title", "标题", FormFieldType.TEXT.name()),
            field("receiver", "接收人", FormFieldType.USER.name())
    );

    /** 断言校验不通过，并返回异常信息（供进一步断言定位文案） */
    private static String invalid(FlowDefinition definition, FormSchema schema) {
        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> FlowDefinitionValidator.validate(definition, schema));
        assertEquals(ErrorCode.FLOW_DEFINITION_INVALID, exception.getErrorCode());
        return exception.getMessage();
    }

    @Test
    @DisplayName("结构完整的流程（含分支汇合）通过校验")
    void validFlow_passes() {
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(regressionFlow(), SCHEMA));
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(regressionFlow(), null),
                "无表单上下文（发布时尚未绑定类型）也应通过结构校验");
    }

    @Test
    @DisplayName("空定义 / 无节点被拒绝")
    void emptyDefinition_rejected() {
        assertTrue(invalid(null, null).contains("流程定义为空"));

        FlowDefinition empty = new FlowDefinition();
        assertTrue(invalid(empty, null).contains("至少要有一个节点"));
    }

    @Test
    @DisplayName("节点标识：格式非法 / 重复 / 未设置")
    void nodeKey_rules() {
        FlowDefinition bad = copyOf(regressionFlow());
        find(bad, "n1").setKey("1starts-with-digit");
        assertTrue(invalid(bad, null).contains("节点标识不合法"));

        FlowDefinition duplicate = copyOf(regressionFlow());
        find(duplicate, "n2").setKey("n1");
        assertTrue(invalid(duplicate, null).contains("节点标识重复"));

        FlowDefinition blank = copyOf(regressionFlow());
        find(blank, "n3").setKey("");
        assertTrue(invalid(blank, null).contains("未设置标识"));
    }

    @Test
    @DisplayName("起始节点必须设置且存在")
    void start_mustExist() {
        FlowDefinition blank = copyOf(regressionFlow());
        blank.setStart("");
        assertTrue(invalid(blank, null).contains("未设置起始节点"));

        FlowDefinition dangling = copyOf(regressionFlow());
        dangling.setStart("ghost");
        assertTrue(invalid(dangling, null).contains("起始节点不存在"));
    }

    @Test
    @DisplayName("节点名称必填且不超过上限")
    void nodeName_requiredAndBounded() {
        FlowDefinition noName = copyOf(regressionFlow());
        find(noName, "n1").setName("  ");
        assertTrue(invalid(noName, null).contains("未设置名称"));

        FlowDefinition tooLong = copyOf(regressionFlow());
        find(tooLong, "n1").setName("名".repeat(65));
        assertTrue(invalid(tooLong, null).contains("名称过长"));
    }

    @Test
    @DisplayName("审批节点：必须配置审批人、必须有 next、签署方式必须合法、不应有分支")
    void approvalNode_rules() {
        FlowDefinition noRule = copyOf(regressionFlow());
        find(noRule, "n1").setApproverRules(new ArrayList<>());
        assertTrue(invalid(noRule, null).contains("未配置任何审批人"));

        FlowDefinition noNext = copyOf(regressionFlow());
        find(noNext, "n1").setNext(null);
        assertTrue(invalid(noNext, null).contains("未指定下一节点"));

        FlowDefinition badNext = copyOf(regressionFlow());
        find(badNext, "n1").setNext("ghost");
        assertTrue(invalid(badNext, null).contains("下一节点不存在"));

        FlowDefinition badSign = copyOf(regressionFlow());
        find(badSign, "n1").setSignType("MAYBE_SIGN");
        assertTrue(invalid(badSign, null).contains("签署方式不合法"));

        FlowDefinition withBranch = copyOf(regressionFlow());
        find(withBranch, "n1").setBranches(new ArrayList<>(List.of(elseBranch("x", "x", "end"))));
        assertTrue(invalid(withBranch, null).contains("是审批节点，不应配置分支"));
    }

    @Test
    @DisplayName("审批节点最多一条「申请人自选」—— 多于一条无法按 nodeKey 归属所选人员")
    void applicantChoose_atMostOne() {
        FlowDefinition flow = copyOf(regressionFlow());
        find(flow, "n2").setApproverRules(new ArrayList<>(List.of(
                applicantChoose("ALL", null, 1, 1),
                applicantChoose("ALL", null, 1, 1))));
        assertTrue(invalid(flow, null).contains("最多只能有 1 条"));
    }

    @Test
    @DisplayName("条件节点：必须恰有一个默认出口，且分支标识唯一 / 有名称 / 有去向")
    void conditionNode_rules() {
        FlowDefinition noElse = copyOf(regressionFlow());
        find(noElse, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))))));
        assertTrue(invalid(noElse, null).contains("必须且只能有一个默认出口"));

        FlowDefinition twoElse = copyOf(regressionFlow());
        find(twoElse, "c1").setBranches(new ArrayList<>(List.of(
                elseBranch("b1", "a", "n2"),
                elseBranch("b2", "b", "n3"))));
        assertTrue(invalid(twoElse, null).contains("必须且只能有一个默认出口"));

        FlowDefinition dupBranch = copyOf(regressionFlow());
        find(dupBranch, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))),
                elseBranch("b1", "其它", "n3"))));
        assertTrue(invalid(dupBranch, null).contains("标识重复"));

        FlowDefinition noBranchName = copyOf(regressionFlow());
        find(noBranchName, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", " ", "n2", cond("AND", rule("amount", "GT", "5000"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(noBranchName, null).contains("未设置名称"));

        FlowDefinition noTarget = copyOf(regressionFlow());
        find(noTarget, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "大于5000", null, cond("AND", rule("amount", "GT", "5000"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(noTarget, null).contains("未指定去向节点"));
    }

    @Test
    @DisplayName("条件节点不应额外配 next / 审批人")
    void conditionNode_forbidsNextAndRules() {
        FlowDefinition withNext = copyOf(regressionFlow());
        find(withNext, "c1").setNext("end");
        assertTrue(invalid(withNext, null).contains("出口写在分支里"));

        FlowDefinition withRules = copyOf(regressionFlow());
        find(withRules, "c1").setApproverRules(new ArrayList<>(List.of(roleRule("admin"))));
        assertTrue(invalid(withRules, null).contains("不应配置审批人"));
    }

    @Test
    @DisplayName("结束节点不应配 next / 分支 / 审批人")
    void endNode_forbidsEverything() {
        FlowDefinition withNext = copyOf(regressionFlow());
        find(withNext, "end").setNext("n1");
        assertTrue(invalid(withNext, null).contains("结束节点，不应配置 next"));

        FlowDefinition withBranch = copyOf(regressionFlow());
        find(withBranch, "end").setBranches(new ArrayList<>(List.of(elseBranch("x", "x", "n1"))));
        assertTrue(invalid(withBranch, null).contains("结束节点，不应配置分支"));

        FlowDefinition withRules = copyOf(regressionFlow());
        find(withRules, "end").setApproverRules(new ArrayList<>(List.of(roleRule("admin"))));
        assertTrue(invalid(withRules, null).contains("结束节点，不应配置审批人"));
    }

    @Test
    @DisplayName("图结构：检测环 / 不可达（死路由单节点规则先行拦截）")
    void graphRules() {
        FlowDefinition cycle = copyOf(regressionFlow());
        find(cycle, "n1").setNext("n1");
        assertTrue(invalid(cycle, null).contains("存在环"));

        FlowDefinition unreachable = copyOf(regressionFlow());
        unreachable.getNodes().add(approval("orphan", "孤儿", "ANY_SIGN", "end", roleRule("user")));
        assertTrue(invalid(unreachable, null).contains("不可达的节点"));

        // 「某节点通向不了 END」在单节点校验阶段就被拦下：审批节点必须指定 next（requireNext），
        // 且条件节点每个分支都必须有去向。因此真正的死路（next 置空）会先报「未指定下一节点」。
        // 图级的「无法到达结束节点」是无环 + 每节点必有出边两条规则下的**防御性兜底**，
        // 在现有规则体系下无法单独触达 —— 这里验证前置拦截确实生效即可。
        FlowDefinition deadEnd = copyOf(regressionFlow());
        find(deadEnd, "n3").setNext(null);
        assertTrue(invalid(deadEnd, null).contains("未指定下一节点"));
    }

    @Test
    @DisplayName("至少要有一个审批节点")
    void atLeastOneApprovalNode() {
        FlowDefinition onlyConditionAndEnd = new FlowDefinition();
        onlyConditionAndEnd.setStart("c1");
        onlyConditionAndEnd.setNodes(new ArrayList<>(List.of(
                com.enterprise.ticket.common.flow.FlowTestFixtures.condition("c1", "判断",
                        elseBranch("b1", "其它", "end")),
                end("end", "结束"))));
        assertTrue(invalid(onlyConditionAndEnd, null).contains("至少要有一个审批节点"));
    }

    @Test
    @DisplayName("节点数量上限 50")
    void nodeCountBounded() {
        FlowDefinition flow = copyOf(regressionFlow());
        for (int i = 0; i < 50; i++) {
            flow.getNodes().add(end("extra" + i, "额外" + i));
        }
        assertTrue(invalid(flow, null).contains("超过上限"));
    }

    @Test
    @DisplayName("条件字段：仅在提供 schema 时校验存在性与类型可比性")
    void conditionFieldRules() {
        // 无 schema：字段存在性延后到"绑定申请类型"时校验，发布阶段放行
        FlowDefinition unknownField = copyOf(regressionFlow());
        find(unknownField, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "未知字段", "n2", cond("AND", rule("noSuchField", "NOT_EMPTY", null))),
                elseBranch("b2", "其它", "n3"))));
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(unknownField, null));

        // 有 schema：字段不存在 → 拒绝
        assertTrue(invalid(unknownField, SCHEMA).contains("不存在"));

        // 有 schema：数值比较用在文本字段 → 拒绝
        FlowDefinition numericOnText = copyOf(regressionFlow());
        find(numericOnText, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "标题大于5", "n2", cond("AND", rule("title", "GT", "5"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(numericOnText, SCHEMA).contains("只能用于数字或日期字段"));

        // 有 schema：包含 用在数字字段 → 拒绝
        FlowDefinition containsOnNumber = copyOf(regressionFlow());
        find(containsOnNumber, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "金额包含5", "n2", cond("AND", rule("amount", "CONTAINS", "5"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(containsOnNumber, SCHEMA).contains("只能用于文本或选项字段"));

        // 布局元素不能作为条件
        FormSchema withLayout = schema(
                field("amount", "金额", FormFieldType.NUMBER.name()),
                field("note", "说明", FormFieldType.DESCRIPTION.name()));
        FlowDefinition layoutField = copyOf(regressionFlow());
        find(layoutField, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "说明为空", "n2", cond("AND", rule("note", "IS_EMPTY", null))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(layoutField, withLayout).contains("不能作为条件"));
    }

    @Test
    @DisplayName("条件规则：运算符非法 / 需要比较值却为空 / 条件列表为空")
    void conditionRuleRules() {
        FlowDefinition badOp = copyOf(regressionFlow());
        find(badOp, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "非法运算符", "n2", cond("AND", rule("amount", "APPROX", "5"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(badOp, null).contains("运算符不合法"));

        FlowDefinition missingValue = copyOf(regressionFlow());
        find(missingValue, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "缺比较值", "n2", cond("AND", rule("amount", "GT", null))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(missingValue, null).contains("未填写比较值"));

        FlowDefinition emptyRules = copyOf(regressionFlow());
        find(emptyRules, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "空条件", "n2", cond("AND")),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(emptyRules, null).contains("条件列表为空"));

        FlowDefinition badLogic = copyOf(regressionFlow());
        find(badLogic, "c1").setBranches(new ArrayList<>(List.of(
                branch("b1", "非法逻辑", "n2", cond("XOR", rule("amount", "GT", "5"))),
                elseBranch("b2", "其它", "n3"))));
        assertTrue(invalid(badLogic, null).contains("组合逻辑不合法"));
    }

    @Test
    @DisplayName("countApprovalNodes 只数审批节点（用于写入 node_count 与上限判定）")
    void countApprovalNodes_countsOnlyApproval() {
        assertEquals(3, FlowDefinitionValidator.countApprovalNodes(regressionFlow()));
        assertEquals(0, FlowDefinitionValidator.countApprovalNodes(null));
    }

    @Test
    @DisplayName("表单人员字段规则：提供 schema 时必须是「人员选择」类型")
    void formUserFieldRule_mustBeUserField() {
        FlowDefinition flow = copyOf(regressionFlow());
        find(flow, "n3").setApproverRules(new ArrayList<>(List.of(formUserField("title"))));
        assertTrue(invalid(flow, SCHEMA).contains("不是「人员选择」类型"));

        FlowDefinition unknown = copyOf(regressionFlow());
        find(unknown, "n3").setApproverRules(new ArrayList<>(List.of(formUserField("noSuchField"))));
        assertTrue(invalid(unknown, SCHEMA).contains("不存在"));
    }
}
