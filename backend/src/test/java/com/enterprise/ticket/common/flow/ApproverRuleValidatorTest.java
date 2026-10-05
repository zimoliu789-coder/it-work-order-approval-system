package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.applicantChoose;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.bizGroupApproverRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.formUserField;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.handlerGroupRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.specificUserRule;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批人规则参数校验（Phase 15 · Wave 1）—— {@link ApproverRuleValidator} 回归测试。
 *
 * <p>为什么逐条覆盖：规则的参数完备性一旦漏检，缺陷不会当场暴露，
 * 而是等到某天有人提交工单时才表现为"这个节点没人可审批"，
 * 而彼时流程已被申请类型引用、工单已产生，回滚成本远高于发布时拦一下。
 * 参数校验还有一层隐性风险：**开启了列约束放宽**（{@code approver_id} 允许 NULL 后），
 * 数据库不再替我们兜底，校验器成了唯一防线。
 *
 * <p>纯静态逻辑，不依赖 Spring / 数据库。
 */
class ApproverRuleValidatorTest {

    private static final FormSchema SCHEMA = schema(
            field("receiver", "接收人", FormFieldType.USER.name()),
            field("title", "标题", FormFieldType.TEXT.name())
    );

    private static String invalid(ApproverRule rule, FormSchema schema) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> ApproverRuleValidator.validate(rule, 0, "财务复核", schema));
        assertEquals(ErrorCode.FLOW_DEFINITION_INVALID, exception.getErrorCode());
        return exception.getMessage();
    }

    private static void valid(ApproverRule rule, FormSchema schema) {
        assertDoesNotThrow(() -> ApproverRuleValidator.validate(rule, 0, "财务复核", schema));
    }

    @Test
    @DisplayName("错误信息带上节点名与规则序号，便于定位")
    void errorMessage_locatesTheRule() {
        String message = invalid(new ApproverRule(), null);
        assertTrue(message.contains("节点「财务复核」"), message);
        assertTrue(message.contains("第 1 条"), message);
    }

    @Test
    @DisplayName("空规则 / 来源类型非法被拒绝")
    void nullAndUnknownType_rejected() {
        assertTrue(invalid(null, null).contains("为空"));

        ApproverRule unknown = new ApproverRule();
        unknown.setType("TELEPATHY");
        assertTrue(invalid(unknown, null).contains("来源取值不合法"));
    }

    @Test
    @DisplayName("指定人员：必须至少一人，且不能含 null 占位")
    void specificUser_rules() {
        valid(specificUserRule(1L, 2L), null);
        assertTrue(invalid(specificUserRule(), null).contains("未选择任何人员"));

        ApproverRule withNull = new ApproverRule();
        withNull.setType(ApproverRuleType.SPECIFIC_USER.name());
        withNull.setUserIds(new ArrayList<>(java.util.Arrays.asList(1L, null)));
        assertTrue(invalid(withNull, null).contains("未选择任何人员"));

        ApproverRule nullList = new ApproverRule();
        nullList.setType(ApproverRuleType.SPECIFIC_USER.name());
        assertTrue(invalid(nullList, null).contains("未选择任何人员"));
    }

    @Test
    @DisplayName("指定角色：必须有角色码")
    void role_rules() {
        valid(roleRule("admin"), null);
        assertTrue(invalid(roleRule(null), null).contains("未选择角色"));
        assertTrue(invalid(roleRule("  "), null).contains("未选择角色"));
    }

    @Test
    @DisplayName("部门审批人：无参数，恒定通过（提交时按申请人所属分组解析）")
    void bizGroupApprovers_needsNoParam() {
        valid(bizGroupApproverRule(), null);
    }

    @Test
    @DisplayName("最终处理部门成员：必须选择小组")
    void handlerGroup_rules() {
        valid(handlerGroupRule(3L), null);
        assertTrue(invalid(handlerGroupRule(null), null).contains("未选择处理小组"));
    }

    @Test
    @DisplayName("表单人员字段：必须有字段；提供 schema 时必须是「人员选择」类型且字段存在")
    void formUserField_rules() {
        // 无 schema：只校验"填了字段"
        valid(formUserField("receiver"), null);
        assertTrue(invalid(formUserField(null), null).contains("未指定字段"));

        // 有 schema：字段不存在 / 类型不是人员选择
        assertTrue(invalid(formUserField("noSuchField"), SCHEMA).contains("不存在"));
        assertTrue(invalid(formUserField("title"), SCHEMA).contains("不是「人员选择」类型"));
        valid(formUserField("receiver"), SCHEMA);
    }

    @Test
    @DisplayName("申请人自选：范围取值必须合法")
    void applicantChoose_scopeMustBeValid() {
        valid(applicantChoose("ALL", null, 1, 3), null);
        assertTrue(invalid(applicantChoose(null, null, 1, 1), null).contains("范围取值不合法"));
        assertTrue(invalid(applicantChoose("EVERYBODY", null, 1, 1), null).contains("范围取值不合法"));
    }

    @Test
    @DisplayName("申请人自选：范围为角色 / 分组时必须给出具体取值，分组取值必须是合法 id")
    void applicantChoose_scopeValueRules() {
        valid(applicantChoose("ROLE", "admin", 1, 1), null);
        assertTrue(invalid(applicantChoose("ROLE", null, 1, 1), null).contains("未选择角色"));
        assertTrue(invalid(applicantChoose("ROLE", "  ", 1, 1), null).contains("未选择角色"));

        valid(applicantChoose("GROUP", "12", 1, 1), null);
        assertTrue(invalid(applicantChoose("GROUP", null, 1, 1), null).contains("未选择分组"));
        assertTrue(invalid(applicantChoose("GROUP", "采购组", 1, 1), null).contains("不是合法 id"));
    }

    @Test
    @DisplayName("申请人自选：人数区间必须自洽（min>=1 且 max>=min）")
    void applicantChoose_countRules() {
        assertTrue(invalid(applicantChoose("ALL", null, null, 1), null).contains("最少选择人数必须 >= 1"));
        assertTrue(invalid(applicantChoose("ALL", null, 0, 1), null).contains("最少选择人数必须 >= 1"));
        assertTrue(invalid(applicantChoose("ALL", null, 2, null), null).contains("最多选择人数必须 >= 最少选择人数"));
        assertTrue(invalid(applicantChoose("ALL", null, 3, 2), null).contains("最多选择人数必须 >= 最少选择人数"));
        valid(applicantChoose("ALL", null, 2, 2), null);
    }

    @Test
    @DisplayName("按序号定位：第二条规则的报错信息里序号是 2")
    void ruleIndex_isReflected() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> ApproverRuleValidator.validate(roleRule(null), 1, "主管审批", null));
        assertTrue(exception.getMessage().contains("第 2 条"), exception.getMessage());
    }

    @Test
    @DisplayName("FlowDefinitionValidator 会在发布时对节点的每条规则逐条调用本校验器")
    void wiredIntoFlowDefinitionValidator() {
        FlowDefinition flow = FlowTestFixtures.copyOf(FlowTestFixtures.regressionFlow());
        FlowTestFixtures.find(flow, "n1").setApproverRules(new ArrayList<>(List.of(roleRule(null))));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> FlowDefinitionValidator.validate(flow, null));
        assertTrue(exception.getMessage().contains("未选择角色"), exception.getMessage());
    }
}
