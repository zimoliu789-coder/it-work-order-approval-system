package com.enterprise.ticket.module.auth.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.auth.dto.BindContactRequest;
import com.enterprise.ticket.module.auth.dto.BindContactSendCodeRequest;
import com.enterprise.ticket.module.auth.dto.LoginUserInfo;
import com.enterprise.ticket.module.auth.dto.vo.BindContactSendCodeVO;
import com.enterprise.ticket.module.auth.service.AuthService;
import com.enterprise.ticket.module.auth.service.ContactBindCodeService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心（当前登录者自己的资料）——  / 四.3。
 *
 * <h2>为什么单独开一个 {@code /api/profile} 前缀</h2>
 * <p>{@code /api/users/**} 是<b>管理他人</b>的接口，整个前缀都挂在
 * {@code staff:view} / {@code staff:manage} 权限码下。而「改自己的联系方式」
 * 是每个员工都必须能做的事 —— 它不该、也不能依赖任何管理权限。
 * 把两者混在同一前缀下，迟早出现「给普通用户放开某个 /api/users 端点」
 * 这类会连带放开管理能力的改动。前缀分开后，权限模型一目了然：
 * <b>/api/users 管别人，/api/profile 管自己</b>。
 *
 * <h2>为什么不需要额外权限</h2>
 * <p>本端点只操作 {@code SecurityUtils.getCurrentUserId()} 指向的那一行，
 * <b>入参里没有任何 userId</b> —— 想改别人也无从表达。
 * 这比「接收 userId 然后校验它等于当前用户」更安全：后者漏一次校验就是越权写。
 *
 * <h2>：为什么绑定要拆成「发码 + 提交」两步</h2>
 * <p>改造前一次性提交即可绑定，等于<b>任何人只要能操作这个会话，就能把联系方式
 * 改成任意号码</b>。拆开之后，「能把新号码绑上去」的前提变成了
 * 「能读到发到新号码上的验证码」—— 而新号码的正确性恰恰是绑定唯一要保证的事。
 * 发码见 {@link #sendBindContactCode}，两枚码（手机 / 邮箱）各自独立验证。
 *
 * <h2>审计（）</h2>
 * <p>「首次绑定」与「自己换绑」都要记 HIGH。
 * 刻意不用 {@code @AuditLog}：那需要把「本次是首绑还是换绑」这个判断写进切面，
 * 而它依赖操作前的库中状态。放在这里用一次查询判定，语义更直接，
 * 也能把「从什么改成了什么」写进详情（打码后）。
 */
@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final AuthService authService;
    private final SystemConfigService systemConfigService;
    private final OperationLogService operationLogService;
    private final ContactBindCodeService contactBindCodeService;

    /**
     * 向「准备绑定的号码」发送验证码（，）。
     *
     * <p>测试路径：
     * <pre>
     * POST http://localhost:8080/api/profile/bind-contact/send-code
     * {"contactType": "SMS", "target": "13800001111"}
     * </pre>
     *
     * <p>免权限、仅操作当前用户；限流与作废规则见 {@link ContactBindCodeService}。
     */
    @PostMapping("/bind-contact/send-code")
    public ApiResponse<BindContactSendCodeVO> sendBindContactCode(
            @Valid @RequestBody BindContactSendCodeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success("验证码已发送",
                contactBindCodeService.sendCode(userId, request.getContactType(), request.getTarget()));
    }

    /**
     * 绑定 / 修改自己的手机号与邮箱（：首次登录引导；：个人中心自助改绑）。
     *
     * <p>返回更新后的 {@link LoginUserInfo}：绑定成功后 {@code requireContactBinding}
     * 会随之变为 {@code false}，前端据此直接放行进入系统，不必再发一次 {@code /auth/me}。
     *
     * <p>测试路径：
     * <pre>
     * PUT http://localhost:8080/api/profile/bind-contact
     * {"phone": "13800001111", "email": "zhangwei@example.com", "phoneCode": "483920"}
     * </pre>
     */
    @PutMapping("/bind-contact")
    public ApiResponse<LoginUserInfo> bindContact(@Valid @RequestBody BindContactRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        User before = userService.getByIdRequired(userId);

        String requestedPhone = trimToNull(request.getPhone());
        String requestedEmail = trimToNull(request.getEmail());

        // 「值是否发生**变化**」是本接口唯一的分支依据：它同时决定
        //   · 渠道关闭时要不要拦（没动过的字段不该被开关挡住）
        //   · 要不要验证码（没动过就不必再验一次）
        // 判据是「与库中值不同」而不是「请求里带了值」：员工只想改邮箱时，
        // 表单通常会把已绑的手机号一并回传；若按「带了值」判定，手机验证一关
        // 就连改邮箱都做不了 —— 那显然不是需求想要的效果。
        // 邮箱用忽略大小写比较：库中的值已由 normalizeEmail 转成小写，
        // 用户输入「A@x.com」在语义上与「a@x.com」是同一个地址。
        boolean phoneChanged = requestedPhone != null && !requestedPhone.equals(before.getPhone());
        boolean emailChanged = requestedEmail != null
                && !requestedEmail.equalsIgnoreCase(before.getEmail());

        // ：验证渠道被管理员关闭后，对应方式就**不能**再用于绑定 / 改绑。
        // 只拦「本人自助」这一条路径：管理员在员工管理页补录联系方式不受此限
        // （后台配置能力不应被前台开关绑住，否则关掉开关就再也没有补救手段）。
        if (phoneChanged && !systemConfigService.smsVerifyEnabled()) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_DISABLED,
                    "管理员已关闭手机验证，暂不能绑定或修改手机号");
        }
        if (emailChanged && !systemConfigService.emailVerifyEnabled()) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_DISABLED,
                    "管理员已关闭邮箱验证，暂不能绑定或修改邮箱");
        }

        //  ：**发生变化的渠道必须带对验证码**。
        // 两个渠道各验各的 —— 手机码不能顶替邮箱码（键空间按渠道分开，见 ContactBindCodeService）。
        if (phoneChanged) {
            contactBindCodeService.verify(userId, ContactRecovery.CONTACT_SMS,
                    requestedPhone, request.getPhoneCode());
        }
        if (emailChanged) {
            contactBindCodeService.verify(userId, ContactRecovery.CONTACT_EMAIL,
                    requestedEmail, request.getEmailCode());
        }

        // 「首绑」还是「换绑」必须在写入**之前**判定 —— 写完再读，两者就都成了「已绑定」，
        // 审计日志里会永远看不到「第一次绑定」这件事，而它正是要追踪的动作
        boolean firstBind = !StringUtils.hasText(before.getPhone()) && !StringUtils.hasText(before.getEmail());

        User after = userService.bindContact(userId, request.getPhone(), request.getEmail());

        // 写库成功后才消费验证码：写失败（如号码被并发占用）时保留码，用户重试不必重新发码。
        // 消费是必须的 —— 不消费就允许同一枚码反复通关，而绑定改的正是身份标识。
        if (phoneChanged) {
            contactBindCodeService.consume(userId, ContactRecovery.CONTACT_SMS);
        }
        if (emailChanged) {
            contactBindCodeService.consume(userId, ContactRecovery.CONTACT_EMAIL);
        }

        recordContactChange(before, after, firstBind);
        return ApiResponse.success(firstBind ? "联系方式绑定成功" : "联系方式已更新",
                authService.currentUserInfo(userId));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 记录联系方式变更审计（：首次绑定 / 自己换绑，均为 HIGH）。
     *
     * <p>详情里只写**打码后**的值：审计表会被导出、被检索，完整手机号落在里面
     * 等于给「员工通讯录」开了个旁路。而「改成了 138****8888」已足够用于追溯
     * 「那次改动是不是我做的」。
     */
    private void recordContactChange(User before, User after, boolean firstBind) {
        try {
            StringBuilder detail = new StringBuilder(firstBind
                    ? "首次登录绑定联系方式（）" : "员工自助修改联系方式（）");
            if (!java.util.Objects.equals(before.getPhone(), after.getPhone())) {
                detail.append("；绑定手机 ").append(AccountFormats.maskContact(after.getPhone()));
            }
            if (!java.util.Objects.equals(before.getEmail(), after.getEmail())) {
                detail.append("；绑定邮箱 ").append(AccountFormats.maskContact(after.getEmail()));
            }
            operationLogService.record(after.getId(), after.getUsername(), "AUTH",
                    firstBind ? "CONTACT_FIRST_BIND" : "CONTACT_SELF_UPDATE",
                    detail.toString(), true, RiskLevel.HIGH);
        } catch (Exception e) {
            // 审计失败不阻断业务（与 AuthService 的高危审计同一取舍）
            log.error("联系方式变更审计写入失败（已忽略，不影响主流程）：userId={}", after.getId(), e);
        }
    }

    // 打码统一走 AccountFormats.maskContact（ ：全库唯一实现）
}
