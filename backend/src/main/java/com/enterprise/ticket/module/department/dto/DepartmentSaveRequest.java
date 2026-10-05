package com.enterprise.ticket.module.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑部门的请求。
 *
 * <p>{@code parentId} 为 {@code null} 表示挂在根节点（公司）下。
 * <b>本请求不含 path / depth</b>：它们是派生的，由服务层按 parentId 统一算出来 ——
 * 让前端传物化路径等于把「树结构的一致性」交给一个不该关心它的地方。
 */
@Data
public class DepartmentSaveRequest {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 64, message = "部门名称最长 64 个字符")
    private String deptName;

    /** 上级部门ID；{@code null} = 根节点 */
    private Long parentId;

    /** 同级显示顺序；{@code null} 时由服务层追加到同级末尾 */
    private Integer sortOrder;

    /** 绑定的已发布审批流程版本；{@code null} = 不绑定（走系统内置流程） */
    private Long approvalFlowVersionId;

    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
