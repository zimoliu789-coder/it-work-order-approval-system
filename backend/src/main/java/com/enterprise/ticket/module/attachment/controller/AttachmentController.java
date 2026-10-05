package com.enterprise.ticket.module.attachment.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.attachment.dto.AttachmentDownload;
import com.enterprise.ticket.module.attachment.dto.vo.AttachmentVO;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.service.AttachmentService;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 附件接口（ 附件上传通用能力）
 *
 * <p>规范约束逐条落地：
 * <ul>
 *   <li>「附件资源访问必须后端鉴权，不能直接暴露 NAS 静态文件地址」
 *       → 下载走 {@code GET /api/attachments/{id}/download}，由服务层按业务可见性校验；</li>
 *   <li>「下载接口后端做权限校验……未授权返回 403」→ 见 {@code AttachmentServiceImpl#assertBizAccessible}；</li>
 *   <li>「附件下载记入操作日志」→ 下载端点标注 {@code @AuditLog}，
 *       由既有审计切面统一落 {@code operation_logs}，无需在业务代码里手写日志。</li>
 * </ul>
 *
 * <p>所有端点仅要求登录：<b>细粒度权限在服务层按「业务记录可见性」判定</b>，
 * 因为同一个附件类型对申请人、审批人、执行人都要放行，用 {@code @PreAuthorize}
 * 表达会退化成一堆 {@code hasAnyRole} 且拿不到 bizId 上下文。
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentStorage storage;

    /**
     * 上传附件（multipart：bizType / bizId / file）
     *
     * <p>测试路径：POST http://localhost:8080/api/attachments
     * （form-data：bizType=APPLY_ATTACHMENT、bizId=1、file=@a.pdf）
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ATTACHMENT", action = "ATTACHMENT_UPLOAD", risk = RiskLevel.NORMAL,
            description = "上传附件")
    public ApiResponse<AttachmentVO> upload(@RequestParam("bizType") String bizType,
                                            @RequestParam("bizId") Long bizId,
                                            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("附件已上传", attachmentService.upload(bizType, bizId, file));
    }

    /**
     * 下载附件（后端鉴权 + 记操作日志）
     *
     * <p>{@code inline=true} 时使用 inline 语义，便于前端直接以 {@code <img>} 渲染照片缩略图；
     * 默认 {@code attachment}（浏览器下载）。
     *
     * <p>测试路径：GET http://localhost:8080/api/attachments/1/download
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ATTACHMENT", action = "ATTACHMENT_DOWNLOAD", risk = RiskLevel.NORMAL,
            description = "下载附件")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @RequestParam(value = "inline", defaultValue = "false") boolean inline) {
        AttachmentDownload download = attachmentService.download(id);
        Attachment meta = download.attachment();

        // 渲染类型只认服务端按扩展名推断的结果，**不采用**客户端上传时声明的 contentType：
        // 否则可把非图片附件伪造成 text/html，或用 inline 打开 svg，构成同源存储型 XSS。
        String imageType = storage.imageContentType(meta.getFileName());
        boolean asInline = inline && imageType != null;

        ContentDisposition disposition = (asInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(meta.getFileName(), StandardCharsets.UTF_8)
                .build();
        MediaType mediaType = imageType == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(imageType);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // nosniff 与「非图片一律强制下载」形成双层防护：即便类型推断有缺口也不至于被解析为 HTML
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType)
                .body(download.resource());
    }

    /**
     * 删除附件（软删；仅上传者本人或 admin 以上）
     *
     * <p>测试路径：DELETE http://localhost:8080/api/attachments/1
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ATTACHMENT", action = "ATTACHMENT_DELETE", risk = RiskLevel.NORMAL,
            description = "删除附件")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        attachmentService.delete(id);
        return ApiResponse.success("附件已删除", null);
    }

    /**
     * 按业务查询附件列表
     *
     * <p>测试路径：GET http://localhost:8080/api/attachments?bizType=APPLY_ATTACHMENT&bizId=1
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<AttachmentVO>> list(@RequestParam("bizType") String bizType,
                                                @RequestParam("bizId") Long bizId) {
        return ApiResponse.success(attachmentService.listByBiz(bizType, bizId));
    }
}
