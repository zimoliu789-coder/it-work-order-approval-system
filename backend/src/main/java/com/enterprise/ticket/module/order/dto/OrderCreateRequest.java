package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 借用申请提交请求（； 简化后为**三项 + 附件**）。
 *
 * <h2> 的字段取舍（需求文档 三·）</h2>
 * <pre>
 *   借什么设备  deviceId + lockToken   必填   （先「锁定设备」再提交，，两者一体）
 *   还的日期    expectedReturnDate     必填   （ 起一律必填：借用类型固定为短期借用）
 *   用途        reason                 选填   （原「借用原因」，一句话即可）
 * </pre>
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

    @NotNull(message = "请选择要借用的设备")
    private Long deviceId;

    /** 设备临时锁令牌：由「锁定设备」接口下发，提交时原样回传 */
    @NotBlank(message = "设备临时锁已失效，请返回重新选择设备")
    @Size(max = 64, message = "临时锁令牌长度不合法")
    private String lockToken;

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
}
