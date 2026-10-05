package com.enterprise.ticket.module.department.dto;

import lombok.Data;

/**
 * 移动部门到新的上级。
 *
 * <p>刻意做成**独立端点**而不是复用 {@code DepartmentSaveRequest.parentId}：
 * 移动是一个会**整体重写子树 path / depth** 的重操作，与「改个名字」的风险不是一个量级。
 * 独立端点让「改名字不会误触发移动」成为结构上的保证，而不是靠代码里的 if。
 *
 * <p>{@code parentId} 允许为 {@code null}（= 移到根节点下），
 * 因此字段上**不加 {@code @NotNull}**：根节点是一个合法的移动目标，
 * 把它判成「参数缺失」会让「把部门提到顶层」这条路径永远走不通。
 */
@Data
public class DepartmentMoveRequest {

    /** 新的上级部门ID；{@code null} = 移到根节点（公司）下 */
    private Long parentId;
}
