package com.enterprise.ticket.module.auth.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.auth.dto.ChangePasswordRequest;
import com.enterprise.ticket.module.auth.dto.ForgotPasswordAccountRequest;
import com.enterprise.ticket.module.auth.dto.ForgotPasswordResetRequest;
import com.enterprise.ticket.module.auth.dto.ForgotPasswordSendCodeRequest;
import com.enterprise.ticket.module.auth.dto.LoginRequest;
import com.enterprise.ticket.module.auth.dto.LoginUserInfo;
import com.enterprise.ticket.module.auth.dto.PasswordPolicyVO;
import com.enterprise.ticket.module.auth.dto.ResetPasswordRequest;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordChannelsVO;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordMetaVO;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordSendCodeVO;
import com.enterprise.ticket.module.auth.service.AuthService;
import com.enterprise.ticket.module.auth.service.ForgotPasswordService;
import com.enterprise.ticket.module.auth.service.PasswordPolicyService;
import com.enterprise.ticket.module.user.dto.vo.UserPasswordResetVO;
import com.enterprise.ticket.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（ 菜单：登录 / 个人中心 / 修改密码 / 退出登录）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ForgotPasswordService forgotPasswordService;
    private final AppProperties appProperties;
    private final PasswordPolicyService passwordPolicyService;

    /**
     * 姓名登录
     *
     * <p>测试路径：POST http://localhost:8080/api/auth/login
     */
    @PostMapping("/login")
    public ApiResponse<LoginUserInfo> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        return ApiResponse.success("登录成功", authService.login(request, httpRequest, httpResponse));
    }

    /**
     * 退出登录：拉黑当前令牌的 jti 并清除 HttpOnly Cookie（）
     *
     * <p>需要 {@code HttpServletRequest} 的原因：登出要作废「当前这一枚」Token，
     * 而 Token 只存在于 Cookie 里（服务端无状态，没有会话可查）。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(httpRequest, httpResponse);
        return ApiResponse.success("已退出登录", null);
    }

    /**
     * 获取当前登录用户信息（强制改密状态下仍可访问）
     *
     * <p>返回体中包含该角色的权限码集合，前端据此动态渲染菜单与按钮（）。
     */
    @GetMapping("/me")
    public ApiResponse<LoginUserInfo> me() {
        return ApiResponse.success(authService.currentUserInfo(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 修改当前用户密码
     *
     * <p>改密会使该用户全部旧 Token 失效（），因此这里会把新签发的 Token
     * 重新写入 Cookie —— 否则用户一改完密码，自己的下一个请求就会 401 被踢出去。
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletResponse httpResponse) {
        authService.changePassword(SecurityUtils.getCurrentUserId(), request, httpResponse);
        return ApiResponse.success("密码修改成功", null);
    }

    /**
     * 管理员重置指定员工密码（ 角色权限：仅 super_admin；2026-09-20  改造）
     *
     * <p>临时口令由服务端生成并在响应体里回传，供调用方展示给管理员 ——
     * 不再由请求方指定口令（原因见 {@link ResetPasswordRequest} 的说明）。
     * 该员工全部既有会话会立即失效，下次登录必须改密。
     */
    @PostMapping("/reset-password")
    @PreAuthorize("@perm.has('staff:manage')")
    public ApiResponse<UserPasswordResetVO> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String temporaryPassword = authService.resetPassword(
                SecurityUtils.getCurrentUserId(), request.getUserId());
        return ApiResponse.success("密码重置成功，请将临时密码转交该员工（他下次登录需修改）",
                new UserPasswordResetVO(temporaryPassword));
    }

    /**
     * 获取 CSRF 请求头要求，便于前端与联调方确认自定义头规范（ 双重防护）
     */
    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf() {
        return ApiResponse.success(Map.of(
                "headerName", appProperties.getSecurity().getCsrfHeaderName(),
                "headerValue", appProperties.getSecurity().getCsrfHeaderValue()
        ));
    }

    /**
     * 当前生效的密码策略说明（ 收尾优化·）
     *
     * <p>「密码管理」页用它渲染策略清单。之所以不硬编码在前端：策略阈值来自可热改的
     * 系统参数，写死会导致「页面提示 8 位、实际要求 12 位」这种自相矛盾的引导。
     *
     * <p>同时回传当前账号来源（{@code authType}），AD 域账号据此把整页切为
     * 「域账号请在公司 AD 中修改密码」的提示，而不是让用户填完表单再吃一个 400。
     */
    @GetMapping("/password-policy")
    public ApiResponse<PasswordPolicyVO> passwordPolicy() {
        LoginUser current = SecurityUtils.getCurrentUser();
        String authType = current == null ? null : current.getAuthType();
        return ApiResponse.success(passwordPolicyService.describe(authType));
    }

    // ==================================================================
    // 找回密码（ / 五 / 六 / 七）—— 全部**免登录**
    // ==================================================================

    /**
     * 找回密码功能元信息：是否可用、有哪些渠道（ / 六.3）。
     *
     * <p>登录页用它决定「无法登录？」链接是否显示 —— 两个验证开关都关时链接直接隐藏，
     * 而不是让用户点进一个必然失败的页面。
     *
     * <p>测试路径：GET http://localhost:8080/api/auth/forgot-password/meta
     */
    @GetMapping("/forgot-password/meta")
    public ApiResponse<ForgotPasswordMetaVO> forgotPasswordMeta() {
        return ApiResponse.success(forgotPasswordService.meta());
    }

    /**
     * 找回密码第一步：按账号标识查询可用渠道（前端第 1~2 步）。
     *
     * <p>测试路径：POST http://localhost:8080/api/auth/forgot-password/channels
     * <pre>{"account": "10001"} 或 {"account": "13800001111"} 或 {"account": "张伟"}</pre>
     */
    @PostMapping("/forgot-password/channels")
    public ApiResponse<ForgotPasswordChannelsVO> forgotPasswordChannels(
            @Valid @RequestBody ForgotPasswordAccountRequest request) {
        return ApiResponse.success(forgotPasswordService.channels(request.getAccount()));
    }

    /**
     * 找回密码第二步：发送验证码（ / 二.3 / 二.4）。
     *
     * <p>开发环境会把验证码放在响应体的 {@code devCode} 字段里返回，便于验收与自动化
     * （当前未接入真实短信 / 邮件网关，见 {@code VerificationCodeSender}）。
     *
     * <p>测试路径：POST http://localhost:8080/api/auth/forgot-password/send-code
     * <pre>{"account": "13800001111", "contactType": "SMS"}</pre>
     */
    @PostMapping("/forgot-password/send-code")
    public ApiResponse<ForgotPasswordSendCodeVO> forgotPasswordSendCode(
            @Valid @RequestBody ForgotPasswordSendCodeRequest request) {
        return ApiResponse.success("验证码已发送",
                forgotPasswordService.sendCode(request.getAccount(), request.getContactType()));
    }

    /**
     * 找回密码第三步：校验验证码并设置新密码（ / 二.5）。
     *
     * <p><b>刻意不挂 {@code @AuditLog}</b>：该切面会把<b>入参</b>序列化进审计日志
     * （ 的既定约定），而本端点的入参里含 {@code newPassword} 明文 ——
     * 挂上去等于把用户的新口令写进审计表。审计改由 {@code ForgotPasswordService}
     * 在口令写库之后自行记录（只记「谁、什么时候、成功了」，不含口令）。
     * 这与「重置密码」端点当初刻意不接收口令是同一个思路：**不让敏感值出现在会被记录的位置**。
     *
     * <p>测试路径：POST http://localhost:8080/api/auth/forgot-password/reset
     * <pre>{"account": "10001", "code": "483920", "newPassword": "NewPass@2026"}</pre>
     */
    @PostMapping("/forgot-password/reset")
    public ApiResponse<Void> forgotPasswordReset(@Valid @RequestBody ForgotPasswordResetRequest request) {
        forgotPasswordService.reset(request.getAccount(), request.getCode(), request.getNewPassword());
        return ApiResponse.success("密码已重置，请登录", null);
    }
}
