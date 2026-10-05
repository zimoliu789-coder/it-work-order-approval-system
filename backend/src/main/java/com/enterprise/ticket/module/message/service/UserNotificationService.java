package com.enterprise.ticket.module.message.service;

import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.system.service.SystemConfigMailService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 「站内消息 + 邮件」双通道的用户通知出口（P2 修复）。
 *
 * <h2>要修的缺陷</h2>
 * <p>全量验收表 7#14 / 表 16#6：申请 / 权限结果通知**只有站内消息，没有邮件** ——
 * 而站内消息的接收前提是「用户主动打开系统」。审批结果（尤其是权限申请被跳过的部分）
 * 恰恰需要在无人登录时也能推到用户的邮箱里。
 *
 * <h2>三条约定</h2>
 * <ol>
 *   <li><b>站内消息必达、先发</b>：它不依赖任何通道配置，是唯一「必定成功」的通道；</li>
 *   <li><b>邮件是尽力而为</b>：未开启邮箱通知、或用户没填邮箱时静默跳过；发送失败只记日志，
 *       绝不把「站内消息已发出」这件事带崩 —— 通知失败不该改变业务结果；</li>
 *   <li><b>邮件在事务提交后发送</b>：本类常被「审批完成」这类事务内路径调用，
 *       若在事务里直接发 SMTP，一次 10 秒的连接超时会把数据库连接与行锁一起拖住。
 *       因此有事务时注册 {@code afterCommit} 回调，提交成功后才真正投递。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final MessageService messageService;
    private final SystemConfigMailService mailService;
    private final SystemConfigService systemConfigService;
    private final UserMapper userMapper;

    /**
     * 向单个用户发送结果通知（站内消息 + 邮件）。
     *
     * @param userId  接收人
     * @param type    站内消息类型（决定消息中心里的分类图标）
     * @param title   标题（同时作为邮件主题的正文部分）
     * @param body    正文
     * @param orderId 关联工单 id（可空）
     */
    public void notify(Long userId, MessageType type, String title, String body, Long orderId) {
        if (userId == null) {
            return;
        }
        // ① 站内消息（必达通道）
        messageService.send(userId, type, title, body, orderId);
        // ② 邮件（尽力而为，事务提交后投递）
        scheduleMailAfterCommit(userId, title, body);
    }

    private void scheduleMailAfterCommit(Long userId, String title, String body) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendMailQuietly(userId, title, body);
                }
            });
        } else {
            sendMailQuietly(userId, title, body);
        }
    }

    /** 实际投递（吞掉所有异常：通知失败不能影响业务结果） */
    private void sendMailQuietly(Long userId, String title, String body) {
        try {
            if (!Boolean.TRUE.equals(systemConfigService.emailVerifyEnabled())) {
                return;
            }
            User user = userMapper.selectById(userId);
            if (user == null || !StringUtils.hasText(user.getEmail())) {
                return;
            }
            String siteName = systemConfigService.siteName();
            mailService.sendAlert(user.getEmail().trim(),
                    "【" + siteName + "】" + title,
                    body + "\n\n—— 本消息由系统自动发出，请勿直接回复。");
        } catch (Exception e) {
            log.warn("[通知] 邮件发送失败（不影响站内消息）：userId={} 原因={}", userId, e.getMessage());
        }
    }
}
