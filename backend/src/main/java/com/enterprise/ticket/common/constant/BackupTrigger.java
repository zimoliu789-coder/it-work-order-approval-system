package com.enterprise.ticket.common.constant;

/**
 * 备份触发方式（P0）
 *
 * <h2>为什么要单独落列，而不是「看 operator_id 是否为空」</h2>
 * 「今天是否已经自动备份过」这条判据是<b>补跑语义</b>的基础（见
 * {@code DatabaseBackupJob}）：定时备份每天只应执行一次，靠的就是
 * 「当天已存在一条 SCHEDULED 记录」。
 * 若用 {@code operator_id IS NULL} 反推触发方式，一旦将来出现「系统代操作」
 * 之类的第三种来源（例如升级前自动备份），这条判据就会静默失准 ——
 * 而失准的表现是「同一天备份两遍」或「当天一次都没跑」，两者都不会报错。
 */
public enum BackupTrigger {

    /** 定时自动（每天最多一次，由补跑判据保证） */
    SCHEDULED("定时自动"),

    /** 管理员手动触发（不受「当天已跑过」限制，可随时执行） */
    MANUAL("手动触发");

    private final String label;

    BackupTrigger(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static String labelOf(String value) {
        if (value == null) {
            return null;
        }
        for (BackupTrigger trigger : values()) {
            if (trigger.name().equals(value)) {
                return trigger.getLabel();
            }
        }
        return value;
    }
}
