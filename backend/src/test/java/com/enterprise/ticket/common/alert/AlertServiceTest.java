package com.enterprise.ticket.common.alert;

import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.system.service.SystemConfigMailService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用告警分发（P4-C2）—— 单元测试。
 *
 * <p>本类是三处告警（主备 / 异常 / 安全）**唯一**的分发出口，因此这里守的是
 * 「通道行为」这一层，而不是任何一处告警的文案。五条口径：
 * <ol>
 *   <li><b>没有接收人不许静默</b> —— 「没人可通知」必须与「没触发告警」在审计里可区分，
 *       否则排障方向会完全相反；</li>
 *   <li><b>站内消息是必达通道</b> —— 它不依赖任何通道配置，必须在邮件 / 短信之前发出，
 *       且邮件失败绝不向外抛（抛出去会让定时任务整轮回滚、让脚本收到 500 后无限重试）；</li>
 *   <li><b>短信绝不假成功</b> —— 网关未接入，审计里写 {@code sms=log/N} 而不是
 *       {@code smsDelivered=N}。假成功会让管理员在真正需要它的那天才发现它从没发过；</li>
 *   <li><b>额外收件人只随 P0/P1 发</b> —— 提示级信息不该扩散到运维组之外；</li>
 *   <li><b>审计写失败只降级为日志</b> —— 告警是「救人」的、审计是「取证」的。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通用告警分发（必达通道 / 逐人隔离 / 短信不假成功）")
class AlertServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private SystemConfigMailService mailService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AlertService alertService;

    private static final String TITLE = "测试告警标题";
    private static final String BODY = "测试告警正文";

    private User user(long id, String email, String phone) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPhone(phone);
        return user;
    }

    private String capturedAuditDetail() {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).record(any(), any(), eq(AlertService.AUDIT_MODULE), eq("TEST_ACTION"),
                detail.capture(), anyBoolean(), eq(RiskLevel.NORMAL));
        return detail.getValue();
    }

    private AlertService.AlertOutcome fire() {
        return alertService.alert(AlertLevel.P1, MessageType.EXCEPTION_ALERT, TITLE, BODY,
                "TEST_ACTION", "detail");
    }

    // ==================================================================
    // 接收人为空：不静默
    // ==================================================================

    @Test
    @DisplayName("★ 没有可用超管 → 不发消息，但必须留一条 recipients=0 的失败审计（不许静默）")
    void noRecipientsStillLeavesAudit() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of());

        AlertService.AlertOutcome outcome = fire();

        verify(messageService, never()).send(anyCollection(), any(), anyString(), anyString(), any());
        verify(mailService, never()).sendAlert(anyString(), anyString(), anyString());
        assertTrue(capturedAuditDetail().contains("recipients=0"),
                "「没人可通知」必须与「没触发告警」在审计里可区分");
        assertTrue(!outcome.delivered(), "没有接收人时不应报告「已送达」");
    }

    // ==================================================================
    // 站内消息（必达通道）
    // ==================================================================

    @Test
    @DisplayName("站内消息发给全部超管，标题原样传递，并追加通道说明页脚")
    void inAppMessageGoesToAllSuperAdmins() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L, 2L));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(false);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        fire();

        verify(messageService).send(eq(List.of(1L, 2L)), eq(MessageType.EXCEPTION_ALERT),
                eq(TITLE), body.capture(), isNull());
        assertTrue(body.getValue().startsWith(BODY), "原正文必须在前：" + body.getValue());
        assertTrue(body.getValue().contains("告警级别"), "应追加分级说明：" + body.getValue());
        assertTrue(body.getValue().contains("站内消息为必达通道"), "应说明各通道状态：" + body.getValue());
    }

    @Test
    @DisplayName("两个通道都关 → 站内消息照发（必达通道不依赖任何通道配置）")
    void inAppChannelIndependentOfOthers() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(false);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);

        fire();

        verify(messageService).send(anyCollection(), eq(MessageType.EXCEPTION_ALERT),
                anyString(), anyString(), any());
        assertTrue(capturedAuditDetail().contains("email=0/0"), capturedAuditDetail());
    }

    // ==================================================================
    // 邮件通道
    // ==================================================================

    @Test
    @DisplayName("邮件通道启用且有邮箱 → 真实发送，并在审计里记 email=1/1")
    void emailSentAndAudited() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L, 2L));
        when(userMapper.selectBatchIds(any()))
                .thenReturn(List.of(user(1L, "ops@company.com", null), user(2L, null, null)));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(true);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);
        when(systemConfigService.siteName()).thenReturn("设备借用工单系统");
        when(systemConfigService.exceptionAlertExtraRecipients()).thenReturn("");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        fire();

        verify(mailService).sendAlert(eq("ops@company.com"), subject.capture(), anyString());
        assertTrue(subject.getValue().contains("设备借用工单系统"), subject.getValue());
        assertTrue(subject.getValue().contains("P1"), "主题应带级别，便于在收件箱里一眼分流：" + subject.getValue());
        assertTrue(capturedAuditDetail().contains("email=1/1"), capturedAuditDetail());
    }

    @Test
    @DisplayName("★ 邮件通道未启用 → 一条都不发（不能因为「填了邮箱」就绕过开关）")
    void emailSkippedWhenChannelDisabled() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(false);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);

        fire();

        verify(mailService, never()).sendAlert(anyString(), anyString(), anyString());
        verify(messageService).send(anyCollection(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("★ 某个人发失败不影响其他人 —— 也不影响已经落袋的站内消息")
    void emailFailureIsIsolatedPerRecipient() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L, 2L));
        when(userMapper.selectBatchIds(any()))
                .thenReturn(List.of(user(1L, "bad@company.com", null), user(2L, "ok@company.com", null)));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(true);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);
        when(systemConfigService.siteName()).thenReturn("工单系统");
        when(systemConfigService.exceptionAlertExtraRecipients()).thenReturn("");
        doThrow(new RuntimeException("SMTP 连接超时"))
                .when(mailService).sendAlert(eq("bad@company.com"), anyString(), anyString());

        assertDoesNotThrow(this::fire);

        verify(mailService).sendAlert(eq("ok@company.com"), anyString(), anyString());
        verify(messageService).send(anyCollection(), any(), anyString(), anyString(), any());
        assertTrue(capturedAuditDetail().contains("email=1/2"), capturedAuditDetail());
    }

    @Test
    @DisplayName("读通道开关抛异常 → 按关闭处理，站内消息仍然照发（告警链路不能被配置读坏）")
    void channelFlagReadFailureFallsBackToDisabled() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(systemConfigService.emailVerifyEnabled()).thenThrow(new RuntimeException("参数行缺失"));
        when(systemConfigService.smsVerifyEnabled()).thenThrow(new RuntimeException("参数行缺失"));

        assertDoesNotThrow(this::fire);

        verify(mailService, never()).sendAlert(anyString(), anyString(), anyString());
        verify(messageService).send(anyCollection(), any(), anyString(), anyString(), any());
    }

    // ==================================================================
    // 额外收件人（P0/P1 才发）
    // ==================================================================

    @Test
    @DisplayName("P1 → 额外收件人收到邮件；审计记 extra=2（含去重）")
    void extraRecipientsGetMailOnP1() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, null, null)));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(true);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);
        when(systemConfigService.siteName()).thenReturn("工单系统");
        // 同一个邮箱写两遍：必须去重，否则同一个人收两封同样的邮件
        when(systemConfigService.exceptionAlertExtraRecipients())
                .thenReturn("ops@company.com, ops@company.com , devops@company.com");

        alertService.alert(AlertLevel.P1, MessageType.EXCEPTION_ALERT, TITLE, BODY, "TEST_ACTION", "detail");

        verify(mailService).sendAlert(eq("ops@company.com"), anyString(), anyString());
        verify(mailService).sendAlert(eq("devops@company.com"), anyString(), anyString());
        assertTrue(capturedAuditDetail().contains("extra=2"), capturedAuditDetail());
    }

    @Test
    @DisplayName("★ P2 → 不发给额外收件人（提示级信息不该扩散到运维组之外）")
    void extraRecipientsSkippedOnP2() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(1L, null, null)));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(true);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);
        when(systemConfigService.siteName()).thenReturn("工单系统");

        alertService.alert(AlertLevel.P2, MessageType.EXCEPTION_ALERT, TITLE, BODY, "TEST_ACTION", "detail");

        verify(mailService, never()).sendAlert(anyString(), anyString(), anyString());
        assertTrue(capturedAuditDetail().contains("extra=0"), capturedAuditDetail());
    }

    // ==================================================================
    // 短信：只记日志，绝不假成功
    // ==================================================================

    @Test
    @DisplayName("★ 短信通道启用 → 只写日志，审计记 sms=log/1，绝不出现「已送达」")
    void smsCountedButNeverDelivered() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L, 2L));
        when(userMapper.selectBatchIds(any()))
                .thenReturn(List.of(user(1L, null, "13800000001"), user(2L, null, null)));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(false);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(true);

        AlertService.AlertOutcome outcome = fire();

        verify(mailService, never()).sendAlert(anyString(), anyString(), anyString());
        String detail = capturedAuditDetail();
        assertTrue(detail.contains("sms=log/1"), detail);
        assertTrue(!detail.contains("Delivered"), "审计里不得出现任何「已送达」字样：" + detail);
        assertTrue(outcome.smsLogged() == 1, "只有填了手机号的那一个被记入");
    }

    // ==================================================================
    // 审计自身的健壮性
    // ==================================================================

    @Test
    @DisplayName("★ 写审计抛异常 → 只降级为日志，绝不向外抛（取证失败不能把告警判为失败）")
    void auditFailureNeverPropagates() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));
        when(systemConfigService.emailVerifyEnabled()).thenReturn(false);
        when(systemConfigService.smsVerifyEnabled()).thenReturn(false);
        doThrow(new RuntimeException("审计库不可用"))
                .when(operationLogService).record(any(), any(), anyString(), anyString(),
                        anyString(), anyBoolean(), any());

        assertDoesNotThrow(this::fire);

        // 告警本身照常发出
        verify(messageService).send(anyCollection(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("读超管列表抛异常 → 当作「没有接收人」处理，不留半条告警（宁可不发也不发半条）")
    void superAdminReadFailureTreatedAsEmpty() {
        when(userMapper.selectSuperAdminIds()).thenThrow(new RuntimeException("用户表不可用"));

        AlertService.AlertOutcome outcome = fire();

        verify(messageService, never()).send(anyCollection(), any(), anyString(), anyString(), any());
        assertTrue(!outcome.delivered());
    }
}
