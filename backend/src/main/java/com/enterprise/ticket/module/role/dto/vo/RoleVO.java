package com.enterprise.ticket.module.role.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 角色视图（需求方三波·第一波·）
 *
 * <p>带上 {@code userCount} 与 {@code permissionCount} 是为了让列表页能直接回答
 * 「这个角色还有几个人在用」「它到底有多少权限」——否则删除角色时用户只能猜。
 */
@Data
public class RoleVO {

    private String roleCode;

    private String roleName;

    private String dataScope;

    /** 数据权限中文名，供列表直接展示（避免前端各自维护一份映射） */
    private String dataScopeLabel;

    private String remark;

    private Boolean builtin;

    private Boolean enabled;

    private Integer sortNo;

    /** 使用该角色的员工数（删除前必须为 0） */
    private Long userCount;

    /** 已授予的权限码数量 */
    private Integer permissionCount;

    /**
     * 已授予的权限码明细。
     *
     * <p>列表接口默认不返回（可能上百个码，列表页用不上），
     * 仅「查看详情 / 打开授权抽屉」时按需返回。
     */
    private List<String> permissions;
}
