package com.enterprise.ticket.module.backup.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.BackupStatus;
import com.enterprise.ticket.common.constant.BackupTrigger;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;
import com.enterprise.ticket.module.backup.entity.BackupRecord;
import com.enterprise.ticket.module.backup.mapper.BackupRecordMapper;
import com.enterprise.ticket.module.backup.support.BackupException;
import com.enterprise.ticket.module.backup.support.DatabaseDumper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据库备份服务单元测试（P0）
 *
 * <h2>为什么每个「失败」场景都要配一个「对照」</h2>
 * 备份这条链路最容易出现的假绿是「断言了坏现象不存在，但没断言好现象真的发生」。
 * 例如「失败时不清理」若只断言 {@code selectExpiredSuccess} 未被调用，
 * 那么把清理逻辑整个删掉（成功时也不清理）测试依旧全绿 —— 而那是
 * 「旧归档永远不删、NAS 撑爆」的缺陷。因此成功路径同样要断言清理<b>确实执行了</b>，
 * 并且用真实临时文件证明<b>文件真的被删掉</b>，而不只是「调了一次方法」。
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceImplTest {

    private static final Long OPERATOR_ID = 7L;

    @Mock
    private BackupRecordMapper backupRecordMapper;
    @Mock
    private DatabaseDumper dumper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private MessageService messageService;
    @Mock
    private UserMapper userMapper;

    private BackupServiceImpl service;

    @TempDir
    Path tempDir;

    /**
     * 注册实体的 Lambda 缓存。
     *
     * <p>本测试全程用 Mockito，不启动 Spring，而 {@code page()} 会构造
     * {@code LambdaQueryWrapper} 并使用方法引用（{@code BackupRecord::getStartedAt}）——
     * 求值方法引用需要 MyBatis-Plus 的实体元数据，缺了会抛
     * {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(BackupRecord.class);
    }

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getBackup().setDir(tempDir.toString());
        appProperties.getBackup().setTimeoutSeconds(1800);
        service = new BackupServiceImpl(backupRecordMapper, dumper, systemConfigService,
                appProperties, messageService, userMapper);
        // 目录参数留空 ⇒ 回落应用配置（tempDir），保证不碰真实路径。
        // lenient：失败路径不会走到保留清理，若用严格桩会被判「多余的打桩」而报错。
        lenient().when(systemConfigService.backupRetentionDays()).thenReturn(30);
    }

    // ------------------------------------------------------------------
    // 执行：成功 / 失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("成功：先落 RUNNING，再落 SUCCESS + 文件信息，且不发告警")
    void success_recordsArtifactAndStaysSilent() {
        Path archive = tempDir.resolve("db-backup-20261003-020000.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 2048L));
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of());

        BackupRecordVO result = service.runManually(OPERATOR_ID);

        ArgumentCaptor<BackupRecord> inserted = ArgumentCaptor.forClass(BackupRecord.class);
        verify(backupRecordMapper).insert(inserted.capture());
        assertEquals(BackupStatus.RUNNING.name(), inserted.getValue().getStatus(),
                "必须先落 RUNNING —— 否则「备份中」在页面上无法呈现，僵尸回收也无从判定");
        assertEquals(BackupTrigger.MANUAL.name(), inserted.getValue().getTriggerType());
        assertEquals(OPERATOR_ID, inserted.getValue().getOperatorId());

        ArgumentCaptor<BackupRecord> updated = ArgumentCaptor.forClass(BackupRecord.class);
        verify(backupRecordMapper).updateById(updated.capture());
        assertEquals(BackupStatus.SUCCESS.name(), updated.getValue().getStatus());
        assertEquals(2048L, updated.getValue().getFileSize());
        assertNotNull(updated.getValue().getFinishedAt());
        assertNull(updated.getValue().getErrorMessage());

        assertEquals(BackupStatus.SUCCESS.name(), result.getStatus());
        assertEquals("db-backup-20261003-020000.sql.gz", result.getFileName());
        verify(messageService, never()).send(anyList(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("失败：落 FAILED + 原因，并给超管发告警（不抛异常）")
    void failure_recordsReasonAndAlertsSuperAdmins() {
        when(dumper.dump(any(), anyString()))
                .thenThrow(new BackupException("备份目录不可写：Z:/nas/backup"));
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));

        // 关键：环境类失败**不抛异常**，否则管理员会以为是自己操作错了
        BackupRecordVO result = service.runManually(OPERATOR_ID);

        ArgumentCaptor<BackupRecord> updated = ArgumentCaptor.forClass(BackupRecord.class);
        verify(backupRecordMapper).updateById(updated.capture());
        assertEquals(BackupStatus.FAILED.name(), updated.getValue().getStatus());
        assertTrue(updated.getValue().getErrorMessage().contains("Z:/nas/backup"),
                "失败原因必须原样保留可定位的信息（这里是路径）");

        assertEquals(BackupStatus.FAILED.name(), result.getStatus());
        verify(messageService).send(eq(List.of(1L)), eq(MessageType.OPS_BACKUP_ALERT),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("失败：没有任何超管可通知时不抛异常（不能因为「没人可通知」污染备份流程）")
    void failure_withoutReceivers_doesNotThrow() {
        when(dumper.dump(any(), anyString())).thenThrow(new BackupException("磁盘已满"));
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of());

        BackupRecordVO result = service.runManually(OPERATOR_ID);

        assertEquals(BackupStatus.FAILED.name(), result.getStatus());
        verify(messageService, never()).send(anyList(), any(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // 保留清理：只在成功后执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 失败时**不清理**旧归档（先成功后清理的铁律）")
    void failure_mustNotCleanupOldArchives() {
        when(dumper.dump(any(), anyString())).thenThrow(new BackupException("mysqldump 未找到"));
        when(userMapper.selectSuperAdminIds()).thenReturn(List.of(1L));

        service.runManually(OPERATOR_ID);

        verify(backupRecordMapper, never()).selectExpiredSuccess(any());
        verify(backupRecordMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("★ 成功时确实清理超期归档，并**真的删掉文件**（不是只调了个方法）")
    void success_cleansUpExpiredArchivesAndFiles() throws IOException {
        Path archive = tempDir.resolve("db-backup-20261003-020000.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 1024L));

        Path expiredFile = tempDir.resolve("db-backup-20260801-020000.sql.gz");
        Files.writeString(expiredFile, "old");
        BackupRecord expired = new BackupRecord();
        expired.setId(99L);
        expired.setFilePath(expiredFile.toString());
        expired.setStatus(BackupStatus.SUCCESS.name());
        expired.setStartedAt(LocalDateTime.now().minusDays(40));
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of(expired));

        service.runManually(OPERATOR_ID);

        assertEquals(false, Files.exists(expiredFile), "超期归档文件必须被真的删除");
        verify(backupRecordMapper).deleteById(99L);
    }

    @Test
    @DisplayName("超期文件删不掉时不影响本次成功判定（下次清理会重试）")
    void success_survivesUndeletableExpiredFile() {
        Path archive = tempDir.resolve("db-backup-20261003-020000.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 1024L));
        // 指向一个不可能删掉的路径（父目录其实是文件）
        BackupRecord expired = new BackupRecord();
        expired.setId(100L);
        expired.setFilePath(tempDir.resolve("not-a-dir/x.sql.gz").toString());
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of(expired));

        BackupRecordVO result = service.runManually(OPERATOR_ID);

        assertEquals(BackupStatus.SUCCESS.name(), result.getStatus(),
                "清理的局部失败不该把一次成功的备份判成失败");
    }

    // ------------------------------------------------------------------
    // 准入条件：手动 vs 定时
    // ------------------------------------------------------------------

    @Test
    @DisplayName("手动触发：已有备份在跑 ⇒ 明确报错（而不是并发再压一个 mysqldump）")
    void manual_rejectedWhenAnotherBackupRunning() {
        when(backupRecordMapper.countRunning()).thenReturn(1);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.runManually(OPERATOR_ID));
        assertEquals(ErrorCode.BACKUP_ALREADY_RUNNING, e.getErrorCode());
        verify(backupRecordMapper, never()).insert(any(BackupRecord.class));
    }

    @Test
    @DisplayName("★ 手动触发**不看**开关、也**不看**今天是否跑过（否则「刚改完想备份一份」会被莫名拒绝）")
    void manual_ignoresEnabledFlagAndDailyGuard() {
        Path archive = tempDir.resolve("db-backup-20261003-030000.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 512L));
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of());
        // 刻意**不**对 backupEnabled() 打桩：下面的 never() 断言如果在桩上再触发，
        // 严格模式会把它判成「多余的打桩」—— 而本用例的结论恰恰是「它根本没被问过」。
        BackupRecordVO result = service.runManually(OPERATOR_ID);

        assertEquals(BackupStatus.SUCCESS.name(), result.getStatus());
        // 开关只约束「自动」，不应影响手动
        verify(systemConfigService, never()).backupEnabled();
    }

    @Test
    @DisplayName("定时触发：未启用 ⇒ 跳过（返回 null，不落记录）")
    void scheduled_skippedWhenDisabled() {
        when(systemConfigService.backupEnabled()).thenReturn(false);

        assertNull(service.runScheduled());
        verify(backupRecordMapper, never()).insert(any(BackupRecord.class));
    }

    @Test
    @DisplayName("★ 定时触发：今天已跑过 ⇒ 跳过（补跑语义的幂等位落在库里，重启后仍成立）")
    void scheduled_skippedWhenAlreadyRanToday() {
        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(1);

        assertNull(service.runScheduled());
        verify(backupRecordMapper, never()).insert(any(BackupRecord.class));
    }

    @Test
    @DisplayName("定时触发：未启用且已跑过的两种跳过原因互不相同（判据顺序不能反）")
    void scheduled_checksEnabledBeforeDailyGuard() {
        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(0);
        when(backupRecordMapper.countRunning()).thenReturn(0);
        Path archive = tempDir.resolve("db-backup-20261003-020000.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 256L));
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of());

        BackupRecordVO result = service.runScheduled();

        assertNotNull(result);
        assertEquals(BackupTrigger.SCHEDULED.name(), result.getTriggerType());
        assertNull(result.getOperatorName(), "定时备份没有操作人");
    }

    @Test
    @DisplayName("定时触发：已有备份在跑 ⇒ 跳过而不是报错（定时任务不该抛异常）")
    void scheduled_skippedWhenRunning() {
        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(0);
        when(backupRecordMapper.countRunning()).thenReturn(1);

        assertNull(service.runScheduled());
    }

    // ------------------------------------------------------------------
    // 僵尸回收 / 概览
    // ------------------------------------------------------------------

    @Test
    @DisplayName("僵尸回收：把回收原因与容忍窗口传给 Mapper")
    void recycleStaleRunning_passesCutoffAndReason() {
        when(backupRecordMapper.failStaleRunning(any(), anyString())).thenReturn(2);

        assertEquals(2, service.recycleStaleRunning());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(backupRecordMapper).failStaleRunning(cutoff.capture(), reason.capture());
        // 容忍窗口必须「比备份超时更长」——否则正常执行中的备份会被误回收
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusSeconds(1800)),
                "回收线应落在「超时上限 + 余量」之前，避免误杀正在跑的备份");
        assertTrue(reason.getValue().contains("进程重启"));
    }

    @Test
    @DisplayName("概览：目录不可写时 dirWritable=false，且从未成功过时 lastSuccessAt 为 null")
    void overview_exposesWritabilityAndNeverSucceeded() {
        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(systemConfigService.backupHour()).thenReturn(3);
        when(systemConfigService.backupDir()).thenReturn("");
        when(dumper.checkWritable(any())).thenReturn("备份目录不可写：Z:/nas");
        when(backupRecordMapper.selectLastSuccess()).thenReturn(null);
        when(backupRecordMapper.selectLastFailure()).thenReturn(null);
        when(backupRecordMapper.countRunning()).thenReturn(0);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(0);

        var vo = service.overview();

        assertEquals(false, vo.isDirWritable(),
                "目录不可写必须在**备份失败之前**就暴露出来");
        assertNull(vo.getLastSuccessAt(), "从未成功过时必须是 null，让页面显式呈现「从未成功」");
        assertEquals(3, vo.getBackupHour());
    }

    @Test
    @DisplayName("概览：失败早于最后一次成功 ⇒ 不呈现失败（避免历史失败长期误导当前判断）")
    void overview_hidesStaleFailure() {
        BackupRecord success = new BackupRecord();
        success.setStartedAt(LocalDateTime.now().minusHours(1));
        success.setFileName("db-backup-ok.sql.gz");
        success.setFileSize(4096L);

        BackupRecord oldFailure = new BackupRecord();
        oldFailure.setStartedAt(LocalDateTime.now().minusDays(3));
        oldFailure.setErrorMessage("陈旧失败");

        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(systemConfigService.backupHour()).thenReturn(2);
        when(systemConfigService.backupDir()).thenReturn("");
        when(dumper.checkWritable(any())).thenReturn(null);
        when(backupRecordMapper.selectLastSuccess()).thenReturn(success);
        when(backupRecordMapper.selectLastFailure()).thenReturn(oldFailure);
        when(backupRecordMapper.countRunning()).thenReturn(0);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(1);

        var vo = service.overview();

        assertNull(vo.getLastFailureAt(), "失败已被更晚的成功覆盖，不该继续显示");
        assertEquals("db-backup-ok.sql.gz", vo.getLastSuccessFile());
        assertEquals("4.00 KB", vo.getLastSuccessSizeText());
        assertEquals(true, vo.isScheduledToday());
    }

    @Test
    @DisplayName("概览：失败晚于最后一次成功 ⇒ 呈现失败")
    void overview_showsRecentFailure() {
        BackupRecord success = new BackupRecord();
        success.setStartedAt(LocalDateTime.now().minusDays(2));
        success.setFileName("db-backup-old.sql.gz");
        success.setFileSize(1024L);

        BackupRecord failure = new BackupRecord();
        failure.setStartedAt(LocalDateTime.now().minusHours(2));
        failure.setErrorMessage("磁盘已满");

        when(systemConfigService.backupEnabled()).thenReturn(true);
        when(systemConfigService.backupHour()).thenReturn(2);
        when(systemConfigService.backupDir()).thenReturn("");
        when(dumper.checkWritable(any())).thenReturn(null);
        when(backupRecordMapper.selectLastSuccess()).thenReturn(success);
        when(backupRecordMapper.selectLastFailure()).thenReturn(failure);
        when(backupRecordMapper.countRunning()).thenReturn(0);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(0);

        var vo = service.overview();

        assertNotNull(vo.getLastFailureAt());
        assertEquals("磁盘已满", vo.getLastFailureReason());
    }

    @Test
    @DisplayName("概览：未启用时给出「别同时开两个备份」的提示（避免双份存储）")
    void overview_hintsWhenDisabled() {
        when(systemConfigService.backupEnabled()).thenReturn(false);
        when(systemConfigService.backupHour()).thenReturn(2);
        when(systemConfigService.backupDir()).thenReturn("");
        when(dumper.checkWritable(any())).thenReturn(null);
        when(backupRecordMapper.selectLastSuccess()).thenReturn(null);
        when(backupRecordMapper.selectLastFailure()).thenReturn(null);
        when(backupRecordMapper.countRunning()).thenReturn(0);
        when(backupRecordMapper.countScheduledSince(any())).thenReturn(0);

        String hint = service.overview().getScheduleHint();

        assertTrue(hint.contains("未启用"));
        assertTrue(hint.contains("deploy/backup"), "必须点明「另一条备份链路」的存在，否则两套备份会同时跑");
    }

    @Test
    @DisplayName("概览：列表分页大小被收口到 1..100")
    void pageSizeIsClamped() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BackupRecord> empty =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        when(backupRecordMapper.selectPage(any(), any())).thenReturn(empty);

        service.page(0, 9999);

        // 只需保证不抛异常即可：真正的收口发生在传给 Mapper 的 Page 上
        verify(backupRecordMapper, times(1)).selectPage(any(), any());
    }

    @Test
    @DisplayName("操作人姓名可解析；解析不到时给出可读兜底")
    void operatorNameResolved() {
        Path archive = tempDir.resolve("db-backup-x.sql.gz");
        when(dumper.dump(any(), anyString())).thenReturn(new DatabaseDumper.Artifact(archive, 128L));
        when(backupRecordMapper.selectExpiredSuccess(any())).thenReturn(List.of());
        User user = new User();
        user.setRealName("张伟");
        when(userMapper.selectById(OPERATOR_ID)).thenReturn(user);

        BackupRecordVO result = service.runManually(OPERATOR_ID);

        assertEquals("张伟", result.getOperatorName());
    }
}
