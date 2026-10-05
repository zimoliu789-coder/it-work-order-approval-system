package com.enterprise.ticket.module.upgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线升级任务
 *
 * <p><b>为什么必须落库</b>：升级的本质是替换正在运行的进程，而替换那一瞬间进程一定会死。
 * 若状态只存在内存或随进程消失的 Redis 会话里，重启后这个任务就凭空蒸发了 ——
 * 管理员回到页面只看到一片空白，既不知道上次升级成没成功，也不知道新版本到底上去没有。
 * 因此状态必须落在<b>与升级目标同库</b>的 MySQL 表里。
 *
 * <p><b>为什么不加 {@code @TableLogic}</b>：升级记录是「每次发版一行」的量级
 * （一年几十行），加逻辑删除只会让每个查询都要多带一个条件，
 * 换不来任何实际收益（与 export_tasks 同一取舍）。
 *
 * <p>{@code stagingPath} / {@code backupPath} 存的是<b>相对</b>
 * {@code app.upgrade.storage-root} 的相对路径 —— 与附件、导出同策略：
 * 换挂载点只改配置，不刷数据。
 */
@Data
@TableName("upgrade_tasks")
public class UpgradeTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 升级业务号（时间戳 + 随机后缀），外部编排脚本据此回写结果 */
    private String taskNo;

    /** 升级包原始文件名 */
    private String packageName;

    /** 升级包大小（字节） */
    private Long packageSize;

    /** 升级包 SHA-256（十六进制） */
    private String packageSha256;

    /** 升级前版本（读 state/current.json，首次部署可能为空） */
    private String sourceVersion;

    /** 目标版本（取自包内 manifest.json） */
    private String targetVersion;

    /** 任务状态，取值见 {@link com.enterprise.ticket.common.constant.UpgradeStatus} */
    private String status;

    /** 当前步骤（过程态，供进度展示；与 status 这个结果态互补） */
    private String step;

    /** 进度百分比 0-100 */
    private Integer progress;

    /** 当前步骤说明 / 失败原因 */
    private String message;

    /** 新产物落盘目录（相对 app.upgrade.storage-root） */
    private String stagingPath;

    /** 本次升级前的备份目录（相对 app.upgrade.storage-root） */
    private String backupPath;

    /** 发起人 user_id（刻意不建外键，见 V30 设计要点 D） */
    private Long operatorId;

    /** 发起人姓名冗余快照 */
    private String operatorName;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
