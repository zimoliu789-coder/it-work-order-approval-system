package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 型号下拉项（R3）。
 *
 * <p>只下发启用中的型号，供「借用申请」与「设备台账新增/编辑」两处下拉使用。
 * 刻意只带 id / 名称 / 品牌：下拉只需要这些，带状态与设备数会让前端误以为
 * 这些字段在提交时有用（提交只回传 id）。
 */
@Data
public class DeviceModelOptionVO {

    private Long id;

    private String modelName;

    private String brand;
}
