package com.enterprise.ticket.module.attachment.service;

import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 附件孤儿文件清理单测（需求方三波·第三波·需求 13）
 *
 * <p>「删磁盘文件」是本项目里最不可逆的一步，因此本类把<b>宽限期</b>这一唯一安全阀钉死：
 * 落盘与插库之间有时间窗，若任务恰好在窗口内运行，会把「即将被引用」的文件当孤儿删掉，
 * 表现为「刚上传成功、下载却 404」。用例覆盖：
 * <ol>
 *   <li>被引用的文件永不删除（无论多旧）；</li>
 *   <li>无引用且超过宽限期的文件被回收；</li>
 *   <li>无引用但<b>未超宽限期</b>的文件必须保留（核心安全属性）；</li>
 *   <li>宽限期配置为 0 时按最小值 1 小时兜底（否则 cutoff=now，任何文件都会被判定为「过旧」）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AttachmentOrphanCleanupServiceTest {

    private static final String GRACE_KEY = "attachment_orphan_grace_hours";
    private static final int DEFAULT_GRACE_HOURS = 24;
    private static final long HOUR_MILLIS = 3600_000L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Attachment.class);
    }

    @TempDir
    Path tempDir;

    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private AttachmentStorage storage;
    @Mock
    private JobLockService jobLockService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AttachmentOrphanCleanupService service;

    /** 造一个「最后修改时间在 hoursAgo 小时前」的真实文件，并把它接进 storage 桩 */
    private Path orphanFile(String relative, double hoursAgo) throws IOException {
        Path file = tempDir.resolve(relative.replace('/', '_'));
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                System.currentTimeMillis() - (long) (hoursAgo * HOUR_MILLIS)));
        when(storage.relativeOf(file)).thenReturn(relative);
        // resolve 只在「真的要删」时才被调用：用 lenient 以免「保留」类用例触发严格模式的
        // UnnecessaryStubbing（保留类用例本就不应走到删除分支）
        lenient().when(storage.resolve(relative)).thenReturn(file);
        return file;
    }

    @Test
    @DisplayName("被引用的文件即使很旧也不会被删除")
    void referencedFileIsKept() throws IOException {
        when(systemConfigService.getInt(GRACE_KEY, DEFAULT_GRACE_HOURS)).thenReturn(1);

        Path file = orphanFile("2026/09/keep.txt", 48);
        Attachment attachment = new Attachment();
        attachment.setStoredPath("2026/09/keep.txt");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment));
        when(storage.listFiles()).thenReturn(List.of(file));

        assertEquals(0, service.doCleanup());
        assertTrue(Files.exists(file), "被未软删记录引用的文件不得删除");
    }

    @Test
    @DisplayName("无引用且超过宽限期的文件被回收")
    void staleOrphanIsDeleted() throws IOException {
        when(systemConfigService.getInt(GRACE_KEY, DEFAULT_GRACE_HOURS)).thenReturn(1);
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        Path file = orphanFile("2026/09/orphan.txt", 2);
        when(storage.listFiles()).thenReturn(List.of(file));

        assertEquals(1, service.doCleanup());
        assertFalse(Files.exists(file), "超过宽限期的孤儿文件应被删除");
    }

    @Test
    @DisplayName("无引用但未超宽限期的文件必须保留（防止删掉即将被引用的文件）")
    void freshFileWithinGraceIsKept() throws IOException {
        when(systemConfigService.getInt(GRACE_KEY, DEFAULT_GRACE_HOURS)).thenReturn(1);
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        // 刚落盘 1 分钟，处于「落盘→插库」时间窗内
        Path file = orphanFile("2026/09/fresh.txt", 1.0 / 60);
        when(storage.listFiles()).thenReturn(List.of(file));

        assertEquals(0, service.doCleanup());
        assertTrue(Files.exists(file), "宽限期内的文件不得删除");
    }

    @Test
    @DisplayName("宽限期配置为 0 时按最小值 1 小时兜底，30 分钟前的文件仍保留")
    void graceHoursFlooredToOne() throws IOException {
        when(systemConfigService.getInt(GRACE_KEY, DEFAULT_GRACE_HOURS)).thenReturn(0);
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        Path file = orphanFile("2026/09/recent.txt", 0.5);
        when(storage.listFiles()).thenReturn(List.of(file));

        assertEquals(0, service.doCleanup());
        assertTrue(Files.exists(file),
                "若未做下限兜底，cutoff=now 会把 30 分钟前的文件误判为孤儿并删除");
    }
}
