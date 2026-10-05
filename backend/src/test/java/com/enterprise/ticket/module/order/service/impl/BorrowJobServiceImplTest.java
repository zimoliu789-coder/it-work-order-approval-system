package com.enterprise.ticket.module.order.service.impl;

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
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 借用到期预警 / 自动顺延 / 超时告警 定时任务单元测试
 * （规范 §16.3 / §16.5 / §27，需求方 Phase 5 需求 2 / 3 / 4）
 *
 * <p>本类不验证「SQL 文本」，而验证三条<b>业务不变量</b>——它们一旦被破坏，
 * 后果是重复骚扰用户或静默漏发通知，属于线上事故而非体验问题：
 * <ol>
 *   <li><b>幂等</b>：同一工单同一天只发一次预警、只顺延一次、告警按间隔去重；</li>
 *   <li><b>「通知」必须晚于「状态真正推进」</b>：条件 UPDATE 返回 0 行时绝不发消息；</li>
 *   <li><b>兜底</b>：执行人离职时告警转发 super_admin 并留审计，绝不发给已离职账号。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BorrowJobServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long SUPER_ADMIN_ID = 1L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, Device.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private JobLockService jobLockService;

    @InjectMocks
    private BorrowJobServiceImpl service;

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    /** 让分布式锁「直接放行」：把 runLocked 的动作体取出来当场执行 */
    private void lockRunsAction() {
        when(jobLockService.runLocked(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    }

    private User user(Long id, String role, boolean enabled, boolean dimission) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setDimission(dimission);
        return user;
    }

    private Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo("IT-2026-0001");
        device.setStatus("IN_USE");
        return device;
    }

    private Order order(String status, LocalDateTime plannedEnd, Integer autoExtendCount,
                        Boolean borrowTimeout, LocalDateTime dueRemindedAt,
                        LocalDateTime remindBeforeSentAt, LocalDateTime lastTimeoutAlertAt) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(APPLICANT_ID);
        order.setActualFinalHandlerId(HANDLER_ID);
        order.setStatus(status);
        order.setPlannedEndTime(plannedEnd);
        order.setAutoExtendCount(autoExtendCount);
        order.setBorrowTimeout(borrowTimeout);
        order.setDueRemindedAt(dueRemindedAt);
        order.setRemindBeforeSentAt(remindBeforeSentAt);
        order.setLastTimeoutAlertAt(lastTimeoutAlertAt);
        return order;
    }

    /** 申请人与执行人均在职可用 */
    private void stubHealthyParticipants() {
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(APPLICANT_ID, RoleCode.USER, true, false),
                user(HANDLER_ID, RoleCode.USER, true, false)));
    }

    /** 断言消息恰好发给给定接收人集合（且类型/关联工单一致） */
    private void verifyMessage(MessageType type, Long... recipients) {
        verify(messageService).send(
                ArgumentMatchers.<Collection<Long>>argThat(ids -> ids != null
                        && ids.size() == recipients.length
                        && Arrays.stream(recipients).allMatch(ids::contains)),
                eq(type), any(), any(), eq(ORDER_ID));
    }

    private void verifyNeverNotified() {
        verify(messageService, never()).send(
                ArgumentMatchers.<Collection<Long>>any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 一、到期预警
    // ------------------------------------------------------------------

    @Test
    @DisplayName("到期预警：到期当天 → 申请人 + 执行人各收到一条「到期提醒」")
    void warnExpiring_dueToday_notifiesBothParties() {
        lockRunsAction();
        when(systemConfigService.borrowExpireWarningDays()).thenReturn(1);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDate.now().atTime(23, 59, 59),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        JobResult result = service.warnExpiringBorrows();

        assertEquals(BorrowJobService.JOB_EXPIRE_WARNING, result.jobName());
        assertEquals(1, result.affected());
        verifyMessage(MessageType.BORROW_DUE_REMINDER, APPLICANT_ID, HANDLER_ID);
    }

    @Test
    @DisplayName("到期预警：到期前 N 天 → 使用「即将到期」类型（与到期当天提醒区分）")
    void warnExpiring_upcomingWithinWindow_usesExpireWarning() {
        lockRunsAction();
        when(systemConfigService.borrowExpireWarningDays()).thenReturn(1);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDate.now().plusDays(1).atTime(23, 59, 59),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        JobResult result = service.warnExpiringBorrows();

        assertEquals(1, result.affected());
        verifyMessage(MessageType.BORROW_EXPIRE_WARNING, APPLICANT_ID, HANDLER_ID);
    }

    @Test
    @DisplayName("到期预警：当日提醒已发送过 → 不再重复发送、不再更新（幂等）")
    void warnExpiring_whenAlreadyReminded_isIdempotent() {
        lockRunsAction();
        when(systemConfigService.borrowExpireWarningDays()).thenReturn(1);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDate.now().atTime(23, 59, 59),
                        0, false, LocalDateTime.now(), null, null)));

        JobResult result = service.warnExpiringBorrows();

        assertEquals(0, result.affected());
        verify(orderMapper, never()).update(any(), any());
        verifyNeverNotified();
    }

    @Test
    @DisplayName("到期预警：条件 UPDATE 0 行（幂等位已被并发写入）→ 不发送任何通知")
    void warnExpiring_whenConditionalUpdateZeroRows_doesNotNotify() {
        lockRunsAction();
        when(systemConfigService.borrowExpireWarningDays()).thenReturn(1);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDate.now().atTime(23, 59, 59),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(0);

        service.warnExpiringBorrows();

        verifyNeverNotified();
    }

    // ------------------------------------------------------------------
    // 二、自动顺延
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自动顺延：到期未归还 → 结束时间后移 1 天并通知双方")
    void autoExtend_pushesEndTimeForwardAndNotifies() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusHours(2),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        JobResult result = service.autoExtendOverdueBorrows();

        assertEquals(1, result.affected());
        verifyMessage(MessageType.BORROW_AUTO_EXTEND, APPLICANT_ID, HANDLER_ID);
    }

    @Test
    @DisplayName("自动顺延：严重逾期 → 一次运行补齐到上限，并明确提示「最后一次顺延」")
    void autoExtend_severelyOverdue_catchesUpToMaxAndMarksLast() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusDays(5),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        service.autoExtendOverdueBorrows();

        verify(messageService).send(
                ArgumentMatchers.<Collection<Long>>any(),
                eq(MessageType.BORROW_AUTO_EXTEND),
                eq("设备借用已自动顺延"),
                contains("最后一次顺延"),
                eq(ORDER_ID));
    }

    @Test
    @DisplayName("自动顺延：条件 UPDATE 0 行（并发已顺延）→ 不发通知")
    void autoExtend_whenUpdateZeroRows_doesNotNotify() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusHours(2),
                        0, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(0);

        service.autoExtendOverdueBorrows();

        verifyNeverNotified();
    }

    // ------------------------------------------------------------------
    // 三、超时标记与告警
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超时告警：首次判定超时 → 置标记位并只通知实际执行人（不含申请人）")
    void alertTimeout_firstTime_setsFlagAndNotifiesHandlerOnly() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(systemConfigService.timeoutAlertIntervalHours()).thenReturn(24);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusDays(1),
                        2, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        JobResult result = service.alertTimeoutBorrows();

        assertEquals(1, result.affected());
        verifyMessage(MessageType.BORROW_TIMEOUT, HANDLER_ID);
    }

    @Test
    @DisplayName("超时告警：已标记且未到重复推送间隔 → 跳过（幂等）")
    void alertTimeout_alreadyMarkedWithinInterval_skips() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(systemConfigService.timeoutAlertIntervalHours()).thenReturn(24);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusDays(1),
                        2, true, null, null, LocalDateTime.now())));

        JobResult result = service.alertTimeoutBorrows();

        assertEquals(0, result.affected());
        verify(orderMapper, never()).update(any(), any());
        verifyNeverNotified();
    }

    @Test
    @DisplayName("超时告警：已超过重复推送间隔 → 再次推送")
    void alertTimeout_afterInterval_reAlerts() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(systemConfigService.timeoutAlertIntervalHours()).thenReturn(24);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusDays(1),
                        2, true, null, null, LocalDateTime.now().minusHours(25))));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        stubHealthyParticipants();

        JobResult result = service.alertTimeoutBorrows();

        assertEquals(1, result.affected());
        verifyMessage(MessageType.BORROW_TIMEOUT, HANDLER_ID);
    }

    @Test
    @DisplayName("超时告警：实际执行人已离职 → 转发 super_admin 并写审计（规范 §16.5 / §28）")
    void alertTimeout_whenHandlerDimission_forwardsToSuperAdminAndAudits() {
        lockRunsAction();
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(systemConfigService.timeoutAlertIntervalHours()).thenReturn(24);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(OrderStatus.BORROWED.name(), LocalDateTime.now().minusDays(1),
                        2, false, null, null, null)));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(APPLICANT_ID, RoleCode.USER, true, false),
                user(HANDLER_ID, RoleCode.USER, false, true)));
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(SUPER_ADMIN_ID, RoleCode.SUPER_ADMIN, true, false)));

        service.alertTimeoutBorrows();

        // 只发给超管，绝不发给已离职的执行人
        verifyMessage(MessageType.BORROW_TIMEOUT, SUPER_ADMIN_ID);
        verify(operationLogService).record(any(), anyString(), eq("JOB"), eq("TIMEOUT_ALERT_FALLBACK"),
                anyString(), eq(true), eq(RiskLevel.NORMAL));
    }

    // ------------------------------------------------------------------
    // 四、统一入口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("runAll：三个任务各自在分布式锁内执行一次")
    void runAll_executesThreeJobsUnderLock() {
        lockRunsAction();
        when(systemConfigService.borrowExpireWarningDays()).thenReturn(1);
        when(systemConfigService.autoExtendMaxCount()).thenReturn(2);
        when(systemConfigService.timeoutAlertIntervalHours()).thenReturn(24);

        List<JobResult> results = service.runAll();

        assertEquals(3, results.size());
        verify(jobLockService, times(3)).runLocked(anyString(), any(Duration.class), any(), any());
    }
}
