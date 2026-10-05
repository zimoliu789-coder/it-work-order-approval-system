package com.enterprise.ticket.module.backup.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 备份记录列表项（P0）
 *
 * <p>{@code sizeText} / {@code durationText} 由服务端格式化而不是让前端算：
 * 「1.2 GB」这类展示口径（保留几位、用 KB 还是 MB）应当全站一致，
 * 散在前端每个用到的页面里必然会漂移。
 */
@Data
public class BackupRecordVO {

    private Long id;

    private String fileName;

    /** 原始字节数；0 表示本次未产出归档 */
    private Long fileSize;

    /** 人类可读大小，例如 1.2 MB；失败为 "-" */
    private String sizeText;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    private String statusLabel;

    /** SCHEDULED / MANUAL */
    private String triggerType;

    private String triggerLabel;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    /** 人类可读耗时，例如 3.4 s */
    private String durationText;

    /** 失败原因（已截断）；成功为 null */
    private String errorMessage;

    /** 手动触发的操作人姓名；定时为 null */
    private String operatorName;
}
