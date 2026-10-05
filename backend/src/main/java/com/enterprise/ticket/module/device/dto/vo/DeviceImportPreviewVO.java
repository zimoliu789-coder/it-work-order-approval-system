package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 设备批量导入的预览结果
 *
 * <p>校验<b>不阻断</b>：即使存在失败行，也把全部解析结果返回，
 * 由用户在弹窗中看到「成功 N 条 / 失败 M 条」以及每一行的失败原因，
 * 再决定是否只导入通过的行。
 */
@Data
public class DeviceImportPreviewVO {

    /** 原始文件名（用于审计与失败明细导出） */
    private String fileName;

    /** 解析出的数据行总数（不含表头） */
    private int totalCount;

    /** 校验通过行数 */
    private int successCount;

    /** 校验失败行数 */
    private int failCount;

    /** 全部数据行（含失败行，失败行 valid=false + reason） */
    private List<DeviceImportRowVO> rows;
}
