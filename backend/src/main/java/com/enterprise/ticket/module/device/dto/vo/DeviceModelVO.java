package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 设备型号字典列表项（R3） */
@Data
public class DeviceModelVO {

    private Long id;

    private String modelName;

    private String brand;

    private String status;

    private String statusLabel;

    /**
     * 当前关联该型号的设备数量（含各状态）。
     *
     * <p>停用前需要知道「有多少台设备在用这个型号」—— 停用不会拆关联，
     * 但这个数字决定了停用是否会让一批设备的型号在下拉里「查不到」。
     */
    private Long deviceCount;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
