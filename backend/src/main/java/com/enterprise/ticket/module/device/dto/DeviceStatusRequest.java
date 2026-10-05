package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备状态操作请求（ / ）
 *
 * <p> 只允许两类管理员手动变更，由
 * {@link com.enterprise.ticket.common.constant.DeviceStatus#canManualTransfer} 判定：
 * <ul>
 *   <li>AVAILABLE / MAINTENANCE → SCRAPPED（报废， 属高风险操作）</li>
 *   <li>MAINTENANCE → AVAILABLE（维修完成）</li>
 * </ul>
 *
 * <p>{@code reason} 是<b>操作理由</b>，只随 {@code @AuditLog(risk = HIGH)} 的入参摘要写入操作日志，
 * <b>不写回</b>设备的 {@code remark} 字段 —— 设备备注是台账业务描述，与「本次为什么改状态」语义不同，
 * 互相覆盖会造成台账信息丢失（详见  代码评审 C2）。
 */
@Data
public class DeviceStatusRequest {

    @NotBlank(message = "目标状态不能为空")
    @Size(max = 32, message = "目标状态取值过长")
    private String targetStatus;

    /** 操作理由（选填），仅用于操作日志审计留痕，不写回设备备注 */
    @Size(max = 255, message = "操作理由长度不能超过 255 个字符")
    private String reason;
}
