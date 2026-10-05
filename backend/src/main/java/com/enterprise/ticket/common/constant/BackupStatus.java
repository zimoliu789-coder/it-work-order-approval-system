package com.enterprise.ticket.common.constant;

/**
 * 备份记录状态（P0）
 *
 * <h2>为什么是「三段式」而不是布尔值</h2>
 * 备份的失败形态里有很大一类是<b>跑了一半</b>：mysqldump 中途报错、磁盘写满、
 * 目录不可写。若只用「有没有文件」判断成功，这些全会被当成成功
 * （部分写出的文件很可能不是 0 字节）。
 * 三段式让「进行中」与「已失败」在页面上分得开，也才能对残留的
 * {@link #RUNNING} 做僵尸回收（进程被杀时来不及落终态）。
 */
public enum BackupStatus {

    /** 进行中（进程被杀会残留在这个状态，由任务启动时的僵尸回收兜住） */
    RUNNING("进行中"),

    /** 成功（归档已产出并通过校验） */
    SUCCESS("成功"),

    /** 失败（原因见 backup_record.error_message） */
    FAILED("失败");

    private final String label;

    BackupStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static String labelOf(String value) {
        if (value == null) {
            return null;
        }
        for (BackupStatus status : values()) {
            if (status.name().equals(value)) {
                return status.getLabel();
            }
        }
        return value;
    }
}
