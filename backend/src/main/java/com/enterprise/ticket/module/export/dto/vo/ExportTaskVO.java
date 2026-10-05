package com.enterprise.ticket.module.export.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出记录视图
 *
 * <p>不暴露 {@code storedPath}：磁盘相对路径属于内部实现，暴露出去既无用又会给
 * 「拼路径访问文件」留下想象空间（真正的下载一律走后端鉴权端点）。
 */
@Data
public class ExportTaskVO {

    private Long id;

    /** 发起人 ID（管理员查看全部记录时用于区分「谁导出了什么」） */
    private Long requesterId;

    /** 发起人显示名（管理员视图；普通用户看自己的记录时该字段同样有值，无副作用） */
    private String requesterName;

    private String exportType;

    private String exportTypeLabel;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    private String statusLabel;

    private String fileName;

    private Long fileSize;

    private Integer totalRows;

    /** 失败原因（仅 FAILED 有值） */
    private String errorMessage;

    /** 文件过期时间：过期后下载会返回 EXPORT_EXPIRED */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 后端鉴权下载地址；未生成完成时为 null */
    private String downloadUrl;
}
