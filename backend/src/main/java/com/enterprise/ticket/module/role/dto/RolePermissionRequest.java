package com.enterprise.ticket.module.role.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色权限授予请求（需求方三波·第一波·）
 *
 * <p>整体覆盖语义（PUT），不是增量追加：授权界面展示的是「勾选后的完整集合」，
 * 用增量语义会让「取消勾选」无法表达。服务端保存前会过滤掉目录中不存在的码，
 * 保证库里不会残留已废弃的权限。
 */
@Data
public class RolePermissionRequest {

    /** 该角色应拥有的完整权限码集合；空数组表示收回全部可授权限 */
    private List<String> permissions;
}
