package com.enterprise.ticket.common.constant;

/**
 * 设备故障记录状态（ 新增，）
 *
 * <p>故障记录与设备状态是**两个维度**：
 * <ul>
 *   <li>故障记录状态描述「这次故障处理到哪一步」；</li>
 *   <li>设备状态（{@link DeviceStatus}）描述「这台设备现在能不能用」。</li>
 * </ul>
 * 一次「维修完成」会同时推进两者：故障记录 {@code PENDING_REPAIR → REPAIRED}，
 * 设备 {@code MAINTENANCE → AVAILABLE}；一次「报废」则故障记录 → {@code SCRAPPED}，
 * 设备 → {@link DeviceStatus#SCRAPPED}（：仅 AVAILABLE / MAINTENANCE 可报废）。
 */
public enum FaultStatus {

    /** 待维修：故障已登记，设备处于 MAINTENANCE（维修中） */
    PENDING_REPAIR("待维修"),

    /** 维修完成：设备已回到 AVAILABLE（可用） */
    REPAIRED("维修完成"),

    /** 已报废：设备已进入 SCRAPPED（生命周期终结，仍可见可查历史） */
    SCRAPPED("已报废");

    private final String label;

    FaultStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FaultStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (FaultStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，用于列表与详情展示 */
    public static String labelOf(String value) {
        FaultStatus status = of(value);
        return status == null ? null : status.getLabel();
    }

    /** 是否仍为未完结状态（待维修）：只有未完结的故障记录才允许「维修完成 / 报废」 */
    public boolean isOpen() {
        return this == PENDING_REPAIR;
    }
}
