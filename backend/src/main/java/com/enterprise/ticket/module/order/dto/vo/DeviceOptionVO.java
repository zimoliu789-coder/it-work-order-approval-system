package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

/**
 * 可选设备选项（需求方 ：借用申请页的「选择设备」下拉）
 *
 * <p>为什么不复用设备台账接口：台账查询对 super_admin/admin 开放而已，
 * 而<b>提交申请是普通 user 的核心动作</b>。若为此把台账接口放开给 user，
 * 会让普通员工看到全部资产（含已报废、维修中设备、序列号、购置日期等管理信息），
 * 违背最小权限原则。因此单独提供只含「可申请设备」的轻量选项接口。
 */
@Data
public class DeviceOptionVO {

    private Long id;

    private String deviceName;

    private String assetNo;

    private String primaryCategoryName;

    private String secondaryCategoryName;

    private String brand;

    private String model;

    private String storageLocation;

    /** 设备状态（仅 AVAILABLE 会出现在本接口；保留字段便于前端展示与调试） */
    private String status;

    private String statusLabel;
}
