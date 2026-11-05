package com.enterprise.ticket.module.applytype.dto.vo;

import com.enterprise.ticket.common.form.FormSchema;
import lombok.Data;

/**
 * 内置类型（借用 / 报修）的**自定义字段**表单定义（V59 / 批次 3.5）。
 *
 * <h2>为什么不复用 {@link BuiltInConfigVO}</h2>
 * 那个 VO 是给「申请类型配置页」用的，要求 {@code apply_type:manage} 权限，
 * 且携带流程模板、通知对象等后台概念。提交页是**普通员工**在用，
 * 它只需要知道「系统字段之后还要填哪些自定义字段」，多给一样都是越权暴露。
 *
 * <h2>为什么 schema 里不含系统字段</h2>
 * 系统字段（一级分类 / 型号 / 故障设备……）的值由提交页的专属控件填写
 * （三级联动、扫码查设备不是通用渲染器能表达的）。若把它们也下发，
 * 前端渲染器会再画一遍同样的输入框，且提交时两处值未必一致 ——
 * 谁覆盖谁全看代码顺序。切分口径见 {@code SystemFields}。
 */
@Data
public class BuiltInExtraFormVO {

    /** 类型编码（BORROW / FAULT_REPORT） */
    private String typeCode;

    /**
     * 该类型当前绑定的表单版本 id；{@code null} = 尚未播种。
     *
     * <p>为 null 时 {@link #schema} 也是 null，提交页整块不渲染、提交时不带 {@code formData} ——
     * 升级未跑播种的部署照旧能提交（与后端 {@code resolveBorrowExtraFormData} 的容忍口径一致）。
     */
    private Long formTemplateVersionId;

    /** 只含自定义字段的 schema（保留分区标题等布局字段，保持原顺序） */
    private FormSchema schema;
}
