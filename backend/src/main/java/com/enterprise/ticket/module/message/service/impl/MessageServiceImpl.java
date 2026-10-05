package com.enterprise.ticket.module.message.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.message.dto.MessageQuery;
import com.enterprise.ticket.module.message.dto.vo.MessageVO;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 站内消息实现
 *
 * <p><b>跨模块依赖约定</b>：本类为了在消息列表里附带工单编号，注入了 {@code OrderMapper}
 * 而<b>不是</b> {@code OrderService} —— 与项目既有约定一致（跨模块只依赖 Mapper，
 * 避免「工单 ↔ 消息」形成 Service 级循环依赖）。Mapper 不反向依赖 Service，因此无循环风险。
 *
 * <p><b>发送失败不影响业务</b>：{@link #send} 全部包在 try/catch 内，见接口注释中的设计理由。
 * 消息写入<b>跟随调用方事务</b>（默认传播行为）是刻意的：调用方事务回滚时消息一并回滚是正确的；
 * 而「业务提交成功、消息写失败」时异常被吞掉即可，不会制造脏数据也不会阻断已完成的业务动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    /** 单页上限，避免前端传入超大 size 拖垮消息表 */
    private static final long MAX_PAGE_SIZE = 100L;

    /** 单次批量删除上限，避免一次 DELETE ... IN (几千个) 造成长事务与锁等待 */
    private static final int MAX_BATCH_DELETE = 200;

    /**
     * 显式声明 Mapper 字段而不是复用 {@code ServiceImpl#baseMapper}。
     *
     * <p>与本项目其它 Service 的写法保持一致，同时也让纯 Mockito 单测可以不启动 Spring
     * 直接注入该依赖（{@code ServiceImpl} 的 {@code baseMapper} 由 Spring 装配，测试中难以替换）。
     */
    private final MessageMapper messageMapper;
    private final OrderMapper orderMapper;

    @Override
    public void send(Long userId, MessageType type, String title, String content, Long orderId) {
        if (userId == null) {
            // 工单尚未分配实际执行人等场景：没有接收人，静默跳过，不视为错误
            log.debug("站内消息跳过：接收人为空 type={} title={}", type, title);
            return;
        }
        send(List.of(userId), type, title, content, orderId);
    }

    @Override
    public void send(Collection<Long> userIds, MessageType type, String title, String content, Long orderId) {
        if (userIds == null || userIds.isEmpty()) {
            log.debug("站内消息跳过：接收人列表为空 type={} title={}", type, title);
            return;
        }
        // 去重：申请人与实际执行人可能是同一人，不应收到两条相同消息
        Set<Long> recipients = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Long userId : recipients) {
                Message message = new Message();
                message.setUserId(userId);
                message.setTitle(title);
                message.setContent(content);
                message.setOrderId(orderId);
                message.setMessageType(type.name());
                message.setIsRead(false);
                message.setCreatedAt(now);
                messageMapper.insert(message);
            }
        } catch (Exception e) {
            // 见接口注释：消息是通知产物，不得因通知失败回滚业务
            log.error("站内消息写入失败（已忽略，不影响业务）：type={} title={} orderId={} 接收人={}",
                    type, title, orderId, recipients, e);
        }
    }

    @Override
    public PageResult<MessageVO> pageMine(MessageQuery query) {
        Long currentUserId = requireCurrentUserId();
        long safePage = Math.max(query.getPage(), 1L);
        long safeSize = Math.min(Math.max(query.getSize(), 1L), MAX_PAGE_SIZE);

        // 消息类型：给了就必须合法（静默忽略会让用户误判「筛选无数据」是数据问题）
        String typeCode = query.getMessageType() == null ? null : query.getMessageType().trim();
        if (typeCode != null && !typeCode.isEmpty() && MessageType.of(typeCode) == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "消息类型不合法：" + typeCode);
        }
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();

        IPage<Message> resultPage = messageMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getUserId, currentUserId)
                        // unreadOnly=null 查全部；true 仅未读；false 仅已读
                        .eq(query.getUnreadOnly() != null, Message::getIsRead,
                                Boolean.TRUE.equals(query.getUnreadOnly()) ? 0 : 1)
                        .eq(typeCode != null && !typeCode.isEmpty(), Message::getMessageType, typeCode)
                        // 关键词命中标题或正文。必须用 and(w -> ...or()...) 把 OR 包成一组：
                        // 若直接 or() 出去，会把上面的「只看我的消息」也变成 OR 条件，
                        // 造成越权读到他人消息（这是这里唯一需要小心的写法）。
                        .and(keyword != null && !keyword.isEmpty(),
                                w -> w.like(Message::getTitle, keyword).or().like(Message::getContent, keyword))
                        .orderByDesc(Message::getId));

        Map<Long, String> orderNos = orderNoMap(resultPage.getRecords());
        return PageResult.of(resultPage, message -> toVO(message, orderNos));
    }

    @Override
    public long unreadCount() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            // 未登录（例如登录页轮询铃铛）不报错，返回 0
            return 0L;
        }
        return messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                .eq(Message::getUserId, currentUserId)
                .eq(Message::getIsRead, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long messageId) {
        Long currentUserId = requireCurrentUserId();
        if (messageId == null) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND);
        }
        int updated = messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, currentUserId)
                .eq(Message::getIsRead, false)
                .set(Message::getIsRead, true)
                .set(Message::getReadAt, LocalDateTime.now()));
        if (updated > 0) {
            return;
        }
        // 0 行更新有两种可能：① 本就已读（幂等，视为成功）；② 不存在或不属于我（报错）。
        // 两种情况刻意返回同一个错误码，避免通过错误差异探测他人消息是否存在。
        long owned = messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, currentUserId));
        if (owned == 0) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long currentUserId = requireCurrentUserId();
        return messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getUserId, currentUserId)
                .eq(Message::getIsRead, false)
                .set(Message::getIsRead, true)
                .set(Message::getReadAt, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMine(Long messageId) {
        Long currentUserId = requireCurrentUserId();
        if (messageId == null) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND);
        }
        // 物理删除 + user_id 前置条件：命中 0 行一律按「不存在」处理，
        // 不区分「不存在」与「不是你的」，避免探测他人消息是否存在。
        int deleted = messageMapper.delete(Wrappers.<Message>lambdaQuery()
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, currentUserId));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBatchMine(Collection<Long> ids) {
        Long currentUserId = requireCurrentUserId();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要删除的消息");
        }
        // 去重 + 过滤 null：入参来自前端勾选，可能重复或含空值
        Set<Long> idSet = ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (idSet.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要删除的消息");
        }
        if (idSet.size() > MAX_BATCH_DELETE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "单次最多删除 " + MAX_BATCH_DELETE + " 条消息");
        }
        // 条件恒带 user_id：传入他人消息 ID 不会误删，只会不计入返回条数
        return messageMapper.delete(Wrappers.<Message>lambdaQuery()
                .in(Message::getId, idSet)
                .eq(Message::getUserId, currentUserId));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private MessageVO toVO(Message message, Map<Long, String> orderNos) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        Long orderId = message.getOrderId();
        vo.setOrderId(orderId);
        // 注意：orderNoMap 在无关联工单时返回 Map.of()（ImmutableCollections.MapN），
        // 而 MapN.get(null) 会抛 NPE（内部 Objects.requireNonNull(key)）。
        // 因此 orderId 为空的「系统通知类」消息必须在此短路，不能直接把 null 当 key 查询。
        vo.setOrderNo(orderId == null ? null : orderNos.get(orderId));
        vo.setMessageType(message.getMessageType());
        vo.setMessageTypeLabel(MessageType.labelOf(message.getMessageType()));
        vo.setIsRead(Boolean.TRUE.equals(message.getIsRead()));
        vo.setReadAt(message.getReadAt());
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    /** 一次查出这一页消息涉及的工单编号，避免逐行查询造成 N+1 */
    private Map<Long, String> orderNoMap(Collection<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        Set<Long> orderIds = messages.stream().map(Message::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .select(Order::getId, Order::getOrderNo)
                        .in(Order::getId, orderIds))
                .stream()
                .collect(Collectors.toMap(Order::getId, Order::getOrderNo, (a, b) -> a));
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
