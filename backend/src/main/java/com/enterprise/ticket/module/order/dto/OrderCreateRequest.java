package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 借用申请提交请求（； 简化后为**三项 + 附件**）。
 *
 * <h2> 的字段取舍（需求文档 三·）</h2>
 * <pre>
 *   借什么设备  primaryCategoryId + secondaryCategoryId + modelId  必填（一级）/ 选填（二三级）
 *   还的日期    expectedReturnDate     必填   （ 起一律必填：借用类型固定为短期借用）
 *   用途        reason                 选填   （原「借用原因」，一句话即可）
 * </pre>
 *
 * <p><b>V56 / 第 9 项再改一次</b>：原 {@code deviceId + lockToken} 两列<b>整体移除</b>。
 * 申请人不再挑选具体资产，临时锁机制随之取消 —— 提交时没有设备，自然也没有锁。
 * 设备绑定延后到 IT 交付扫码那一刻（见 {@code OrderDeliverRequest}）。
 *
 * <p><b>已砍掉的字段</b>：使用地点、备注、借用类型（表单上不再出现）。
 * 其中「使用地点 / 备注」是**删字段**，「借用类型」是**转为系统默认值**（见下）。
 *
 * <h2>{@code useType} 为什么保留入参却被注释成「不再采集」</h2>
 * <p>需求文档要求把「借用类型」从表单上拿掉，但 {@code UseType} 的语义
 * （长期领用不催还、不支持延期）被 13 个文件、约 108 处引用
 * （延期 / 到期顺延 / 导出 / 使用记录）依赖。因此这里**保留入参**，
 * 把「不传」解释为 {@code SHORT_TERM}：
 * <ul>
 *   <li>新表单不再让员工选 ⇒ 行为等价于「全员短期借用」；</li>
 *   <li>既有调用方与回归脚本显式传值时，语义**逐字不变**（传非法值仍报 {@code USE_TYPE_INVALID}）；</li>
 *   <li>存量工单的 {@code use_type} 值与导出列一并保留，**不需要数据迁移**。</li>
 * </ul>
 *
 * <p>字段名 {@code reason} 沿用存量契约（存量流程条件的 {@code borrow.reason}、导出列），
 * 只把**用户可见标签**改成「用途」—— 与 对 {@code handlerGroupName} 的处理同一原则：
 * 只改语义标签，不改标识符。
 */
@Data
public class OrderCreateRequest {

    /**
     * 要借用的<b>一级分类</b>（V56 / 第 9 项）。
     *
     * <p>申请人不再挑选具体资产：提交时只声明「借哪一类 + 什么型号」，
     * 具体设备由 IT 在交付时扫码录入资产编号，经三层校验（分类 → 型号 → 空闲）后绑定。
     * 这样做的直接好处是「审批中不再占用设备」—— 设备只在真正交到人手里才变成使用中。
     */
    @NotNull(message = "请选择要借用的设备类别")
    private Long primaryCategoryId;

    /** 二级分类，选填（非空时必须归属所选一级分类） */
    private Long secondaryCategoryId;

    /**
     * 型号 {@code device_model.id}，选填（V56 / R3）。
     *
     * <p>填了就在交付时按型号校验；留空表示「同类别内哪台都行」。
     */
    private Long modelId;

    /**
     * 借用类型：SHORT_TERM 短期借用 / LONG_TERM 长期领用。
     *
     * <p> 起表单不再采集 —— **留空即按 {@code SHORT_TERM} 处理**。
     */
    @Size(max = 16, message = "借用类型长度不合法")
    private String useType;

    /** 用途（原「借用原因」）：选填，一句话说明即可 */
    @Size(max = 500, message = "用途长度不能超过 500 个字符")
    private String reason;

    /** 归还日期（原「期望归还日期」）： 起必填 */
    @NotNull(message = "请选择归还日期")
    private LocalDate expectedReturnDate;

    /**
     * 当前所在分部（一级分部 {@code department.id}，V54 / D5.1）。
     *
     * <p><b>可以留空</b>：留空时服务端按预填逻辑自行确定（今天在出差中 ⇒ 出差目标分部，
     * 否则 ⇒ 人事主部门所属分部）。前端拿到预填值展示给员工，员工可手动改 ——
     * 改过就回传改后的值。
     *
     * <p>不接受「随便一个部门 id」：服务端会校验它必须是**启用中的一级分部**，
     * 否则报 {@code PARAM_INVALID}（子分部或停用分部会让处理小组匹配走偏）。
     */
    private Long currentDivisionId;

    /**
     * 借用表单的**自定义字段值**（V59 / 批次 3.5「表单完全放开」）。
     *
     * <h2>它装什么</h2>
     * 借用单放开表单自定义后，管理员可以在系统字段之后追加任意字段（如「是否需要电源适配器」）。
     * 这些值按字段 key 放在本 map 里，随工单一起落 {@code order_form_data} ——
     * 与自定义申请（{@code CustomOrderRequest.formData}）走同一条存储链路。
     *
     * <h2>系统字段为什么也走这里</h2>
     * 一级分类 / 二级分类 / 型号 / 分部 / 归还日期 / 用途**同时**有专用入参（见上方各字段），
     * 提交页把 schema 值映射到那些入参上；map 里只放**新增字段**的值。
     * 两处都写也不会错：服务端按 schema 校验 map，专用入参按业务规则校验，
     * 落库时 map 原样存档（详情页按 schema 回显）。
     *
     * <p>可为空：改造前提交的工单没有这个 map，存量调用方不受影响。
     */
    private Map<String, Object> formData;
}
