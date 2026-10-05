package com.enterprise.ticket.module.system.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.auth.service.VerificationCodeSender;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.system.support.SmsSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「发送测试短信」（P19-F 需求五）。
 *
 * <h2>本类要钉住的三件事</h2>
 * <ol>
 *   <li><b>诚实</b>：{@code delivered} 必须为 {@code false}，{@code channel} 为 {@code LOG} ——
 *       短信网关尚未接入，返回「发送成功」会让管理员误以为配置已可用；</li>
 *   <li><b>闸门</b>：手机号非法、通道未启用、参数缺项三种情况都必须被拒，
 *       且拒绝要发生在<b>投递之前</b>（不能先发一条日志再报错）；</li>
 *   <li><b>走真实链路</b>：参数齐备时确实经 {@link VerificationCodeSender} 投递了一次 ——
 *       接入网关后这条路径一行不改就会真的发短信。</li>
 * </ol>
 *
 * <p>注意测试电话号码用 {@code 13800001111} 形式：与生产同一套
 * {@code AccountFormats.isPhone} 判定，不另写正则（否则测过的号码线上可能被判非法）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("发送测试短信")
class SystemConfigSmsServiceTest {

    private static final String PHONE = "13800001111";

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private VerificationCodeSender verificationCodeSender;

    @InjectMocks
    private SystemConfigSmsService service;

    private SmsSettings.Settings full() {
        return new SmsSettings.Settings("ALIYUN", "LTAI-xxx", "secret-value", "设备借用", "SMS_123456");
    }

    private void channelEnabled(boolean enabled) {
        when(systemConfigService.smsVerifyEnabled()).thenReturn(enabled);
        when(systemConfigService.forgotCodeExpireMinutes()).thenReturn(5);
    }

    @Test
    @DisplayName("参数齐备且通道开启：投递一次，并诚实汇报「未真实送达、走的是日志通道」")
    void successButNotDelivered() {
        channelEnabled(true);

        SystemConfigSmsService.SmsTestResult result = service.sendTestSms(PHONE, full());

        assertNotNull(result);
        assertFalse(result.delivered(),
                "短信网关尚未接入，delivered 必须是 false —— 返回 true 会让管理员以为用户已经能收到短信");
        assertEquals("LOG", result.channel());
        assertEquals("ALIYUN", result.provider());
        assertEquals("阿里云短信", result.providerLabel());
        assertTrue(result.message().contains("未接入") || result.message().contains("没有真实发出"),
                "说明文案必须讲清「没有真实发出」，实际：" + result.message());

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(verificationCodeSender).send(eq(ContactRecovery.CONTACT_SMS), eq(PHONE),
                code.capture(), anyInt());
        assertEquals(6, code.getValue().length(), "测试验证码固定 6 位（用途只是产生一条可 grep 的日志）");
        assertTrue(code.getValue().chars().allMatch(Character::isDigit), "应当是纯数字验证码");
    }

    @Test
    @DisplayName("手机号格式非法：直接拒绝，且不触发任何投递（避免「报了错却已发出」）")
    void invalidPhone() {
        channelEnabled(true);

        for (String bad : new String[]{"", "  ", "1380000", "138000011112", "abcdefghijk", null}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.sendTestSms(bad, full()), "非法号码应被拒：" + bad);
            assertEquals(ErrorCode.CONTACT_PHONE_INVALID, ex.getErrorCode());
        }
        verify(verificationCodeSender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("通道未启用：拒绝并给出「先打开开关」的指引，且不触发投递")
    void channelDisabled() {
        channelEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendTestSms(PHONE, full()));
        assertEquals(ErrorCode.CONFIG_CHANNEL_DISABLED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("启用"),
                "要说清「怎么才能继续」，实际：" + ex.getMessage());
        verify(verificationCodeSender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("参数缺项：一次报出全部缺项（含项数），且不触发投递")
    void incompleteSettings() {
        channelEnabled(true);

        SmsSettings.Settings missing = new SmsSettings.Settings("", "", "", "", "");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendTestSms(PHONE, missing));
        assertEquals(ErrorCode.SMS_SEND_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("5 项"), "应报出缺项数量，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("短信签名"), "应逐条列出缺什么，实际：" + ex.getMessage());
        verify(verificationCodeSender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("服务商无法识别同样被拦（不能带着一个认不出的编码去投递）")
    void unknownProvider() {
        channelEnabled(true);

        SmsSettings.Settings bad = new SmsSettings.Settings("DINGTALK", "id", "s", "签名", "TPL");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendTestSms(PHONE, bad));
        assertEquals(ErrorCode.SMS_SEND_FAILED, ex.getErrorCode());
        verify(verificationCodeSender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("生效设置取自 SystemConfigService（未保存的表单值由控制器合并后传入）")
    void effectiveSettings() {
        SmsSettings.Settings saved = full();
        when(systemConfigService.smsSettings()).thenReturn(saved);
        assertEquals(saved, service.effectiveSettings());
    }
}
