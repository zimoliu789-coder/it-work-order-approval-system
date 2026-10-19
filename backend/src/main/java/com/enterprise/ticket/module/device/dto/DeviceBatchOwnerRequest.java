package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量设置设备负责人（V57 / 第 10 项）
 *
 * <p>为什么需要批量：负责人是「报修页我名下的设备」的唯一数据来源，而存量台账
 * 一千多台设备的 {@code owner_id} 全是 NULL —— 逐台点开编辑不现实，功能等于用不起来。
 *
 * <p>{@code ownerId} 允许为 {@code null}，表示<b>清空负责人</b>（设备转为「公共设备」，
 * 只能扫码报修）。这一点与「没填这个字段」是同一件事，因此不需要额外的清除标记。
 *
 * <p>只改 {@code owner_id}，其余字段一律不动 —— 单条 {@code update} 是整体替换语义，
 * 批量套用会把品牌 / 型号 / 存放位置一并抹掉。
 */
@Data
public class DeviceBatchOwnerRequest {

    @NotEmpty(message = "请选择要设置负责人的设备")
    private List<Long> ids;

    /** 负责人 user_id；传 null 表示清空（该设备转为公共设备） */
    private Long ownerId;
}
