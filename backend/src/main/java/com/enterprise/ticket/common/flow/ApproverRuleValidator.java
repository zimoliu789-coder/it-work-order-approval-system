package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;

/**
 * 审批人规则参数校验。
 *
 * <p>发布流程时逐条校验规则的参数完备性。之所以要在**发布时**就拦住，
 * 是因为配置错误的代价不对称：
 * <ul>
 *   <li>拦住 → 管理员当场在设计器里改一下；</li>
 *   <li>放过 → 直到某天有人提交时才发现"这个节点没人可审批"，
 *       而此时流程已被引用、工单已产生，回滚成本高得多。</li>
 * </ul>
 *
 * <p>这与一期表单模板"发布是唯一严格校验点"的取舍完全一致。
 */
public final class ApproverRuleValidator {

    private ApproverRuleValidator() {
    }

    /**
     * 校验一条规则。
     *
     * @param rule     规则
     * @param index    规则在节点内的序号（用于定位报错位置）
     * @param nodeName 所属节点名（用于定位报错位置）
     * @param schema   该流程将被绑定到的表单版本 schema（校验 FORM_USER_FIELD 时用；可为 null 表示无表单上下文，跳过字段存在性校验）
     */
    public static void validate(ApproverRule rule, int index, String nodeName, FormSchema schema) {
        validate(rule, index, nodeName, schema, FlowScope.CUSTOM);
    }

    /**
     * 校验一条规则（指定业务域，M1）。
     *
     * <p>两条规则在 {@link FlowScope#BORROW} 下**被禁用**（由
     * {@code FlowDefinitionValidator} 负责报错，因为"禁用"是业务域层面的策略而非规则参数问题）：
     * {@code FORM_USER_FIELD} 与 {@code APPLICANT_CHOOSE} —— 借用提交页既无表单字段也无选人器。
     * 本条仍会照常校验它们在自定义域下的参数完备性，以保证同一条规则在两种域下的
     * 参数校验口径一致（不会出现"换个域这种残缺配置就合法了"）。
     *
     * @param rule     规则
     * @param index    规则在节点内的序号（用于定位报错位置）
     * @param nodeName 所属节点名（用于定位报错位置）
     * @param schema   字段域 schema（校验 FORM_USER_FIELD 时用；可为 null 表示无表单上下文）
     * @param scope    业务域；{@link FlowScope#BORROW} 的字段域由上层换为 {@code BorrowFieldCatalog}
     */
    public static void validate(ApproverRule rule, int index, String nodeName, FormSchema schema,
                                FlowScope scope) {
        String where = "节点「" + nodeName + "」的第 " + (index + 1) + " 条审批人规则";
        if (rule == null) {
            invalid(where + "为空");
        }
        ApproverRuleType type = ApproverRuleType.of(rule.getType());
        if (type == null) {
            invalid(where + "的审批人来源取值不合法：" + rule.getType());
        }
        switch (type) {
            case SPECIFIC_USER -> {
                if (rule.getUserIds() == null || rule.getUserIds().isEmpty()
                        || rule.getUserIds().stream().anyMatch(java.util.Objects::isNull)) {
                    invalid(where + "为「指定人员」，但未选择任何人员");
                }
            }
            case ROLE -> {
                if (!org.springframework.util.StringUtils.hasText(rule.getRoleCode())) {
                    invalid(where + "为「指定角色」，但未选择角色");
                }
            }
            case BIZ_GROUP_APPROVERS -> {
                // 无参数：提交时按申请人所属部门解析
            }
            case PARENT_DEPT_APPROVERS -> {
                // 无参数：提交时按申请人所属部门的**上级**部门解析。
                // 「已在根部门 → 解析为空」不是配置错误，而是组织事实，因此不在发布期拦。
            }
            case HANDLER_GROUP -> {
                if (rule.getHandlerGroupId() == null) {
                    invalid(where + "为「最终处理部门成员」，但未选择处理小组");
                }
            }
            case FORM_USER_FIELD -> {
                if (!org.springframework.util.StringUtils.hasText(rule.getFieldKey())) {
                    invalid(where + "为「表单人员字段」，但未指定字段");
                }
                validateUserField(rule.getFieldKey(), where, schema);
            }
            case LEADER -> {
                // 无参数：提交时取 applicant.users.leader_id 解析（四种失败情形见 ApproverRuleResolver）
            }
            case PREV_ASSIGN -> {
                // 人员在上一节点通过时回填，故此处只校验「要指定几个人」。
                // assignCount 缺省视为 1，但配了就必须 >= 1。
                Integer assignCount = rule.getAssignCount();
                if (assignCount != null && assignCount < 1) {
                    invalid(where + "为「上一节点审批人指定」，指定人数必须 >= 1");
                }
            }
            case APPLICANT_CHOOSE -> validateApplicantChoose(rule, where);
        }
    }

    private static void validateUserField(String fieldKey, String where, FormSchema schema) {
        if (schema == null) {
            return;
        }
        FormField field = schema.getFields().stream()
                .filter(f -> fieldKey.equals(f.getKey()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            invalid(where + "引用的表单字段「" + fieldKey + "」不存在");
        }
        FormFieldType fieldType = FormFieldType.of(field.getType());
        if (fieldType == null || fieldType.getValueKind() != FormFieldType.ValueKind.USER_REF) {
            invalid(where + "引用的字段「" + field.getLabel() + "」不是「人员选择」类型");
        }
    }

    private static void validateApplicantChoose(ApproverRule rule, String where) {
        ApproverRuleType.ChooseScope scope = ApproverRuleType.ChooseScope.of(rule.getScope());
        if (scope == null) {
            invalid(where + "为「申请人自选」，但可选范围取值不合法：" + rule.getScope());
        }
        if (scope == ApproverRuleType.ChooseScope.ROLE
                && !org.springframework.util.StringUtils.hasText(rule.getScopeValue())) {
            invalid(where + "为「申请人自选」，范围为指定角色但未选择角色");
        }
        if (scope == ApproverRuleType.ChooseScope.GROUP) {
            if (!org.springframework.util.StringUtils.hasText(rule.getScopeValue())) {
                invalid(where + "为「申请人自选」，范围为指定分组但未选择分组");
            }
            try {
                Long.parseLong(rule.getScopeValue().trim());
            } catch (NumberFormatException e) {
                invalid(where + "为「申请人自选」，范围分组的取值不是合法 id：" + rule.getScopeValue());
            }
        }
        Integer min = rule.getMinCount();
        Integer max = rule.getMaxCount();
        if (min == null || min < 1) {
            invalid(where + "为「申请人自选」，最少选择人数必须 >= 1");
        }
        if (max == null || max < min) {
            invalid(where + "为「申请人自选」，最多选择人数必须 >= 最少选择人数");
        }
    }

    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.FLOW_DEFINITION_INVALID, message);
    }
}
