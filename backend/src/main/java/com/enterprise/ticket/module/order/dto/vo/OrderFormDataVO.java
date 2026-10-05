package com.enterprise.ticket.module.order.dto.vo;

import com.enterprise.ticket.common.form.FormSchema;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工单自定义表单数据视图
 *
 * <h2>为什么 schema 与 data 一起返回</h2>
 * <p>详情页要展示的是一张「有标签、有值」的表。若只返回 data（{@code {purchaseName: "笔记本电脑"}}），
 * 前端还需另调接口取字段定义才能渲染出「采购物品：笔记本电脑」——
 * 而字段定义在 {@code form_template:view} 权限后面，普通员工根本无权访问。
 * 两者一起返回，详情页一次请求即可完整渲染。
 *
 * <p>返回的 schema 是<b>这笔工单当初用的那一版</b>（由 {@code order_form_data.form_template_version_id}
 * 决定），不是申请类型当前绑定的版本 —— 这正是快照语义的体现。
 */
@Data
public class OrderFormDataVO {

    private Long orderId;

    private Long applyTypeId;

    /** 申请类型名称 */
    private String applyTypeName;

    private Long formTemplateVersionId;

    private Integer formTemplateVersionNo;

    /** 表单定义（用于渲染标签与只读控件） */
    private FormSchema schema;

    /** 用户填写的值：字段 key → 值 */
    private Map<String, Object> data;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
