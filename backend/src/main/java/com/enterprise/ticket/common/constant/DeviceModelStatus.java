package com.enterprise.ticket.common.constant;

/**
 * 设备型号字典状态（R3 / 批次 3）。
 *
 * <p><b>停用 ≠ 删除</b>：停用只是「新增时下拉不再出现」，已有设备的 {@code model_id}
 * 与历史工单的型号快照都不受影响。字典行<b>永不物理删除</b> —— 删掉会让台账里
 * 指向它的 {@code model_id} 变成悬空 id，展示层只能回落成「型号已删除」，
 * 而资产管理员根本看不出那台设备原本是什么型号。
 *
 * <p>因此本枚举刻意只有两态，不提供 DELETED：要「清理」一个录错的型号，
 * 正确做法是停用后新建一条正确的（原始台账文本仍在 {@code device.model} 里可回溯）。
 */
public enum DeviceModelStatus {

    /** 启用：新增设备 / 借用申请时可选 */
    ENABLED("启用"),

    /** 停用：下拉不再出现，既有设备与历史工单保持原关联 */
    DISABLED("停用");

    private final String label;

    DeviceModelStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 中文名称（静态安全版）：非法/空值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        DeviceModelStatus status = of(value);
        return status == null ? value : status.getLabel();
    }

    /** 按名称解析，非法值返回 {@code null}（由调用方决定报错还是回落） */
    public static DeviceModelStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (DeviceModelStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }
}
