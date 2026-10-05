package com.enterprise.ticket.module.upgrade.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.upgrade.dto.vo.UpgradeTaskVO;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import com.enterprise.ticket.module.upgrade.mapper.UpgradeTaskMapper;
import com.enterprise.ticket.module.upgrade.support.UpgradeApplier;
import com.enterprise.ticket.module.upgrade.support.UpgradePackageValidator;
import com.enterprise.ticket.module.upgrade.support.UpgradeStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线升级服务单测（Phase 18 批次 G）
 *
 * <p>钉住三组行为：
 * <ol>
 *   <li><b>闸门</b> —— 总开关关闭时一切写操作都拒绝；已有活跃任务时拒绝新上传；</li>
 *   <li><b>上传全链路</b> —— 校验 → 备份 → 落 staging → 待应用，
 *       以及失败时任务必须落到 FAILED（否则它会一直占住活跃唯一键，挡死后续升级）；</li>
 *   <li><b>应用与回滚</b> —— 未配命令时报错而不是静默什么都不做；
 *       CAS 未命中时报「状态不允许」而不是覆盖；
 *       回滚必须「先留存现场、再还原」，绝不能出现「两边都没有」的窗口。</li>
 * </ol>
 */
class UpgradeServiceTest {

    @TempDir
    Path tempDir;

    private AppProperties properties;
    private UpgradeStorage storage;
    private UpgradeApplier applier;
    private UpgradeTaskMapper mapper;
    private UpgradeService service;

    /** mapper.selectOne 的返回值（模拟库里那一行） */
    private UpgradeTask stored;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        AppProperties.Upgrade upgrade = properties.getUpgrade();
        upgrade.setEnabled(true);
        upgrade.setStorageRoot(tempDir.resolve("root").toString());
        upgrade.setCurrentArtifactDir(tempDir.resolve("current").toString());
        upgrade.setPackageMaxSizeMb(5);
        upgrade.setExtractMaxSizeMb(20);

        ObjectMapper objectMapper = new ObjectMapper();
        storage = new UpgradeStorage(properties, objectMapper);
        applier = Mockito.mock(UpgradeApplier.class);
        mapper = Mockito.mock(UpgradeTaskMapper.class);
        service = new UpgradeService(properties, storage,
                new UpgradePackageValidator(properties, storage, objectMapper), applier, mapper);
    }

    // ==================================================================
    // 闸门
    // ==================================================================

    @Test
    @DisplayName("总开关关闭：上传直接被拒（fail-closed，不降级成「只校验不应用」）")
    void upload_rejectedWhenDisabled() {
        properties.getUpgrade().setEnabled(false);
        assertEquals(ErrorCode.UPGRADE_DISABLED,
                errorOf(() -> service.upload("a.zip", stream(packageBytes()))));
    }

    @Test
    @DisplayName("已有活跃任务：拒绝新上传，并在提示里带上那个任务的编号与状态")
    void upload_rejectedWhenActiveTaskExists() {
        UpgradeTask active = new UpgradeTask();
        active.setTaskNo("20261001-aaaa1111");
        active.setStatus(UpgradeStatus.READY_TO_APPLY.name());
        when(mapper.selectActive()).thenReturn(active);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload("a.zip", stream(packageBytes())));
        assertEquals(ErrorCode.UPGRADE_TASK_CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("20261001-aaaa1111"), "提示里应带上冲突的任务号");
        assertTrue(e.getMessage().contains("已就绪待应用"), "提示里应带上该任务的当前状态");
    }

    // ==================================================================
    // 上传全链路
    // ==================================================================

    @Test
    @DisplayName("合法包：走到「已就绪待应用」，且备份与 staging 目录都已生成")
    void upload_happyPath() throws IOException {
        stubInsert();
        when(mapper.selectActive()).thenReturn(null);

        // 先造一份「当前生效产物」，让备份步骤有东西可备
        Path current = storage.currentArtifactDir();
        Files.createDirectories(current.resolve("frontend/dist"));
        Files.writeString(current.resolve("backend.jar"), "OLD-JAR");
        Files.writeString(current.resolve("frontend/dist/index.html"), "<old/>");

        UpgradeTaskVO vo = service.upload("ticket-1.5.0.zip", stream(packageBytes()));

        assertEquals(UpgradeStatus.READY_TO_APPLY.name(), vo.status());
        assertEquals("1.5.0", vo.targetVersion());
        assertNotNull(vo.packageSha256());
        assertEquals(64, vo.packageSha256().length());

        // 备份：旧产物被完整复制到 backup/<taskNo>
        Path backup = storage.backupDir(vo.taskNo());
        assertTrue(Files.isDirectory(backup), "备份目录应已生成");
        assertEquals("OLD-JAR", Files.readString(backup.resolve("backend.jar")));

        // staging：新产物已解压到位
        Path staging = storage.stagingDir(vo.taskNo());
        assertTrue(Files.isDirectory(staging), "staging 目录应已生成");
        assertTrue(Files.exists(staging.resolve("backend.jar")));
        assertTrue(Files.exists(staging.resolve("frontend/dist/index.html")));

        // 备份是复制而非移动：当前产物必须原封不动
        assertEquals("OLD-JAR", Files.readString(current.resolve("backend.jar")));

        // 临时文件已清理
        try (var files = Files.list(storage.tempDir())) {
            assertTrue(files.findAny().isEmpty(), "上传临时文件应已清理");
        }
    }

    @Test
    @DisplayName("包不合法：抛业务异常，且任务被标为 FAILED（否则会一直占住活跃唯一键）")
    void upload_marksFailedOnInvalidPackage() {
        stubInsert();
        when(mapper.selectActive()).thenReturn(null);

        byte[] notAZip = "这不是 zip".getBytes(StandardCharsets.UTF_8);
        assertEquals(ErrorCode.UPGRADE_PACKAGE_INVALID,
                errorOf(() -> service.upload("bad.zip", stream(notAZip))));
        // 失败时任务停在「校验中」，CAS 的 expected 就是这个状态
        verify(mapper).finish(eq(99L), eq(UpgradeStatus.VALIDATING.name()),
                eq(UpgradeStatus.FAILED.name()), anyString());
    }

    @Test
    @DisplayName("首次部署（当前产物目录不存在）：备份跳过但升级照常走到待应用")
    void upload_skipsBackupWhenNoCurrentArtifacts() {
        stubInsert();
        when(mapper.selectActive()).thenReturn(null);

        UpgradeTaskVO vo = service.upload("ticket-1.5.0.zip", stream(packageBytes()));

        assertEquals(UpgradeStatus.READY_TO_APPLY.name(), vo.status());
        assertNotNull(vo.targetVersion());
        // 没有备份目录（首次部署没有「上一版」）
        assertFalse(Files.isDirectory(storage.backupDir(vo.taskNo())));
    }

    // ==================================================================
    // 应用
    // ==================================================================

    @Test
    @DisplayName("未配置外部应用命令：明确报错（而不是静默什么都不做）")
    void apply_rejectedWhenCommandNotConfigured() {
        stubReadyTask("t-1", null);
        when(applier.isConfigured()).thenReturn(false);

        assertEquals(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED,
                errorOf(() -> service.apply("t-1")));
        verify(mapper, never()).markApplying(any(), anyString());
    }

    @Test
    @DisplayName("CAS 未命中（任务已被别的操作迁移）：报「状态不允许」而不是硬写 APPLYING")
    void apply_rejectedWhenCasMisses() {
        stubReadyTask("t-2", storage.stagingDir("t-2").toString());
        when(applier.isConfigured()).thenReturn(true);
        when(mapper.markApplying(eq(77L), anyString())).thenReturn(0);

        assertEquals(ErrorCode.UPGRADE_TASK_STATUS_INVALID, errorOf(() -> service.apply("t-2")));
        verify(applier, never()).launch(anyString(), any(), any());
    }

    @Test
    @DisplayName("正常发起：状态落到 APPLYING，并调用外部编排脚本")
    void apply_transitionsAndLaunches() throws IOException {
        Path staging = storage.stagingDir("t-3");
        Files.createDirectories(staging);
        stubReadyTask("t-3", staging.toString());
        when(applier.isConfigured()).thenReturn(true);
        when(mapper.markApplying(eq(77L), anyString())).thenReturn(1);

        service.apply("t-3");

        verify(mapper).markApplying(eq(77L), anyString());
        verify(applier).launch(eq("t-3"), any(), any());
    }

    @Test
    @DisplayName("外部命令起不来：回退到「待应用」而不是判失败（产物完好，修好配置即可重试）")
    void apply_revertsToReadyWhenLaunchFails() throws IOException {
        Path staging = storage.stagingDir("t-4");
        Files.createDirectories(staging);
        stubReadyTask("t-4", staging.toString());
        when(applier.isConfigured()).thenReturn(true);
        when(mapper.markApplying(eq(77L), anyString())).thenReturn(1);
        Mockito.doThrow(new BusinessException(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED, "命令不存在"))
                .when(applier).launch(anyString(), any(), any());

        assertEquals(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED, errorOf(() -> service.apply("t-4")));
        verify(mapper).revertToReady(eq(77L), anyString());
    }

    // ==================================================================
    // 回滚
    // ==================================================================

    @Test
    @DisplayName("部署配置关闭回滚：直接拒绝")
    void rollback_rejectedWhenDisabledByConfig() {
        properties.getUpgrade().setRollbackEnabled(false);
        stubReadyTask("t-5", null);

        assertEquals(ErrorCode.UPGRADE_ROLLBACK_DISABLED, errorOf(() -> service.rollback("t-5")));
    }

    @Test
    @DisplayName("没有备份目录（首次部署）：明确报错，不谎称回滚成功")
    void rollback_requiresBackup() {
        stubReadyTask("t-6", storage.stagingDir("t-6").toString());
        stored.setBackupPath(null);

        assertEquals(ErrorCode.UPGRADE_STORAGE_FAILED, errorOf(() -> service.rollback("t-6")));
    }

    @Test
    @DisplayName("状态不允许回滚（例如正在应用）：拒绝")
    void rollback_rejectedForNonRollbackableStatus() {
        stubReadyTask("t-7", storage.stagingDir("t-7").toString());
        stored.setStatus(UpgradeStatus.APPLYING.name());
        stored.setBackupPath(storage.toStoredPath(storage.backupDir("t-7")));

        assertEquals(ErrorCode.UPGRADE_TASK_STATUS_INVALID, errorOf(() -> service.rollback("t-7")));
    }

    @Test
    @DisplayName("回滚：先留存现场再还原 —— 当前产物回到旧版本，且回滚前现场被另存")
    void rollback_restoresAndKeepsSafetyCopy() throws IOException {
        Path current = storage.currentArtifactDir();
        Files.createDirectories(current);
        Files.writeString(current.resolve("backend.jar"), "NEW-JAR");

        Path backup = storage.backupDir("t-8");
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("backend.jar"), "OLD-JAR");

        stubReadyTask("t-8", storage.stagingDir("t-8").toString());
        stored.setBackupPath(storage.toStoredPath(backup));
        stored.setSourceVersion("1.4.0");
        stubFinish("READY_TO_APPLY", "ROLLED_BACK");

        UpgradeTaskVO vo = service.rollback("t-8");

        assertEquals(UpgradeStatus.ROLLED_BACK.name(), vo.status());
        // 当前产物已还原为旧版本
        assertEquals("OLD-JAR", Files.readString(current.resolve("backend.jar")));
        // 回滚前的现场被另存 —— 万一还原本身也有问题，还能人工救回来
        Path safety = storage.backupDir("t-8-pre-rollback");
        assertTrue(Files.isDirectory(safety), "回滚前现场应被另存");
        assertEquals("NEW-JAR", Files.readString(safety.resolve("backend.jar")));
    }

    @Test
    @DisplayName("回滚：当前产物目录里「新版本多出来的文件」会被清掉（否则新旧混杂）")
    void rollback_removesFilesIntroducedByNewVersion() throws IOException {
        Path current = storage.currentArtifactDir();
        Files.createDirectories(current.resolve("frontend/dist"));
        Files.writeString(current.resolve("backend.jar"), "NEW-JAR");
        Files.writeString(current.resolve("frontend/dist/new-only.js"), "new");

        Path backup = storage.backupDir("t-9");
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("backend.jar"), "OLD-JAR");

        stubReadyTask("t-9", storage.stagingDir("t-9").toString());
        stored.setBackupPath(storage.toStoredPath(backup));
        stubFinish("READY_TO_APPLY", "ROLLED_BACK");

        service.rollback("t-9");

        assertEquals("OLD-JAR", Files.readString(current.resolve("backend.jar")));
        assertFalse(Files.exists(current.resolve("frontend/dist/new-only.js")),
                "新版本独有的文件必须被清掉，否则回滚后是新旧混合产物");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void stubInsert() {
        doAnswer(invocation -> {
            UpgradeTask task = invocation.getArgument(0);
            task.setId(99L);
            stored = task;
            return 1;
        }).when(mapper).insert(any(UpgradeTask.class));
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored);
    }

    /**
     * 让 finish 的 CAS 桩「真的生效」：既返回 1，也把内存里那一行的状态改掉。
     *
     * <p>不这样做的话，{@code detail()} 读到的仍是旧状态 ——
     * 测试会断言在一个 Mock 的幻觉上，以为回滚没生效。
     */
    private void stubFinish(String expected, String target) {
        when(mapper.finish(eq(77L), eq(expected), eq(target), anyString())).thenAnswer(invocation -> {
            stored.setStatus(invocation.getArgument(2));
            return 1;
        });
    }

    /** 造一条处于 READY_TO_APPLY 的库中任务 */
    private void stubReadyTask(String taskNo, String stagingPath) {
        UpgradeTask task = new UpgradeTask();
        task.setId(77L);
        task.setTaskNo(taskNo);
        task.setStatus(UpgradeStatus.READY_TO_APPLY.name());
        task.setTargetVersion("1.5.0");
        task.setSourceVersion("1.4.0");
        task.setPackageSha256("a".repeat(64));
        task.setStagingPath(stagingPath);
        task.setBackupPath(storage.toStoredPath(storage.backupDir(taskNo)));
        stored = task;
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored);
    }

    private ErrorCode errorOf(Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        return exception.getErrorCode();
    }

    private static InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    /** 构造一个合法升级包（backend.jar + frontend/dist/index.html + manifest.json） */
    private byte[] packageBytes() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("backend.jar", "NEW-JAR-CONTENT".getBytes(StandardCharsets.UTF_8));
        entries.put("frontend/dist/index.html", "<new/>".getBytes(StandardCharsets.UTF_8));

        StringBuilder manifest = new StringBuilder("{\"version\":\"1.5.0\",\"files\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!first) {
                manifest.append(',');
            }
            first = false;
            manifest.append("{\"path\":\"").append(entry.getKey()).append("\",\"sha256\":\"")
                    .append(sha256Hex(entry.getValue())).append("\"}");
        }
        manifest.append("]}");
        entries.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));

        try (var out = new java.io.ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
