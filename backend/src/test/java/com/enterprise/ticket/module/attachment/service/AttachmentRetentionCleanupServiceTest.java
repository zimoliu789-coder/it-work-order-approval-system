package com.enterprise.ticket.module.attachment.service;

import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 附件保留期清理单测（Phase 18 批次 E·需求五「已删工单附件保留」）
 *
 * <p>本任务会<b>物理删除磁盘文件与数据库行</b>，是整个系统里唯一不可逆的批量操作。
 * 因此测试不追求「功能跑通」，而是把三类安全属性钉死：
 * <ol>
 *   <li><b>保留期基准正确</b>：截止时刻必须由 {@code attachment_retention_days} 推出
 *       （基准是删除时刻，不是上传时刻）—— 算错一天就意味着多删或少删一批；</li>
 *   <li><b>不该删的绝不删</b>：主体仍存在、未知业务类型、保留期内，三种情况都必须原样保留；</li>
 *   <li><b>审计先于删除</b>：清单必须先同步落审计；审计写不成就一个一个都不删 ——
 *       否则会出现「文件没了，日志里也查不到删了什么」的不可追溯删除。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AttachmentRetentionCleanupServiceTest {

    private static final int RETENTION_DAYS = 30;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Attachment.class, Order.class, DeviceFault.class);
    }

    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private AttachmentStorage storage;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private DeviceFaultMapper faultMapper;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private AttachmentRetentionCleanupService service;

    private Attachment attachment(Long id, String bizType, Long bizId, String path) {
        Attachment entity = new Attachment();
        entity.setId(id);
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        entity.setStoredPath(path);
        entity.setCreatedAt(LocalDateTime.now().minusDays(90));
        return entity;
    }

    /** 「主体不存在」候选为空 —— 让用例只聚焦软删那一路 */
    private void noOrphanCandidates() {
        lenient().when(attachmentMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("保留期基准：截止时刻 = 现在 - 保留天数（不是上传时间）")
    void deadlineComesFromRetentionDays() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(7);
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of());
        noOrphanCandidates();

        assertEquals(0, service.doCleanup());

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachmentMapper).selectSoftDeletedBefore(deadline.capture(), anyInt());
        LocalDateTime captured = deadline.getValue();
        assertTrue(captured.isBefore(LocalDateTime.now().minusDays(6))
                        && captured.isAfter(LocalDateTime.now().minusDays(8)),
                "截止时刻应约为 7 天前，实际=" + captured);
    }

    @Test
    @DisplayName("超过保留期的已删附件：先删盘、再物理删行")
    void softDeletedExpiredIsPurged() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        Attachment target = attachment(1L, "APPLY_ATTACHMENT", 100L, "2026/06/abc.pdf");
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of(target));
        noOrphanCandidates();

        assertEquals(1, service.doCleanup());
        verify(storage).deleteQuietly("2026/06/abc.pdf");
        verify(attachmentMapper).hardDeleteById(1L);
        // 逻辑删除接口不能再被用来「物理删除」——它只会再写一次 deleted 标记
        verify(attachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("主体仍存在 → 即使很旧也不清理")
    void liveBizIsKept() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of());
        Attachment target = attachment(2L, "FAULT_PHOTO", 55L, "2026/06/f.png");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(target));
        when(faultMapper.selectById(55L)).thenReturn(new DeviceFault());

        assertEquals(0, service.doCleanup());
        verify(attachmentMapper, never()).hardDeleteById(anyLong());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    @DisplayName("主体已不存在（故障记录被删） → 清理")
    void orphanBizIsPurged() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of());
        Attachment target = attachment(3L, "FAULT_PHOTO", 55L, "2026/06/gone.png");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(target));
        when(faultMapper.selectById(55L)).thenReturn(null);

        assertEquals(1, service.doCleanup());
        verify(attachmentMapper).hardDeleteById(3L);
    }

    @Test
    @DisplayName("未知业务类型一律保留（不为不认识的类型做删除决定）")
    void unknownBizTypeIsKept() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of());
        Attachment target = attachment(4L, "FUTURE_TYPE", 7L, "2026/06/future.pdf");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(target));

        assertEquals(0, service.doCleanup());
        verify(attachmentMapper, never()).hardDeleteById(anyLong());
    }

    @Test
    @DisplayName("审计写入失败 → 本轮一个都不删（宁可不清理，也不可无痕删除）")
    void auditFailureAbortsCleanup() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        Attachment target = attachment(5L, "APPLY_ATTACHMENT", 100L, "2026/06/x.pdf");
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of(target));
        noOrphanCandidates();
        doThrow(new RuntimeException("审计库不可用"))
                .when(operationLogService)
                .record(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());

        assertThrows(RuntimeException.class, () -> service.doCleanup());
        verify(attachmentMapper, never()).hardDeleteById(anyLong());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    @DisplayName("清单为空 → 不写审计、不碰磁盘")
    void nothingToDoWritesNoAudit() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of());
        noOrphanCandidates();

        assertEquals(0, service.doCleanup());
        verifyNoInteractions(operationLogService);
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    @DisplayName("顺序不变量：审计 → 删盘 → 物理删行")
    void auditPrecedesDeletion() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        Attachment target = attachment(6L, "APPLY_ATTACHMENT", 100L, "2026/06/order.pdf");
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of(target));
        noOrphanCandidates();

        assertEquals(1, service.doCleanup());

        InOrder order = inOrder(operationLogService, storage, attachmentMapper);
        order.verify(operationLogService)
                .record(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
        order.verify(storage).deleteQuietly("2026/06/order.pdf");
        order.verify(attachmentMapper).hardDeleteById(6L);
    }

    @Test
    @DisplayName("单条失败不影响其余：剩余条目继续清理（下一轮调度会重试失败项）")
    void singleFailureDoesNotStopOthers() {
        when(systemConfigService.attachmentRetentionDays()).thenReturn(RETENTION_DAYS);
        Attachment first = attachment(7L, "APPLY_ATTACHMENT", 100L, "2026/06/a.pdf");
        Attachment second = attachment(8L, "APPLY_ATTACHMENT", 100L, "2026/06/b.pdf");
        when(attachmentMapper.selectSoftDeletedBefore(any(), anyInt())).thenReturn(List.of(first, second));
        noOrphanCandidates();
        when(attachmentMapper.hardDeleteById(7L)).thenThrow(new RuntimeException("行被并发删除"));

        assertEquals(1, service.doCleanup());
        verify(attachmentMapper).hardDeleteById(7L);
        verify(attachmentMapper).hardDeleteById(8L);
    }
}
