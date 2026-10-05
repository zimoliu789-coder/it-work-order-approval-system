package com.enterprise.ticket.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑员工请求（需求方 2026-09-18 小迭代 · ）
 *
 * <p>可改：姓名、角色、部门、显示名称；<b>登录名不可改</b> ——
 * 因此本 DTO 刻意<b>不</b>含 {@code username} 字段，从结构上杜绝「改登录名」的可能
 * （与 {@code DeviceSaveRequest} 不含 status 是同一手法：让非法操作无法表达，
 * 而不是靠一次 if 判断拦住）。
 *
 * <p>姓名修改同样要过「姓名唯一」校验，只是排除自己。
 */
@Data
public class UserUpdateRequest {

    /** 姓名（必填，唯一，排除自己） */
    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名不能超过 64 个字符")
    private String realName;

    /** 所属部门ID */
    private Long departmentId;

    /** 角色：super_admin / admin / user */
    @NotBlank(message = "请选择角色")
    private String role;

    /** 显示名称（选填；留空回退为姓名） */
    @Size(max = 64, message = "显示名称不能超过 64 个字符")
    private String displayName;

    /**
     * 直属领导 user_id（；选填，null = 不设置/清除）。
     *
     * <p>供「申请人直属领导」审批规则解析使用。服务端校验：目标必须是在职启用员工、
     * 且不能是本人（自审回避）；不满足则 400。领导离职时<b>不会</b>自动清空本字段
     * （保留历史配置），提交工单时解析到离职领导会走超管兜底并通知管理员。
     */
    private Long leaderId;

    /**
     * 手机号（V26；选填）。
     *
     * <p><b>权限说明</b>：这两个字段只有<b>内置超级管理员</b>（登录名 = 配置的
     * {@code app.super-admin.username}）能改；其他角色即使传了，服务端也会**静默忽略**
     * （既不写入、也不报错）—— 理由见 {@code UserServiceImpl#updateUser} 的说明。
     *
     * <p>传空 = 保持原值。刻意不提供「清空」语义：清空手机 / 邮箱会让该账号
     * 失去自助找回密码的能力，那是「重置密码」这类管理动作的副作用，
     * 不该是一个编辑弹窗上的顺手操作。
     */
    @Size(max = 20, message = "手机号不能超过 20 个字符")
    private String phone;

    /** 邮箱（V26；选填）。语义与权限同 {@link #phone} */
    @Size(max = 128, message = "邮箱不能超过 128 个字符")
    private String email;
}
