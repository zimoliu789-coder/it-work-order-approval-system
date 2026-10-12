package com.enterprise.ticket.module.applytype.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统内置申请类型配置请求（V48）
 *
 * <h2>为什么不为内置类型复用 {@link ApplyTypeSaveRequest}</h2>
 * 那个请求体承担的是「自定义类型」的完整定义：类型编码、图标、排序号、
 * <b>表单模板版本</b>、提交权限…… 内置类型这五项**全都不该被改** ——
 * 编码是 {@code OrderType} 的取值（改了就对不上订单类型）、表单由代码提供（没有模板版本）。
 * 复用它意味着服务层要写一串「内置就忽略这个字段」的分支，
 * 而每一处漏写的分支都会在下次改代码时变成一个静默的越权口子。
 *
 * <p>因此给它一个**只含可改字段**的请求体：能改什么，看这个类就够了。
 *
 * <h2>V50：多了两项「处理流程」字段</h2>
 * 故障报修没有审批流，但有处理流（通知给谁 → 接单 → 修完）。
 * 两项字段只在 {@code hasHandler} 为 true 的类型上被接受 ——
 * 对借用设备传它们会报错而不是被忽略：静默忽略会让调用方以为配置生效了。
 */
@Data
public class BuiltInConfigRequest {

    /**
     * 表单说明（卡片上的那行小字）。
     *
     * <p>可空。空串与 null 都表示「未填写说明」——服务层统一写成 null，
     * 避免库里同时存在 {@code ''} 与 {@code NULL} 两种「空」。
     */
    @Size(max = 255, message = "说明不能超过 255 个字")
    private String description;

    /**
     * 要绑定的审批流程版本 id（必须是**已发布**版本）。
     *
     * <p>{@code null} = 解绑，回到该内置类型的默认审批方式
     * （借用设备回到「按部门逐级 / 系统预置流程」；故障报修本就没有审批环节，
     * 传值会被直接拒绝）。
     *
     * <p>流程模板本身的创建 / 编辑 / 发布仍走流程模块的接口 ——
     * 本请求只负责「把这个已发布的版本绑上去」，与自定义类型的做法一致，
     * 不在这里重复造一套流程管理。
     */
    private Long approvalFlowVersionId;

    // ------------------------------------------------------------------
    // 处理流程（V50，仅故障报修）
    // ------------------------------------------------------------------

    /**
     * 报修通知方式：{@code DEPARTMENT} / {@code ROLE} / {@code USER}。
     *
     * <p>{@code null} = 本次不改动「处理流程」——「借用设备」的保存请求里
     * 永远不会出现这两项，而同一个接口恰好也要服务它，因此「没传 = 不动」是必需的语义。
     */
    @Size(max = 32, message = "通知方式取值过长")
    private String assigneeType;

    /**
     * 通知目标值：部门 id / 角色码 / 用户 id，含义随 {@link #assigneeType} 而变。
     *
     * <p>取值校验在 {@code FaultRepairHandlerConfigService#save} 里做，
     * 因为「什么样的值是合法的」取决于类型（部门 id 与角色码的合法性判据完全不同）。
     * 这里只限长度，避免把超长垃圾写进配置表。
     */
    @Size(max = 64, message = "通知目标取值过长")
    private String assigneeValue;
}
