package com.enterprise.ticket.module.message.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.message.dto.MessageQuery;
import com.enterprise.ticket.module.message.dto.vo.MessageVO;
import com.enterprise.ticket.module.message.entity.Message;

import java.util.Collection;

/**
 * 站内消息服务
 *
 * <p><b>可靠性策略（重要设计决策）</b>：发送消息一律「尽力而为」——
 * 内部捕获异常并记 error 日志，<b>不向上抛、不回滚调用方的业务事务</b>。
 * 理由是消息只是业务事实的<b>通知产物</b>：工单状态机、设备状态、审批节点结果都已经落库，
 * 并由操作日志可追溯；反过来让「通知表写入失败」把一次合法的归还/审批操作整体回滚，
 * 会造成用户操作结果与提示不一致，代价明显更大。因此宁可有极低概率漏一条消息，也不阻断业务。
 *
 * <p>已读操作则不同：它本身就是用户可见的状态变更，失败必须明确报错（见 {@link #markRead}）。
 */
public interface MessageService extends IService<Message> {

    /**
     * 发送站内消息（尽力而为，失败仅记日志，不抛异常）
     *
     * @param userId  接收人；为空时静默跳过（例如工单尚未分配实际执行人）
     * @param type    消息类型，决定前端图标与跳转语义
     * @param title   标题
     * @param content 正文
     * @param orderId 关联工单，可为空
     */
    void send(Long userId, MessageType type, String title, String content, Long orderId);

    /**
     * 批量发送同一条消息（自动去重，避免「申请人与执行人是同一人」时收到两遍）
     */
    void send(Collection<Long> userIds, MessageType type, String title, String content, Long orderId);

    /** 我的消息分页（仅当前登录用户自己的消息） */
    PageResult<MessageVO> pageMine(MessageQuery query);

    /** 我的未读消息数（右上角铃铛红点，） */
    long unreadCount();

    /**
     * 标记单条消息已读。
     *
     * <p>只允许操作<b>自己的</b>消息：条件更新带 {@code user_id} 前置条件，
     * 越权访问与「消息不存在」返回同一错误码，避免通过错误差异探测他人消息是否存在。
     */
    void markRead(Long messageId);

    /**
     * 全部标记已读
     *
     * @return 实际置为已读的条数
     */
    int markAllRead();

    /**
     * 删除我的一条消息（消息中心用）
     *
     * <p>消息是「通知产物」，删掉自己的通知不影响任何业务事实，因此用<b>物理删除</b>：
     * 若改软删，消息表会被用户操作不断膨胀，而软删标记对「通知」这一语义没有价值
     * （审计需求由 {@code operation_logs} 承担）。
     *
     * <p>只允许删自己的：条件删除带 {@code user_id} 前置条件，命中 0 行统一返回
     * {@code MESSAGE_NOT_FOUND} —— 不区分「不存在」与「不是你的」，
     * 避免通过错误差异探测他人消息是否存在（与 {@link #markRead} 同一策略）。
     */
    void deleteMine(Long messageId);

    /**
     * 批量删除我的消息（需求方三波·第二波·）
     *
     * <p>与 {@link #deleteMine} 同一策略：条件删除恒带 {@code user_id}，
     * 传入的 ID 里即便混入他人的消息也只会「命中 0 行」而不报错 ——
     * 批量场景下逐条报错会打断操作，返回实际删除条数更贴合「勾选删除」的语义。
     *
     * @param ids 消息 ID 集合
     * @return 实际删除的条数（只统计属于当前用户的）
     */
    int deleteBatchMine(Collection<Long> ids);
}
