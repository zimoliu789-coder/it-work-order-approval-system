package com.enterprise.ticket.module.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备故障记录（ 新增，）
 *
 * <p>来源有三条，都落到本表：
 * <ol>
 *   <li><b>工单内上报</b> —— 借用人在 BORROWED 状态上报，{@code orderId} 绑定该工单；</li>
 *   <li><b>台账直接登记</b> —— 管理员 / 最终处理人在设备台账页面登记，{@code orderId} 为空，
 *       设备立即 AVAILABLE → MAINTENANCE；</li>
 *   <li><b>归还登记故障</b> —— 收回环节登记「故障」时系统自动生成一条记录并关联工单，
 *       设备进入 MAINTENANCE（ / ）。</li>
 * </ol>
 *
 * <p><b>图片附件不在本阶段交付</b>：按需求方本轮确认，附件随 通用附件能力统一实现，
 * 本阶段只做「文字描述 + 发生时间 + 状态流转」。
 */
@Data
@TableName("device_fault")
public class DeviceFault {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 故障设备 device.id */
    private Long deviceId;

    /** 关联工单 orders.id（无工单直接登记时为 null） */
    private Long orderId;

    /** 上报人 user_id */
    private Long reporterId;

    /** 故障描述 */
    private String faultDescription;

    /** 故障发生时间 */
    private LocalDateTime occurredAt;

    /** 状态，取值见 {@link com.enterprise.ticket.common.constant.FaultStatus} */
    private String status;

    /** 处理人 user_id（维修完成 / 报废操作人） */
    private Long handledBy;

    /** 处理时间 */
    private LocalDateTime handledAt;

    /** 处理说明（维修结果 / 报废原因） */
    private String handleRemark;

    // ------------------------------------------------------------------
    // P2 追加：维修过程记录（三列全部可空，理由见 V40 迁移注释）
    // ------------------------------------------------------------------

    /** 实际维修人用户 id；与 handledBy（登记人）区分；外送维修时为空 */
    private Long repairerId;

    /** 维修费用（元）；DECIMAL(10,2)，不要改成 Double */
    private BigDecimal repairCost;

    /** 更换配件说明 */
    private String replacedParts;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
