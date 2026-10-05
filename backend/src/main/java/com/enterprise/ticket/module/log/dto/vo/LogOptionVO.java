package com.enterprise.ticket.module.log.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 操作日志筛选项（模块 / 动作的下拉选项）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogOptionVO {

    /** 编码（提交筛选时回传的值） */
    private String code;

    /** 中文名（下拉显示） */
    private String label;
}
