package com.enterprise.ticket.module.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量调整员工所属部门（P3 批量操作）
 *
 * <p>只改归属，**不重算审批人快照** —— 已提交的工单仍按提交时冻结的流程与审批人走完，
 * 否则在途工单的审批人会中途换人（这类"看起来更实时"的改动是在途流程的污染源）。
 * 部门变更对**新提交**的工单生效。
 *
 * <p>本操作不涉及角色，因此不触碰「超管不可降级」那道护栏。
 */
@Data
public class UserBatchDepartmentRequest {

    @NotEmpty(message = "请选择要调整的员工")
    private List<Long> ids;

    @NotNull(message = "请选择目标部门")
    private Long departmentId;
}
