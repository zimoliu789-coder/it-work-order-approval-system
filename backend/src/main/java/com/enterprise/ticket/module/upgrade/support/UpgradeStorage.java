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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 升级工作目录
 *
 * <h2>目录布局（全部在 {@code app.upgrade.storage-root} 之下）</h2>
 * <pre>
 *   staging/&lt;taskNo&gt;/                新产物（解压后的 backend.jar + frontend/dist/**）
 *   backup/&lt;taskNo&gt;/                 本次升级前的旧产物副本
 *   state/current.json               当前已应用版本描述（外部脚本写，后端读来展示）
 *   state/result/&lt;taskNo&gt;.json        外部应用结果回执（外部脚本写，后端启动时读）
 *   tmp/                             上传临时文件（校验完即删）
 * </pre>
 *
 * <h2>为什么「备份来源」目录（currentArtifactDir）刻意与 storageRoot 分离</h2>
 * <p>storageRoot 是<b>升级过程的工作区</b>（可随时清空重建，丢了也不影响系统运行）；
 * currentArtifactDir 是<b>系统正在使用的产物</b>（丢了系统就跑不起来）。
 * 把两者放进同一棵树，会让「清理工作区」这个看起来无害的操作有机会删掉运行中的产物。
 * 分开之后，{@link #deleteTree(Path)} 只会在 storageRoot 之下被调用，
 * 而 {@link #currentArtifactDir()} 永远只被<b>读</b>（复制出来）或被显式还原写入。
 *
 * <h2>为什么路径入库要转成相对路径</h2>
 * <p>与附件、导出同策略：换挂载点（本地盘 → NAS → 另一台机）只改配置，不刷数据。
 * 若存绝对路径，迁移环境后所有历史任务记录都会指向不存在的目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpgradeStorage {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    public AppProperties.Upgrade config() {
        return appProperties.getUpgrade();
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public Path storageRoot() {
        return Paths.get(config().getStorageRoot()).toAbsolutePath().normalize();
    }

    public Path stagingDir(String taskNo) {
        return storageRoot().resolve("staging").resolve(taskNo);
    }

    public Path backupDir(String taskNo) {
        return storageRoot().resolve("backup").resolve(taskNo);
    }

    public Path stateDir() {
        return storageRoot().resolve("state");
    }

    public Path resultFile(String taskNo) {
        return stateDir().resolve("result").resolve(taskNo + ".json");
    }

    /** {@code state/current.json}：当前已应用版本的描述（由外部脚本维护） */
    public Path currentStateFile() {
        return stateDir().resolve("current.json");
    }

    public Path tempDir() {
        return storageRoot().resolve("tmp");
    }

    /**
     * 「当前生效产物」目录 —— 备份的来源。
     *
     * <p>与 storageRoot 分离，见类注释。首次部署时该目录**不存在是正常的**：
     * 那时还没有「上一版产物」，备份步骤会跳过并记一条提示，而不是报错。
     */
    public Path currentArtifactDir() {
        return Paths.get(config().getCurrentArtifactDir()).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // 入库路径 ↔ 真实路径
    // ------------------------------------------------------------------

    /**
     * 转成入库用的路径字符串。
     *
     * <p>在 storageRoot 之下 ⇒ 存相对路径（可迁移）；之外 ⇒ 存绝对路径
     * （例如被显式配置到别处的 currentArtifactDir，相对化会得到一堆 {@code ../../../}，
     * 既不可读又会在迁移后指向完全不同的位置）。
     */
    public String toStoredPath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path root = storageRoot();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    /** 由入库路径还原真实路径（绝对路径原样返回） */
    public Path fromStoredPath(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        Path path = Paths.get(stored);
        return path.isAbsolute() ? path.normalize() : storageRoot().resolve(path).normalize();
    }

    // ------------------------------------------------------------------
    // 校验和 / 大小
    // ------------------------------------------------------------------

    /** 计算文件 SHA-256（十六进制小写） */
    public String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[8192];
            while (digestIn.read(buffer) != -1) {
                // 读取即累积摘要，无需保留内容
            }
            return HexFormat.of().formatHex(digestIn.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境被破坏
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "当前 JVM 不支持 SHA-256", e);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED, "读取文件失败：" + e.getMessage(), e);
        }
    }

    public long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED, "读取文件大小失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 目录操作
    // ------------------------------------------------------------------

    public void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "创建目录失败：" + dir + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 递归复制目录（{@code src} 必须已存在）。
     *
     * <p><b>不跟随符号链接</b>：升级包由外部提供，若包内放了指向 {@code /etc} 的软链，
     * 跟随复制会把系统目录内容搬进备份区 —— 既是信息泄露，也可能在复制时耗光磁盘。
     * {@code walkFileTree} 默认不跟随链接，这里依赖该默认值。
     */
    public void copyTree(Path src, Path dst) {
        if (!Files.isDirectory(src)) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED, "源目录不存在：" + src);
        }
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, dst.resolve(src.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "复制目录失败：" + src + " → " + dst + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 递归删除目录（不存在则静默返回）。
     *
     * <p><b>只应用于 storageRoot 之下的工作区</b>：调用方在校验阶段/回滚阶段清理
     * staging 与 backup。绝不接受外部传入的任意路径 —— 否则一次参数错误就是
     * 「把当前生效产物删掉」。
     */
    public void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 删除失败不抛出：它出现在 finally 式的清理里，抛异常会掩盖真正的失败原因
            log.warn("[升级] 清理目录失败（忽略）：{} - {}", path, e.getMessage());
        }
    }

    /** 清空目录内容但保留目录本身（目录不存在则创建） */
    public void clearDir(Path dir) {
        ensureDir(dir);
        try (var stream = Files.list(dir)) {
            stream.forEach(this::deleteTree);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "清空目录失败：" + dir + "（" + e.getMessage() + "）", e);
        }
    }

    // ------------------------------------------------------------------
    // JSON 状态文件
    // ------------------------------------------------------------------

    /** 读 {@code state/current.json} 里的当前版本号；不存在或损坏时返回空 */
    public Optional<String> readCurrentVersion() {
        Path file = currentStateFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            UpgradeManifest manifest = objectMapper.readValue(file.toFile(), UpgradeManifest.class);
            String version = manifest == null ? null : manifest.version();
            return (version == null || version.isBlank()) ? Optional.empty() : Optional.of(version);
        } catch (IOException e) {
            // 损坏的状态文件不值得让整个升级流程失败：它只用于「升级前版本」的展示
            log.warn("[升级] 读取 state/current.json 失败（忽略）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 读外部脚本写的结果回执；不存在或损坏时返回空 */
    public Optional<UpgradeApplyResult> readApplyResult(String taskNo) {
        Path file = resultFile(taskNo);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(file.toFile(), UpgradeApplyResult.class));
        } catch (IOException e) {
            log.warn("[升级] 读取结果回执失败（忽略）：{} - {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写结果回执（供测试与后端自身标记用途） */
    public void writeApplyResult(String taskNo, UpgradeApplyResult result) {
        writeJson(resultFile(taskNo), result);
    }

    /** 写 JSON 文件（自动创建父目录） */
    public void writeJson(Path file, Object value) {
        ensureDir(file.getParent());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "写入文件失败：" + file + "（" + e.getMessage() + "）", e);
        }
    }
}
