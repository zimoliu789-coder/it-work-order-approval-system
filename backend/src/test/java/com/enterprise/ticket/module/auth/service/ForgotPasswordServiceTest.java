package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordMetaVO;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.VerificationChannelStatus;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.security.RateLimitGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 找回密码服务（P18-A 需求五：补齐本类的单测）。
 *
 * <h2>为什么这些用例进的是 {@code mvn test} 而不是外部脚本</h2>
 * <p>本服务在 P17 只被 bash 回归脚本（{@code _p17-verify.sh}）覆盖 —— 那些脚本要起服务、
 * 连数据库、等限流窗口，代价高且不在构建门禁里。于是「改坏了订单流程的找回密码」
 * 这类问题只能在手动跑回归时才暴露。本类把关键分支钉进构建期。
 *
 * <h2>覆盖重点</h2>
 * <ol>
 *   <li><b>可用性闸门</b>：两个验证渠道都关时，找回密码整体不可用（需求六.3）；</li>
 *   <li><b>渠道可见性</b>：没绑任何联系方式时报的是「未绑定」而不是「渠道被关」——
 *       两者提示不同、用户的下一步动作也不同（找管理员 vs 换个方式）；</li>
 *   <li><b>AD 账号拒绝本地重置</b>（需求二.4）：域口令不在本地，改了也不会生效；</li>
 *   <li><b>强度校验先于兑码</b>：口令太弱时不得消耗验证码 —— 否则用户换个强口令时
 *       发现码已经被用掉了，只能重新发码；</li>
 *   <li><b>成功后的三件套</b>：写新口令 + {@code tokenVersion+1} 作废旧会话 + 清理三个 Redis 键。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("找回密码服务")
class ForgotPasswordServiceTest {

    private static final long USER_ID = 9L;

    @Mock
    private UserService userService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private PasswordPolicyService passwordPolicyService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private VerificationCodeSender codeSender;
    @Mock
    private Environment environment;
    @Mock
    private RateLimitGuard rateLimitGuard;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private ForgotPasswordService service;

    /** 限流放行：本类测的是业务分支，限流另有用例兜底；不 stub 会让判定读到 null。 */
    private void allowRateLimit() {
        when(rateLimitGuard.tryAcquireFixedWindow(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitGuard.Decision(true, 0L));
    }

    /** 两条链路都就绪（短信网关已接入 + SMTP 配齐）—— 大多数用例的默认前提。 */
    private void channelsEnabled() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(true, true, true, true));
    }

    /**
     * 构造渠道有效性快照。
     *
     * <p>{@code smsGatewayIntegrated} 由参数给出而不是读生产常量：短信网关当前尚未接入
     * （{@code SmsSettings.GATEWAY_INTEGRATED = false}），传 {@code true} 即可覆盖
     * 「将来接入网关后」的分支，不必为了测试去改生产常量。
     */
    private static VerificationChannelStatus channelStatus(boolean smsOn, boolean emailOn,
                                                          boolean smsGateway, boolean smtpComplete) {
        return new VerificationChannelStatus(smsOn, emailOn, smsGateway, smtpComplete,
                smtpComplete ? null : "未填写 SMTP 服务器地址");
    }

    private User localUser(String phone, String email) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("10001");
        user.setAuthType("LOCAL");
        user.setPhone(phone);
        user.setEmail(email);
        return user;
    }

    private static ErrorCode codeOf(BusinessException ex) {
        return ex.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两个渠道都关：meta 报告功能不可用且渠道为空")
    void metaDisabledWhenAllChannelsOff() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(false, false, false, false));

        ForgotPasswordMetaVO vo = service.meta();

        assertFalse(vo.isEnabled());
        assertTrue(vo.getChannels().isEmpty());
    }

    @Test
    @DisplayName("两个渠道都关：channels 直接拒绝（渠道全关时找回密码整体不可用）")
    void channelsRejectedWhenDisabled() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(false, false, false, false));
        allowRateLimit();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.channels("10001"));
        assertEquals(ErrorCode.FORGOT_PASSWORD_DISABLED, codeOf(ex));
    }

    @Test
    @DisplayName("一个渠道关着不影响另一个：开关是并集而非交集")
    void metaEnabledWhenOneChannelOn() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(false, true, false, true));

        ForgotPasswordMetaVO vo = service.meta();

        assertTrue(vo.isEnabled());
        assertEquals(1, vo.getChannels().size());
        // 不可用的短信渠道必须带出「为什么不可用」，供界面直接展示
        assertTrue(vo.getChannelDisabledReasons().containsKey("SMS"));
        assertFalse(vo.getChannelDisabledReasons().containsKey("EMAIL"));
    }

    // ------------------------------------------------------------------
    // 渠道可见性：提示语义必须区分「没绑」与「被关」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未绑定任何联系方式：报「未绑定」而不是「渠道被关」")
    void channelsReportsUnboundFirst() {
        channelsEnabled();
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser(null, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.channels("10001"));
        // 提示顺序错了的后果：什么都没绑的用户会收到「该验证方式已被管理员关闭」，
        // 于是他去等管理员开开关，而管理员那边一切正常 —— 问题永远定位不到。
        assertEquals(ErrorCode.ACCOUNT_WITHOUT_CONTACT, codeOf(ex));
    }

    // ------------------------------------------------------------------
    // 发码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AD 域账号：拒绝本地发码（域口令不在本地，改了也不生效）")
    void sendCodeRejectsAdAccount() {
        channelsEnabled();
        allowRateLimit();
        User ad = localUser("13900000001", null);
        ad.setAuthType("LDAP");
        when(userService.findByAccount("aduser")).thenReturn(ad);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("aduser", "SMS"));
        assertEquals(ErrorCode.AD_PASSWORD_MANAGED_BY_AD, codeOf(ex));
    }

    @Test
    @DisplayName("所选渠道被管理员关闭：拒绝发码")
    void sendCodeRejectsDisabledChannel() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(false, true, false, true));
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser("13900000001", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("10001", "SMS"));
        assertEquals(ErrorCode.CONTACT_CHANNEL_DISABLED, codeOf(ex));
    }

    @Test
    @DisplayName("渠道开着但账号没绑：报「未绑定所选的验证方式」")
    void sendCodeRejectsUnboundTarget() {
        channelsEnabled();
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser(null, "user10001@example.com"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("10001", "SMS"));
        assertEquals(ErrorCode.CONTACT_CHANNEL_UNBOUND, codeOf(ex));
    }

    // ------------------------------------------------------------------
    // 方案②：链路没配好 = 渠道不可用（本轮修复的核心）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开关开着但 SMTP 未配齐：meta 报告不可用 —— 不能只信开关")
    void metaDisabledWhenSmtpNotConfigured() {
        // 改造前的缺陷：两个开关默认都是「开」，一个 SMTP 都没配的全新部署里
        // meta 会报 enabled=true，登录页显示「无法登录？」入口，用户点进去只会一步步走进死路。
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(true, true, false, false));

        ForgotPasswordMetaVO vo = service.meta();

        assertFalse(vo.isEnabled());
        assertTrue(vo.getChannels().isEmpty());
        // 两个渠道都要给出原因：一个「网关未接入」，一个「SMTP 未配齐」
        assertTrue(vo.getChannelDisabledReasons().containsKey("SMS"));
        assertTrue(vo.getChannelDisabledReasons().containsKey("EMAIL"));
    }

    @Test
    @DisplayName("开关开着但 SMTP 未配齐：channels 直接拒绝（不可用即视为关闭）")
    void channelsRejectedWhenSmtpNotConfigured() {
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(true, true, false, false));
        allowRateLimit();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.channels("10001"));
        assertEquals(ErrorCode.FORGOT_PASSWORD_DISABLED, codeOf(ex));
    }

    @Test
    @DisplayName("邮箱开关开着但 SMTP 未配齐：拒绝发码，原因是「未配置完成」而非「管理员关闭」")
    void sendCodeRejectsWhenSmtpIncomplete() {
        // 短信这一路保持就绪（网关已接入），使 ensureEnabled() 的整体闸门放行 ——
        // 否则两条链路都不可用时会先以「找回密码整体不可用」短路，
        // 就测不到「邮箱渠道不可用」这条更具体的分支了（该分支才是本用例的意图）。
        when(systemConfigService.verificationChannels())
                .thenReturn(channelStatus(true, true, true, false));
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser(null, "user10001@example.com"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("10001", "EMAIL"));
        assertEquals(ErrorCode.CONTACT_CHANNEL_DISABLED, codeOf(ex));
        // 文案必须说清是「SMTP 没配齐」：说成「管理员已关闭」会把运维引到错误的方向
        assertTrue(ex.getMessage().contains("邮件服务器尚未配置完成"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 重置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("验证码不存在（没发 / 已过期 / 已兑掉）：一律同一提示")
    void resetRejectsMissingCode() {
        channelsEnabled();
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser("13900000001", null));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reset("10001", "123456", "NewPass123"));
        assertEquals(ErrorCode.VERIFY_CODE_INVALID, codeOf(ex));
    }

    @Test
    @DisplayName("口令强度校验先于兑码：弱口令被拒时不改库")
    void resetValidatesPolicyBeforeConsumingCode() {
        channelsEnabled();
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser("13900000001", null));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("123456");
        doThrow(new BusinessException(ErrorCode.PARAM_INVALID, "口令强度不足"))
                .when(passwordPolicyService).validate(anyString(), anyString());

        assertThrows(BusinessException.class,
                () -> service.reset("10001", "123456", "weak"));

        // 强度校验排在兑码之前，弱口令不会消耗验证码 ——
        // 否则用户换成强口令时会发现码已被用掉，只能重新发码。
        verify(userService, never()).updatePassword(anyLong(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("重置成功：写新口令 + 作废该账号全部会话 + 清掉三个 Redis 键")
    void resetSucceedsAndRevokesSessions() {
        channelsEnabled();
        allowRateLimit();
        when(userService.findByAccount("10001")).thenReturn(localUser("13900000001", null));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("123456");

        service.reset("10001", "123456", "NewPass123");

        verify(userService).updatePassword(USER_ID, "NewPass123", true);
        // 找回密码的典型场景是「账号可能已泄露」，此刻旧会话正是必须立刻切断的东西
        verify(userService).bumpTokenVersion(USER_ID);
        verify(redisTemplate).delete("forget:code:" + USER_ID);
        verify(redisTemplate).delete("forget:fail:" + USER_ID);
        verify(redisTemplate).delete("forget:send:" + USER_ID);
        // 同一枚验证码不允许兑两次：码与计数器都被删掉，重放会落到「码不存在」分支
        // （record 是 7 参签名：userId / username / module / action / detail / success / risk）
        verify(operationLogService).record(any(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean(), any());
    }
}
