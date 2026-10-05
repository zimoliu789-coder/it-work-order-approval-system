package com.enterprise.ticket.module.applytype.dto;

import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.form.FormSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 一步创建申请类型
 *
 * <h2>为什么需要它</h2>
 * <p>改造前要新建一个申请类型，管理员得走**三段**：
 * 先在「表单模板」里拖字段并发布版本 → 再到「审批流程」里画流程并发布版本 →
 * 最后回「申请类型」挑两个版本。说明：「不用先建表单版本再关联，一步到位」。
 *
 * <p>本请求把这三段合成一次提交：{@code formSchema} 是表单字段，{@code flow} 是审批流程，
 * 其余是类型自身的属性。服务端在**一个事务**里建齐「表单模板 + 首发版本 + 流程 + 首发版本 + 类型」，
 * 中途任何一步失败都整体回滚 —— 不会留下「有表单没类型」这种半成品（那正是三段式最容易出的问题）。
 *
 * <h2>为什么 {@code flow} 是完整定义而不是「几个审批人」</h2>
 * <p>前端的钉钉式编辑器虽然只让用户「加一行审批人」，但它产出的就是标准 {@link FlowDefinition}
 * （线性 start → node → node → END）。让前端把结构拼好再提交，服务端就不必再造一套
 * 「简化节点」模型与它做双向转换 —— 那种转换每加一种能力（会签、抄送、条件）就要改两遍。
 * 提交上来的定义仍会过 {@code FlowDefinitionValidator} 与 {@code FlowGraphValidator}，与手工画流程完全同一道闸门。
 */
@Data
public class ApplyConfigCreateRequest {

    /** 类型名称（管理员在第一步填的；表单与流程的名称会由它派生） */
    @NotBlank(message = "申请名称不能为空")
    @Size(max = 64, message = "申请名称不能超过 64 个字符")
    private String typeName;

    /** 图标名（Element Plus 图标组件名）；可空，界面用默认图标 */
    @Size(max = 64, message = "图标名称不能超过 64 个字符")
    private String icon;

    @Size(max = 255, message = "说明不能超过 255 个字符")
    private String description;

    /** 排序号（决定提交页卡片顺序）；为空按 100 处理 */
    private Integer sortOrder;

    /**
     * 工单编号前缀（2-10 位、字母开头）；**留空表示用系统默认规则**。
     *
     * <p>刻意允许留空：前缀属于「编码类技术细节」，需求要求把它藏起来；
     * 管理员不填时不该被卡住。
     */
    @Size(max = 10, message = "编号前缀不能超过 10 个字符")
    private String orderPrefix;

    /** 谁能提交：ROLE / GROUP / ALL；为空按 ALL（所有员工都能提） */
    private String submitPermissionType;

    /** 提交权限值：ROLE = 角色码列表；GROUP = 部门 id 列表；ALL 忽略 */
    private List<String> submitPermissionValues;

    /** 表单字段（管理员在第二步配的） */
    @NotNull(message = "请至少配置一个表单字段")
    private FormSchema formSchema;

    /** 审批流程定义（管理员在第三步配的） */
    @NotNull(message = "请至少配置一个审批节点")
    private FlowDefinition flow;

    /**
     * 类型编码；**留空由服务端生成**。
     *
     * <p>界面不暴露它（需求：「编码、版本号这些技术细节藏起来」），
     * 保留这个字段只是为了预置播种时能指定稳定的编码。
     */
    @Size(max = 20, message = "类型编码不能超过 20 个字符")
    private String typeCode;
}
