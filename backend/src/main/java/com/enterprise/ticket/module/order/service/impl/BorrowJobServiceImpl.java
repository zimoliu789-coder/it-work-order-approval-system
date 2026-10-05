package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.BorrowJobService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 借用到期预警 / 自动顺延 / 超时告警 实现（ /  / ）
 *
 * <p><b>事务边界</b>：本类刻意<b>不加</b> {@code @Transactional}。
 * 每个任务可能一次处理成百上千笔工单，用一个大事务包住会长时间持有行锁并放大 undo log；
 * 而这里的每一步都用「状态前置条件的条件 UPDATE」自我保证原子性，
 * 通知又严格发生在状态推进成功之后，因此逐单自动提交是更合适的选择。
 *
 * <p><b>跨模块依赖</b>：只依赖其它模块的 Mapper（DeviceMapper / UserMapper）与
 * MessageService（统一通知出口），不依赖其它业务 Service，避免循环依赖。
 *
 * <p>三个任务的调度顺序被固定为「到期预警 → 自动顺延 → 超时告警」，原因见
 * {@link BorrowJobService} 接口注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowJobServiceImpl implements BorrowJobService {

    /** 分布式锁有效期：远大于任务正常耗时，又小于每日触发间隔 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    /** 计划结束时间统一取当日 23:59:59，与工单服务保持一致 */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OrderMapper orderMapper;
    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final SystemConfigService systemConfigService;
    private final OperationLogService operationLogService;
    private final JobLockService jobLockService;

    // ------------------------------------------------------------------
    // 一、到期预警（需求方  ）
    // ------------------------------------------------------------------

    @Override
    public JobResult warnExpiringBorrows() {
        return jobLockService.runLocked(JOB_EXPIRE_WARNING, LOCK_TTL,
                this::doWarnExpiring, () -> JobResult.skipped(JOB_EXPIRE_WARNING));
    }

    private JobResult doWarnExpiring() {
        int warningDays = Math.max(systemConfigService.borrowExpireWarningDays(), 0);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // 窗口：计划结束时间落在 [今天 00:00, 今天+N 天 23:59:59]
        List<Order> candidates = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .isNotNull(Order::getPlannedEndTime)
                .ge(Order::getPlannedEndTime, today.atStartOfDay())
                .le(Order::getPlannedEndTime, LocalDateTime.of(today.plusDays(warningDays), END_OF_DAY))
                .orderByAsc(Order::getId));
        if (candidates.isEmpty()) {
            return JobResult.of(JOB_EXPIRE_WARNING, 0, 0, "无到期在即的工单");
        }

        Map<Long, Device> devices = devicesOf(candidates);
        Map<Long, User> users = usersOf(candidates);
        Long superAdminId = resolveSuperAdminId();

        int affected = 0;
        int notified = 0;
        int dueTodayCount = 0;
        for (Order order : candidates) {
            LocalDate dueDate = order.getPlannedEndTime().toLocalDate();
            long daysUntil = ChronoUnit.DAYS.between(today, dueDate);
            boolean dueToday = daysUntil == 0;
            boolean alreadySent = dueToday
                    ? order.getDueRemindedAt() != null
                    : order.getRemindBeforeSentAt() != null;
            if (alreadySent) {
                continue;
            }

            // 幂等位写入同时充当乐观锁：并发或重复触发时只有一次能更新成功，
            // 保证「通知」严格发生在「状态已推进」之后（ 幂等要求）
            int updated = dueToday
                    ? orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                            .eq(Order::getId, order.getId())
                            .eq(Order::getStatus, OrderStatus.BORROWED.name())
                            .isNull(Order::getDueRemindedAt)
                            .set(Order::getDueRemindedAt, now))
                    : orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                            .eq(Order::getId, order.getId())
                            .eq(Order::getStatus, OrderStatus.BORROWED.name())
                            .isNull(Order::getRemindBeforeSentAt)
                            .set(Order::getRemindBeforeSentAt, now));
            if (updated == 0) {
                continue;
            }
            affected++;
            if (dueToday) {
                dueTodayCount++;
            }

            String applicantName = userName(users, order.getApplicantId());
            String deviceLabel = deviceLabel(order, devices);
            String title = dueToday ? "设备借用今日到期" : "设备借用即将到期";
            String content = dueToday
                    ? "%s 借用的设备「%s」今日到期，请及时在「我的工单」发起归还（工单 %s）。"
                    .formatted(applicantName, deviceLabel, order.getOrderNo())
                    : "%s 借用的设备「%s」将于 %s 到期（还有 %d 天），请提前安排归还（工单 %s）。"
                    .formatted(applicantName, deviceLabel, dueDate.format(DATE_FMT), daysUntil, order.getOrderNo());

            Recipients recipients = resolveRecipients(order, true, users, superAdminId);
            if (recipients.ids().isEmpty()) {
                continue;
            }
            messageService.send(recipients.ids(),
                    dueToday ? MessageType.BORROW_DUE_REMINDER : MessageType.BORROW_EXPIRE_WARNING,
                    title, content, order.getId());
            notified += recipients.ids().size();
        }

        String detail = "扫描 %d 单，发送预警 %d 单（其中到期当天 %d 单，提前预警天数=%d）"
                .formatted(candidates.size(), affected, dueTodayCount, warningDays);
        auditJob(JOB_EXPIRE_WARNING, detail, affected);
        log.info("到期预警完成：{}", detail);
        return JobResult.of(JOB_EXPIRE_WARNING, affected, notified, detail);
    }

    // ------------------------------------------------------------------
    // 二、自动顺延（需求方  ，）
    // ------------------------------------------------------------------

    @Override
    public JobResult autoExtendOverdueBorrows() {
        return jobLockService.runLocked(JOB_AUTO_EXTEND, LOCK_TTL,
                this::doAutoExtend, () -> JobResult.skipped(JOB_AUTO_EXTEND));
    }

    private JobResult doAutoExtend() {
        int maxCount = Math.max(systemConfigService.autoExtendMaxCount(), 0);
        LocalDateTime now = LocalDateTime.now();

        // 长期领用（planned_end_time 为空）天然被排除，不参与顺延与超时（需求方  ）
        List<Order> candidates = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .isNotNull(Order::getPlannedEndTime)
                .lt(Order::getPlannedEndTime, now)
                .lt(Order::getAutoExtendCount, maxCount)
                .orderByAsc(Order::getId));
        if (candidates.isEmpty()) {
            return JobResult.of(JOB_AUTO_EXTEND, 0, 0, "无到期未归还的工单");
        }

        Map<Long, Device> devices = devicesOf(candidates);
        Map<Long, User> users = usersOf(candidates);
        Long superAdminId = resolveSuperAdminId();

        int affected = 0;
        int notified = 0;
        int reachedLimit = 0;
        for (Order order : candidates) {
            int original = order.getAutoExtendCount() == null ? 0 : order.getAutoExtendCount();
            int count = original;
            LocalDateTime end = order.getPlannedEndTime();
            // 一次运行内按规范逐次顺延：结束时间每后移 1 天算一次，直到越过当前时间或达到上限。
            // 这样即使任务因故停摆数日，重启后也能一次性补齐顺延次数并进入超时判定。
            while (count < maxCount && end.isBefore(now)) {
                end = end.plusDays(1);
                count++;
            }
            if (count == original) {
                continue;
            }

            // 以 auto_extend_count（单调递增）作为乐观锁条件，避免并发/重复触发时重复顺延
            int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, OrderStatus.BORROWED.name())
                    .eq(Order::getAutoExtendCount, original)
                    .set(Order::getAutoExtendCount, count)
                    .set(Order::getPlannedEndTime, end)
                    // 顺延完成说明仍在顺延窗口内，因此明确清掉超时标记
                    // （：延期通过后同样要重算超时状态）
                    .set(Order::getBorrowTimeout, false));
            if (updated == 0) {
                continue;
            }
            affected++;
            boolean last = count >= maxCount;
            if (last) {
                reachedLimit++;
            }

            String applicantName = userName(users, order.getApplicantId());
            String deviceLabel = deviceLabel(order, devices);
            String content = last
                    ? "%s 借用的设备「%s」已到期，系统已自动顺延 %d 天（这是最后一次顺延），请立即发起归还；到期后执行人可直接收回（工单 %s）。"
                    .formatted(applicantName, deviceLabel, count, order.getOrderNo())
                    : "%s 借用的设备「%s」已到期，系统已自动顺延 %d 天，新的归还时间为 %s，请及时发起归还（工单 %s）。"
                    .formatted(applicantName, deviceLabel, count, end.toLocalDate().format(DATE_FMT),
                            order.getOrderNo());

            Recipients recipients = resolveRecipients(order, true, users, superAdminId);
            if (recipients.ids().isEmpty()) {
                continue;
            }
            messageService.send(recipients.ids(), MessageType.BORROW_AUTO_EXTEND,
                    "设备借用已自动顺延", content, order.getId());
            notified += recipients.ids().size();
        }

        String detail = "扫描 %d 单，自动顺延 %d 单（已用满 %d 次顺延的 %d 单，最大顺延次数=%d）"
                .formatted(candidates.size(), affected, maxCount, reachedLimit, maxCount);
        auditJob(JOB_AUTO_EXTEND, detail, affected);
        log.info("自动顺延完成：{}", detail);
        return JobResult.of(JOB_AUTO_EXTEND, affected, notified, detail);
    }

    // ------------------------------------------------------------------
    // 三、超时标记与告警（需求方  ，）
    // ------------------------------------------------------------------

    @Override
    public JobResult alertTimeoutBorrows() {
        return jobLockService.runLocked(JOB_TIMEOUT_ALERT, LOCK_TTL,
                this::doAlertTimeout, () -> JobResult.skipped(JOB_TIMEOUT_ALERT));
    }

    private JobResult doAlertTimeout() {
        int maxCount = Math.max(systemConfigService.autoExtendMaxCount(), 0);
        int intervalHours = Math.max(systemConfigService.timeoutAlertIntervalHours(), 1);
        LocalDateTime now = LocalDateTime.now();

        // 超时判定（ 末段）：已到期 + 顺延次数已达上限
        List<Order> candidates = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .isNotNull(Order::getPlannedEndTime)
                .lt(Order::getPlannedEndTime, now)
                .ge(Order::getAutoExtendCount, maxCount)
                .orderByAsc(Order::getId));
        if (candidates.isEmpty()) {
            return JobResult.of(JOB_TIMEOUT_ALERT, 0, 0, "无超时工单");
        }

        Map<Long, Device> devices = devicesOf(candidates);
        Map<Long, User> users = usersOf(candidates);
        Long superAdminId = resolveSuperAdminId();

        int newlyMarked = 0;
        int reAlerted = 0;
        int notified = 0;
        int fallbackToSuperAdmin = 0;
        for (Order order : candidates) {
            boolean firstMark = !Boolean.TRUE.equals(order.getBorrowTimeout());
            LocalDateTime lastAlertAt = order.getLastTimeoutAlertAt();
            // 幂等：首次标记必发；之后按 timeout_alert_interval_hours 间隔重复推送
            boolean alertDue = lastAlertAt == null || !lastAlertAt.plusHours(intervalHours).isAfter(now);
            if (!firstMark && !alertDue) {
                continue;
            }

            // last_timeout_alert_at 作为乐观锁：取旧值做条件，保证同一轮告警只发出一次
            var update = Wrappers.<Order>lambdaUpdate()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, OrderStatus.BORROWED.name())
                    .set(Order::getBorrowTimeout, true)
                    .set(Order::getLastTimeoutAlertAt, now);
            if (lastAlertAt == null) {
                update.isNull(Order::getLastTimeoutAlertAt);
            } else {
                update.eq(Order::getLastTimeoutAlertAt, lastAlertAt);
            }
            if (orderMapper.update(null, update) == 0) {
                continue;
            }
            if (firstMark) {
                newlyMarked++;
            } else {
                reAlerted++;
            }

            // 注意：超时只置标记位，不改工单主状态、不改设备状态、不自动归还（ / ）
            Recipients recipients = resolveRecipients(order, false, users, superAdminId);
            if (recipients.handlerFallback()) {
                fallbackToSuperAdmin++;
                //  / ：执行人离职无法接收消息 → 转发 super_admin，并记录转发原因
                operationLogService.record(null, "系统调度", "JOB", "TIMEOUT_ALERT_FALLBACK",
                        "超时告警转发超管：工单 %s 的实际执行人（user_id=%s）已离职或禁用，告警改发 super_admin"
                                .formatted(order.getOrderNo(), order.getActualFinalHandlerId()),
                        true, RiskLevel.NORMAL);
            }
            if (recipients.ids().isEmpty()) {
                continue;
            }
            String content = "%s 的工单 %s 已超过计划归还时间且完成 %d 次自动顺延，请联系使用人归还，"
                    .formatted(userName(users, order.getApplicantId()), order.getOrderNo(), maxCount)
                    + "或直接确认收回设备「%s」（超时后无需使用人先发起归还）。".formatted(deviceLabel(order, devices));
            messageService.send(recipients.ids(), MessageType.BORROW_TIMEOUT,
                    "设备借用已超时", content, order.getId());
            notified += recipients.ids().size();
        }

        String detail = "扫描 %d 单，新标记超时 %d 单、重复告警 %d 单（告警间隔=%d 小时，转发超管 %d 单）"
                .formatted(candidates.size(), newlyMarked, reAlerted, intervalHours, fallbackToSuperAdmin);
        auditJob(JOB_TIMEOUT_ALERT, detail, newlyMarked + reAlerted);
        log.info("超时告警完成：{}", detail);
        return JobResult.of(JOB_TIMEOUT_ALERT, newlyMarked + reAlerted, notified, detail);
    }

    @Override
    public List<JobResult> runAll() {
        // 顺序固定：预警 → 顺延 → 超时（原因见 BorrowJobService 接口注释）
        return List.of(warnExpiringBorrows(), autoExtendOverdueBorrows(), alertTimeoutBorrows());
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /** 通知接收人解析结果：接收人 id 集合 + 是否发生了「执行人离职 → 转发超管」 */
    private record Recipients(Set<Long> ids, boolean handlerFallback) {
    }

    /**
     * 解析通知接收人（ / ）
     *
     * <p>规则：
     * <ul>
     *   <li>申请人：在职可用才收；已离职时直接丢弃（其账号已禁用，收了也看不到）；</li>
     *   <li>实际执行人：在职可用则收；缺失或已离职/禁用则<b>转发 super_admin</b>，并标记 fallback
     *       以便写审计日志（「若 actual_final_handler_id 已离职，告警自动兜底转发 super_admin」）。</li>
     * </ul>
     */
    private Recipients resolveRecipients(Order order, boolean includeApplicant,
                                         Map<Long, User> users, Long superAdminId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (includeApplicant && deliverable(order.getApplicantId(), users)) {
            ids.add(order.getApplicantId());
        }
        Long handlerId = order.getActualFinalHandlerId();
        boolean handlerFallback = false;
        if (deliverable(handlerId, users)) {
            ids.add(handlerId);
        } else if (superAdminId != null) {
            ids.add(superAdminId);
            handlerFallback = true;
        } else {
            log.warn("工单 {} 无可用通知接收人：实际执行人缺失且系统无 super_admin", order.getOrderNo());
        }
        return new Recipients(ids, handlerFallback);
    }

    private boolean deliverable(Long userId, Map<Long, User> users) {
        User user = userId == null ? null : users.get(userId);
        return user != null
                && Boolean.TRUE.equals(user.getEnabled())
                && !Boolean.TRUE.equals(user.getDimission());
    }

    /** super_admin 兜底账号；不存在时返回 null，由调用方降级处理 */
    private Long resolveSuperAdminId() {
        List<User> superAdmins = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId)
                .eq(User::getRole, RoleCode.SUPER_ADMIN)
                .orderByAsc(User::getId)
                .last("LIMIT 1"));
        return superAdmins.isEmpty() ? null : superAdmins.get(0).getId();
    }

    private Map<Long, Device> devicesOf(Collection<Order> orders) {
        Set<Long> deviceIds = orders.stream().map(Order::getDeviceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, device -> device, (a, b) -> a,
                        LinkedHashMap::new));
    }

    /** 一次载入这批工单涉及的申请人 + 实际执行人，避免逐单查询造成 N+1 */
    private Map<Long, User> usersOf(Collection<Order> orders) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (Order order : orders) {
            if (order.getApplicantId() != null) {
                userIds.add(order.getApplicantId());
            }
            if (order.getActualFinalHandlerId() != null) {
                userIds.add(order.getActualFinalHandlerId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a, LinkedHashMap::new));
    }

    /** 设备展示名「设备名（资产编号）」；设备缺失时退化为 id，避免消息正文出现 null */
    private String deviceLabel(Order order, Map<Long, Device> devices) {
        Device device = devices.get(order.getDeviceId());
        if (device == null) {
            return "设备#" + order.getDeviceId();
        }
        return device.getDeviceName() + "（" + device.getAssetNo() + "）";
    }

    /** 姓名兜底显示：显示名 → 登录名 → 「用户#id」 */
    private String userName(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        if (user == null) {
            return "用户#" + userId;
        }
        return user.getDisplayName() == null ? user.getUsername() : user.getDisplayName();
    }

    /**
     * 记录任务执行日志（「所有定时任务必须幂等，记录执行日志，便于运维排查」）
     *
     * <p>复用 {@code operation_logs}（module = JOB）而不是另建任务日志表：
     * 日志表已具备时间、结果、详情与保留期清理策略，再建一张表只会扩大运维面。
     */
    private void auditJob(String jobName, String detail, int affected) {
        operationLogService.record(null, "系统调度", "JOB", jobName.toUpperCase().replace('-', '_'),
                detail + "（影响 " + affected + " 单）", true, RiskLevel.NORMAL);
    }
}
