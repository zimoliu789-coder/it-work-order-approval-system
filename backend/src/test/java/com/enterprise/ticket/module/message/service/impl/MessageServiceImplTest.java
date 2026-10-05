package com.enterprise.ticket.module.message.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.message.dto.MessageQuery;
import com.enterprise.ticket.module.message.dto.vo.MessageVO;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内消息服务单元测试（规范 §24）
 *
 * <p>重点覆盖三类隐患：
 * <ul>
 *   <li><b>通知不阻断业务</b>：写库失败必须被吞掉（这是「归还成功但通知失败」不会回滚业务的根据）；</li>
 *   <li><b>越权读取</b>：已读只能操作自己的消息，且「不存在」与「不属于我」返回同一错误码以防探测；</li>
 *   <li><b>重复接收人</b>：申请人与执行人同人时不得产生两条相同消息。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    private static final Long USER_ID = 5L;
    private static final Long ORDER_ID = 100L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Message.class, Order.class);
    }

    @Mock
    private MessageMapper messageMapper;
    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private MessageServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(RoleCode.USER);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发送：接收人为 null → 静默跳过，不写库")
    void send_withNullRecipient_skips() {
        service.send((Long) null, MessageType.RETURN_REQUESTED, "标题", "正文", ORDER_ID);
        verify(messageMapper, never()).insert(any());
    }

    @Test
    @DisplayName("发送：接收人列表为空 → 静默跳过，不写库")
    void send_withEmptyRecipients_skips() {
        service.send(List.<Long>of(), MessageType.RETURN_REQUESTED, "标题", "正文", ORDER_ID);
        verify(messageMapper, never()).insert(any());
    }

    @Test
    @DisplayName("发送：同一接收人重复出现 → 只写一条（申请人与执行人同人场景）")
    void send_deduplicatesSameRecipient() {
        service.send(List.of(1L, 1L, 2L), MessageType.BORROW_TIMEOUT, "标题", "正文", ORDER_ID);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        assertEquals(List.of(1L, 2L),
                captor.getAllValues().stream().map(Message::getUserId).toList());
    }

    @Test
    @DisplayName("发送：写库异常被吞掉（通知失败不影响业务）")
    void send_whenMapperThrows_doesNotPropagate() {
        when(messageMapper.insert(any())).thenThrow(new RuntimeException("messages 表不可写"));
        assertDoesNotThrow(() ->
                service.send(List.of(1L), MessageType.BORROW_TIMEOUT, "标题", "正文", ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 未读数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未读数：未登录 → 返回 0，且不查库（登录页铃铛轮询场景）")
    void unreadCount_whenNotLoggedIn_returnsZero() {
        assertEquals(0L, service.unreadCount());
        verify(messageMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("未读数：已登录 → 统计当前账号未读")
    void unreadCount_countsCurrentUser() {
        loginAs(USER_ID);
        when(messageMapper.selectCount(any())).thenReturn(3L);
        assertEquals(3L, service.unreadCount());
    }

    // ------------------------------------------------------------------
    // 标记已读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("标记已读：成功更新自己的未读消息")
    void markRead_marksOwnUnreadMessage() {
        loginAs(USER_ID);
        when(messageMapper.update(any(), any())).thenReturn(1);

        service.markRead(ORDER_ID);

        verify(messageMapper).update(any(), any());
        verify(messageMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("标记已读：消息不属于自己 → 报「不存在或不属于当前账号」")
    void markRead_whenNotOwned_throwsNotFound() {
        loginAs(USER_ID);
        when(messageMapper.update(any(), any())).thenReturn(0);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        assertEquals(ErrorCode.MESSAGE_NOT_FOUND, errorCodeOf(() -> service.markRead(999L)));
    }

    @Test
    @DisplayName("标记已读：本就已读 → 幂等成功（0 行更新但归属校验通过）")
    void markRead_whenAlreadyRead_isIdempotent() {
        loginAs(USER_ID);
        when(messageMapper.update(any(), any())).thenReturn(0);
        when(messageMapper.selectCount(any())).thenReturn(1L);

        assertDoesNotThrow(() -> service.markRead(ORDER_ID));
    }

    @Test
    @DisplayName("标记已读：消息 id 为空 → 报「消息不存在」")
    void markRead_withNullId_throwsNotFound() {
        loginAs(USER_ID);
        assertEquals(ErrorCode.MESSAGE_NOT_FOUND, errorCodeOf(() -> service.markRead(null)));
    }

    @Test
    @DisplayName("标记已读：未登录 → 报未授权")
    void markRead_whenNotLoggedIn_throwsUnauthorized() {
        assertEquals(ErrorCode.UNAUTHORIZED, errorCodeOf(() -> service.markRead(ORDER_ID)));
    }

    @Test
    @DisplayName("全部已读：返回受影响行数")
    void markAllRead_returnsAffectedCount() {
        loginAs(USER_ID);
        when(messageMapper.update(any(), any())).thenReturn(4);
        assertEquals(4, service.markAllRead());
    }

    // ------------------------------------------------------------------
    // 我的消息分页
    // ------------------------------------------------------------------

    @Test
    @DisplayName("我的消息分页：未登录 → 报未授权")
    void pageMine_whenNotLoggedIn_throwsUnauthorized() {
        MessageQuery query = new MessageQuery();
        assertEquals(ErrorCode.UNAUTHORIZED, errorCodeOf(() -> service.pageMine(query)));
    }

    @Test
    @DisplayName("我的消息分页：附带工单编号与消息类型中文名")
    void pageMine_mapsOrderNoAndTypeLabel() {
        loginAs(USER_ID);
        Message message = new Message();
        message.setId(9L);
        message.setUserId(USER_ID);
        message.setTitle("设备借用已自动顺延");
        message.setContent("正文");
        message.setOrderId(ORDER_ID);
        message.setMessageType(MessageType.BORROW_AUTO_EXTEND.name());
        message.setIsRead(false);
        message.setCreatedAt(LocalDateTime.now());
        Page<Message> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(message));
        mockPage.setTotal(1);
        when(messageMapper.selectPage(ArgumentMatchers.<IPage<Message>>any(), any())).thenReturn(mockPage);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        PageResult<MessageVO> result = service.pageMine(new MessageQuery());

        assertEquals(1, result.getRecords().size());
        assertEquals("BO-100", result.getRecords().get(0).getOrderNo());
        assertEquals("自动顺延", result.getRecords().get(0).getMessageTypeLabel());
    }

    @Test
    @DisplayName("我的消息分页：无关联工单的消息（如导出完成通知 orderId=null）→ orderNo 为 null，不抛 NPE")
    void pageMine_messageWithoutOrder_doesNotThrow() {
        loginAs(USER_ID);
        Message message = new Message();
        message.setId(9L);
        message.setUserId(USER_ID);
        message.setTitle("导出完成");
        message.setContent("借用记录导出已完成，请前往导出记录下载");
        message.setOrderId(null);
        message.setMessageType(MessageType.EXPORT_READY.name());
        message.setIsRead(false);
        message.setCreatedAt(LocalDateTime.now());
        Page<Message> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(message));
        mockPage.setTotal(1);
        when(messageMapper.selectPage(ArgumentMatchers.<IPage<Message>>any(), any())).thenReturn(mockPage);

        // 全部消息都无关联工单 → 不应触发工单表查询（orderNoMap 直接返回 Map.of()）
        PageResult<MessageVO> result = assertDoesNotThrow(() -> service.pageMine(new MessageQuery()));

        assertEquals(1, result.getRecords().size());
        assertEquals(null, result.getRecords().get(0).getOrderId());
        assertEquals(null, result.getRecords().get(0).getOrderNo());
        verify(orderMapper, never()).selectList(any());
    }
}
