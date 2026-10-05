package com.enterprise.ticket.module.form.dto;

import com.enterprise.ticket.common.form.FormSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存表单模板（新建草稿 / 更新草稿）请求
 *
 * <p>请求体同时携带「模板基本信息」与「完整表单定义」：设计器的「保存草稿」是一次
 * 整体覆盖式保存，而非字段级增量 —— 增量保存需要前后端各维护一份 diff，
 * 一旦某次保存丢失（网络中断）就会出现「界面显示已改、库里没改」的静默不一致。
 * 整体覆盖的代价是请求体稍大，换来的是「看到什么就是存了什么」。
 */
@Data
public class FormTemplateSaveRequest {

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称不能超过 64 个字符")
    private String templateName;

    @Size(max = 255, message = "模板说明不能超过 255 个字符")
    private String description;

    /** 表单定义（可为空：允许先建空草稿，之后再设计字段） */
    private FormSchema schema;
}
