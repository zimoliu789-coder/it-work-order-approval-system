package com.enterprise.ticket.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增员工请求（需求方 2026-09-18 小迭代 · ）
 *
 * <p>字段与对应：姓名 / 登录名 / 初始密码 / 部门 / 角色 / 显示名称。
 *
 * <p><b>「姓名」与「显示名称」的区别</b>（落库映射见 {@code users.real_name} / {@code users.display_name}）：
 * <ul>
 *   <li><b>姓名</b>（{@code realName}）—— 员工真实姓名，是「姓名唯一」这条业务规则的校验锚点，必填；</li>
 *   <li><b>显示名称</b>（{@code displayName}）—— 界面上展示的名字，选填；留空时回退为姓名。
 *        明确允许 display_name 重复（同名员工场景），因此它<b>不</b>参与唯一性校验。</li>
 * </ul>
 * <b>登录名</b>（{@code username}）与姓名是两个独立字段： 的「姓名即账号」是默认形态，
 * 而本迭代按需求方要求允许显式指定登录名（例如姓名「张三」+ 登录名「zhangsan」）。
 */
@Data
public class UserCreateRequest {

    /** 姓名（必填，唯一） */
    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名不能超过 64 个字符")
    private String realName;

    /** 登录名（必填，唯一；不可修改） */
    @NotBlank(message = "请输入登录名")
    @Size(max = 64, message = "登录名不能超过 64 个字符")
    private String username;

    /** 初始密码（必填；首登强制改密） */
    @NotBlank(message = "请输入初始密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String password;

    /** 所属部门ID（必填； 员工必须归属一个分组） */
    private Long departmentId;

    /** 角色：super_admin / admin / user */
    @NotBlank(message = "请选择角色")
    private String role;

    /** 显示名称（选填；留空回退为姓名） */
    @Size(max = 64, message = "显示名称不能超过 64 个字符")
    private String displayName;

    /**
     * 直属领导 user_id（；选填，null = 未配置）。
     *
     * <p>供「申请人直属领导」审批规则解析使用；服务端校验目标必须是
     * 在职启用员工且不能是本人。
     */
    private Long leaderId;

    /**
     * 手机号（V26；选填）。格式与唯一性由服务端校验（{@code AccountFormats} + {@code uk_users_phone}）。
     *
     * <p>允许新增时留空：首次登录会引导员工自己绑定，
     * 管理员在创建阶段不必先去要一遍手机号。
     */
    @Size(max = 20, message = "手机号不能超过 20 个字符")
    private String phone;

    /** 邮箱（V26；选填）。语义同 {@link #phone} */
    @Size(max = 128, message = "邮箱不能超过 128 个字符")
    private String email;
}
