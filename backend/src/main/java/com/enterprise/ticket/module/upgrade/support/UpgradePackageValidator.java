package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 升级包校验器
 *
 * <p>这是整个升级功能里<b>唯一一道真正拦得住灾难的闸门</b>：一旦校验放行，
 * 包里的文件就会被替换到运行目录并重启进程。因此所有检查都是「不合格即拒绝」，
 * 不做任何「尽力而为」的降级。
 *
 * <h2>校验项（缺一不可）</h2>
 * <ol>
 *   <li><b>包大小上限</b> —— 与 multipart 上限形成双层防护（multipart 拦在容器层）；</li>
 *   <li><b>是合法 zip</b> —— 打不开直接拒绝，交给用户去查产出流程；</li>
 *   <li><b>条目路径安全（Zip Slip）</b> —— 拒绝 {@code ../}、绝对路径、盘符、反斜杠、空字节。
 *       不拦的后果是：一个名为 {@code ../../../etc/cron.d/x} 的条目会被写到预期目录之外；</li>
 *   <li><b>条目路径白名单</b> —— 只允许 {@code manifest.json}、{@code backend.jar}、
 *       {@code frontend/dist/**} 三类。包内塞别的东西一律拒绝；</li>
 *   <li><b>条目数与解压体积上限（zip 炸弹）</b> —— 1MB 的包可以解出几十 GB，
 *       只限制压缩包大小完全挡不住；体积在<b>读取过程中累计</b>并提前中断；</li>
 *   <li><b>manifest 存在且可解析</b>、版本号格式合法；</li>
 *   <li><b>包内文件与 manifest 声明<u>完全一致</u></b>（双向）：
 *       声明了却没在包里 → 拒绝；在包里却没声明 → 也拒绝。
 *       只做单向会让「悄悄多塞一个文件」成为绕过手段 ——
 *       <b>声明项哈希都对得上，但多出来的那个文件照样会被解压出去</b>。</li>
 *   <li><b>每个文件 SHA-256 与大小匹配</b>。</li>
 * </ol>
 *
 * <h2>这道闸门能防什么、不能防什么（务必看清）</h2>
 * <p><b>能防</b>：传输 / 存储过程中产生的损坏（大文件传输中断、磁盘坏块、被中间环节
 * 重新编码）。这是真实且高频的一类，且后果是「换上一个起不来的进程」——
 * 而且往往发生在深夜没人盯着的时候。<b>校验和是唯一能在替换前发现它的手段。</b>
 *
 * <p><b>不能防</b>：一个已经拿到「上传升级包」权限的人。上传者正是有权限执行升级的人，
 * 他完全可以自己造一个格式完备、哈希自洽的包。所以包整体 SHA-256 的价值是
 * <b>审计留痕</b>（事后能回答「当时上的是哪一个包」），而不是防篡改。
 * 真正的边界在三处，缺一不可：
 * <ol>
 *   <li>{@code app.upgrade.enabled} 默认关闭；</li>
 *   <li>权限码 {@code system:upgrade:execute} 只归超管；</li>
 *   <li>「替换 + 重启」交给受控的 root 编排脚本，而不是让应用进程自己动手。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpgradePackageValidator {

    /** 升级包内清单文件名（固定，不可配置） */
    public static final String MANIFEST_NAME = "manifest.json";
    /** 升级包内后端产物文件名（固定） */
    public static final String BACKEND_JAR_NAME = "backend.jar";
    /** 升级包内前端产物目录前缀（固定） */
    public static final String FRONTEND_DIST_PREFIX = "frontend/dist/";

    /**
     * 版本号格式：字母或数字开头，随后是字母 / 数字 / 点 / 下划线 / 连字符，总长 1~64。
     *
     * <p>刻意不允许空格、斜杠、引号等字符 —— 版本号会出现在目录名、日志文件名与
     * shell 参数里（外部脚本用它拼路径），允许任意字符等于把注入面直接交给打包方。
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$");

    /** Windows 盘符前缀（{@code C:} / {@code c:}） */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    private final AppProperties appProperties;
    private final UpgradeStorage storage;
    private final ObjectMapper objectMapper;

    /**
     * 校验通过后的「升级计划」——后续备份 / 落盘阶段需要的信息。
     *
     * @param version        manifest 声明的目标版本
     * @param packageSha256  升级包整体的 SHA-256（审计留痕用）
     * @param packageSize    升级包大小
     * @param entries        包内全部文件条目（已过滤目录条目），相对路径
     */
    public record UpgradePlan(String version, String packageSha256, long packageSize, List<String> entries) {
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /**
     * 校验升级包。
     *
     * @param zipFile 已落到临时目录的升级包
     * @return 校验通过的升级计划
     * @throws BusinessException 任一校验项不通过（错误码指出具体原因与修复方向）
     */
    public UpgradePlan validate(Path zipFile) {
        AppProperties.Upgrade config = storage.config();

        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            throw BusinessException.of(ErrorCode.UPGRADE_PACKAGE_REQUIRED);
        }

        long packageSize = storage.sizeOf(zipFile);
        if (packageSize <= 0) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_REQUIRED, "升级包为空文件");
        }
        long maxPackageBytes = (long) config.getPackageMaxSizeMb() * 1024 * 1024;
        if (packageSize > maxPackageBytes) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_TOO_LARGE,
                    "升级包 " + human(packageSize) + " 超过上限 " + config.getPackageMaxSizeMb() + "MB");
        }

        String packageSha256 = storage.sha256Hex(zipFile);
        long maxExtractBytes = config.getExtractMaxSizeMb() * 1024 * 1024;

        Map<String, String> actualHashes = new LinkedHashMap<>();
        List<String> entries = new ArrayList<>();

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            int count = 0;
            long accumulated = 0;

            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();

                // 目录条目：只做路径安全检查，不参与哈希与体积统计
                if (entry.isDirectory()) {
                    assertSafeEntryName(name);
                    continue;
                }

                assertSafeEntryName(name);
                assertAllowedEntry(name);

                if (++count > config.getMaxEntries()) {
                    throw new BusinessException(ErrorCode.UPGRADE_ENTRY_TOO_MANY,
                            "升级包内文件数超过上限 " + config.getMaxEntries());
                }

                // 边读边累计：条目头里声明的 size 可以被伪造（甚至为 -1），
                // 只有实际读出来的字节数才可信，超限立即中断而不是读完再判断
                ReadResult read = readEntryBounded(zip, entry, maxExtractBytes - accumulated);
                accumulated += read.bytes();

                actualHashes.put(name, read.sha256());
                entries.add(name);
            }
        } catch (ZipException e) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_INVALID,
                    "升级包不是合法的 zip 文件或已损坏：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_INVALID,
                    "读取升级包失败：" + e.getMessage(), e);
        }

        if (!actualHashes.containsKey(MANIFEST_NAME)) {
            throw BusinessException.of(ErrorCode.UPGRADE_MANIFEST_MISSING);
        }
        if (!actualHashes.containsKey(BACKEND_JAR_NAME)) {
            throw BusinessException.of(ErrorCode.UPGRADE_JAR_MISSING);
        }

        UpgradeManifest manifest = readManifest(zipFile);
        String version = manifest.version();
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            throw new BusinessException(ErrorCode.UPGRADE_VERSION_INVALID,
                    "版本号必须匹配 " + VERSION_PATTERN.pattern() + "，实际值：" + version);
        }

        assertManifestMatchesEntries(manifest, actualHashes);
        assertFileHashes(manifest, actualHashes);

        log.info("[升级] 升级包校验通过：version={} size={} files={} sha256={}",
                version, human(packageSize), entries.size(), packageSha256);
        return new UpgradePlan(version, packageSha256, packageSize, List.copyOf(entries));
    }

    // ==================================================================
    // 解压（校验通过后调用；解压时**再次**做路径安全检查）
    // ==================================================================

    /**
     * 把校验通过的包解压到目标目录。
     *
     * <p>解压时对每个条目<b>重新执行</b>一次路径检查与白名单检查，而不是信任
     * {@link #validate(Path)} 已经查过：两次读取之间包文件理论上可被替换
     * （TOCTOU），且「解压函数自己保证安全」比「依赖调用方先校验」更难被后续改坏。
     * 这类检查的成本是纳秒级，没有理由省。
     */
    public void extract(Path zipFile, Path targetDir) {
        long maxExtractBytes = storage.config().getExtractMaxSizeMb() * 1024 * 1024;
        long accumulated = 0;

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();

                Path target = resolveSafely(targetDir, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                assertAllowedEntry(name);
                accumulated += readEntryBounded(zip, entry, maxExtractBytes - accumulated).bytes();

                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    // REPLACE_EXISTING：staging 目录在落盘前会被清空，但保留它可以让
                    // 「同一 taskNo 重试」也安全。绝不能跟随链接写（copyTree 已说明原因）
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (ZipException e) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_INVALID,
                    "升级包不是合法的 zip 文件或已损坏：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "解压升级包失败：" + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 内部：路径安全
    // ==================================================================

    /**
     * 条目名安全检查（Zip Slip 的第一道防线）。
     *
     * <p>逐条拒绝：
     * <ul>
     *   <li>空白名；</li>
     *   <li>含空字节（{@code \0}）—— 某些解压实现会在空字节处截断，导致「校验的名字」
     *       与「实际落盘的名字」不是同一个；</li>
     *   <li>以 {@code /} 或 {@code \} 开头（绝对路径）；</li>
     *   <li>以盘符开头（{@code C:...}）—— 在 Windows 上 {@code Paths.resolve} 会
     *       直接跳到另一个盘，等价于绝对路径；</li>
     *   <li>含反斜杠 —— zip 规范用 {@code /} 作分隔符，出现反斜杠说明这个包
     *       要么是给另一套解压器准备的，要么是刻意构造的跨平台歧义；</li>
     *   <li>任何一段等于 {@code ..}（向上穿越）。</li>
     * </ul>
     */
    void assertSafeEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE, "升级包内含空文件名");
        }
        if (name.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE,
                    "升级包内含空字节的文件名：" + name.replace("\0", "\\0"));
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE, "升级包内含绝对路径：" + name);
        }
        if (DRIVE_PREFIX.matcher(name).find()) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE, "升级包内含盘符路径：" + name);
        }
        if (name.indexOf('\\') >= 0) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE, "升级包内含反斜杠路径：" + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE,
                        "升级包内含向上穿越的路径：" + name);
            }
        }
    }

    /** 条目白名单：只允许 manifest.json / backend.jar / frontend/dist/** */
    void assertAllowedEntry(String name) {
        if (MANIFEST_NAME.equals(name) || BACKEND_JAR_NAME.equals(name)) {
            return;
        }
        // 注意必须带前缀里的结尾斜杠：只判 startsWith("frontend/dist") 会让
        // "frontend/dist-evil/x" 也通过（目录名相似但完全不同）
        if (name.startsWith(FRONTEND_DIST_PREFIX) && name.length() > FRONTEND_DIST_PREFIX.length()) {
            return;
        }
        throw new BusinessException(ErrorCode.UPGRADE_ENTRY_NOT_ALLOWED,
                "升级包内含有不允许的文件：" + name);
    }

    /**
     * 安全解析落盘路径：在 {@link #assertSafeEntryName} 之上，再做一次
     * 「解析结果必须仍在目标目录之内」的兜底断言。
     *
     * <p>只靠字符串检查是不够的 —— 各种平台差异（大小写、Unicode 归一化、
     * 尾部空格与点）都可能让「看起来安全的名字」解析到别处。
     * 这里用规范化后的路径前缀做最终判定，是廉价且无遗漏的收口。
     */
    Path resolveSafely(Path targetDir, String name) {
        assertSafeEntryName(name);
        Path base = targetDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw new BusinessException(ErrorCode.UPGRADE_ENTRY_UNSAFE,
                    "升级包条目解析后超出目标目录：" + name);
        }
        return resolved;
    }

    // ==================================================================
    // 内部：manifest 与哈希一致性
    // ==================================================================

    private UpgradeManifest readManifest(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST_NAME);
            if (entry == null) {
                throw BusinessException.of(ErrorCode.UPGRADE_MANIFEST_MISSING);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                UpgradeManifest manifest = objectMapper.readValue(in, UpgradeManifest.class);
                if (manifest == null) {
                    throw BusinessException.of(ErrorCode.UPGRADE_MANIFEST_INVALID);
                }
                return manifest;
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID,
                    "manifest.json 无法解析：" + e.getMessage(), e);
        }
    }

    /**
     * 校验「包内实际条目」与「manifest 声明条目」<b>完全一致</b>（双向）。
     *
     * <p>manifest.json 自己不需要被声明（它是清单本身，由包整体哈希兜底）。
     */
    private void assertManifestMatchesEntries(UpgradeManifest manifest, Map<String, String> actualHashes) {
        Set<String> declared = new LinkedHashSet<>();
        for (UpgradeManifest.FileEntry file : manifest.fileList()) {
            if (file == null || file.path() == null || file.path().isBlank()) {
                throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID, "manifest 中存在空的文件条目");
            }
            assertSafeEntryName(file.path());
            assertAllowedEntry(file.path());
            if (!declared.add(file.path())) {
                throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID,
                        "manifest 中重复声明了文件：" + file.path());
            }
        }

        List<String> declaredMissing = declared.stream()
                .filter(path -> !actualHashes.containsKey(path))
                .toList();
        if (!declaredMissing.isEmpty()) {
            throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID,
                    "manifest 声明的文件在包内不存在：" + String.join(", ", declaredMissing));
        }

        List<String> undeclared = actualHashes.keySet().stream()
                .filter(path -> !MANIFEST_NAME.equals(path))
                .filter(path -> !declared.contains(path))
                .toList();
        if (!undeclared.isEmpty()) {
            // 这是「悄悄多塞一个文件」的拦截点：不拦的话，声明项的哈希全都对得上，
            // 但多出来的那个照样会被解压到运行目录
            throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID,
                    "包内存在 manifest 未声明的文件：" + String.join(", ", undeclared));
        }
    }

    private void assertFileHashes(UpgradeManifest manifest, Map<String, String> actualHashes) {
        for (UpgradeManifest.FileEntry file : manifest.fileList()) {
            String actual = actualHashes.get(file.path());
            String expected = file.sha256();
            if (expected == null || expected.isBlank()) {
                throw new BusinessException(ErrorCode.UPGRADE_MANIFEST_INVALID,
                        "manifest 中缺少 sha256：" + file.path());
            }
            if (!expected.toLowerCase(Locale.ROOT).equals(actual)) {
                throw new BusinessException(ErrorCode.UPGRADE_CHECKSUM_MISMATCH,
                        "文件 " + file.path() + " 校验和不匹配（期望 " + shortHash(expected)
                                + "，实际 " + shortHash(actual) + "）");
            }
        }
    }

    // ==================================================================
    // 内部：有界读取
    // ==================================================================

    private record ReadResult(long bytes, String sha256) {
    }

    /**
     * 有界读取一个条目：边读边算 SHA-256，并累计字节数。
     *
     * @param remaining 剩余可用配额（字节）；读到超过即抛「解压体积超限」
     */
    private ReadResult readEntryBounded(ZipFile zip, ZipEntry entry, long remaining) throws IOException {
        if (remaining <= 0) {
            throw new BusinessException(ErrorCode.UPGRADE_EXTRACT_TOO_LARGE,
                    "升级包解压后体积超出上限 " + storage.config().getExtractMaxSizeMb() + "MB");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "当前 JVM 不支持 SHA-256", e);
        }

        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream raw = zip.getInputStream(entry);
             DigestInputStream in = new DigestInputStream(raw, digest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > remaining) {
                    // 提前中断：zip 炸弹的判定必须发生在读取过程中，
                    // 等读完了再比大小，磁盘早已被写满
                    throw new BusinessException(ErrorCode.UPGRADE_EXTRACT_TOO_LARGE,
                            "升级包解压后体积超出上限 " + storage.config().getExtractMaxSizeMb() + "MB（在 "
                                    + entry.getName() + " 处中断）");
                }
            }
        }
        return new ReadResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private String shortHash(String hash) {
        if (hash == null) {
            return "(空)";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "…";
    }

    private String human(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }
}
