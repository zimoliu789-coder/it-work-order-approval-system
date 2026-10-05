package com.enterprise.ticket.module.attachment.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.storage.RelativeFileStorage;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 附件落盘支撑（：存 NAS 本地磁盘，DB 只存相对路径）
 *
 * <p>把「校验 → 生成落盘名 → 写盘 → 解析路径 → 删除」收敛在一处，好处有三：
 * <ol>
 *   <li><b>路径穿越防护集中且可测</b>：落盘名恒为服务端生成的 UUID，
 *       用户原始文件名<b>不参与</b>路径拼接；读取时再对解析结果做一次
 *       「必须在存储根之内」的断言，形成双层防护；</li>
 *   <li><b>中文/特殊字符文件名不落盘</b>：避免不同文件系统编码差异导致的乱码与写入失败，
 *       原始文件名只存库用于展示与下载命名；</li>
 *   <li><b>可单测</b>：纯构造函数 + 配置，无需 Spring 容器即可覆盖扩展名/大小/穿越等边界。</li>
 * </ol>
 *
 * <p>「写盘 / 相对路径解析 / 越界断言」这几件与附件无关的通用能力已下沉到
 * {@link RelativeFileStorage}（与  的导出文件共用同一份<b>路径穿越防护</b>实现，
 * 避免两处各写一份后只给其中一处打补丁）；本类只保留<b>附件特有</b>的规则：
 * 类型白名单、照片类仅图片、图片 MIME 推断、单业务数量上限。
 *
 * <h2>根目录为什么每次现取，而不是构造时固定</h2>
 * <p>附件目录是<b>可热改的系统参数</b>（{@code storage_attachment_path}，配置页「文件存储」卡）：
 * 运维把目录指向 NAS 挂载点后应当<b>立刻</b>生效。若在构造时把 {@code Path} 固定下来，
 * 配置页改了参数、进程不重启就仍写进旧目录 —— 表现为「参数显示已改，新附件却还落在老地方」，
 * 而这类「看起来生效了、其实没有」的问题最难被发现。
 *
 * <p>因此本类不持有 {@link RelativeFileStorage} 实例，改为每次操作前按<b>当前生效参数</b>
 * 构造一个（见 {@link #fileStorage()}）。该对象只做路径运算，构造成本可忽略；
 * 参数读取走 {@code SystemConfigService} 的本地缓存，不产生数据库往返。
 *
 * <p>生效值的解析顺序在 {@link SystemConfigService#effectiveAttachmentRoot()}：
 * 配置页值非空则用它，否则回落 {@code app.attachment.storage-root}（部署形态）。
 * 本类再兜一层「两边都空则回落部署配置」，避免注入的是 mock 或参数被清空时把
 * {@code Paths.get(null)} 变成 NPE。
 */
@Component
public class AttachmentStorage {

    private final AppProperties appProperties;

    /** 生效附件根目录的事实源（配置页参数 → 部署配置回落） */
    private final SystemConfigService systemConfigService;

    public AttachmentStorage(AppProperties appProperties, SystemConfigService systemConfigService) {
        this.appProperties = appProperties;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 按当前生效参数构造落盘支撑。
     *
     * <p>本模块用<b>自己的错误码</b>，前端才能区分是附件还是导出出了错。
     */
    private RelativeFileStorage fileStorage() {
        return new RelativeFileStorage(
                effectiveRoot(),
                ErrorCode.ATTACHMENT_NOT_FOUND,
                ErrorCode.ATTACHMENT_SAVE_FAILED);
    }

    /** 生效根目录：系统参数优先，空则回落部署配置（二次兜底，防 mock / 参数被清空） */
    private String effectiveRoot() {
        String configured = systemConfigService == null ? null : systemConfigService.effectiveAttachmentRoot();
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        return appProperties.getAttachment().getStorageRoot();
    }

    /**
     * 上传前校验：必填 → 大小 → 扩展名 → 照片类仅图片。
     *
     * <p>顺序即失败原因的优先级：先告诉用户「没选文件」，再谈大小与格式，
     * 避免用户拿到一个与真正原因无关的报错。
     */
    public void validate(AttachmentBizType bizType, MultipartFile file) {
        AppProperties.Attachment cfg = appProperties.getAttachment();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.ATTACHMENT_FILE_REQUIRED);
        }
        long maxBytes = (long) cfg.getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.ATTACHMENT_FILE_TOO_LARGE,
                    "附件不能超过 " + cfg.getMaxSizeMb() + "MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null || !cfg.getAllowedExtensions().contains(ext)) {
            throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                    "允许的附件类型：" + String.join("、", cfg.getAllowedExtensions()));
        }
        if (bizType.isImageOnly() && !cfg.getImageExtensions().contains(ext)) {
            throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                    "「" + bizType.getLabel() + "」仅支持图片：" + String.join("、", cfg.getImageExtensions()));
        }
    }

    /**
     * 写盘并返回**相对存储根目录**的相对路径（形如 {@code 2026/09/<uuid>.png}）。
     *
     * <p>落盘名用 UUID，所以并发上传同名文件不会互相覆盖，也不需要先查重。
     */
    public String store(InputStream in, String originalFilename) {
        String ext = extensionOf(originalFilename);
        if (ext == null) {
            throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
        }
        return fileStorage().store(in, ext);
    }

    /**
     * 相对路径 → 绝对路径，并断言未越出存储根（路径穿越第二道防线）。
     *
     * <p>落盘名由服务端生成，理论上不会出现 {@code ../}；此处仍做断言，
     * 因为 {@code stored_path} 可以被人工改库，或未来被其它写入路径污染。
     */
    public Path resolve(String relativePath) {
        return fileStorage().resolve(relativePath);
    }

    /** 删除磁盘文件；失败只告警（不影响业务回滚，DB 记录已删掉足够） */
    public void deleteQuietly(String relativePath) {
        fileStorage().deleteQuietly(relativePath);
    }

    /** 递归列出存储根下全部文件（ 附件孤儿清理） */
    public java.util.List<Path> listFiles() {
        return fileStorage().listFiles();
    }

    /** 绝对路径 → 相对存储根的相对路径（{@code /} 分隔，与落库格式一致） */
    public String relativeOf(Path file) {
        return fileStorage().relativeOf(file);
    }

    /** 是否图片（前端缩略图与业务展示用） */
    public boolean isImage(String fileName) {
        String ext = extensionOf(fileName);
        return ext != null && appProperties.getAttachment().getImageExtensions().contains(ext);
    }

    /**
     * 由<b>服务端按文件扩展名</b>推断图片 MIME；非图片返回 {@code null}。
     *
     * <p>刻意不采用上传时客户端声明的 {@code contentType}：该字段完全由客户端控制，
     * 若直接回写到下载响应，攻击者可以把 {@code .txt}/{@code .png} 附件的类型伪造成
     * {@code text/html} 或 {@code image/svg+xml}，再以 {@code inline} 方式打开，
     * 就构成「在同源下渲染任意 HTML/SVG」的存储型 XSS。因此渲染用的类型只能来自
     * 服务端白名单（扩展名 → MIME），客户端声明的类型仅作元数据保留。
     */
    public String imageContentType(String fileName) {
        String ext = extensionOf(fileName);
        if (ext == null || !appProperties.getAttachment().getImageExtensions().contains(ext)) {
            return null;
        }
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> null;
        };
    }

    public int maxPerBiz() {
        return appProperties.getAttachment().getMaxPerBiz();
    }

    /**
     * 从原始文件名取小写扩展名（不含点）；无扩展名或扩展名含非法字符返回 null。
     *
     * <p>只接受 {@code [A-Za-z0-9]} 组成的扩展名：防止把 {@code a.b/../c} 这类
     * 畸形「扩展名」带进落盘名。
     */
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

    /** 存储根目录（绝对路径，按当前生效参数解析） */
    public Path root() {
        return fileStorage().root();
    }
}
