package com.enterprise.ticket.module.attachment.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附件落盘支撑单元测试（规范 §25）
 *
 * <p>这一层是「不信任外部输入」的集中地，因此测试重点锁定三类边界：
 * <ul>
 *   <li><b>扩展名解析</b>：无扩展名、多级扩展名、含路径分隔符的畸形「扩展名」都必须被拒或正确截取；</li>
 *   <li><b>校验顺序与错误码</b>：空文件 / 超限 / 类型不允许 / 照片类只收图片，各自返回确切错误码；</li>
 *   <li><b>路径穿越</b>：{@code ../} 形式的相对路径必须被拒绝（第二道防线）。</li>
 * </ul>
 */
class AttachmentStorageTest {

    @TempDir
    Path tempDir;

    private AttachmentStorage storage;

    /** 生效根目录的事实源：不 stub 时返回 null ⇒ 走「回落部署配置」的兜底分支 */
    private SystemConfigService configService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getAttachment().setStorageRoot(tempDir.toString());
        props.getAttachment().setMaxSizeMb(1);
        props.getAttachment().setAllowedExtensions(
                List.of("pdf", "png", "jpg", "jpeg", "docx"));
        props.getAttachment().setImageExtensions(List.of("png", "jpg", "jpeg"));
        configService = mock(SystemConfigService.class);
        storage = new AttachmentStorage(props, configService);
    }

    private MultipartFile file(String name, long size) {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn(name);
        when(f.getSize()).thenReturn(size);
        when(f.isEmpty()).thenReturn(size == 0);
        return f;
    }

    private ErrorCode codeOf(Runnable action) {
        BusinessException e = assertThrows(BusinessException.class, action::run);
        return e.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 扩展名解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("扩展名统一小写；无扩展名与畸形扩展名返回 null")
    void extensionOf_variants() {
        assertEquals("pdf", storage.extensionOf("合同.PDF"));
        assertEquals("gz", storage.extensionOf("a.tar.gz"), "只取最后一级扩展名");
        assertNull(storage.extensionOf(null), "文件名缺失");
        assertNull(storage.extensionOf("noext"), "无扩展名");
        assertNull(storage.extensionOf("trailing."), "尾部只有点");
        assertNull(storage.extensionOf("a.b/../c"), "含路径分隔符的畸形扩展名必须被拒");
        assertNull(storage.extensionOf("x.verylongextension"), "超长扩展名（>10）拒绝");
    }

    // ------------------------------------------------------------------
    // 上传校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未选择文件 → ATTACHMENT_FILE_REQUIRED")
    void validate_missingFile() {
        assertEquals(ErrorCode.ATTACHMENT_FILE_REQUIRED,
                codeOf(() -> storage.validate(AttachmentBizType.APPLY_ATTACHMENT, null)));
    }

    @Test
    @DisplayName("超过大小上限 → ATTACHMENT_FILE_TOO_LARGE")
    void validate_tooLarge() {
        assertEquals(ErrorCode.ATTACHMENT_FILE_TOO_LARGE,
                codeOf(() -> storage.validate(AttachmentBizType.APPLY_ATTACHMENT, file("a.pdf", 2 * 1024 * 1024))));
    }

    @Test
    @DisplayName("扩展名不在白名单 → ATTACHMENT_TYPE_NOT_ALLOWED")
    void validate_extensionNotAllowed() {
        assertEquals(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                codeOf(() -> storage.validate(AttachmentBizType.APPLY_ATTACHMENT, file("virus.exe", 100))));
    }

    @Test
    @DisplayName("照片类附件只收图片：pdf 被拒、png 通过")
    void validate_imageOnly() {
        assertEquals(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                codeOf(() -> storage.validate(AttachmentBizType.RETURN_PHOTO, file("scan.pdf", 100))));
        // 不抛异常即通过
        storage.validate(AttachmentBizType.RETURN_PHOTO, file("photo.PNG", 100));
    }

    @Test
    @DisplayName("文档类附件接受 pdf（照片类限制不误伤文档类）")
    void validate_documentTypeAcceptsPdf() {
        storage.validate(AttachmentBizType.APPLY_ATTACHMENT, file("申请材料.pdf", 100));
    }

    // ------------------------------------------------------------------
    // 写盘与路径安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写盘返回相对路径，落盘文件存在且不在存储根之外")
    void store_writesUnderRoot() throws Exception {
        String relative = storage.store(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "证据.pdf");
        assertTrue(relative.endsWith(".pdf"), "相对路径应保留扩展名：" + relative);
        Path abs = storage.resolve(relative);
        assertTrue(Files.exists(abs), "文件应已落盘");
        assertTrue(abs.toAbsolutePath().normalize().startsWith(tempDir.toAbsolutePath().normalize()),
                "文件必须位于存储根之内");
    }

    @Test
    @DisplayName("落盘名不含原始文件名（避免中文/特殊字符与路径穿越）")
    void store_generatedNameOnly() {
        String relative = storage.store(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), "../../evil.pdf");
        assertTrue(relative.endsWith(".pdf"));
        assertTrue(!relative.contains(".."), "相对路径不得出现上跳片段：" + relative);
        assertTrue(!relative.contains("evil"), "不得把用户文件名拼进落盘路径：" + relative);
    }

    @Test
    @DisplayName("路径穿越：resolve(../) 必须被拒绝")
    void resolve_rejectsTraversal() {
        assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND,
                codeOf(() -> storage.resolve("../outside.txt")));
        assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND,
                codeOf(() -> storage.resolve("")));
    }

    @Test
    @DisplayName("isImage 按图片扩展名判定")
    void isImage_variants() {
        assertTrue(storage.isImage("photo.JPG"));
        assertTrue(storage.isImage("a.png"));
        assertTrue(!storage.isImage("doc.pdf"));
        assertTrue(!storage.isImage(null));
    }

    // ------------------------------------------------------------------
    // 生效根目录（Phase 18 批次 E：目录可热改，改完立刻生效）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("系统参数优先：改了附件目录，无需重启就写到新目录")
    void root_followsSystemConfig() throws Exception {
        Path newRoot = Files.createDirectory(tempDir.resolve("nas"));
        when(configService.effectiveAttachmentRoot()).thenReturn(newRoot.toString());

        String relative = storage.store(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), "a.pdf");

        assertTrue(storage.resolve(relative).toAbsolutePath().normalize()
                        .startsWith(newRoot.toAbsolutePath().normalize()),
                "落盘位置必须跟随系统参数指定的目录");
    }

    @Test
    @DisplayName("系统参数为空（或注入的是 mock）时回落部署配置 app.attachment.storage-root")
    void root_fallsBackToDeployConfig() {
        when(configService.effectiveAttachmentRoot()).thenReturn("   ");
        assertEquals(tempDir.toAbsolutePath().normalize(), storage.root(),
                "参数为空时应沿用部署配置里的目录，而不是 Paths.get(null)");
    }

    // ------------------------------------------------------------------
    // 下载渲染类型（服务端白名单推断，防同源存储型 XSS）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("imageContentType 只对图片扩展名返回服务端 MIME，其余一律 null")
    void imageContentType_whitelistOnly() {
        assertEquals("image/png", storage.imageContentType("photo.PNG"), "大小写不敏感");
        assertEquals("image/jpeg", storage.imageContentType("a.jpg"));
        assertEquals("image/jpeg", storage.imageContentType("a.jpeg"));
        // 非图片扩展名（含可伪造为 HTML 的类型）必须返回 null → 由控制器强制 attachment 下载
        assertNull(storage.imageContentType("doc.pdf"));
        assertNull(storage.imageContentType("page.html"), "html 绝不能被当作可 inline 渲染的类型");
        assertNull(storage.imageContentType("vector.svg"), "svg 未列入图片白名单，不得 inline");
        assertNull(storage.imageContentType("noext"));
        assertNull(storage.imageContentType(null));
    }
}
