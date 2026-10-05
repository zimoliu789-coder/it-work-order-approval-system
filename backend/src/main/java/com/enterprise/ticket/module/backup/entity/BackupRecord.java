package com.enterprise.ticket.module.backup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库备份执行流水（P0 / V39）
 *
 * <p><b>为什么必须落库</b>：备份的价值只有在「需要它的时候」才体现，
 * 而那一刻恰恰是数据库已经出问题的时候 —— 届时如果没有一条独立的表记录
 * 「最后一次成功备份是什么时候、多大」，维护人员只能去翻服务器上的目录，
 * 而目录本身可能就是不可访问的那台机器。
 * 落库后，「备份到底有没有在跑」这个问题在系统内就能回答。
 *
 * <p><b>为什么不用逻辑删除</b>：备份记录是「每天一行」的量级，
 * 保留期清理会物理删除超期行（连同归档文件），加 {@code deleted} 列只会
 * 让每次查询都多带一个条件（与 {@code upgrade_tasks} / {@code export_tasks} 同一取舍）。
 */
@Data
@TableName("backup_record")
public class BackupRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归档文件名（不含目录）；失败时为空串 */
    private String fileName;

    /** 归档绝对路径；保留清理按它删文件 */
    private String filePath;

    /** 归档字节数；0 表示「本次未产出归档」，不是「空文件」 */
    private Long fileSize;

    /** 状态：见 {@link com.enterprise.ticket.common.constant.BackupStatus} */
    private String status;

    /** 触发方式：见 {@link com.enterprise.ticket.common.constant.BackupTrigger} */
    private String triggerType;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间；进行中为 null */
    private LocalDateTime finishedAt;

    /** 耗时毫秒 */
    private Long durationMs;

    /** 失败原因（截断到前 8 行）；成功为 null */
    private String errorMessage;

    /** 手动触发的操作人；定时为 null */
    private Long operatorId;

    private LocalDateTime createdAt;
}
