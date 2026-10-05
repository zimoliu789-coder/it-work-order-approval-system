package com.enterprise.ticket.module.user.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 员工批量导入执行结果（需求方 2026-09-18 小迭代 · ）
 *
 * <p>{@code failures} 可原样喂给「下载失败明细」接口，导出后修改即可重新导入。
 */
@Data
public class UserImportResultVO {

    /** 成功创建的新员工数 */
    private int importedCount;

    /** 失败行数（预校验失败 + 写入阶段失败） */
    private int failedCount;

    /** 失败明细（附失败原因） */
    private List<UserImportRowVO> failures;
}
