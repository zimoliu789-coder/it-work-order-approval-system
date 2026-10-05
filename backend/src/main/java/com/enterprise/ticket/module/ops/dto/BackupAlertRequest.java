package com.enterprise.ticket.module.ops.dto;

import lombok.Data;

/**
 * 备份作业结果上报（：备份必须可观测，失败不得静默）
 *
 * <p>由备份容器内的脚本 POST 上来。字段刻意保持宽松（不启用 {@code @NotBlank} 这类强校验）：
 * 告警通道的首要目标是「把消息送到」，脚本在异常路径上可能拿不到完整上下文
 * （例如 mysqldump 直接段错误、容器 OOM 被杀），此时少一个字段也应该照样发出告警，
 * 而不是因为参数校验不通过被拒绝 —— 那等于把「不完整告警」变成「没有告警」。
 */
@Data
public class BackupAlertRequest {

    /** 运行结果：SUCCESS / FAILED */
    private String status;

    /** 备份文件名（含时间戳），便于运维直接去备份目录定位 */
    private String fileName;

    /** 备份文件字节数；校验通过时才有值 */
    private Long sizeBytes;

    /** 失败原因（脚本捕获到的最后一段错误输出，不要求完整堆栈） */
    private String error;

    /** 执行备份的主机名（群晖 / 服务器），多结点部署时用于区分来源 */
    private String host;

    /** 保留策略天数，便于在告警正文里直接说明「清理策略是否生效」 */
    private Integer retentionDays;
}
