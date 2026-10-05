package com.enterprise.ticket.module.form.dto.vo;

import com.enterprise.ticket.common.form.FormSchema;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单模板详情（含 schema，）
 *
 * <h2>{@code schema} 给的是「哪一份」</h2>
 * <p>取值规则（也是设计器的加载规则）：
 * <ol>
 *   <li>存在<b>草稿</b>时给草稿 —— 用户上次没发布完的编辑应当被继续看到；</li>
 *   <li>无草稿时给<b>最新已发布版本</b> —— 让设计器有内容可编辑（保存时会自动开新草稿）。</li>
 * </ol>
 * 用 {@code draft} 字段告诉调用方当前返回的是哪一种，界面据此提示
 * 「当前为未发布的草稿」或「当前为已发布版本 vN，保存将创建新草稿」。
 */
@Data
public class FormTemplateDetailVO {

    private Long id;

    private String templateName;

    private String description;

    private String status;

    private String statusLabel;

    /** 当前返回的 schema 来自哪个版本 id */
    private Long versionId;

    /** 当前返回的 schema 来自哪个版本号 */
    private Integer versionNo;

    /** 当前返回的是否为草稿（未发布） */
    private Boolean draft;

    /** 当前版本是否已发布（草稿为 false，据此判断能否单独引用它） */
    private Boolean published;

    /** 最新已发布版本 id；从未发布为 null */
    private Long latestPublishedVersionId;

    /** 最新已发布版本号；从未发布为 null */
    private Integer latestPublishedVersionNo;

    /** 该模板是否已被申请类型引用（用于界面提示「只能停用不能删除」） */
    private Boolean referenced;

    /** 表单定义 */
    private FormSchema schema;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
