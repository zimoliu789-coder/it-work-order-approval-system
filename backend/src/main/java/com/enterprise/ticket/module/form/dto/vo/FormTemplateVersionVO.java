package com.enterprise.ticket.module.form.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单模板版本列表项 / 版本详情
 *
 * <p>版本详情（{@code GET /api/form/templates/versions/{vid}}）会带上
 * {@code schema}，用于「版本历史」里预览某一版到底长什么样；
 * 列表场景则该字段为空（服务层按需填充，避免一次拉回全部版本的字段定义）。
 */
@Data
public class FormTemplateVersionVO {

    private Long id;

    private Long templateId;

    private Integer versionNo;

    /** 是否为草稿（未发布） */
    private Boolean draft;

    /** 字段数量（列表展示用，避免为统计数量解析整份 schema） */
    private Integer fieldCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    private Long publishedBy;

    /** 发布人姓名（列表展示） */
    private String publishedByName;

    /** 版本详情才返回；列表为 null */
    private com.enterprise.ticket.common.form.FormSchema schema;
}
