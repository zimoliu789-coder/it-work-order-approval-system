package com.enterprise.ticket.module.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 员工批量导入确认请求（需求方 2026-09-18 小迭代 · ）
 *
 * <p>提交的是「预览返回的行」。后端<b>不信任</b>这份数据，会重新跑一遍完整校验
 * （库内唯一性可能已被他人抢先占用），只写入校验通过的行 —— 与设备导入同一策略。
 */
@Data
public class UserImportExecuteRequest {

    /** 原始文件名（仅用于审计留痕） */
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    /** 待导入的行（通常为预览返回的全部行；后端会过滤掉未通过校验的行） */
    @NotEmpty(message = "没有可导入的数据行")
    private List<UserImportRow> rows;
}
