package com.enterprise.ticket.common.constant;

/**
 * 设备故障记录状态（ 新增， 扩展为处理流程）
 *
 * <p>故障记录与设备状态是**两个维度**：
 * <ul>
 *   <li>故障记录状态描述「这次故障处理到哪一步」；</li>
 *   <li>设备状态（{@link DeviceStatus}）描述「这台设备现在能不能用」。</li>
 * </ul>
 *
 * <h2>V50：从「两态」扩成「处理流程」</h2>
 * <p>改造前只有 {@code 待维修 / 维修完成 / 已报废}，中间没有「谁接了这单」这一步 ——
 * 报修人提交后看到的状态一直停在「待维修」，直到有人直接把它标成完成。
 * 现实里这段是有过程的：维修人要**先接单**（否则同一台设备可能两个人同时去修），
 * 修完才知道是「修好了」还是「修不好」。
 *
 * <pre>
 *   待处理 ──接单──→ 处理中 ──修完──→ 已修复
 *                      └──修不了──→ 无法修复
 *   （任何一步也可能直接判定）──→ 已报废
 * </pre>
 *
 * <h2>为什么「无法修复」与「已报废」是两个状态</h2>
 * <p>它们回答的不是同一个问题：<b>无法修复是维修结论</b>（这单修不了），
 * <b>已报废是资产决策</b>（这台设备从此退出台账）。一次「修不了」未必当场报废 ——
 * 设备可能先搁置、等采购决定，也可能走调拨。把两者合成一个，等于强迫维修人在
 * 「报修修不好」的时候顺手替资产管理员做报废决定。
 *
 * <h2>为什么状态名沿用旧枚举值</h2>
 * <p>{@code PENDING_REPAIR} / {@code REPAIRED} / {@code SCRAPPED} 是**已落库的字符串**，
 * 改名等于写一条数据迁移去扫历史行，而收益只是名字好看一点。
 * 因此这里只改**中文标签**（用户看到的那一层），枚举名与库中取值一字不动。
 */
public enum FaultStatus {

    /** 待处理：故障已登记、已通知维修人，等待有人接单 */
    PENDING_REPAIR("待处理"),

    /** 处理中：已有维修人接单，正在处理 */
    IN_PROGRESS("处理中"),

    /** 已修复：设备已回到 AVAILABLE（可用） */
    REPAIRED("已修复"),

    /**
     * 无法修复：维修结论是「修不了」，但**尚未决定报废**。
     *
     * <p>设备状态保持不变（仍为 MAINTENANCE），由资产管理员另行决定报废或调拨。
     */
    UNREPAIRABLE("无法修复"),

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

    /**
     * 是否仍为未完结状态。
     *
     * <p>判据是「还在处理流程里」而不是「等于某一个状态」——
     * 加了「处理中」之后，若仍按 {@code == PENDING_REPAIR} 判，
     * 已接单的记录会变得**谁也不能再操作**（既不能标修完，也不能标无法修复），
     * 维修人只能干看着 —— 而且不会报错，只会提示「已是『处理中』，不可重复操作」。
     */
    public boolean isOpen() {
        return this == PENDING_REPAIR || this == IN_PROGRESS;
    }

    /** 是否可接单：只有还没人接的才允许接 */
    public boolean isAcceptable() {
        return this == PENDING_REPAIR;
    }
}
