package com.enterprise.ticket.module.ops.service;

import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.ops.dto.BackupAlertRequest;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 备份作业结果处理（：数据备份与恢复）
 *
 * <h2>为什么备份结果要回到应用内部</h2>
 * <p>备份脚本跑在独立容器里（容器内 cron 每日 02:00），它无法直接调用应用的消息服务。
 * 若只把结果写进容器日志，就等于「失败静默」—— 而备份失败恰恰是那种
 * 「平时没人看、发现时已经无法挽回」的问题：等到需要恢复数据那天才发现备份是空的。
 * 因此把结果回调进应用，由应用向超管发站内消息，让失败出现在用户每天都会看的界面里。
 *
 * <h2>成功为什么只记审计、不发消息</h2>
 * <p>每日成功消息会在 30 天内堆出 30 条无人阅读的噪音，最终让「备份告警」这个标签
 * 被用户主动忽略 —— 那时真正的失败告警也会一起被忽略。成功进入操作日志（可检索、可按天回溯），
 * 失败才推消息，是「信号不被噪音淹没」的必要取舍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupAlertService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String STATUS_SUCCESS = "SUCCESS";

    private final UserMapper userMapper;
    private final MessageService messageService;
    private final OperationLogService operationLogService;

    /**
     * 处理一次备份结果上报
     *
     * @return 实际收到失败告警的超管数量；成功或无人可通知时返回 0
     */
    public int report(BackupAlertRequest request) {
        boolean success = STATUS_SUCCESS.equalsIgnoreCase(trim(request.getStatus()));
        String fileName = trim(request.getFileName());
        String host = trim(request.getHost());
        String error = trim(request.getError());

        recordAudit(success, fileName, host, error, request.getSizeBytes());

        if (success) {
            log.info("备份作业上报成功：file={} size={} host={}", fileName, request.getSizeBytes(), host);
            return 0;
        }

        List<Long> recipients = userMapper.selectSuperAdminIds();
        if (recipients.isEmpty()) {
            // 不能静默：日志里必须留痕，否则「没人可通知」会与「没触发告警」混为一谈
            log.warn("备份作业失败，但系统中没有可用的超级管理员接收人：file={} error={}", fileName, error);
            return 0;
        }
        messageService.send(recipients, MessageType.OPS_BACKUP_ALERT,
                "备份作业失败", buildFailureContent(fileName, host, error, request), null);
        log.warn("备份作业失败告警已推送 {} 位超管：file={} error={}", recipients.size(), fileName, error);
        return recipients.size();
    }

    private String buildFailureContent(String fileName, String host, String error, BackupAlertRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("数据库备份作业执行失败，请立即排查。\n");
        builder.append("发生时间：").append(LocalDateTime.now().format(FORMATTER)).append('\n');
        builder.append("执行主机：").append(StringUtils.hasText(host) ? host : "未上报").append('\n');
        builder.append("备份文件：").append(StringUtils.hasText(fileName) ? fileName : "未生成").append('\n');
        if (request.getSizeBytes() != null) {
            builder.append("文件大小：").append(request.getSizeBytes()).append(" 字节\n");
        }
        builder.append("失败原因：").append(StringUtils.hasText(error) ? error : "未捕获到明确原因，请查看容器日志").append('\n');
        if (request.getRetentionDays() != null) {
            builder.append("保留策略：").append(request.getRetentionDays()).append(" 天\n");
        }
        builder.append("处理建议：查看备份容器日志（docker compose logs backup），"
                + "修复后手动执行一次 deploy/scripts/backup.sh 并在备份目录确认产物存在，"
                + "切勿等到需要恢复数据时才发现备份不可用。");
        return builder.toString();
    }

    /**
     * 无论成功失败都留一条审计。
     *
     * <p>操作人记为 null（系统作业）：这是「系统自身」的行为，若挂到某个用户头上，
     * 会让审计日志出现「某人执行了备份」这种不存在的事实。
     */
    private void recordAudit(boolean success, String fileName, String host, String error, Long sizeBytes) {
        StringBuilder details = new StringBuilder();
        details.append("备份结果上报").append(" | file=").append(StringUtils.hasText(fileName) ? fileName : "-");
        details.append(" | host=").append(StringUtils.hasText(host) ? host : "-");
        if (sizeBytes != null) {
            details.append(" | size=").append(sizeBytes);
        }
        if (!success) {
            details.append(" | error=").append(StringUtils.hasText(error) ? error : "未捕获");
        }
        try {
            operationLogService.record(null, null, "OPS", "BACKUP_ALERT_REPORT",
                    details.toString(), success, RiskLevel.NORMAL);
        } catch (Exception e) {
            // 审计写失败不能连带把告警也吞掉：告警是「救人」的，审计是「取证」的
            log.warn("写入备份上报审计失败：{}", e.getMessage());
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
