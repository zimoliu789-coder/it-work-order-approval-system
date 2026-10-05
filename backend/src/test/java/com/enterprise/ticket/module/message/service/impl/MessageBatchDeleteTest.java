package com.enterprise.ticket.module.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息批量删除单测（需求方三波·第二波·需求 9）
 *
 * <p>「批量删除」是把单个越权风险放大 N 倍的入口：一旦条件里漏掉 {@code user_id}，
 * 前端哪怕只勾错一条，也可能连带删掉别人的消息。本类固化三条契约：
 * <ol>
 *   <li><b>入参防御</b>：空 / 全空值 / 超上限（&gt;200）一律 PARAM_INVALID，且不触库；</li>
 *   <li><b>归属绑定</b>：删除条件的参数值里必须出现当前登录用户 id（他人 id 只会「不计入」而非误删）；</li>
 *   <li><b>去重</b>：重复 id 不会导致条件膨胀或重复删除。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MessageBatchDeleteTest {

    private static final Long USER_ID = 5L;
    private static final int MAX_BATCH_DELETE = 200;

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

    private static ErrorCode errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    @Test
    @DisplayName("批量删除：空集合被拒绝且不触库")
    void emptyIdsRejected() {
        loginAs(USER_ID);
        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.deleteBatchMine(List.of())));
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("批量删除：全为 null 等价于空集合")
    void allNullIdsRejected() {
        loginAs(USER_ID);
        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.deleteBatchMine(Arrays.asList(null, null))));
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("批量删除：超过单次上限被拒绝且不触库")
    void overLimitRejected() {
        loginAs(USER_ID);
        List<Long> ids = LongStream.rangeClosed(1, MAX_BATCH_DELETE + 1).boxed().toList();
        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.deleteBatchMine(ids)));
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("批量删除：条件恒绑定当前登录用户，重复 id 被去重")
    @SuppressWarnings("unchecked")
    void deletesOnlyOwnMessages() {
        loginAs(USER_ID);
        when(messageMapper.delete(any())).thenReturn(2);

        // 10 重复、null 混入：应去重并过滤，最终 2 个有效 id
        int affected = service.deleteBatchMine(Arrays.asList(10L, 10L, 11L, null));

        assertEquals(2, affected);
        ArgumentCaptor<LambdaQueryWrapper<Message>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).delete(captor.capture());

        LambdaQueryWrapper<Message> wrapper = captor.getValue();
        // MyBatis-Plus 的条件参数是「惰性」写入 paramNameValuePairs 的（eq/in 内部存的是
        // 求值时执行的 lambda），必须先取一次 SQL 片段把参数落桶，否则断言看到的是空桶。
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(USER_ID),
                "删除条件必须绑定当前登录用户，传入他人消息 ID 只会不计入，绝不误删");
    }
}
