package com.enterprise.ticket.module.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 初始化向导请求（本次新增）—— 管理员现场设定超管账号。
 *
 * <p>刻意**不复用** {@code UserCreateRequest}：那个请求面向「员工」，
 * 带 department/role/leader 等与超管无关的字段，复用会让向导的入参看起来
 * 像是可以指定角色与部门 —— 而超管的角色是固定的，不接受外部指定。
 */
@Data
public class SetupInitializeRequest {

    /** 超管登录名（允许字母 / 数字 / . _ -，3~32 位） */
    @NotBlank(message = "请填写超管账号名")
    @Size(max = 32, message = "超管账号名不能超过 32 位")
    private String username;

    /** 登录密码 */
    @NotBlank(message = "请填写登录密码")
    @Size(max = 64, message = "密码不能超过 64 位")
    private String password;

    /** 确认密码 */
    @NotBlank(message = "请再次输入登录密码")
    private String confirmPassword;

    /** 展示名（可空，默认与账号名相同） */
    @Size(max = 32, message = "展示名不能超过 32 位")
    private String displayName;
}
