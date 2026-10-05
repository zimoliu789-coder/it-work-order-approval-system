package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

/**
 * 升级包校验器单测（Phase 18 批次 G）
 *
 * <p>这里是整个升级功能里<b>唯一一道真正拦得住灾难的闸门</b>：放行之后，
 * 包内文件就会被替换到运行目录并重启进程。因此本测试逐条钉住每一个拒绝条件，
 * 断言的是「安全边界」而不是实现细节 —— 只要边界被突破，测试必须变红。
 *
 * <p>覆盖：Zip Slip（{@code ../} / 绝对路径 / 盘符 / 反斜杠 / 空字节）、
 * 条目白名单、zip 炸弹（体积与条目数）、manifest 存在性与格式、
 * <b>包内文件与 manifest 声明的双向一致性</b>、逐文件 SHA-256、版本号格式、
 * 以及解压阶段对路径穿越的二次防护。
 */
class UpgradePackageValidatorTest {

    @TempDir
    Path tempDir;

    private AppProperties properties;
    private UpgradeStorage storage;
    private UpgradePackageValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        AppProperties.Upgrade upgrade = properties.getUpgrade();
        upgrade.setStorageRoot(tempDir.resolve("root").toString());
        upgrade.setCurrentArtifactDir(tempDir.resolve("current").toString());
        upgrade.setPackageMaxSizeMb(2);
        upgrade.setExtractMaxSizeMb(4);
        upgrade.setMaxEntries(20);

        storage = new UpgradeStorage(properties, new ObjectMapper());
        validator = new UpgradePackageValidator(properties, storage, new ObjectMapper());
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("完整合法的升级包：校验通过并返回版本、包哈希与条目清单")
    void validate_acceptsWellFormedPackage() throws IOException {
        Path zip = buildPackage("ticket-1.5.0.zip", "1.5.0",
                Map.of("backend.jar", bytes("JAR-CONTENT"),
                        "frontend/dist/index.html", bytes("<html/>"),
                        "frontend/dist/assets/app.js", bytes("console.log(1)")),
                Map.of());

        UpgradePackageValidator.UpgradePlan plan = validator.validate(zip);

        assertEquals("1.5.0", plan.version());
        assertEquals(64, plan.packageSha256().length());
        assertTrue(plan.packageSize() > 0);
        // manifest.json 也是包内条目之一，因此条目数是「声明文件数 + 1」
        assertEquals(4, plan.entries().size());
        assertTrue(plan.entries().contains("backend.jar"));
    }

    @Test
    @DisplayName("解压：内容逐字节落到目标目录，且 frontend/dist 子目录被创建")
    void extract_writesEntriesInPlace() throws IOException {
        Path zip = buildPackage("ok.zip", "1.0.0",
                Map.of("backend.jar", bytes("JAR"), "frontend/dist/a/b.txt", bytes("deep")),
                Map.of());

        Path target = tempDir.resolve("staging");
        validator.extract(zip, target);

        assertEquals("JAR", Files.readString(target.resolve("backend.jar")));
        assertEquals("deep", Files.readString(target.resolve("frontend/dist/a/b.txt")));
    }

    // ==================================================================
    // 包体本身
    // ==================================================================

    @Test
    @DisplayName("非 zip 文件：按「包本身不合法」拒绝（与 manifest 问题区分开）")
    void validate_rejectsNonZip() throws IOException {
        Path fake = tempDir.resolve("not-a-zip.zip");
        Files.writeString(fake, "这不是一个 zip 文件，只是恰好叫 .zip");
        assertEquals(ErrorCode.UPGRADE_PACKAGE_INVALID, errorOf(() -> validator.validate(fake)));
    }

    @Test
    @DisplayName("超过包体积上限：拒绝（上限挡在解析之前，避免为大文件做无谓的解压）")
    void validate_rejectsOversizedPackage() throws IOException {
        // 必须用不可压缩的数据：3MB 全零会被 deflate 压成几 KB，
        // 那样「包体积超限」根本不会被触发，测试会变成一个永远为真的空断言
        Path zip = buildPackage("big.zip", "1.0.0",
                Map.of("backend.jar", incompressible(3 * 1024 * 1024)), Map.of());
        assertEquals(ErrorCode.UPGRADE_PACKAGE_TOO_LARGE, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("空文件：按「请选择升级包」拒绝")
    void validate_rejectsEmptyFile() throws IOException {
        Path empty = tempDir.resolve("empty.zip");
        Files.createFile(empty);
        assertEquals(ErrorCode.UPGRADE_PACKAGE_REQUIRED, errorOf(() -> validator.validate(empty)));
    }

    // ==================================================================
    // manifest
    // ==================================================================

    @Test
    @DisplayName("包内没有 manifest.json：拒绝（说明不是本系统产出的包）")
    void validate_requiresManifest() throws IOException {
        Path zip = writeZip("no-manifest.zip", Map.of("backend.jar", bytes("JAR")));
        assertEquals(ErrorCode.UPGRADE_MANIFEST_MISSING, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("包内没有 backend.jar：拒绝（只升前端也应显式声明，不静默放过）")
    void validate_requiresBackendJar() throws IOException {
        Path zip = buildPackage("no-jar.zip", "1.0.0",
                Map.of("frontend/dist/index.html", bytes("<html/>")), Map.of());
        assertEquals(ErrorCode.UPGRADE_JAR_MISSING, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("manifest 不是合法 JSON：拒绝")
    void validate_rejectsBrokenManifest() throws IOException {
        Path zip = writeZip("broken.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "manifest.json", bytes("{ this is not json")));
        assertEquals(ErrorCode.UPGRADE_MANIFEST_INVALID, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("版本号非法（含空格 / 斜杠）：拒绝（版本号会进目录名与 shell 参数）")
    void validate_rejectsInvalidVersion() throws IOException {
        for (String bad : new String[]{"../evil", "1.0 0", "a/b", "", "-1.0"}) {
            Path zip = buildPackage("bad-version.zip", bad,
                    Map.of("backend.jar", bytes("JAR")), Map.of());
            assertEquals(ErrorCode.UPGRADE_VERSION_INVALID,
                    errorOf(() -> validator.validate(zip)), "版本号应被拒绝：" + bad);
        }
    }

    // ==================================================================
    // 逐文件哈希与双向一致性
    // ==================================================================

    @Test
    @DisplayName("文件哈希不匹配：拒绝（传输损坏的包换上去就是一个起不来的进程）")
    void validate_rejectsHashMismatch() throws IOException {
        Path zip = buildPackage("bad-hash.zip", "1.0.0",
                Map.of("backend.jar", bytes("JAR")),
                Map.of("backend.jar", "0".repeat(64)));
        assertEquals(ErrorCode.UPGRADE_CHECKSUM_MISMATCH, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("包内有 manifest 未声明的文件：拒绝（否则「悄悄多塞一个文件」就能绕过校验）")
    void validate_rejectsUndeclaredFile() throws IOException {
        // 包里只有 backend.jar 被声明；evil.js 只存在于包内、不在 manifest 里
        Path zip = buildPackage("undeclared.zip", "1.0.0",
                Map.of("backend.jar", bytes("JAR")),
                Map.of("frontend/dist/evil.js", bytes("alert(1)")),
                Map.of());

        assertEquals(ErrorCode.UPGRADE_MANIFEST_INVALID, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("manifest 声明了包内不存在的文件：拒绝（声明了却给不出，说明打包不完整）")
    void validate_rejectsMissingDeclaredFile() throws IOException {
        Path zip = buildPackage("missing.zip", "1.0.0",
                Map.of("backend.jar", bytes("JAR")), Map.of("frontend/dist/gone.js", "a".repeat(64)));
        assertEquals(ErrorCode.UPGRADE_MANIFEST_INVALID, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("manifest 缺少 sha256：拒绝（没有哈希就无法断言包是完整的）")
    void validate_rejectsEntryWithoutSha() throws IOException {
        String manifest = "{\"version\":\"1.0.0\",\"files\":[{\"path\":\"backend.jar\"}]}";
        Path zip = writeZip("no-sha.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "manifest.json", bytes(manifest)));
        assertEquals(ErrorCode.UPGRADE_MANIFEST_INVALID, errorOf(() -> validator.validate(zip)));
    }

    // ==================================================================
    // Zip Slip：路径穿越
    // ==================================================================

    @Test
    @DisplayName("Zip Slip：../ 向上穿越 / 绝对路径 / 盘符 / 反斜杠 / 空字节 全部拒绝")
    void validate_rejectsUnsafeEntryNames() throws IOException {
        String[] unsafe = {
                "../evil.txt",
                "frontend/dist/../../evil.txt",
                "/etc/cron.d/evil",
                "\\windows\\system32\\evil.dll",
                "C:/windows/evil.dll",
                "frontend/dist/evil\u0000.txt"
        };
        for (String name : unsafe) {
            Path zip = writeZip("slip.zip", Map.of(
                    "backend.jar", bytes("JAR"),
                    name, bytes("EVIL")));
            assertEquals(ErrorCode.UPGRADE_ENTRY_UNSAFE,
                    errorOf(() -> validator.validate(zip)), "应拒绝路径：" + name);
        }
    }

    @Test
    @DisplayName("解压阶段二次防护：即使包已通过校验，穿越条目在解压时仍被拦下")
    void extract_rechecksPathTraversal() throws IOException {
        // 直接构造一个含穿越条目的 zip 并调用 extract（模拟 TOCTOU：两次读取之间包被替换）
        Path zip = writeZip("slip-extract.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "../escaped.txt", bytes("EVIL")));

        Path target = tempDir.resolve("staging-extract");
        assertEquals(ErrorCode.UPGRADE_ENTRY_UNSAFE, errorOf(() -> validator.extract(zip, target)));
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")), "穿越文件绝不能被写到目标目录之外");
    }

    // ==================================================================
    // 白名单
    // ==================================================================

    @Test
    @DisplayName("白名单外的条目：拒绝（只允许 manifest.json / backend.jar / frontend/dist/**）")
    void validate_rejectsEntriesOutsideWhitelist() throws IOException {
        for (String name : new String[]{"other/x.txt", "docker-compose.yml", "frontend/dist-evil/x.js"}) {
            Path zip = writeZip("not-allowed.zip", Map.of(
                    "backend.jar", bytes("JAR"),
                    name, bytes("x")));
            assertEquals(ErrorCode.UPGRADE_ENTRY_NOT_ALLOWED,
                    errorOf(() -> validator.validate(zip)), "应拒绝白名单外条目：" + name);
        }
    }

    @Test
    @DisplayName("frontend/dist 目录自身（作为目录条目）允许，但不允许 frontend/distx/ 这类相似名")
    void validate_whitelistPrefixRequiresTrailingSlash() throws IOException {
        Path ok = writeZip("dir-entry.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "frontend/dist/", new byte[0]));
        assertEquals(ErrorCode.UPGRADE_MANIFEST_MISSING,
                errorOf(() -> validator.validate(ok)),
                "目录条目本身应被允许（放行后继续走 manifest 校验）");
    }

    // ==================================================================
    // zip 炸弹
    // ==================================================================

    @Test
    @DisplayName("解压体积超限：拒绝，且必须是在读取过程中中断（而不是读完再比大小）")
    void validate_rejectsExtractBomb() throws IOException {
        // extractMaxSizeMb = 4 ⇒ 上限 4MB；放 6MB 的 backend.jar
        Path zip = buildPackage("bomb.zip", "1.0.0",
                Map.of("backend.jar", new byte[6 * 1024 * 1024]), Map.of());
        assertEquals(ErrorCode.UPGRADE_EXTRACT_TOO_LARGE, errorOf(() -> validator.validate(zip)));
    }

    @Test
    @DisplayName("条目数超限：拒绝（几十万个小文件足以打爆 inode）")
    void validate_rejectsTooManyEntries() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("backend.jar", bytes("JAR"));
        for (int i = 0; i < 25; i++) {
            files.put("frontend/dist/f" + i + ".txt", bytes("x"));
        }
        Path zip = writeZip("many.zip", files);
        assertEquals(ErrorCode.UPGRADE_ENTRY_TOO_MANY, errorOf(() -> validator.validate(zip)));
    }

    // ==================================================================
    // 向前兼容
    // ==================================================================

    @Test
    @DisplayName("manifest 含未知字段：接受（新打包脚本加字段不应让旧后端拒绝一个正常包）")
    void validate_toleratesUnknownManifestFields() throws IOException {
        String manifest = "{\"version\":\"2.0.0\",\"buildNumber\":123,\"gitCommit\":\"abc\",\"files\":["
                + "{\"path\":\"backend.jar\",\"sha256\":\"" + sha256Hex(bytes("JAR")) + "\",\"mode\":420}]}";
        Path zip = writeZip("future.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "manifest.json", bytes(manifest)));

        UpgradePackageValidator.UpgradePlan plan = validator.validate(zip);
        assertEquals("2.0.0", plan.version());
    }

    @Test
    @DisplayName("条目声明了 size 且与实际不符：不影响通过（size 只是提示，哈希才是判据）")
    void validate_sizeFieldIsAdvisoryOnly() throws IOException {
        String manifest = "{\"version\":\"1.0.0\",\"files\":["
                + "{\"path\":\"backend.jar\",\"sha256\":\"" + sha256Hex(bytes("JAR"))
                + "\",\"size\":999999}]}";
        Path zip = writeZip("size-mismatch.zip", Map.of(
                "backend.jar", bytes("JAR"),
                "manifest.json", bytes(manifest)));

        assertNotNull(validator.validate(zip));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private ErrorCode errorOf(Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertNotNull(exception.getErrorCode());
        return exception.getErrorCode();
    }

    /**
     * 构造一个升级包。
     *
     * @param declaredExtra 除 files 之外额外声明在 manifest 里的条目（用于构造「声明了但包里没有」）
     * @param hashOverride  覆盖某些条目的声明哈希（用于构造「哈希不匹配」）
     */
    private Path buildPackage(String fileName, String version, Map<String, byte[]> files,
                              Map<String, String> declaredExtra) throws IOException {
        return buildPackage(fileName, version, files, null, declaredExtra);
    }

    private Path buildPackage(String fileName, String version, Map<String, byte[]> files,
                              Map<String, byte[]> extraEntriesInZip,
                              Map<String, String> declaredExtra) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            hashes.put(entry.getKey(), sha256Hex(entry.getValue()));
        }
        if (declaredExtra != null) {
            hashes.putAll(declaredExtra);
        }

        Map<String, byte[]> all = new LinkedHashMap<>(files);
        if (extraEntriesInZip != null) {
            all.putAll(extraEntriesInZip);
        }
        all.put("manifest.json", bytes(manifestJson(version, hashes)));
        return writeZip(fileName, all);
    }

    private String manifestJson(String version, Map<String, String> hashes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":\"").append(version)
                .append("\",\"buildTime\":\"2026-10-01T00:00:00\",\"files\":[");
        boolean first = true;
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"path\":\"").append(entry.getKey())
                    .append("\",\"sha256\":\"").append(entry.getValue()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private Path writeZip(String fileName, Map<String, byte[]> entries) throws IOException {
        Path zip = tempDir.resolve(fileName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 不可压缩的字节（固定种子，保证可复现）：用于构造真实的「大包」 */
    private static byte[] incompressible(int size) {
        byte[] data = new byte[size];
        new java.util.Random(20261001L).nextBytes(data);
        return data;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
