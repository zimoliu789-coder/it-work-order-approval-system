package com.enterprise.ticket.module.export.dto.vo;

import lombok.Data;

/**
 * 导出受理结果
 *
 * <p>同步与异步<b>共用同一个返回结构</b>，只靠 {@code mode} 区分，前端因此只需一条分支：
 * <ul>
 *   <li>{@code SYNC}——文件已生成，直接拿 {@code downloadUrl} 去下载；</li>
 *   <li>{@code ASYNC}——行数超过阈值，已受理；前端提示「生成完成后站内消息通知」，
 *       用户之后从消息中心或导出记录进入下载。</li>
 * </ul>
 *
 * <p>两条路径都会落一条导出记录并返回 {@code taskId}，因此即便同步导出时用户关掉了页面，
 * 也能在导出记录里找回文件 —— 这是「同步路径也落库」的直接收益。
 */
@Data
public class ExportResultVO {

    /** SYNC / ASYNC */
    private String mode;

    /** 导出任务 ID */
    private Long taskId;

    /** 导出类型（ExportType） */
    private String exportType;

    private String exportTypeLabel;

    /** 数据行数（不含表头） */
    private Integer totalRows;

    /** 文件名（异步时为预期文件名，生成完成后一致） */
    private String fileName;

    /** 文件大小（字节）；异步未生成完成为空 */
    private Long fileSize;

    /** 后端鉴权下载地址；异步未生成完成为空 */
    private String downloadUrl;

    /** 面向用户的提示语（同步=可直接下载；异步=已受理、稍后消息通知） */
    private String message;
}
