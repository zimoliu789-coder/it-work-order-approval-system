package com.enterprise.ticket.module.user.dto;

import com.enterprise.ticket.module.user.entity.User;
import lombok.Data;

/**
 * 员工下拉选项（供审批人选择、分组成员添加、最终小组成员维护等场景复用）
 *
 * <p>界面需要同时看到「姓名 + 当前所属分组」，以便在添加成员时按
 * 提示「该员工已在其他分组，添加后将自动从原分组移出」。
 */
@Data
public class UserOptionVO {

    private Long id;

    private String username;

    private String displayName;

    private String role;

    /** 当前所属部门ID，未分配为 null */
    private Long departmentId;

    /** 当前所属部门名称 */
    private String departmentName;

    /** 是否离职 */
    private Boolean dimission;

    /** 账号是否启用 */
    private Boolean enabled;

    /** 是否可作为审批人/执行人（在职且启用） */
    private Boolean available;

    public static UserOptionVO of(User user, String departmentName) {
        UserOptionVO vo = new UserOptionVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setRole(user.getRole());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setDepartmentName(departmentName);
        vo.setDimission(Boolean.TRUE.equals(user.getDimission()));
        vo.setEnabled(Boolean.TRUE.equals(user.getEnabled()));
        vo.setAvailable(Boolean.TRUE.equals(user.getEnabled()) && !Boolean.TRUE.equals(user.getDimission()));
        return vo;
    }
}
