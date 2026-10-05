package com.enterprise.ticket.module.system.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.storage.RelativeFileStorage;
import com.enterprise.ticket.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 站点 logo 图片的落盘支撑（：logo 支持上传图片，png / jpg，10MB 内）。
 *
 * <h2>为什么不复用附件模块</h2>
 * <p>三处必须分开，任何一处合并都会引入真实缺陷：
 * <ol>
 *   <li><b>目录必须分开</b>：{@code AttachmentOrphanCleanupJob} 会把「文件名不在
 *       attachments 表里」的文件判为孤儿并删除。logo 文件不对应任何业务记录，
 *       放进附件目录会被定时任务当垃圾清掉 —— 表现为「logo 过几小时自己消失」；</li>
 *   <li><b>错误码必须分开</b>：本组错误发生在系统参数页，复用 {@code ATTACHMENT_*}
 *       会让用户看到「附件超过大小上限，请压缩后重试」——指错了对象，也看不出该改哪里；</li>
 *   <li><b>白名单必须更窄</b>：logo 只允许 png / jpg。附件白名单含 doc/xlsx/zip 等，
 *       直接复用会让一个 .zip「当上 logo」，前端 {@code <img>} 渲染成裂图，
 *       而服务端又认为「上传成功」。</li>
 * </ol>
 *
 * <p>「写盘 + 相对路径解析 + 路径穿越断言」这三件与业务无关的能力仍复用
 * {@link RelativeFileStorage}（与附件、导出共用同一份安全实现），本类只补 logo 特有规则。
 */
@Slf4j
@Component
public class SiteLogoStorage {

    private final AppProperties appProperties;

    private final RelativeFileStorage fileStorage;

    public SiteLogoStorage(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.fileStorage = new RelativeFileStorage(
                appProperties.getSite().getStorageRoot(),
                ErrorCode.SITE_LOGO_NOT_FOUND,
                ErrorCode.SITE_LOGO_SAVE_FAILED);
    }

    /**
     * 上传前校验：必填 → 大小 → 扩展名。
     *
     * <p>顺序即失败原因的优先级：先告诉用户「没选文件」，再谈大小与格式，
     * 避免用户拿到一个与真正原因无关的报错。与 {@code AttachmentStorage#validate} 同序。
     */
    public void validate(MultipartFile file) {
        AppProperties.Site cfg = appProperties.getSite();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.SITE_LOGO_FILE_REQUIRED);
        }
        long maxBytes = (long) cfg.getLogoMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.SITE_LOGO_FILE_TOO_LARGE,
                    "logo 图片不能超过 " + cfg.getLogoMaxSizeMb() + "MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null || !cfg.getLogoExtensions().contains(ext)) {
            throw new BusinessException(ErrorCode.SITE_LOGO_TYPE_INVALID,
                    "logo 仅支持 " + String.join(" / ", cfg.getLogoExtensions()) + " 格式的图片");
        }
        // MIME 只作为补充线索：浏览器对 .jpg 常给 image/jpeg，也可能给 application/octet-stream，
        // 因此**不能**因为 MIME 不匹配就拒绝（会误伤合法上传）；只在扩展名已通过后，
        // 对「声明了 image/* 但不是 png/jpeg」这种明显矛盾做拦截（例如 .jpg 却被声明成 image/gif）。
        String declared = file.getContentType();
        if (StringUtils.hasText(declared) && declared.toLowerCase(Locale.ROOT).startsWith("image/")
                && !"image/png".equalsIgnoreCase(declared) && !"image/jpeg".equalsIgnoreCase(declared)) {
            throw new BusinessException(ErrorCode.SITE_LOGO_TYPE_INVALID,
                    "logo 仅支持 png / jpg 格式的图片（识别到的类型：" + declared + "）");
        }
    }

    /**
     * 写盘并返回**相对存储根目录**的相对路径（形如 {@code 2026/09/<uuid>.png}）。
     *
     * <p>{@code MultipartFile#getInputStream()} 会抛受检的 {@code IOException}，
     * 此处转成业务异常统一由全局处理器转 400/500 —— 不让受检异常穿透到控制器。
     */
    public String store(MultipartFile file) {
        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null) {
            throw new BusinessException(ErrorCode.SITE_LOGO_TYPE_INVALID);
        }
        try (var in = file.getInputStream()) {
            return fileStorage.store(in, ext);
        } catch (IOException e) {
            log.error("logo 读取上传流失败：name={} size={}", file.getOriginalFilename(), file.getSize(), e);
            throw new BusinessException(ErrorCode.SITE_LOGO_SAVE_FAILED);
        }
    }

    /** 相对路径 → 绝对路径，并断言未越出存储根（路径穿越第二道防线） */
    public Path resolve(String relativePath) {
        return fileStorage.resolve(relativePath);
    }

    /** 删除磁盘文件；失败只告警（DB 侧的取值已改掉足够） */
    public void deleteQuietly(String relativePath) {
        fileStorage.deleteQuietly(relativePath);
    }

    /**
     * 由<b>服务端按扩展名</b>推断 MIME；非白名单图片返回 {@code null}。
     *
     * <p>刻意不采用客户端声明的 {@code contentType}：该值完全由客户端控制，
     * 直接回写到响应头可以把任意文件声明成 {@code image/svg+xml} 或 {@code text/html}，
     * 在同源下 {@code inline} 打开即构成存储型 XSS。渲染类型只能来自服务端白名单。
     */
    public String contentType(String relativePath) {
        String ext = extensionOf(relativePath);
        if (ext == null || !appProperties.getSite().getLogoExtensions().contains(ext)) {
            return null;
        }
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> null;
        };
    }

    /** 从文件名取小写扩展名（不含点）；无扩展名或扩展名含非法字符返回 null */
    public String extensionOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return RelativeFileStorage.normalizeExtension(fileName.substring(dot + 1));
    }

    /** 存储根目录（绝对路径） */
    public Path root() {
        return fileStorage.root();
    }
}
