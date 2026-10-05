package com.enterprise.ticket.module.form.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单模板
 *
 * <p>模板是「表单定义的容器」，本身不含字段；字段定义存在 {@link FormTemplateVersion}。
 * 这样拆分后，「同一模板的字段演进」表现为版本号递增，而不是原地覆盖 ——
 * 已提交的工单引用的是具体版本，模板后续怎么改都不会改写历史工单的展示口径。
 *
 * <p>{@code status} 见 {@link com.enterprise.ticket.common.constant.FormTemplateStatus}：
 * 从未发布过是 DRAFT，发布过是 PUBLISHED，被管理员停用是 DISABLED。
 */
@Data
@TableName("form_template")
public class FormTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称（全局唯一） */
    private String templateName;

    /** 模板说明 */
    private String description;

    /** 模板状态：DRAFT / PUBLISHED / DISABLED */
    private String status;

    /** 创建人 user_id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
