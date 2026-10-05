package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 故障处理请求（：维修完成 / 标记报废）
 *
 * <p>两个动作共用一个请求体，只有处理说明一个字段：维修完成可填维修结果，
 * 报废可填报废原因。设备状态推进规则由服务层按 校验。
 */
@Data
public class DeviceFaultHandleRequest {

    /** 处理说明（可选）：维修结果 / 报废原因 */
    @Size(max = 500, message = "处理说明不能超过 500 个字符")
    private String remark;

    // ------------------------------------------------------------------
    // P2 追加：维修过程记录（仅「维修完成」使用；报废不涉及这三项）
    // ------------------------------------------------------------------

    /**
     * 实际维修人用户 id（可选）。
     *
     * <p>与 {@code handledBy}（登记人 = 点「维修完成」的管理员）**刻意分开**：
     * 维修可能外送，实际动手的人未必有系统账号 ⇒ 允许为空。
     * 两者合成一个字段会让「谁修的都记成管理员」，维修外包统计直接失真。
     */
    private Long repairerId;

    /**
     * 维修费用（元，可选）。
     *
     * <p>用 {@code BigDecimal} 而不是 {@code Double}：费用是要累加统计的，
     * 浮点累加会出现 0.01 级误差，而维修成本恰恰是这个项目里少数几个「金额」之一。
     * 下限 0 且限制 8 位整数 / 2 位小数，与列定义 {@code DECIMAL(10,2)} 对齐 ——
     * 让超限在请求校验阶段就被拒绝，而不是等到写库时报数据截断。
     */
    @DecimalMin(value = "0", message = "维修费用不能为负数")
    @Digits(integer = 8, fraction = 2, message = "维修费用最多 8 位整数、2 位小数")
    private BigDecimal repairCost;

    /** 更换配件说明（可选） */
    @Size(max = 200, message = "更换配件说明不能超过 200 个字符")
    private String replacedParts;
}
