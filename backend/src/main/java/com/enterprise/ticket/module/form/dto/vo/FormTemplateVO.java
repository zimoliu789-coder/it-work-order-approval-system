package com.enterprise.ticket.module.form.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单模板列表项
 *
 * <p>列表刻意<b>不返回</b> {@code schema}：字段定义可能有几十个字段，
 * 列表页一次展示 10–20 条就会把响应体撑到几百 KB，而列表根本用不到它。
 * 需要 schema 的场景（设计器）走详情接口。
 */
@Data
public class FormTemplateVO {

    private Long id;

    private String templateName;

    private String description;

    /** DRAFT / PUBLISHED / DISABLED */
    private String status;

    private String statusLabel;

    /** 最新已发布版本号；从未发布时为 null */
    private Integer latestVersionNo;

    /** 最新已发布版本 id；从未发布时为 null（申请类型只能引用它） */
    private Long latestPublishedVersionId;

    /** 是否存在未发布的草稿改动 */
    private Boolean hasDraft;

    /** 字段数量（取当前展示版本；草稿优先） */
    private Integer fieldCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
