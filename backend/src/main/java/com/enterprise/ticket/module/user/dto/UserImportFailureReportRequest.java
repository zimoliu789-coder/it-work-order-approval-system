package com.enterprise.ticket.module.user.dto;

import com.enterprise.ticket.module.user.dto.vo.UserImportRowVO;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 员工批量导入失败明细下载请求（需求方 2026-09-18 小迭代 · ）
 *
 * <p>请求体即「确认导入」返回的 {@code failures}，前端原样回传即可。
 */
@Data
public class UserImportFailureReportRequest {

    /** 原始文件名（用于派生「xxx-失败明细.xlsx」） */
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    /** 失败行（含失败原因） */
    @NotEmpty(message = "没有失败行可导出")
    private List<UserImportRowVO> rows;
}
