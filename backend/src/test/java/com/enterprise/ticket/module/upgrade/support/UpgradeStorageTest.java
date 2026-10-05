package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级工作目录单测（Phase 18 批次 G）
 *
 * <p>重点钉住两条不变量：
 * <ol>
 *   <li><b>入库路径的可迁移性</b> —— storageRoot 之下存相对路径，
 *       之外存绝对路径（相对化会得到一串 {@code ../../../}，迁移环境后指向完全不同的位置）；</li>
 *   <li><b>目录操作的边界</b> —— copyTree / deleteTree / clearDir 的行为边界，
 *       尤其是「当前生效产物目录不存在时备份跳过」这一首次部署的正常情形。</li>
 * </ol>
 */
class UpgradeStorageTest {

    @TempDir
    Path tempDir;

    private AppProperties properties;
    private UpgradeStorage storage;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getUpgrade().setStorageRoot(tempDir.resolve("root").toString());
        properties.getUpgrade().setCurrentArtifactDir(tempDir.resolve("current").toString());
        storage = new UpgradeStorage(properties, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 路径入库 / 还原
    // ------------------------------------------------------------------

    @Test
    @DisplayName("storageRoot 之下的路径：存相对路径，且能原样还原")
    void storedPath_isRelativeInsideRoot() {
        Path staging = storage.stagingDir("20261001010101-abcd1234");
        String stored = storage.toStoredPath(staging);

        assertEquals("staging/20261001010101-abcd1234", stored);
        assertEquals(staging.normalize(), storage.fromStoredPath(stored).normalize());
    }

    @Test
    @DisplayName("storageRoot 之外的路径：存绝对路径（相对化会指向完全不同的位置）")
    void storedPath_isAbsoluteOutsideRoot() {
        Path outside = tempDir.resolve("elsewhere/artifacts");
        assertEquals(outside.normalize().toString(), storage.toStoredPath(outside));
    }

    @Test
    @DisplayName("toStoredPath(null) 与 fromStoredPath(空) 都返回 null，不抛异常")
    void storedPath_handlesNullAndBlank() {
        assertNull(storage.toStoredPath(null));
        assertNull(storage.fromStoredPath(null));
        assertNull(storage.fromStoredPath("   "));
    }

    // ------------------------------------------------------------------
    // 校验和
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sha256Hex：与标准测试向量一致（空串 = e3b0c442…）")
    void sha256Hex_matchesKnownVector() throws IOException {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                storage.sha256Hex(file));

        Path text = tempDir.resolve("abc.txt");
        Files.writeString(text, "abc", StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                storage.sha256Hex(text));
    }

    // ------------------------------------------------------------------
    // 目录操作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("copyTree 递归复制且不改动源目录（备份必须是复制而不是移动）")
    void copyTree_isRecursiveAndNonDestructive() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("frontend/dist/assets"));
        Files.writeString(src.resolve("backend.jar"), "JAR");
        Files.writeString(src.resolve("frontend/dist/index.html"), "<html/>");
        Files.writeString(src.resolve("frontend/dist/assets/app.js"), "js");

        Path dst = tempDir.resolve("dst");
        storage.copyTree(src, dst);

        assertEquals("JAR", Files.readString(dst.resolve("backend.jar")));
        assertEquals("<html/>", Files.readString(dst.resolve("frontend/dist/index.html")));
        assertEquals("js", Files.readString(dst.resolve("frontend/dist/assets/app.js")));
        // 源目录必须完好：备份的语义是「复制一份」，移动会让回滚变成不可能
        assertTrue(Files.exists(src.resolve("backend.jar")));
    }

    @Test
    @DisplayName("deleteTree 递归删除；对不存在的路径静默返回")
    void deleteTree_removesRecursivelyAndToleratesMissing() throws IOException {
        Path dir = tempDir.resolve("gone");
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/c.txt"), "x");

        storage.deleteTree(dir);
        assertFalse(Files.exists(dir));

        // 再删一次不应抛异常
        storage.deleteTree(dir);
        storage.deleteTree(null);
    }

    @Test
    @DisplayName("clearDir 只清内容、保留目录本身")
    void clearDir_keepsDirectory() throws IOException {
        Path dir = tempDir.resolve("keep");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/f.txt"), "x");

        storage.clearDir(dir);

        assertTrue(Files.isDirectory(dir));
        assertFalse(Files.exists(dir.resolve("sub")));
    }

    // ------------------------------------------------------------------
    // 状态文件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("current.json 不存在或损坏时返回空 Optional（它只用于展示，不该拖垮升级流程）")
    void readCurrentVersion_toleratesMissingAndBroken() throws IOException {
        assertTrue(storage.readCurrentVersion().isEmpty());

        Path file = storage.currentStateFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        assertTrue(storage.readCurrentVersion().isEmpty());

        storage.writeJson(file, new UpgradeManifest("1.4.2", "2026-10-01T00:00:00", null));
        assertEquals(Optional.of("1.4.2"), storage.readCurrentVersion());
    }

    @Test
    @DisplayName("结果回执：写入后可读回；未知字段被忽略（向前兼容）")
    void applyResult_roundTripAndForwardCompatible() throws IOException {
        assertTrue(storage.readApplyResult("t-1").isEmpty());

        storage.writeApplyResult("t-1", new UpgradeApplyResult("t-1", "SUCCESS", "已完成", "2026-10-01T00:10:00"));
        UpgradeApplyResult read = storage.readApplyResult("t-1").orElseThrow();
        assertEquals("SUCCESS", read.result());
        assertEquals("已完成", read.message());

        // 脚本多写一个字段时，后端仍要能读出来 —— 否则一次正常升级会被当成「没有回执」
        Path file = storage.resultFile("t-2");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"taskNo\":\"t-2\",\"result\":\"ROLLED_BACK\",\"extra\":\"x\"}");
        assertEquals("ROLLED_BACK", storage.readApplyResult("t-2").orElseThrow().result());
    }

    @Test
    @DisplayName("当前产物目录不存在不算错误（首次部署本来就没有上一版可备份）")
    void currentArtifactDir_absenceIsNotAnError() {
        assertFalse(Files.exists(storage.currentArtifactDir()));
        assertEquals(tempDir.resolve("current"), storage.currentArtifactDir());
    }
}
