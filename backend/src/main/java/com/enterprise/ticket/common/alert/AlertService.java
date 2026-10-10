package com.enterprise.ticket.common.alert;

import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.system.service.SystemConfigMailService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用告警分发—— 站内消息 + 邮件（+ 短信占位）三通道。
 *
 * <h2>为什么要把 {@code HaAlertNotifier} 抽成它</h2>
 * 主备告警里已经有一份**正确**的实现范式（站内消息发全部超管 + 邮件 + 审计 + 失败不中断）。
 * 异常告警、安全告警都需要同一套能力 —— 如果各写一份，三份实现必然漂移
 * （最常见的漂移是「有一处忘了发站内消息」，而那种缺失只有真出事时才发现）。
 * 因此本类是三处告警的**唯一**分发出口，主备告警也已改为调用它。
 *
 * <h2>三条硬约定（拍板口径）</h2>
 * <ol>
 *   <li><b>只发超管</b>：接收人 = 全部启用中的超级管理员（{@code selectSuperAdminIds}）——
 *       沿用「基础设施级告警只发超管」；</li>
 *   <li><b>P0/P1 追加额外收件人</b>（系统参数里的运维邮箱）；<b>P2 只发超管</b> ——
 *       提示级信息不该扩散到运维组之外；</li>
 *   <li><b>站内消息必达、先发</b>：它不依赖任何通道配置，是唯一「必定成功」的通道，
 *       必须先落袋为安；邮件 / 短信失败都只降级为日志，绝不向外抛。</li>
 * </ol>
 *
 * <h2>为什么所有失败都只记日志、绝不抛</h2>
 * 调用方包括全局异常处理器与定时任务。前者抛异常会把「已经生成的错误响应」变成另一个错误；
 * 后者抛异常会让整轮扫描回滚（连同已经算出的结论一起丢掉）。
 * 告警是「救人」的，它自己失败不能把主流程拖下水。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 审计模块标识：与备份告警同属运维域（{@code OPS}），便于按模块检索全部基础设施告警 */
    public static final String AUDIT_MODULE = "OPS";

    private final UserMapper userMapper;
    private final MessageService messageService;
    private final OperationLogService operationLogService;
    private final SystemConfigMailService mailService;
    private final SystemConfigService systemConfigService;

    /** 一次分发的通道结果（供调用方写进日志 / 审计，便于回答「为什么我没收到告警」） */
    public record AlertOutcome(int inAppRecipients, int emailAttempted, int emailSent,
                               int extraRecipients, int smsLogged) {

        public boolean delivered() {
            return inAppRecipients > 0;
        }
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 默认按 {@link AlertLevel#P1} 分发（多数基础设施告警的合理默认） */
    public AlertOutcome alert(MessageType type, String title, String body,
                              String auditAction, String auditDetail) {
        return alert(AlertLevel.P1, type, title, body, auditAction, auditDetail);
    }

    /**
     * 分发一条告警。
     *
     * @param level       告警级别（决定是否追加运维收件人、以及正文里的节奏说明）
     * @param type        站内消息类型（决定消息中心里的分类图标 / 文案）
     * @param title       标题
     * @param body        正文（本方法会自动追加统一的通道说明页脚）
     * @param auditAction 审计动作码（写进 {@code operation_log}）
     * @param auditDetail 审计详情
     */
    public AlertOutcome alert(AlertLevel level, MessageType type, String title, String body,
                              String auditAction, String auditDetail) {
        AlertLevel safeLevel = level == null ? AlertLevel.P1 : level;
        String fullBody = (body == null ? "" : body) + "\n\n" + footer(safeLevel);

        List<Long> recipients = superAdminIds();
        if (recipients.isEmpty()) {
            // 不能静默：否则「没人可通知」会与「没触发告警」混为一谈
            log.warn("[告警] 需要发出「{}」，但系统中没有可用的超级管理员接收人", title);
            recordAudit(false, auditAction, auditDetail + " | recipients=0");
            return new AlertOutcome(0, 0, 0, 0, 0);
        }

        // ① 站内消息（必达通道）
        messageService.send(recipients, type, title, fullBody, null);

        // ② 邮件（+ 额外收件人）；③ 短信（网关未接入，只留日志）
        ChannelOutcome outcome = sendExternalChannels(safeLevel, recipients, title, fullBody);

        log.warn("[告警] 已发出（{}）「{}」：站内 {} 人，邮件成功 {}/{}，额外收件人 {}，短信仅日志 {}",
                safeLevel.code(), title, recipients.size(),
                outcome.emailSent(), outcome.emailAttempted(), outcome.extraRecipients(), outcome.smsLogged());
        // 审计里刻意写 `sms=log/N` 而不是 `smsDelivered=N`：
        // 网关未接入，短信**从未真实送达**。写成 delivered 会让管理员在真正需要
        // 短信提醒的那天，才发现它从来没有发出去过 —— 假成功比失败贵得多。
        recordAudit(true, auditAction, auditDetail
                + " | level=" + safeLevel.code()
                + " | recipients=" + recipients.size()
                + " | email=" + outcome.emailSent() + "/" + outcome.emailAttempted()
                + " | extra=" + outcome.extraRecipients()
                + " | sms=log/" + outcome.smsLogged());
        return new AlertOutcome(recipients.size(), outcome.emailAttempted(), outcome.emailSent(),
                outcome.extraRecipients(), outcome.smsLogged());
    }

    // ------------------------------------------------------------------
    // 通道
    // ------------------------------------------------------------------

    /**
     * 站内消息之外的通道。
     *
     * <p><b>邮件</b>已具备真实投递能力（ 接入的 SMTP），只要「启用邮箱通知」打开、
     * 且收件人填了邮箱，就真的发出去。单个人失败不影响其他人，也不影响已落袋的站内消息。
     *
     * <p><b>额外收件人</b>只在 P0/P1 追加：他们是「运维」而非「系统用户」，
     * 没有站内消息可收，因此只走邮件。P2 不追加 —— 提示级信息不该扩散。
     *
     * <p><b>短信</b>网关尚未接入（为预留口径）。这里刻意只写日志，
     * 绝不返回「已送达」—— 假成功会让管理员在真正需要短信提醒时才发现它从来没发过。
     */
    private ChannelOutcome sendExternalChannels(AlertLevel level, List<Long> recipients,
                                                String title, String body) {
        int emailAttempted = 0;
        int emailSent = 0;
        int extraRecipients = 0;
        int smsLogged = 0;

        boolean emailOn = emailEnabled();
        boolean smsOn = smsEnabled();
        if (!emailOn && !smsOn) {
            // 两条通道都关：连用户都不必查（省一次库往返，且避免无意义查询进慢日志）
            return new ChannelOutcome(0, 0, 0, 0);
        }

        List<User> users = userMapper.selectBatchIds(recipients);

        if (emailOn) {
            String subject = "【" + safe(siteName()) + "】[" + level.code() + "] " + title;
            for (User user : users) {
                if (!StringUtils.hasText(user.getEmail())) {
                    continue;
                }
                emailAttempted++;
                if (sendMail(user.getEmail().trim(), subject, body)) {
                    emailSent++;
                }
            }
            // 额外收件人（运维）—— 仅 P0/P1
            if (level == AlertLevel.P0 || level == AlertLevel.P1) {
                for (String to : extraRecipients()) {
                    emailAttempted++;
                    extraRecipients++;
                    if (sendMail(to, subject, body)) {
                        emailSent++;
                    }
                }
            }
        }

        if (smsOn) {
            for (User user : users) {
                if (StringUtils.hasText(user.getPhone())) {
                    // 网关未接入：只留一条可 grep 的日志，绝不记成「已送达」
                    smsLogged++;
                    log.info("[告警] 短信（网关未接入，仅写日志）：target={} title={}",
                            AccountFormats.maskContact(user.getPhone()), title);
                }
            }
        }
        return new ChannelOutcome(emailAttempted, emailSent, extraRecipients, smsLogged);
    }

    private boolean sendMail(String to, String subject, String body) {
        try {
            mailService.sendAlert(to, subject, body);
            return true;
        } catch (Exception e) {
            log.warn("[告警] 邮件发送失败：recipient={} 原因={}", AccountFormats.maskContact(to), e.getMessage());
            return false;
        }
    }

    private record ChannelOutcome(int emailAttempted, int emailSent, int extraRecipients, int smsLogged) {
    }

    // ------------------------------------------------------------------
    // 配置读取（全部容错：读不到不能把告警链路带崩）
    // ------------------------------------------------------------------

    /** 全部启用中的超级管理员 id（告警的默认且唯一站内接收人） */
    public List<Long> superAdminIds() {
        try {
            List<Long> ids = userMapper.selectSuperAdminIds();
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("[告警] 读取超管列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 额外收件人邮箱（逗号分隔）。
     *
     * <p>用 {@link LinkedHashSet} 去重：管理员很容易把同一个邮箱填两遍，
     * 那会让同一个人收到两封同样的邮件。
     */
    public List<String> extraRecipients() {
        String raw = safeRead(() -> systemConfigService.exceptionAlertExtraRecipients());
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            // 只做「看起来像邮箱」的最低限度校验：明显不是邮箱的值发给 SMTP 只会换来一次报错
            if (!trimmed.isEmpty() && trimmed.contains("@")) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    /** 邮件通道是否启用（读不到按关闭处理：宁可少发一封，也不能让告警链路整体中断） */
    public boolean emailEnabled() {
        return Boolean.TRUE.equals(safeRead(() -> systemConfigService.emailVerifyEnabled()));
    }

    private boolean smsEnabled() {
        return Boolean.TRUE.equals(safeRead(() -> systemConfigService.smsVerifyEnabled()));
    }

    public String siteName() {
        return safeRead(() -> systemConfigService.siteName());
    }

    private <T> T safeRead(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[告警] 读取配置失败，按缺省处理：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    /**
     * 系统行为审计。
     *
     * <p>操作人记为 {@code null} 表示「系统自身」—— 告警不是任何用户触发的，
     * 挂到某个用户头上会在审计里制造「某人执行了告警」这种不存在的事实。
     * 写失败只降级为日志：告警是「救人」的、审计是「取证」的，不能因取证失败而判救人失败。
     */
    private void recordAudit(boolean success, String action, String detail) {
        if (action == null || action.isBlank()) {
            return;
        }
        try {
            operationLogService.record(null, null, AUDIT_MODULE, action, detail, success, RiskLevel.NORMAL);
        } catch (Exception e) {
            log.warn("[告警] 写入告警审计失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 告警正文的统一页脚 —— 让收件人一眼知道「消息是怎么来的、哪个通道没通」。
     *
     * <p>写进页脚而不是只写日志：管理员收到邮件时最常问的两个问题是
     * 「这是自动发的吗」与「短信为什么没收到」，页脚直接回答，省一次排查。
     */
    private String footer(AlertLevel level) {
        return "—— 本消息由系统自动发出。\n"
                + "告警级别：" + level.code() + " " + level.label() + "（" + level.delivery() + "）\n"
                + "站内消息为必达通道；邮件在企业已启用邮件通知、且收件人填写了邮箱时同步发送；"
                + "短信通道尚未接入网关，仅在应用日志中留痕。";
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    /** 供正文使用的时间戳格式（与各告警类保持一致，避免同一封邮件里出现两种格式） */
    public static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
