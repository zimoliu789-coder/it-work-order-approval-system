package com.enterprise.ticket.module.attachment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件（ 附件上传通用能力）
 *
 * <p> 要求：「数据库保存路径、文件名、文件大小、关联业务类型、关联业务ID；
 * 禁止将大文件存入 MySQL」——因此本实体只保存**元数据**，文件本体落 NAS/本地磁盘。
 *
 * <p><b>为什么存相对路径</b>：{@code stored_path} 相对 {@code app.attachment.storage-root}。
 * 若存绝对路径，NAS 换挂载点或迁移磁盘就要全表刷数据；相对路径只改一处配置即可。
 *
 * <p><b>落盘文件名与展示文件名分离</b>：磁盘上用「日期目录 + UUID + 扩展名」避免重名覆盖与
 * 中文/特殊字符问题；{@code file_name} 保留用户原始文件名，仅用于展示与下载时的
 * {@code Content-Disposition}。这样既避免了路径穿越（文件名不参与路径拼接），
 * 又保住了「用户看到自己命名的文件」这一体验。
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联业务类型，取值见 {@link com.enterprise.ticket.common.constant.AttachmentBizType} */
    private String bizType;

    /** 关联业务主键：工单 orders.id 或故障记录 device_fault.id（多态，故不建外键） */
    private Long bizId;

    /** 原始文件名（仅用于展示与下载命名，不参与磁盘路径拼接） */
    private String fileName;

    /** 相对 app.attachment.storage-root 的相对路径，形如 {@code 2026/09/<uuid>.png} */
    private String storedPath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 上传人 user_id */
    private Long uploaderId;

    /** 软删除标记：0 正常 / 1 已删除（全局 logic-delete 配置，查询自动过滤） */
    @TableLogic
    private Boolean deleted;

    /**
     * 逻辑删除时间
     *
     * <p>保留期清理（{@code attachment_retention_days}）判定的基准时刻 ——
     * 「删除后再保留 N 天」的 N 从这一刻算起，而不是从 {@link #createdAt}（上传时间）算起：
     * 上传时间与删除时间之间可能隔着任意长的时间，用上传时间会让
     * 「昨天刚删、但上传于半年前」的附件立刻被物理清掉，保留期失去意义。
     *
     * <p>MyBatis-Plus 的 {@code @TableLogic} 只写 {@link #deleted}，不认识本列，
     * 因此它由 {@code AttachmentServiceImpl#delete} 显式赋值。
     */
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;
}
