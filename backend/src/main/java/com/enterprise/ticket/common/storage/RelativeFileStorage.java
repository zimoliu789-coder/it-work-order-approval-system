package com.enterprise.ticket.common.storage;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 「相对路径落盘」通用支撑（附件  与 导出文件  共用）
 *
 * <p><b>为什么必须共用一份实现</b>：本类承载的是<b>路径穿越防护</b>这一安全不变量 ——
 * 落盘名恒为服务端生成的 UUID（用户输入绝不参与路径拼接），读取时再对解析结果断言
 * 「必须在存储根之内」。这类防护一旦被复制成两份，就必然出现「只给其中一处打补丁」
 * 的漂移：例如日后收紧了 {@code startsWith} 的判定，却只改了一处，另一处就成了缺口。
 * 因此文件落盘与相对路径解析收敛到这里，各业务只提供<b>根目录</b>与<b>错误码</b>。
 *
 * <p>不声明为 Spring Bean：它需要「根目录 + 错误码」两个上下文参数，
 * 由各业务模块的支撑类（{@code AttachmentStorage} / {@code ExportStorage}）按自己的配置构造。
 *
 * <p><b>为什么只存相对路径</b>：绝对路径一旦落库，NAS 换挂载点 / 迁移磁盘就要全表刷数据；
 * 相对路径只改一处配置即可。
 */
@Slf4j
public class RelativeFileStorage {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 存储根目录（已绝对化 + normalize，供 {@code startsWith} 断言使用） */
    private final Path root;

    /** 文件不存在 / 路径非法时抛出的错误码（各模块用自己的码，便于前端区分场景） */
    private final ErrorCode notFoundCode;

    /** 写盘失败时抛出的错误码 */
    private final ErrorCode saveFailedCode;

    /**
     * @param storageRoot  存储根目录（可为相对路径，按进程工作目录解析）
     * @param notFoundCode 路径非法 / 文件不存在错误码
     * @param saveFailedCode 写盘失败错误码
     */
    public RelativeFileStorage(String storageRoot, ErrorCode notFoundCode, ErrorCode saveFailedCode) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.notFoundCode = notFoundCode;
        this.saveFailedCode = saveFailedCode;
    }

    /** 存储根目录（绝对路径，已 normalize） */
    public Path root() {
        return root;
    }

    /**
     * 写盘并返回**相对存储根目录**的相对路径（形如 {@code 2026/09/<uuid>.xlsx}）。
     *
     * <p>落盘名用 UUID，因此并发写同名文件不会互相覆盖，也不需要先查重。
     *
     * @param extension 扩展名（不含点，须为服务端可信来源，例如由扩展名白名单校验后的值）
     */
    public String store(InputStream in, String extension) {
        String ext = normalizeExtension(extension);
        if (ext == null) {
            throw new BusinessException(saveFailedCode, "无法确定文件扩展名，已拒绝落盘");
        }
        String relative = LocalDate.now().format(DATE_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("文件落盘失败：relative={} root={}", relative, root, e);
            throw new BusinessException(saveFailedCode);
        }
        return relative;
    }

    /**
     * 相对路径 → 绝对路径，并断言未越出存储根（路径穿越第二道防线）。
     *
     * <p>落盘名由服务端生成，理论上不会出现 {@code ../}；此处仍做断言，
     * 因为 {@code stored_path} 可以被人工改库，或未来被其它写入路径污染。
     */
    public Path resolve(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BusinessException(notFoundCode);
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("文件路径越界，已拒绝：relative={} resolved={}", relativePath, resolved);
            throw new BusinessException(notFoundCode);
        }
        return resolved;
    }

    /** 删除磁盘文件；失败只告警（不影响业务回滚，DB 记录改动已生效足够） */
    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception e) {
            log.warn("磁盘文件删除失败（已忽略）：relative={} err={}", relativePath, e.getMessage());
        }
    }

    /**
     * 递归列出存储根下的全部<b>常规文件</b>（/14 孤儿清理用）。
     *
     * <p>返回绝对路径；相对路径由 {@link #relativeOf(Path)} 转换，保证与
     * {@link #store} 落库的相对路径使用同一分隔符（{@code /}）——
     * 否则在 Windows 上 {@code Path#toString()} 会给反斜杠，与库里的值永远不匹配，
     * 表现为「清理任务把在用文件全删了」这一灾难性误判。
     *
     * <p>根目录不存在（首次部署尚未上传任何文件）时返回空列表，而不是报错。
     */
    public java.util.List<Path> listFiles() {
        if (!Files.isDirectory(root)) {
            return java.util.List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("列目录失败：root={} err={}", root, e.getMessage());
            return java.util.List.of();
        }
    }

    /** 绝对路径 → 相对存储根的相对路径，分隔符统一为 {@code /}（与落库格式一致） */
    public String relativeOf(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 扩展名规整：只接受 {@code [a-z0-9]} 组成的扩展名。
     *
     * <p>该白名单是路径穿越防护的<b>第一层</b>：即便调用方把用户输入当成扩展名传进来，
     * {@code a.b/../c} 这类畸形值也会被拒，不会进入落盘名。
     */
    public static String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            return null;
        }
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        ext = ext.toLowerCase(Locale.ROOT);
        if (ext.isEmpty() || ext.length() > 10) {
            return null;
        }
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return null;
            }
        }
        return ext;
    }
}
