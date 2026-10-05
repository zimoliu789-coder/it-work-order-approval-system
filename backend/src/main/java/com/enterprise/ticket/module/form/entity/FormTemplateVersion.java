package com.enterprise.ticket.module.form.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单模板版本
 *
 * <h2>{@code publishedAt} 兼职表达「草稿」还是「已发布」</h2>
 * <p>刻意不额外加 {@code status} 列：版本的可用性只有两种，「已发布」与「尚未发布」。
 * 用 {@code published_at IS NULL} 表达后者即可，而单列布尔/枚举意味着
 * 「status=草稿」与「published_at 为空」两个事实可能互相矛盾 —— 那才是需要额外维护的一致性负担。
 *
 * <p>同一模板下最多存在一条草稿行（服务层保证），发布时只需把它的
 * {@code published_at / published_by} 填上，草稿即成为不可再改的历史版本。
 */
@Data
@TableName("form_template_version")
public class FormTemplateVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属模板 form_template.id */
    private Long templateId;

    /** 版本号（同一模板内从 1 递增） */
    private Integer versionNo;

    /** 表单字段定义 JSON */
    private String schemaJson;

    /** 发布时间；为 null 表示这是一条<b>草稿</b> */
    private LocalDateTime publishedAt;

    /** 发布人 user_id */
    private Long publishedBy;

    /** 是否为草稿（未发布） */
    public boolean isDraft() {
        return publishedAt == null;
    }
}
