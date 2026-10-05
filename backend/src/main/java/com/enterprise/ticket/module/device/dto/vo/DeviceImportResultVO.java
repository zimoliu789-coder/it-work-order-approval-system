package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 设备批量导入的确认导入结果
 *
 * <p>{@code failures} 记录「确认时再次校验未通过」的行：正常流程下应为空，
 * 有值说明预览与确认之间数据库发生了变化（例如资产编号被他人抢先录入），
 * 此时这些行不会被写入，需用户修正后重试。
 */
@Data
public class DeviceImportResultVO {

    /** 实际写入的设备数量 */
    private int importedCount;

    /** 写入失败的条数 */
    private int failedCount;

    /** 失败明细（含原始字段与失败原因，可导出为 Excel） */
    private List<DeviceImportRowVO> failures;
}
