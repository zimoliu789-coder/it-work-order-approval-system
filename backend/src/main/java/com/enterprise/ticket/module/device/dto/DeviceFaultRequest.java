package com.enterprise.ticket.module.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备故障上报请求
 *
 * <p>两条上报路径共用本 DTO：
 * <ul>
 *   <li><b>工单内上报</b> —— 借用人在「我的工单」对使用中的设备上报，携带 {@code orderId}；</li>
 *   <li><b>台账直接登记</b> —— 管理员 / 最终处理人在设备台账页面登记，{@code orderId} 留空。</li>
 * </ul>
 *
 * <p>图片附件不在本阶段交付（需求方确认随 统一实现）。
 */
@Data
public class DeviceFaultRequest {

    /** 故障设备 device.id */
    @NotNull(message = "请选择故障设备")
    private Long deviceId;

    /** 关联工单 orders.id；无工单直接登记时留空 */
    private Long orderId;

    /** 故障描述（必填） */
    @NotBlank(message = "请填写故障描述")
    @Size(max = 500, message = "故障描述不能超过 500 个字符")
    private String faultDescription;

    /** 故障发生时间（必填，不允许晚于当前时间） */
    @NotNull(message = "请填写故障发生时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;

    /**
     * 当前所在分部（一级分部 {@code department.id}，V54 / D5.1）。
     *
     * <p>语义与借用单完全一致：留空由服务端预填，填了则校验「启用中的一级分部」。
     * 报修目前**不按分部派单**（维修人由「报修处理设置」决定，V50 的设计），
     * 但分部信息必须留痕 —— 「各分部报修量」与「某分部设备质量」都要靠它聚合。
     */
    private Long currentDivisionId;

    /**
     * 报修表单的**自定义字段值**（V59 / 批次 3.5「表单完全放开」）。
     *
     * <p>管理员可以在系统字段（设备 / 发生时间 / 描述 / 分部）之后追加任意字段，
     * 这些值按字段 key 放在本 map 里，随故障记录一起落
     * {@code device_fault.form_data_json}（版本指针落 {@code form_template_version_id}）。
     *
     * <p>可为空：改造前的调用方（台账页登记、归还时自动生成）不传这个字段，
     * 存量记录两列皆为 NULL，语义 = 「没有自定义字段」。
     */
    private Map<String, Object> formData;
}
