package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-F 并发写「认领」契约的单元测试（Phase 16 Wave 4）
 *
 * <p>本类守护的是并发压测（`.docs/_p16-w4-concurrency.sh`）抓出的两个机制，
 * 它们坏起来都**不会报错**，只会静默失效 —— 所以必须用单测钉住：
 *
 * <ol>
 *   <li><b>加锁必须早于读取</b>：{@code lockById} 若被搬到 {@code selectById} 之后，
 *       快照就已经定在加锁之前，后面怎么读都是旧数据，「串行化」整个失效而性能照旧 ——
 *       死单会原样回来。第 1 个用例用 {@link InOrder} 把顺序钉死。</li>
 *   <li><b>受影响行数必须被消费</b>：终态转移命中 0 行时抛错（而非静默成功），
 *       第 2/3 个用例分别钉住 0 行与 1 行两条路径。</li>
 *   <li><b>终态转移必须带状态前置条件</b>：无条件写会在并发下覆盖别的事务的结果，
 *       而且覆盖时返回 1 行、**不触发任何告警**。第 4 个用例把条件本身钉住 ——
 *       只测 0 行/1 行的返回是抓不到「条件被删掉」的。</li>
 * </ol>
 *
 * <p><b>为什么挑 {@code cancel} 而不是 {@code approve} 作为被测入口</b>：两者都走
 * {@code requireOrderForUpdate → … → requireOrderTransitionClaimed} 这条新链路，
 * 但 {@code approve} 需要约 28 个依赖才能驱动（见 W4-A 挂账），而 {@code cancel}
 * 只需 2 个 mock。用最小代价覆盖同一套机制；{@code approve} 的行为由并发压测脚本
 * 端到端覆盖（那里才是它真正会出事的地方）。
 */
@ExtendWith(MockitoExtension.class)
class OrderConcurrencyContractTest {

    private static final Long ORDER_ID = 100L;
    private static final Long APPLICANT_ID = 2L;

    /**
     * LambdaUpdateWrapper 需要先预热列缓存，否则 {@code Order::getId} 这类
     * lambda 在纯单元测试里解析不出列名（与 OrderReturnServiceImplTest 同因）。
     */
    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private FlowActivationService flowActivationService;

    @InjectMocks
    private OrderServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole("user");
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    /** 待审批、无设备的工单：无设备 → 撤回时跳过设备释放，把断言聚焦在工单状态机上 */
    private Order pendingOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO20260928-W4F");
        order.setApplicantId(APPLICANT_ID);
        order.setDeviceId(null);
        order.setStatus(OrderStatus.PENDING_APPROVAL.name());
        return order;
    }

    @Test
    @DisplayName("撤回：orders 行锁必须先于工单读取（顺序颠倒则快照定在加锁前，串行化静默失效）")
    void cancel_takesRowLockBeforeReadingOrder() {
        loginAs(APPLICANT_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.cancel(ORDER_ID, "不需要了");

        InOrder sequence = inOrder(orderMapper);
        sequence.verify(orderMapper).lockById(ORDER_ID);
        sequence.verify(orderMapper).selectById(ORDER_ID);
    }

    @Test
    @DisplayName("撤回：终态转移命中 0 行必须抛「状态已变化」，不得静默成功（W4-F 假成功）")
    void cancel_zeroRowsOnTransitionIsRejected() {
        loginAs(APPLICANT_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel(ORDER_ID, "不需要了"));

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, exception.getErrorCode());
        // 文案必须让用户知道「不是你不该点，而是你点的过程中状态变了」——
        // 与入口守卫（"当前工单状态为 X，不允许撤回"）要能区分开
        assertTrue(exception.getMessage().contains("状态已变化"),
                "命中 0 行的文案应说明状态已被改动：" + exception.getMessage());
        assertTrue(exception.getMessage().contains("刷新"),
                "应提示刷新后重试（否则用户只会盲目重试）：" + exception.getMessage());
    }

    @Test
    @DisplayName("撤回：终态转移命中 1 行时正常完成（对照，防止前置条件被写成恒假）")
    void cancel_oneRowOnTransitionSucceeds() {
        loginAs(APPLICANT_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        assertDoesNotThrow(() -> service.cancel(ORDER_ID, "不需要了"));
        verify(flowActivationService).cancelOpenNodes(ORDER_ID, null);
    }

    @Test
    @DisplayName("撤回：终态转移必须带状态前置条件（无条件写会静默覆盖并发结果）")
    void cancel_transitionCarriesStatusPrecondition() {
        loginAs(APPLICANT_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.cancel(ORDER_ID, "不需要了");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Order>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), captor.capture());

        String segment = captor.getValue().getSqlSegment();
        assertTrue(segment.contains("status"),
                "WHERE 里必须出现 status 条件，否则并发下会覆盖别人的终态转移：" + segment);
        assertTrue(captor.getValue().getSqlSet().contains("actual_final_handler_id"),
                "撤回必须一并清空执行人：" + captor.getValue().getSqlSet());
    }
}
