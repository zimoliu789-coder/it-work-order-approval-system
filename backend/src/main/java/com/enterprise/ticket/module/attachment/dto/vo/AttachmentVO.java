package com.enterprise.ticket.module.attachment.dto.vo;

import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件展示对象
 *
 * <p>刻意<b>不包含</b> {@code storedPath}：磁盘相对路径属服务端实现细节，
 * 暴露出去既无用途，又会给「绕过鉴权猜路径」留下提示。前端只需携带 {@code id}
 * 调下载接口，由后端鉴权后回传文件流。
 */
@Data
public class AttachmentVO {

    private Long id;

    /** 关联业务类型编码 */
    private String bizType;

    /** 关联业务类型中文名 */
    private String bizTypeLabel;

    private Long bizId;

    /** 原始文件名（展示与下载命名用） */
    private String fileName;

    /** 文件大小（字节） */
    private Long fileSize;

    private String contentType;

    private Long uploaderId;

    /** 上传人姓名（displayName 兜底 username） */
    private String uploaderName;

    private LocalDateTime createdAt;

    /** 是否为图片（前端决定是否渲染缩略图而不是文件卡片） */
    private Boolean image;

    /** 下载地址（前端直接请求，后端鉴权） */
    private String downloadUrl;

    public static AttachmentVO of(Attachment entity, String uploaderName, boolean image) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(entity.getId());
        vo.setBizType(entity.getBizType());
        vo.setBizTypeLabel(AttachmentBizType.labelOf(entity.getBizType()));
        vo.setBizId(entity.getBizId());
        vo.setFileName(entity.getFileName());
        vo.setFileSize(entity.getFileSize());
        vo.setContentType(entity.getContentType());
        vo.setUploaderId(entity.getUploaderId());
        vo.setUploaderName(uploaderName);
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setImage(image);
        vo.setDownloadUrl("/api/attachments/" + entity.getId() + "/download");
        return vo;
    }
}
