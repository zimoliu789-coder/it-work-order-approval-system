package com.enterprise.ticket.module.applytype.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建 / 修改申请类型请求
 *
 * <h2>{@code submitPermissionValues} 为什么是字符串数组</h2>
 * <p>ROLE 模式下前端传角色码（{@code ["admin"]}），GROUP 模式下传分组 id
 * （{@code ["3","7"]}）。统一用字符串承载，由服务层按 {@code submitPermissionType}
 * 解释并校验（角色码必须存在于角色表、分组 id 必须存在）——
 * 若这里强约束为 {@code List<Long>}，角色码就会被前端硬塞成数字，反而更容易出错。
 */
@Data
public class ApplyTypeSaveRequest {

    @NotBlank(message = "类型编码不能为空")
    @Size(max = 20, message = "类型编码不能超过 20 个字符")
    private String typeCode;

    @NotBlank(message = "类型名称不能为空")
    @Size(max = 64, message = "类型名称不能超过 64 个字符")
    private String typeName;

    @Size(max = 64, message = "图标名称不能超过 64 个字符")
    private String icon;

    @Size(max = 255, message = "类型说明不能超过 255 个字符")
    private String description;

    /** 排序号；为空按 100 处理 */
    private Integer sortOrder;

    @NotNull(message = "请选择关联的表单模板")
    private Long formTemplateVersionId;

    /** 工单编号前缀（字母开头 2-10 位；可空表示用系统默认规则） */
    @Size(max = 10, message = "工单编号前缀不能超过 10 个字符")
    private String orderPrefix;

    @NotBlank(message = "请选择审批方式")
    private String approvalMode;
    /** 审批方式 = FLOW 时必填：绑定的「已发布」审批流程版本 id；其它方式忽略（一律清空） */
    private Long approvalFlowVersionId;

    @NotBlank(message = "请选择提交权限类型")
    private String submitPermissionType;

    /** 提交权限值：ROLE = 角色码列表；GROUP = 部门 id 列表；ALL 忽略 */
    private List<String> submitPermissionValues;
}
