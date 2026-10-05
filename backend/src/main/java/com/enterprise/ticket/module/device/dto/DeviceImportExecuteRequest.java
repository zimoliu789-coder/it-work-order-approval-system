package com.enterprise.ticket.module.device.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 确认导入请求
 *
 * <p>只提交预览中<b>校验通过</b>的行；后端仍会逐行重新校验后再落库
 * （不信任客户端提交，防止绕过预览直接构造请求写入非法数据）。
 */
@Data
public class DeviceImportExecuteRequest {

    /** 原始文件名（写入审计日志，便于追溯「这批设备是从哪个文件导入的」） */
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    @NotEmpty(message = "没有可导入的数据行")
    @Size(max = 500, message = "单次最多导入 500 行")
    @Valid
    private List<DeviceImportRow> rows;
}
