package com.enterprise.ticket.module.upgrade.dto.vo;

import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;

import java.time.LocalDateTime;

/**
 * 升级任务视图
 *
 * <p>刻意<b>不下发</b> {@code stagingPath} / {@code backupPath} 的原始值：
 * 它们是服务器上的绝对 / 相对文件路径，对前端展示没有价值，
 * 却会把部署目录结构暴露给浏览器（一个只有超管能看的信息，
 * 但没有任何理由让它出现在响应体里）。
 * 前端需要知道的只有「这个任务能不能回滚」——那由 {@link #rollbackable} 表达。
 */
public record UpgradeTaskVO(
        String taskNo,
        String packageName,
        Long packageSize,
        String packageSha256,
        String sourceVersion,
        String targetVersion,
        String status,
        String statusLabel,
        String step,
        Integer progress,
        String message,
        String operatorName,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        /** 当前部署配置是否允许回滚（受 app.upgrade.rollback-enabled 控制） */
        boolean rollbackEnabled,
        /** 本任务此刻是否可回滚（状态 + 配置两者都满足） */
        boolean rollbackable
) {

    public static UpgradeTaskVO of(UpgradeTask task, boolean rollbackEnabled) {
        UpgradeStatus status = UpgradeStatus.of(task.getStatus());
        boolean stateOk = status != null && status.isRollbackable();
        return new UpgradeTaskVO(
                task.getTaskNo(),
                task.getPackageName(),
                task.getPackageSize(),
                task.getPackageSha256(),
                task.getSourceVersion(),
                task.getTargetVersion(),
                task.getStatus(),
                UpgradeStatus.labelOf(task.getStatus()),
                task.getStep(),
                task.getProgress(),
                task.getMessage(),
                task.getOperatorName(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getCreatedAt(),
                rollbackEnabled,
                rollbackEnabled && stateOk
        );
    }
}
