package com.enterprise.ticket.module.backup.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 备份概览（P0）
 *
 * <h2>为什么把「最后一次成功备份时间」放在最显眼的位置</h2>
 * 这是整个功能<b>唯一的健康判据</b>。备份「有没有在跑」这件事，
 * 只看「开关是开的」毫无意义 —— 开关开着而 mysqldump 已经连续失败三周，
 * 界面上仍然一切正常，直到真的需要恢复那一天才发现一个可用归档都没有。
 * 与之相比「上次备份的文件多大、耗时多久」都是次要信息。
 */
@Data
public class BackupOverviewVO {

    /** 是否启用应用内自动备份（false 时页面要明确提示「不会自动跑」） */
    private boolean enabled;

    /** 每天备份的时刻（0-23） */
    private int backupHour;

    /** 保留天数 */
    private int retentionDays;

    /** 实际生效的备份目录（参数留空时为应用配置的默认值） */
    private String dir;

    /** 目录当前是否可用（可写）；false 时页面直接给出原因，而不是等备份失败 */
    private boolean dirWritable;

    /** 最后一次成功备份的时间；从未成功过时为 null（页面必须显式呈现「从未成功」） */
    private LocalDateTime lastSuccessAt;

    private String lastSuccessFile;

    private Long lastSuccessSize;

    private String lastSuccessSizeText;

    /** 最近一次失败的时间与原因（成功晚于失败时置空，避免用历史失败误导当前判断） */
    private LocalDateTime lastFailureAt;

    private String lastFailureReason;

    /** 当前是否有备份正在执行 */
    private boolean running;

    /** 今天是否已经完成过定时备份（用于解释「今天为什么还没跑」） */
    private boolean scheduledToday;

    /** 定时任务下一次可能执行的说明文案 */
    private String scheduleHint;
}
