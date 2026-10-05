package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.vo.ReconcileIssueVO;
import com.enterprise.ticket.module.device.mapper.DeviceReconcileMapper;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备工单对账任务（需求方三波·第二波·）
 *
 * <p>每天凌晨核对设备状态与工单状态是否自洽，发现矛盾即告警给全部管理员。
 * 详见 {@link DeviceReconcileMapper} 的注释（四条规则、以及「为什么只告警不自动修复」）。
 *
 * <h2>通知幂等</h2>
 * <p>告警按<b>自然日</b>去重：Redis 键 {@code ticket:job:reconcile:alert:yyyyMMdd}，
 * TTL 设为「到当天 23:59:59 的剩余秒数」。这样：
 * <ul>
 *   <li>正常的每日调度只会告警一次；</li>
 *   <li>运维手动重复触发不会刷屏；</li>
 *   <li>第二天键自然过期，哪怕未处理的问题还在，也会再提醒一次（不会「报过一次就永远沉默」）。</li>
 * </ul>
 * 注意：<b>去重只作用于「发消息」</b>，任务返回的 {@code JobResult} 始终包含真实的不一致条数 ——
 * 否则运维手动触发时会看到「0 条不一致」而误以为系统没问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceReconcileService {

    private static final String JOB_NAME = "device-order-reconcile";
    private static final String ALERT_KEY_PREFIX = "ticket:job:reconcile:alert:";

    /** 告警正文里最多列出的明细条数 */
    private static final int MAX_DETAIL_LINES = 5;

    private final DeviceReconcileMapper reconcileMapper;
    private final MessageService messageService;
    private final UserMapper userMapper;
    private final OperationLogService operationLogService;
    private final JobLockService jobLockService;
    private final StringRedisTemplate redisTemplate;

    /** 调度入口（由 {@code DeviceOrderReconcileJob} 每天凌晨触发） */
    public JobResult reconcile() {
        return jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(30),
                this::doReconcile, () -> JobResult.skipped(JOB_NAME));
    }

    private JobResult doReconcile() {
        List<ReconcileIssueVO> issues = collectIssues();
        if (issues.isEmpty()) {
            log.info("设备工单对账完成：未发现状态不一致");
            return JobResult.of(JOB_NAME, 0, 0, "设备与工单状态一致，未发现异常");
        }

        // 无论是否发送通知，都要留审计痕迹：对账结果本身对运维有价值
        operationLogService.record(null, "系统", "DEVICE", "DEVICE_RECONCILE",
                "设备工单对账发现 " + issues.size() + " 条状态不一致：" + summarize(issues, MAX_DETAIL_LINES),
                true, RiskLevel.HIGH);

        int notified = alertAdmins(issues);
        return JobResult.of(JOB_NAME, issues.size(), notified,
                "发现 " + issues.size() + " 条不一致"
                        + (notified > 0 ? "，已通知 " + notified + " 名管理员" : "，本日已告警过，跳过重复通知"));
    }

    /** 汇总四条规则的命中结果并补齐中文标签 */
    public List<ReconcileIssueVO> collectIssues() {
        List<ReconcileIssueVO> issues = new ArrayList<>();
        append(issues, reconcileMapper.inUseWithoutOrder(), "IN_USE_WITHOUT_ORDER");
        append(issues, reconcileMapper.occupyingOrderDeviceMismatch(), "OCCUPYING_ORDER_DEVICE_MISMATCH");
        append(issues, reconcileMapper.inApprovalWithoutOrder(), "IN_APPROVAL_WITHOUT_ORDER");
        append(issues, reconcileMapper.inFlightOrderReleasedDevice(), "IN_FLIGHT_ORDER_RELEASED_DEVICE");
        return issues;
    }

    private void append(List<ReconcileIssueVO> target, List<ReconcileIssueVO> rows, String issueType) {
        for (ReconcileIssueVO row : rows) {
            row.setIssueType(issueType);
            row.setIssueLabel(labelOf(issueType));
            row.setDetail(describe(row));
            target.add(row);
        }
    }

    private String labelOf(String issueType) {
        return switch (issueType) {
            case "IN_USE_WITHOUT_ORDER" -> "设备使用中但无占用工单";
            case "OCCUPYING_ORDER_DEVICE_MISMATCH" -> "工单占用设备但设备状态不是使用中";
            case "IN_APPROVAL_WITHOUT_ORDER" -> "设备审批中但无在途工单";
            case "IN_FLIGHT_ORDER_RELEASED_DEVICE" -> "工单在途但设备已回到可用";
            default -> issueType;
        };
    }

    private String describe(ReconcileIssueVO row) {
        String device = row.getDeviceName() + "（" + row.getAssetNo() + "）";
        String devicePart = "设备「" + device + "」当前状态=" + DeviceStatus.labelOf(row.getDeviceStatus());
        if (row.getOrderId() == null) {
            return devicePart + "，但不存在对应的在途工单";
        }
        return devicePart + "，而工单 " + row.getOrderNo()
                + " 状态=" + OrderStatus.labelOf(row.getOrderStatus());
    }

    /**
     * 向全部管理员发送告警消息
     *
     * @return 实际收到消息的管理员数；0 表示本日已告警过（去重命中）或无接收人
     */
    private int alertAdmins(List<ReconcileIssueVO> issues) {
        List<Long> adminIds = userMapper.selectAdminIds();
        if (adminIds.isEmpty()) {
            log.warn("设备工单对账发现异常，但系统中没有可用的管理员接收人");
            return 0;
        }
        if (!markAlertedToday()) {
            return 0;
        }
        String content = "设备工单对账发现 " + issues.size() + " 条状态不一致，请核对处理：\n"
                + summarize(issues, MAX_DETAIL_LINES);
        messageService.send(adminIds, MessageType.DEVICE_RECONCILE_ALERT,
                "设备工单对账告警", content, null);
        return adminIds.size();
    }

    /**
     * 本日告警去重标记
     *
     * @return true 表示本次由我发出告警；false 表示今天已经发过
     */
    private boolean markAlertedToday() {
        String key = ALERT_KEY_PREFIX + LocalDate.now();
        Duration ttl = Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).plusSeconds(1);
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            // Redis 不可用时放行（宁可多告警一次，也不要漏掉状态不一致）
            log.warn("对账告警去重键写入失败，本次仍发送告警：{}", e.getMessage());
            return true;
        }
    }

    private String summarize(List<ReconcileIssueVO> issues, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(issues.size(), maxLines);
        for (int i = 0; i < count; i++) {
            ReconcileIssueVO issue = issues.get(i);
            sb.append(i + 1).append(". [").append(issue.getIssueLabel()).append("] ")
                    .append(issue.getDetail());
            if (i < count - 1) {
                sb.append("\n");
            }
        }
        if (issues.size() > count) {
            sb.append("\n…… 另有 ").append(issues.size() - count).append(" 条，详见「操作日志」中的对账记录");
        }
        return sb.toString();
    }
}
