package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 型号字典「从台账补齐」的执行结果（R3）。
 *
 * <p>对应 {@code device.model} 文本 → {@code device_model} 的存量整理：
 * 台账里 {@code model_id IS NULL} 的行代表「型号还没整理」，
 * 本操作把它们批量纳入字典并回填指向，让「待整理」这件事可以被一次次收敛，
 * 而不是永远挂着。
 *
 * <p>为什么不报「失败清单」：这一步只做「文本去重入字典 + 回填 id」，
 * 不判断文本是否规范（「X1 Carbon」是型号还是备注只有人知道）。
 * 因此不存在失败项 —— 唯一可能的异常是数据库写不进去，那属于系统错误，直接抛出。
 */
@Data
public class DeviceModelSyncResultVO {

    /** 本次新建的字典条目数（台账里有、字典里还没有的写法） */
    private int createdCount;

    /** 本次回填关联的设备台数 */
    private int boundCount;

    /** 仍未能归入字典的台账数（理论为 0；非 0 说明文本为空白字符） */
    private int skippedCount;
}
