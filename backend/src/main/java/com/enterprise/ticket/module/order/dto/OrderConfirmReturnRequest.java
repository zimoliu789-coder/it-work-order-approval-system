package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 确认收回请求（ 第二步）
 *
 * <p>由实际执行人填写：必须登记设备状态（完好 / 轻微损坏 / 故障），
 * 该值直接决定设备回到「可用」还是「维修中」（ +  设备状态机）。
 */
@Data
public class OrderConfirmReturnRequest {

    /** 归还检查结果：GOOD 完好 / DAMAGED 损坏 / MISSING_PARTS 缺配件 / LOST 丢失，取值见 ReturnCondition */
    @NotBlank(message = "请选择收回时的设备状态")
    private String condition;

    /** 收回备注（可选），如损坏情况说明 */
    @Size(max = 500, message = "收回备注不能超过 500 个字符")
    private String remark;
}
