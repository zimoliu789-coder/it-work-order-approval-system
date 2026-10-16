package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import com.enterprise.ticket.module.upgrade.mapper.UpgradeTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 升级状态启动对账单测（Phase 18 批次 G）
 *
 * <p>这个组件存在的唯一理由是：<b>发起升级的进程与升级完成后运行的进程不是同一个</b>。
 * 因此它的判定必须建立在不依赖「发起方还活着」的证据上，本测试逐条验证三种证据的优先级：
 * <ol>
 *   <li>外部回执文件（最直接）；</li>
 *   <li>「当前版本号 == 目标版本」（兜底，覆盖「脚本写回执前就被重启杀掉」这一经典情形）；</li>
 *   <li>超时（仅在既无回执、版本也未变、且确实过了很久时才判失败 ——
 *       否则会在脚本正在跑的窗口里误杀）。</li>
 * </ol>
 *
 * <p>刻意不用 {@code @ExtendWith(MockitoExtension.class)}：本类需要按用例
 * 分别构造 stub，严格模式会把「本用例未使用」的桩判为失败，反而掩盖真实意图。
 */
class UpgradeRecoveryRunnerTest {

    @TempDir
    Path tempDir;

    private AppProperties properties;
    private UpgradeStorage storage;
    private UpgradeTaskMapper mapper;
    private UpgradeRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getUpgrade().setStorageRoot(tempDir.resolve("root").toString());
        properties.getUpgrade().setCurrentArtifactDir(tempDir.resolve("current").toString());
        properties.getUpgrade().setApplyTimeoutMinutes(30);

        storage = new UpgradeStorage(properties, new ObjectMapper());
        mapper = Mockito.mock(UpgradeTaskMapper.class);
        runner = new UpgradeRecoveryRunner(mapper, storage);
    }

    // ------------------------------------------------------------------
    // 证据 1：外部回执
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有 SUCCESS 回执：APPLYING → SUCCESS")
    void appliesSuccessReceipt() {
        stubApplying(task(1L, "t-success", "1.5.0", LocalDateTime.now()));
        storage.writeApplyResult("t-success",
                new UpgradeApplyResult("t-success", "SUCCESS", "新版本已生效", "2026-10-01T00:10:00"));
        when(mapper.finish(eq(1L), eq(UpgradeStatus.APPLYING.name()), eq(UpgradeStatus.SUCCESS.name()), anyString()))
                .thenReturn(1);

        assertEquals(1, runner.reconcile(LocalDateTime.now()));
        verify(mapper).finish(eq(1L), eq("APPLYING"), eq("SUCCESS"), contains("新版本已生效"));
    }

    @Test
    @DisplayName("有 ROLLED_BACK 回执：APPLYING → ROLLED_BACK")
    void appliesRolledBackReceipt() {
        stubApplying(task(2L, "t-rollback", "1.5.0", LocalDateTime.now()));
        storage.writeApplyResult("t-rollback",
                new UpgradeApplyResult("t-rollback", "ROLLED_BACK", "健康检查失败，已还原", null));
        when(mapper.finish(eq(2L), eq("APPLYING"), eq("ROLLED_BACK"), anyString())).thenReturn(1);

        runner.reconcile(LocalDateTime.now());
        verify(mapper).finish(eq(2L), eq("APPLYING"), eq("ROLLED_BACK"), contains("健康检查失败"));
    }

    @Test
    @DisplayName("回执里的结果无法识别：一律判 FAILED（把未知当成功是最坏的一种错）")
    void unknownResultFallsBackToFailed() {
        stubApplying(task(3L, "t-weird", "1.5.0", LocalDateTime.now()));
        storage.writeApplyResult("t-weird", new UpgradeApplyResult("t-weird", "MAYBE_OK", null, null));
        when(mapper.finish(eq(3L), eq("APPLYING"), eq("FAILED"), anyString())).thenReturn(1);

        runner.reconcile(LocalDateTime.now());
        verify(mapper).finish(eq(3L), eq("APPLYING"), eq("FAILED"), contains("MAYBE_OK"));
    }

    // ------------------------------------------------------------------
    // 证据 2：当前版本号
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无回执但当前版本 == 目标版本：判 SUCCESS（覆盖「脚本写回执前被重启杀掉」）")
    void confirmsSuccessByCurrentVersion() throws IOException {
        stubApplying(task(4L, "t-noreceipt", "1.6.0", LocalDateTime.now()));
        writeCurrentVersion("1.6.0");
        when(mapper.finish(eq(4L), eq("APPLYING"), eq("SUCCESS"), anyString())).thenReturn(1);

        runner.reconcile(LocalDateTime.now());
        verify(mapper).finish(eq(4L), eq("APPLYING"), eq("SUCCESS"), contains("1.6.0"));
    }

    @Test
    @DisplayName("无回执、版本仍是旧的、且未超时：保持 APPLYING 不动（脚本可能正在跑）")
    void leavesRunningTaskAloneBeforeTimeout() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        stubApplying(task(5L, "t-running", "1.6.0", now.minusMinutes(5)));
        writeCurrentVersion("1.5.0");

        assertEquals(0, runner.reconcile(now));
        verify(mapper, never()).finish(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("无回执、版本未变、已超时：判 FAILED（否则它会一直占住活跃唯一键）")
    void failsApplyingTaskAfterTimeout() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        stubApplying(task(6L, "t-timeout", "1.6.0", now.minusMinutes(45)));
        writeCurrentVersion("1.5.0");
        when(mapper.finish(eq(6L), eq("APPLYING"), eq("FAILED"), anyString())).thenReturn(1);

        assertEquals(1, runner.reconcile(now));
        verify(mapper).finish(eq(6L), eq("APPLYING"), eq("FAILED"), contains("超时"));
    }

    // ------------------------------------------------------------------
    // 证据 3：处理中僵尸任务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("卡在 STAGING 且已过期的僵尸任务：判 FAILED（否则会一直挡住后续升级）")
    void failsStaleProcessingTasks() {
        LocalDateTime now = LocalDateTime.now();
        UpgradeTask stale = task(7L, "t-stale", "1.6.0", now.minusMinutes(60));
        stale.setStatus(UpgradeStatus.STAGING.name());
        when(mapper.selectStaleProcessing(any())).thenReturn(List.of(stale));
        when(mapper.selectApplying()).thenReturn(List.of());
        when(mapper.finish(eq(7L), eq("STAGING"), eq("FAILED"), anyString())).thenReturn(1);

        assertEquals(1, runner.reconcile(now));
        verify(mapper).finish(eq(7L), eq("STAGING"), eq("FAILED"), contains("处理中断"));
    }

    @Test
    @DisplayName("没有任何待对账任务：返回 0 且不产生任何写操作")
    void doesNothingWhenNothingToReconcile() {
        when(mapper.selectStaleProcessing(any())).thenReturn(List.of());
        when(mapper.selectApplying()).thenReturn(List.of());

        assertEquals(0, runner.reconcile(LocalDateTime.now()));
        verify(mapper, never()).finish(any(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 证据 2 的闸门：版本比对**必须有信息量**才能真正参与判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("目标版本是浮动标签 latest：current.json 也是 latest 时**不得**判成功（恒真陷阱）")
    void floatingTagNeverConfirmsByVersion() throws IOException {
        // 真实场景（2026-10-16 现场实跑）：IMAGE_TAG=latest ⇒ current.json 也写 latest
        // ⇒「当前版本 == 目标版本」恒真 ⇒ 任务在 pull 还在跑时就被标成功，
        //   随后到达的 FAILED 回执因任务已非 APPLYING 被静默丢弃 —— 失败被伪装成成功。
        LocalDateTime now = LocalDateTime.now();
        stubApplying(task(8L, "t-floating", "latest", now.minusMinutes(2)));
        writeCurrentVersion("latest");

        assertEquals(0, runner.reconcile(now));
        verify(mapper, never()).finish(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("源版本 == 目标版本：本次没有版本变化，不得据此判成功")
    void sameSourceAndTargetNeverConfirmsByVersion() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        UpgradeTask same = task(9L, "t-same", "1.5.0", now.minusMinutes(2));
        same.setSourceVersion("1.5.0");
        stubApplying(same);
        writeCurrentVersion("1.5.0");

        assertEquals(0, runner.reconcile(now));
        verify(mapper, never()).finish(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("回执说 FAILED、任务却已记为 SUCCESS：只体检不改写 —— 对账不替管理员改写历史结论")
    void warnsButNeverRewritesContradictingReceipt() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.selectStaleProcessing(any())).thenReturn(List.of());
        when(mapper.selectApplying()).thenReturn(List.of());

        // 回执到了，但任务早已收尾（正是「被提前判定」留下的痕迹）
        storage.writeApplyResult("t-dropped",
                new UpgradeApplyResult("t-dropped", "FAILED", "重建容器失败", now.toString()));
        UpgradeTask settled = task(10L, "t-dropped", "latest", now);
        settled.setStatus(UpgradeStatus.SUCCESS.name());
        when(mapper.selectByTaskNo("t-dropped")).thenReturn(settled);

        assertEquals(0, runner.reconcile(now));
        verify(mapper).selectByTaskNo("t-dropped");   // 体检确实跑了
        verify(mapper, never()).finish(any(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void stubApplying(UpgradeTask task) {
        when(mapper.selectStaleProcessing(any())).thenReturn(List.of());
        when(mapper.selectApplying()).thenReturn(List.of(task));
    }

    private void writeCurrentVersion(String version) throws IOException {
        Path file = storage.currentStateFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":\"" + version + "\"}");
    }

    private UpgradeTask task(Long id, String taskNo, String targetVersion, LocalDateTime updatedAt) {
        UpgradeTask task = new UpgradeTask();
        task.setId(id);
        task.setTaskNo(taskNo);
        task.setStatus(UpgradeStatus.APPLYING.name());
        task.setTargetVersion(targetVersion);
        task.setUpdatedAt(updatedAt);
        task.setCreatedAt(updatedAt);
        return task;
    }
}
