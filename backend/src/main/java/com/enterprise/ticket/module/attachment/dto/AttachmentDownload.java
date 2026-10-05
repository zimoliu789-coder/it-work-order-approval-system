package com.enterprise.ticket.module.attachment.dto;

import com.enterprise.ticket.module.attachment.entity.Attachment;
import org.springframework.core.io.Resource;

/**
 * 附件下载结果：元数据 + 文件资源流。
 *
 * <p>下载必须走后端鉴权（：「附件资源访问必须后端鉴权，不能直接暴露 NAS 静态文件地址」），
 * 因此这里把「实体（提供文件名/大小/MIME）」与「资源（提供流）」一起返回给控制器，
 * 由控制器拼 {@code Content-Disposition} 并写出响应。
 */
public record AttachmentDownload(Attachment attachment, Resource resource) {
}
