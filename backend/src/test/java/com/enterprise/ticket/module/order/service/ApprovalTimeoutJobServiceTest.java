package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OverdueApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
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
import java.time.LocalDateTime;
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
 * 审批超时提醒任务单元测试（Phase 15 · Wave 2）。
 *
 * <p>本类验证的是「超时提醒」这件事最容易出错的三处，而不是 SQL 文本：
 * <ol>
 *   <li><b>双接收人</b>：审批人要「去做」，申请人要「知道」——少发一条就等于功能没做，
 *       而申请人 = 审批人时多发一条就是骚扰；</li>
 *   <li><b>幂等前置</b>：抢占（条件 UPDATE）返回 0 行时<b>绝不能</b>发消息。
 *       这是「每天一次」能否成立的唯一保障——发消息早于抢占就是重复轰炸；</li>
 *   <li><b>两种时限口径</b>：有 deadline 用「约定审批时限」，无 deadline 回落全局阈值，
 *       两者的文案不能串（否则借用单的提醒会突然变成「约定时限」，用户看不懂）。</li>
 * </ol>
 *
 * <p>纯 Mockito，不启动 Spring / 数据库。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalTimeoutJobServiceTest {

    private static final int GLOBAL_HOURS = 24;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(OrderApprovalNode.class);
    }

    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private JobLockService jobLockService;
    /**
     * Phase 16 Wave 2 · M2：超时流程动作（加签 / 改道）的出口。
     * 本类只验证「提醒」，因此把它设为 mock —— 默认返回 false（不动流程），
     * 提醒行为与 M2 之前逐字一致；动作本身由 {@code FlowActivationServiceTest} 覆盖。
     */
    @Mock
    private FlowActivationService flowActivationService;

    @InjectMocks
    private ApprovalTimeoutJobService service;

    /** 让分布式锁「直接放行」：把动作体取出来当场执行 */
    private void lockRunsAction() {
        when(jobLockService.runLocked(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    }

    private OverdueApprovalNode node(Long nodeId, Long orderId, Long approverId, Long applicantId,
                                     LocalDateTime deadlineAt) {
        OverdueApprovalNode node = new OverdueApprovalNode();
        node.setNodeId(nodeId);
        node.setOrderId(orderId);
        node.setOrderNo("GD2026010100" + nodeId);
        node.setApproverId(approverId);
        node.setApplicantId(applicantId);
        node.setApplicantName("张三");
        node.setDeviceName("ThinkPad X1");
        node.setStepOrder(1);
        node.setSubmittedAt(LocalDateTime.now().minusDays(3));
        node.setDeadlineAt(deadlineAt);
        return node;
    }

    @Test
    @DisplayName("超时节点：当前审批人与申请人各收到一条提醒（文案不同）")
    void bothApproverAndApplicantNotified() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(eq(GLOBAL_HOURS), eq(24), eq(200)))
                .thenReturn(List.of(node(1L, 100L, 7L, 2L, LocalDateTime.now().minusHours(30))));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        JobResult result = service.remindOverdueApprovals();

        assertEquals(1, result.affected());
        assertEquals(1, result.notified());
        // 审批人：包含「约定审批时限」；申请人：包含「仍在等待审批人处理」
        verify(messageService, times(1)).send(eq(7L), any(), eq("审批超时提醒"),
                contains("约定审批时限"), eq(100L));
        verify(messageService, times(1)).send(eq(2L), any(), eq("你的工单审批已超时"),
                contains("仍在等待审批人处理"), eq(100L));
    }

    @Test
    @DisplayName("申请人 = 审批人时只发一条，不重复骚扰同一个人")
    void applicantSameAsApprover_singleMessage() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(node(1L, 100L, 5L, 5L, LocalDateTime.now().minusHours(30))));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        service.remindOverdueApprovals();

        verify(messageService, times(1)).send(ArgumentMatchers.<Long>any(), any(), anyString(), anyString(),
                ArgumentMatchers.<Long>any());
    }

    @Test
    @DisplayName("抢占失败（已被其他执行者提醒）时不发任何消息")
    void claimConflict_skipsSend() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(node(1L, 100L, 7L, 2L, null)));
        // 条件 UPDATE 返回 0 行 = 幂等窗口内已提醒过
        when(nodeMapper.update(any(), any())).thenReturn(0);

        JobResult result = service.remindOverdueApprovals();

        assertEquals(0, result.notified());
        verify(messageService, never()).send(ArgumentMatchers.<Long>any(), any(), anyString(), anyString(),
                ArgumentMatchers.<Long>any());
    }

    @Test
    @DisplayName("无 deadline 的节点（借用单 / 分组单）回落全局阈值文案")
    void withoutDeadline_fallsBackToGlobalThreshold() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(node(1L, 100L, 7L, 2L, null)));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        service.remindOverdueApprovals();

        verify(messageService, times(1)).send(eq(7L), any(), eq("审批超时提醒"),
                contains("已超过 24 小时未审批"), eq(100L));
    }

    @Test
    @DisplayName("无超时节点：空结果，不发消息")
    void noOverdueNodes_emptyResult() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of());

        JobResult result = service.remindOverdueApprovals();

        assertEquals(0, result.affected());
        verify(messageService, never()).send(ArgumentMatchers.<Long>any(), any(), anyString(), anyString(),
                ArgumentMatchers.<Long>any());
    }

    @Test
    @DisplayName("未抢到分布式锁：整任务跳过，不扫描也不发消息")
    void lockNotAcquired_skipped() {
        when(jobLockService.runLocked(anyString(), any(Duration.class), any(), any()))
                .thenReturn(JobResult.skipped("approval-timeout-remind"));

        JobResult result = service.remindOverdueApprovals();

        assertEquals(0, result.affected());
        verify(nodeMapper, never()).findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class));
        verify(messageService, never()).send(ArgumentMatchers.<List<Long>>any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("节点无审批人：不发审批人提醒，但仍保留幂等位避免反复空转")
    void approverMissing_stillClaims() {
        lockRunsAction();
        when(systemConfigService.approvalTimeoutRemindHours()).thenReturn(GLOBAL_HOURS);
        when(nodeMapper.findOverdueNodes(any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(node(1L, 100L, null, 2L, null)));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        service.remindOverdueApprovals();

        // 只发给申请人，不发审批人
        verify(messageService, times(1)).send(eq(2L), any(), anyString(), anyString(), eq(100L));
    }
}
