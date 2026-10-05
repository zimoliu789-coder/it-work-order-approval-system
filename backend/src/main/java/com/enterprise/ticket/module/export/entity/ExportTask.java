package com.enterprise.ticket.module.export.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出任务
 *
 * <p><b>为什么同步导出也要落这一行</b>：规范要求「超过 10000 条时异步生成文件，完成后站内消息
 * 通知下载」。异步意味着「用户请求」与「文件产出」在时间上分离，必须有持久化载体，否则
 * 用户刷新页面后就找不到自己提交的导出、也无法做权限校验与过期清理。
 * 因此<b>无论同步还是异步都写一行</b>：同步的那行当场置 {@code SUCCESS}，
 * 异步的先行 {@code PENDING} 由独立线程池回填 —— 状态机只有一条路径。
 *
 * <p>{@code storedPath} 存相对 {@code app.export.storage-root} 的相对路径，
 * 与附件同策略（NAS / 磁盘换挂载点只改配置，不刷数据）。
 */
@Data
@TableName("export_tasks")
public class ExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导出类型，取值见 {@link com.enterprise.ticket.common.constant.ExportType} */
    private String exportType;

    /** 筛选条件快照（JSON），用于复现与排查 */
    private String queryJson;

    /** 发起人 user_id */
    private Long requesterId;

    /** 任务状态，取值见 {@link com.enterprise.ticket.common.constant.ExportStatus} */
    private String status;

    /** 下载时的文件名（含 .xlsx） */
    private String fileName;

    /** 相对 app.export.storage-root 的相对路径，形如 {@code 2026/09/<uuid>.xlsx} */
    private String storedPath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 导出的数据行数（不含表头） */
    private Integer totalRows;

    /** 失败原因（仅 FAILED 时有值） */
    private String errorMessage;

    /** 文件过期时间：过期后下载返回明确错误码 */
    private LocalDateTime expireAt;

    /** 生成完成时间 */
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
