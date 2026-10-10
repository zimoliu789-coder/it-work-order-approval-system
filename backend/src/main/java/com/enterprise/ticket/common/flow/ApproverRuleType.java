package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.RoleCode;

import java.util.List;

/**
 * 审批人选择方式。
 *
 * <p>「一个节点的审批人从哪来」是本期的核心扩展点：一期把审批人写死在
 * {@code biz_group_approver.approver_id}（一人一步），这里改成**规则**，
 * 由 {@link ApproverRuleResolver} 在提交时解析成具体的 user_id 列表后再快照落库。
 *
 * <p> 新增 {@code LEADER}（申请人直属领导，依赖 {@code employee.leader_id}）与
 * {@code PREV_ASSIGN}（上一节点审批人指定，人员在审批动作时才回填）。
 *
 * <h2>{@link #params()} 是干什么的（ · W4-D / C8）</h2>
 * <p>每条规则都要携带若干配置参数（指定人员要 {@code userIds}、角色要 {@code roleCode}……），
 * 这些参数槽位此前只存在于两处**隐式**知识里：后端 {@code ApproverRuleValidator} 的
 * {@code switch} 分支（用到了哪些字段）与前端设计器的属性面板（要渲染哪些控件）。
 *
 * <p>把参数槽位声明在枚举上，是为了让设计器元数据接口
 * （{@code GET /api/approval-flows/design-meta}）能原样下发"这条规则需要配什么"，
 * 从而使"后端校验收哪些字段"与"前端让你填哪些字段"共享同一份声明。
 *
 * <p>取值必须与前端 {@code ApproverRuleParam} 联合类型一致；一致性由共享金样例
 * {@code test-fixtures/golden/flow-design-meta.json} 钉死（两端各有一侧测试对它断言）。
 */
public enum ApproverRuleType {

    /** 指定人员：直接点选若干 user_id */
    SPECIFIC_USER("指定人员", List.of("userIds")),

    /** 指定角色：解析为该角色的全部在职用户 */
    ROLE("指定角色", List.of("roleCode")),

    /**
     * 申请人直属领导：取 {@code employee.leader_id}。
     *
     * <p>四种情形都算"解析不出"并走超管兜底：未配置 / 已离职 / 账号停用 /
     * **领导就是申请人本人**（自审回避）。
     */
    LEADER("申请人直属领导", List.of()),

    /**
     * 部门主管：解析为**申请人所在部门**的部门主管。
     *
     * <p>枚举名保留 {@code BIZ_GROUP_APPROVERS} 是**刻意的**：这个名字会被写进
     * 已发布的流程定义 JSON 里，改名等于让存量流程一夜之间"规则无法识别"。
     * 语义已从「业务分组配置的审批人」换成「部门主管」，但对外标识不动。
     */
    BIZ_GROUP_APPROVERS("部门主管", List.of()),

    /**
     * 上级部门主管：解析为**申请人所在部门的上层部门**的部门主管。
     *
     * <h2>它为什么必须是一个独立的规则类型</h2>
     * <p>的金额分档要求「设备金额 &gt; 5000 元时加一级**上级**部门主管审批」。
     * 这与 {@link #BIZ_GROUP_APPROVERS}（**本**部门主管）只差一个层级，却不能用参数表达：
     * 两者的语义主体不同（"我部门的负责人" vs "管我部门的那一层负责人"），
     * 混成一个带 offset 参数的类型，会让设计器上出现"部门主管（层级 -1）"这种
     * 需要理解树结构才能填对的控件。拆开之后，设计器只要列一个人话标签即可。
     *
     * <p>解析口径：取 {@code department.parent_id} 指向的那一级部门的部门主管；
     * 已经是根部门（没有上级）时解析为空 → 由调用方走超管兜底（与其它规则同一契约）。
     */
    PARENT_DEPT_APPROVERS("上级部门主管", List.of()),

    /**
     * 最终处理部门成员：解析为该部门（{@code department.handler_group = 1}）的在职成员。
     *
     * <p>参数槽位名 {@code handlerGroupId} 同样是存量标识，实际存的是**部门 id**。
     */
    HANDLER_GROUP("最终处理部门成员", List.of("handlerGroupId")),

    /**
     * 表单人员字段：取该申请表单中某个「人员选择」字段的值作为审批人。
     *
     * <p>让"谁审批"由表单内容决定（例如申请人填的"采购专员"），与一期动态表单形成闭环。
     */
    FORM_USER_FIELD("表单人员字段", List.of("fieldKey")),

    /**
     * 上一节点审批人指定：人员在提交时未知，由上一节点通过时回填。
     *
     * <p>硬约束（见 {@link FlowDefinitionValidator}）：必须是该节点唯一规则、
     * 只支持 {@code ANY_SIGN}、且该节点前面必须已存在审批节点。
     * 提交时物化为 {@code approver_id = NULL} 的占位行（{@code status = PENDING}）。
     *
     * <p> 增补 {@code assignScope} 参数：指定者的**可选范围**。
     * 配了范围之后，服务端在回填时校验被指定人确实落在范围内 —— 否则
     * "从 IT执行人里选" 只是一句前端提示，非法的人照样指得进来。
     */
    PREV_ASSIGN("上一节点审批人指定", List.of("assignCount", "assignScope")),

    /**
     * 申请人自选：提交时由申请人在限定范围内选择。
     *
     * <p>解析不由服务端的 {@link ApproverRuleResolver} 完成（它没有"人的选择"），
     * 而是由提交请求携带、经服务端校验后写入快照。
     */
    APPLICANT_CHOOSE("申请人自选", List.of("choose"));

    private final String label;

    /** 该规则需要携带的参数槽位（有序，前端按此渲染输入控件） */
    private final List<String> params;

    ApproverRuleType(String label, List<String> params) {
        this.label = label;
        this.params = List.copyOf(params);
    }

    public String getLabel() {
        return label;
    }

    /**
     * 该规则需要配置的参数槽位（顺序稳定）。
     *
     * <p>槽位名与 {@code ApproverRule} 上的字段一一对应：{@code userIds} / {@code roleCode} /
     * {@code handlerGroupId} / {@code fieldKey} / {@code assignCount} / {@code assignScope} /
     * {@code choose}。
     * 其中 {@code choose} 是一个**复合槽位**（对应 {@code scope} + {@code scopeValue} +
     * {@code minCount} + {@code maxCount} 四个字段），因为设计器上它就是一组控件。
     */
    public List<String> params() {
        return params;
    }

    public static ApproverRuleType of(String value) {
        if (value == null) {
            return null;
        }
        for (ApproverRuleType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 申请人自选的范围类型（规则参数 scope 的取值） */
    public enum ChooseScope {
        /** 限定角色 */
        ROLE("指定角色"),
        /** 限定部门 */
        GROUP("指定分组"),
        /** 全部在职员工 */
        ALL("全部员工");

        private final String label;

        ChooseScope(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static ChooseScope of(String value) {
            if (value == null) {
                return null;
            }
            for (ChooseScope scope : values()) {
                if (scope.name().equals(value)) {
                    return scope;
                }
            }
            return null;
        }
    }

    /**
     * 「上一节点指定审批人」的可选范围（规则参数 assignScope 的取值， 新增）。
     *
     * <h2>为什么不复用 {@link ChooseScope}</h2>
     * <p>两者的值域并不相同：{@code ChooseScope} 表达的是"申请人在哪个人群里挑"，
     * 它的 GROUP 需要配一个具体部门 id；而这里表达的是"系统允许把工单派给谁"，
     * 其中 {@code IT_EXECUTOR} 是**两个来源的并集**（IT执行人角色 ∪ 最终处理部门成员），
     * 用 {@code ChooseScope} 的任何一个值都表达不出来。
     * 硬塞进去会让申请人的选人器里多出一个语义不通的选项。
     *
     * <p>缺省（null）视为 {@link #ALL} —— 存量流程定义没有这个参数，
     * 必须保持"不限制"的旧行为，否则已发布的流程会在无人改动的情况下开始拒绝指派。
     */
    public enum AssignScope {

        /** 不限制：全部在职启用用户都可被指定（存量流程的默认口径） */
        ALL("不限制"),

        /**
         * IT执行人：候选 = IT执行人角色的在职用户 ∪ 最终处理部门（IT运维组）的在职成员。
         *
         * <p>对应 第 3 级：「从 IT执行人角色 / IT运维组里选，谁有空派给谁」。
         * 两个来源取并集而不是二选一，是因为实际组织里常常"岗位挂在 IT运维组、
         * 但角色还没逐个改成 IT执行人"，只认一边会让候选列表突然空掉。
         */
        IT_EXECUTOR("IT执行人（IT执行人角色 或 IT运维组成员）");

        private final String label;

        AssignScope(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** 缺省即 {@link #ALL}：存量定义不带该参数时必须保持"不限制" */
        public static AssignScope of(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            for (AssignScope scope : values()) {
                if (scope.name().equals(value)) {
                    return scope;
                }
            }
            return null;
        }

        /** 是否限定为「IT执行人」候选池 */
        public boolean isItExecutor() {
            return this == IT_EXECUTOR;
        }
    }

    /** 角色类规则是否指向内置角色码（供校验给出更准确的提示） */
    public static boolean isKnownRole(String roleCode) {
        // ：内置角色从 3 个扩到 6 个（+ IT主管 / IT执行人 / 部门经理组长）。
        // 这里改为查 RoleCode.BUILTIN 单一事实源 —— 一个个 equals 的写法在扩角色时
        // 漏改一处，就会让「指定 IT主管」这条合法规则被校验器判为未知角色。
        return RoleCode.isBuiltin(roleCode);
    }
}
