package com.enterprise.ticket.module.device.dto;

import com.enterprise.ticket.module.device.dto.vo.DeviceImportRowVO;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 失败明细导出请求
 *
 * <p>把失败行（含失败原因）回传给后端生成 .xlsx：这样 Excel 的生成只有后端一处实现
 * （与导入模板同一套 POI 工具），前端无需引入额外的表格库，
 * 且失败明细与模板列完全一致，用户可直接在明细文件上修改后重新导入。
 */
@Data
public class DeviceImportFailureReportRequest {

    /** 原始文件名；用于生成「xxx-失败明细.xlsx」 */
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    @NotEmpty(message = "没有需要导出的失败行")
    @Size(max = 500, message = "单次最多导出 500 行")
    private List<DeviceImportRowVO> rows;
}
