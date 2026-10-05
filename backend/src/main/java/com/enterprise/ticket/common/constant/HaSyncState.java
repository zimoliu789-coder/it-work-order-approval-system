package com.enterprise.ticket.common.constant;

/**
 * 主备数据同步状态（，  [168] [177] [182]）
 *
 * <p>需求 [168] 行要求页面显示三项：「最后同步时间 / 数据是否一致 / 同步延迟」。
 * 本枚举是「数据是否一致」这一项的取值域，另外两项（时间 / 秒数）是数值，
 * 直接落在 {@code ha_config} 的 {@code last_sync_at} / {@code sync_delay_seconds} 列上。
 *
 * <h2>为什么「是否一致」不用布尔，而要留一个 LAGGING</h2>
 * <p>主从复制的常态是「几乎一致、偶尔落后几百毫秒」。若做成布尔，
 * 判定的阈值（多少秒算不一致）必然要写死在某个地方 —— 而复制延迟在
 * 批量写入、备份窗口等场景下天然会短暂抬高，一个过紧的阈值会让页面
 * 每分钟红几百次，最终被维护人员当作背景噪音忽略。
 * 把「落后」与「失败」分成两个状态，界面就能用<b>不同的颜色与文案</b>
 * 表达「稍微慢一点，正常」与「真的同步断了，要处理」。
 */
public enum HaSyncState {

    /** 数据一致：复制正常且延迟在容忍范围内 */
    IN_SYNC("数据一致"),

    /** 同步落后：复制仍在进行，但延迟超出容忍范围（不一定是故障） */
    LAGGING("同步落后"),

    /** 同步失败：复制中断 / 报错（需要处理） */
    FAILED("同步失败"),

    /** 未知：从未上报过同步状态（刚启用、或心跳通道尚未接通） */
    UNKNOWN("未知");

    private final String label;

    HaSyncState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否需要告警（由心跳上报服务在状态跃迁时调用，见 {@code HaAlertNotifier}） */
    public boolean needsAlert() {
        return this == FAILED;
    }

    public static HaSyncState of(String value) {
        if (value == null) {
            return null;
        }
        for (HaSyncState state : values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        HaSyncState state = of(value);
        return state == null ? value : state.getLabel();
    }
}
