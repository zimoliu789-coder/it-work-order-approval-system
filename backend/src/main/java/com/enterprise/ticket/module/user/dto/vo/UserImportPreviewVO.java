package com.enterprise.ticket.module.user.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 员工批量导入预览结果（需求方 2026-09-18 小迭代 · ）
 *
 * <p>「校验不阻断」：整体 200 返回，由 {@code successCount} / {@code failCount}
 * 与逐行 {@code valid} 表达结果，前端据此展示「成功 N 条 / 失败 M 条」并标红失败行。
 */
@Data
public class UserImportPreviewVO {

    /** 上传的原始文件名（回显 + 失败明细文件名派生） */
    private String fileName;

    /** 解析出的数据行总数（不含表头 / 空白行 / 示例行） */
    private int totalCount;

    /** 校验通过的行数 */
    private int successCount;

    /** 校验失败的行数 */
    private int failCount;

    /** 逐行结果（含失败原因） */
    private List<UserImportRowVO> rows;
}
