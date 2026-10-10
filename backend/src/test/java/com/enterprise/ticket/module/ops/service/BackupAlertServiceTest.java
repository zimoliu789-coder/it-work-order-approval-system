package com.enterprise.ticket.module.ops.service;

import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.ops.dto.BackupAlertRequest;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 备份结果处理单测（部署与限流加固 · 规范 §32「备份失败不得静默」）
 *
 * <p>备份脚本无法直接调用应用消息服务，只能把结果回调进应用由应用发站内消息。
 * 本类钉死三条行为：
 * <ol>
 *   <li>失败 → 向全部超管发 {@code OPS_BACKUP_ALERT} 消息，正文包含失败原因；</li>
 *   <li>成功 → 只记审计、<b>不</b>发消息（避免每日成功消息淹没真正的失败告警）；</li>
 *   <li>审计写失败不能连带吞掉告警 —— 告警是「救人」的，审计是「取证」的。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupAlertServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private BackupAlertService service;

    private BackupAlertRequest failureRequest() {
        BackupAlertRequest request = new BackupAlertRequest();
        request.setStatus("FAILED");
        request.setFileName("db-backup-20260919-020001.tar.gz");
        request.setError("mysqldump: Got error: 1045 Access denied");
        request.setHost("nas-01");
        request.setRetentionDays(30);
        return request;
    }

    @Test
    @DisplayName("失败：向全部超管推送告警，正文含文件与原因，返回接收人数")
    void failureNotifiesAllSuperAdmins() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L, 2L, 3L));

        int notified = service.report(failureRequest());

        assertEquals(3, notified);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService).send(eq(List.of(1L, 2L, 3L)), eq(MessageType.OPS_BACKUP_ALERT),
                eq("备份作业失败"), content.capture(), isNull());
        assertTrue(content.getValue().contains("db-backup-20260919-020001.tar.gz"));
        assertTrue(content.getValue().contains("Access denied"));
    }

    @Test
    @DisplayName("失败：同时写入审计，动作 BACKUP_ALERT_REPORT 且 success=false")
    void failureRecordsAudit() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));

        service.report(failureRequest());

        verify(operationLogService).record(isNull(), isNull(), eq("OPS"), eq("BACKUP_ALERT_REPORT"),
                anyString(), eq(false), eq(RiskLevel.NORMAL));
    }

    @Test
    @DisplayName("成功：只记审计，不推送消息")
    void successRecordsAuditOnly() {
        BackupAlertRequest request = failureRequest();
        request.setStatus("SUCCESS");
        request.setError(null);
        request.setSizeBytes(204800L);

        int notified = service.report(request);

        assertEquals(0, notified);
        verify(messageService, never()).send(anyList(), any(), anyString(), anyString(), any());
        verify(operationLogService).record(isNull(), isNull(), eq("OPS"), eq("BACKUP_ALERT_REPORT"),
                anyString(), eq(true), eq(RiskLevel.NORMAL));
    }

    @Test
    @DisplayName("失败但系统内无可用超管：返回 0、不发消息，但审计照记（不静默）")
    void failureWithoutRecipients() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of());

        int notified = service.report(failureRequest());

        assertEquals(0, notified);
        verify(messageService, never()).send(anyList(), any(), anyString(), anyString(), any());
        verify(operationLogService).record(isNull(), isNull(), eq("OPS"), eq("BACKUP_ALERT_REPORT"),
                anyString(), eq(false), eq(RiskLevel.NORMAL));
    }

    @Test
    @DisplayName("审计写失败被吞掉，但告警仍照常发出（取证失败不牵连救人）")
    void alertSurvivesAuditFailure() {
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(7L));
        doThrow(new RuntimeException("operation_log insert failed"))
                .when(operationLogService).record(any(), any(), anyString(), anyString(), anyString(), anyBoolean(), any());

        int notified = service.report(failureRequest());

        assertEquals(1, notified);
        verify(messageService).send(eq(List.of(7L)), eq(MessageType.OPS_BACKUP_ALERT),
                eq("备份作业失败"), anyString(), isNull());
    }
}
