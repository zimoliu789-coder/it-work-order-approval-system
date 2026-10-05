package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.auth.dto.vo.BindContactSendCodeVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 绑定联系方式验证码服务（批次 C 需求六）。
 *
 * <h2>本类要钉住的四类行为</h2>
 * <ol>
 *   <li><b>渠道开关</b>：管理员关掉某渠道后，该渠道连码都发不出去；</li>
 *   <li><b>目标绑定</b>：校验时除了比对「码」，还必须比对「发码时的目标值」——
 *       漏掉这一步就等于允许「用发给自己的码绑定别人的号码」；</li>
 *   <li><b>限流与作废</b>：发码 10 分钟 3 条；校验错满 5 次即作废（删码，而非只锁一段时间）；</li>
 *   <li><b>用后即焚</b>：{@code consume} 把码、目标、失败计数一并清除。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("绑定联系方式验证码服务")
class ContactBindCodeServiceTest {

    private static final long USER_ID = 7L;
    private static final String PHONE = "13800001111";
    private static final String EMAIL = "zhangwei@example.com";
    private static final String SMS = "SMS";
    private static final String EMAIL_CH = "EMAIL";

    @Mock
    private UserService userService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private VerificationCodeSender codeSender;
    @Mock
    private Environment environment;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private ContactBindCodeService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(systemConfigService.forgotCodeLength()).thenReturn(6);
        when(systemConfigService.forgotCodeExpireMinutes()).thenReturn(5);
        // 默认按生产语义（不回传 devCode），需要时的用例再单独打开
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
    }

    private void channelsEnabled() {
        when(systemConfigService.smsVerifyEnabled()).thenReturn(true);
        when(systemConfigService.emailVerifyEnabled()).thenReturn(true);
    }

    private static ErrorCode codeOf(BusinessException ex) {
        return ex.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 发码：渠道与目标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("渠道被管理员关闭：拒绝发码，且不落任何 Redis 键")
    void rejectsWhenChannelDisabled() {
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode(USER_ID, SMS, PHONE));

        assertEquals(ErrorCode.CONTACT_CHANNEL_DISABLED, codeOf(ex));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(codeSender, never()).send(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("手机号格式非法：报 CONTACT_PHONE_INVALID")
    void rejectsInvalidPhoneFormat() {
        channelsEnabled();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode(USER_ID, SMS, "abc123"));

        assertEquals(ErrorCode.CONTACT_PHONE_INVALID, codeOf(ex));
    }

    @Test
    @DisplayName("邮箱格式非法：报 CONTACT_EMAIL_INVALID")
    void rejectsInvalidEmailFormat() {
        channelsEnabled();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode(USER_ID, EMAIL_CH, "not-an-email"));

        assertEquals(ErrorCode.CONTACT_EMAIL_INVALID, codeOf(ex));
    }

    @Test
    @DisplayName("目标已被其他账号占用：发码前就拒绝（不让用户白等一条短信）")
    void rejectsWhenTargetOccupied() {
        channelsEnabled();
        doThrow(new BusinessException(ErrorCode.CONTACT_PHONE_EXISTS))
                .when(userService).assertPhoneAvailable(PHONE, USER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode(USER_ID, SMS, PHONE));

        assertEquals(ErrorCode.CONTACT_PHONE_EXISTS, codeOf(ex));
        verify(codeSender, never()).send(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("发码成功：同时写入「码」与「目标值」两个键，并调用投递通道")
    void writesCodeAndTargetOnSuccess() {
        channelsEnabled();
        when(valueOps.increment("bind:send:" + USER_ID + ":" + SMS)).thenReturn(1L);

        BindContactSendCodeVO vo = service.sendCode(USER_ID, SMS, PHONE);

        assertEquals(SMS, vo.getContactType());
        assertEquals("138****1111", vo.getMaskedTarget());
        assertEquals(5, vo.getExpireMinutes());
        // 码与目标必须**都**落库：只存码就无法防「换号重放」
        verify(valueOps).set(eq("bind:code:" + USER_ID + ":" + SMS), anyString(), any(Duration.class));
        verify(valueOps).set(eq("bind:target:" + USER_ID + ":" + SMS), eq(PHONE), any(Duration.class));
        verify(codeSender).send(eq(SMS), eq(PHONE), anyString(), eq(5));
    }

    @Test
    @DisplayName("邮箱归一化：发码时目标值转小写（与 UserServiceImpl 同口径）")
    void normalizesEmailToLowerCase() {
        channelsEnabled();
        when(valueOps.increment(anyString())).thenReturn(1L);

        service.sendCode(USER_ID, EMAIL_CH, "ZhangWei@Example.com");

        verify(valueOps).set(eq("bind:target:" + USER_ID + ":" + EMAIL_CH), eq(EMAIL), any(Duration.class));
        verify(codeSender).send(eq(EMAIL_CH), eq(EMAIL), anyString(), eq(5));
    }

    @Test
    @DisplayName("发码限流：同渠道第 4 次被拒（RATE_LIMITED），且不投递")
    void rateLimitsFourthSend() {
        channelsEnabled();
        when(valueOps.increment("bind:send:" + USER_ID + ":" + SMS)).thenReturn(4L);
        when(redisTemplate.getExpire(anyString())).thenReturn(300L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode(USER_ID, SMS, PHONE));

        assertEquals(ErrorCode.RATE_LIMITED, codeOf(ex));
        verify(codeSender, never()).send(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ------------------------------------------------------------------
    // 校验：码 + 目标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("从未发过码：报验证码错误")
    void verifyWithoutCode() {
        when(valueOps.get("bind:code:" + USER_ID + ":" + SMS)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(USER_ID, SMS, PHONE, "123456"));

        assertEquals(ErrorCode.VERIFY_CODE_INVALID, codeOf(ex));
    }

    @Test
    @DisplayName("发码目标 ≠ 提交目标：按验证码错误拒绝，且不计失败次数")
    void verifyRejectsTargetMismatch() {
        when(valueOps.get("bind:code:" + USER_ID + ":" + SMS)).thenReturn("123456");
        when(valueOps.get("bind:target:" + USER_ID + ":" + SMS)).thenReturn("13900002222");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(USER_ID, SMS, PHONE, "123456"));

        assertEquals(ErrorCode.VERIFY_CODE_INVALID, codeOf(ex));
        // 关键：这个分支不能消耗失败次数，否则攻击者可以用「换号」把正常用户的码耗成作废
        verify(valueOps, never()).increment("bind:fail:" + USER_ID + ":" + SMS);
    }

    @Test
    @DisplayName("码错误：记一次失败并报验证码错误")
    void verifyRegistersWrongAttempt() {
        when(valueOps.get("bind:code:" + USER_ID + ":" + SMS)).thenReturn("123456");
        when(valueOps.get("bind:target:" + USER_ID + ":" + SMS)).thenReturn(PHONE);
        when(valueOps.increment("bind:fail:" + USER_ID + ":" + SMS)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(USER_ID, SMS, PHONE, "000000"));

        assertEquals(ErrorCode.VERIFY_CODE_INVALID, codeOf(ex));
        verify(valueOps).increment("bind:fail:" + USER_ID + ":" + SMS);
        // 未达上限（5 次）时**不**作废：码仍在，用户还能继续试
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("错满 5 次：作废验证码（删码 + 删目标 + 删计数）并报锁定")
    void verifyLocksAfterFiveWrongAttempts() {
        when(valueOps.get("bind:code:" + USER_ID + ":" + SMS)).thenReturn("123456");
        when(valueOps.get("bind:target:" + USER_ID + ":" + SMS)).thenReturn(PHONE);
        when(valueOps.increment("bind:fail:" + USER_ID + ":" + SMS)).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(USER_ID, SMS, PHONE, "000000"));

        assertEquals(ErrorCode.VERIFY_CODE_LOCKED, codeOf(ex));
        verify(redisTemplate).delete("bind:code:" + USER_ID + ":" + SMS);
        verify(redisTemplate).delete("bind:target:" + USER_ID + ":" + SMS);
        verify(redisTemplate).delete("bind:fail:" + USER_ID + ":" + SMS);
    }

    @Test
    @DisplayName("码与目标都正确：通过，且**不**在 verify 阶段消费（留给写库成功后 consume）")
    void verifySucceedsWithoutConsuming() {
        when(valueOps.get("bind:code:" + USER_ID + ":" + SMS)).thenReturn("123456");
        when(valueOps.get("bind:target:" + USER_ID + ":" + SMS)).thenReturn(PHONE);

        // 走到这里即表示未抛异常；verify 刻意不消费，消费由写库成功后的 consume 负责
        service.verify(USER_ID, SMS, PHONE, "123456");
        verify(redisTemplate, never()).delete(anyString());
    }

    // ------------------------------------------------------------------
    // 消费
    // ------------------------------------------------------------------

    @Test
    @DisplayName("consume：码、目标、失败计数三个键一并清除（用后即焚）")
    void consumeDeletesAllKeys() {
        service.consume(USER_ID, SMS);

        verify(redisTemplate, times(3)).delete(anyString());
        verify(redisTemplate).delete("bind:code:" + USER_ID + ":" + SMS);
        verify(redisTemplate).delete("bind:target:" + USER_ID + ":" + SMS);
        verify(redisTemplate).delete("bind:fail:" + USER_ID + ":" + SMS);
    }
}
