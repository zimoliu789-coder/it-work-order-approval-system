package com.enterprise.ticket.module.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色新增 / 编辑请求（需求方三波·第一波·）
 */
@Data
public class RoleSaveRequest {

    /**
     * 角色编码：仅新增时使用。
     *
     * <p>限制为「小写字母开头，允许小写字母/数字/下划线」，且长度 ≤ 32：
     * 编码会进入 {@code users.role} 与权限码前缀，放开字符集只会给后续 SQL 与
     * 前端路由埋雷（含点号、斜杠的编码很容易被误当成路径）。
     */
    @NotBlank(message = "角色编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,31}$",
            message = "角色编码需以小写字母开头，仅含小写字母、数字与下划线，长度 2~32")
    private String roleCode;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称最长 64 个字符")
    private String roleName;

    /** 数据权限范围：ALL / GROUP / SELF */
    @NotBlank(message = "数据权限不能为空")
    @Pattern(regexp = "^(ALL|GROUP|SELF)$", message = "数据权限只能是 ALL / GROUP / SELF")
    private String dataScope;

    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;

    /** 是否启用；为空按 true 处理 */
    private Boolean enabled;

    /** 排序号；为空按 100 处理 */
    private Integer sortNo;

    /**
     * 权限码集合（可空）。
     *
     * <p>允许与角色基本信息一起提交，是因为授权界面本身就是「改完一起保存」；
     * 分开两个接口会让前端在「保存失败」时难以回滚。为空表示不变更权限。
     */
    private List<String> permissions;
}
