package com.enterprise.ticket.common.alert;

import com.enterprise.ticket.common.trace.TraceContext;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.system.entity.ExceptionLog;
import com.enterprise.ticket.module.system.mapper.ExceptionLogMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

/**
 * 未预期异常的落库记录—— 告警链路的「采集」端。
 *
 * <h2>只记录未预期异常，不记录 4xx 业务异常</h2>
 * 拍板口径：<b>业务异常不告警</b>。否则用户填错一个字段就发一封邮件，
 * 告警疲劳之后真故障会被忽略。因此本类只被 {@code GlobalExceptionHandler}
 * 的**兜底分支**（{@code @ExceptionHandler(Exception.class)}）调用 ——
 * {@code BusinessException} 有专用处理器、根本不经过这里。
 *
 * <h2>它自己绝不允许抛异常</h2>
 * 调用点在一个正在生成错误响应的请求线程里。这里抛出去会把「一个错误」
 * 变成「两个错误」，用户拿到的是 500 而不是原本的业务提示。
 * 因此整个方法体被 try/catch 包住，失败只留一条 WARN 日志。
 *
 * <h2>落库状态由「该不该告警」决定，而不是「重不重要」</h2>
 * <ul>
 *   <li>总开关关闭 / 该分类被管理员忽略 ⇒ {@code SUPPRESSED}（记录但不告警）；</li>
 *   <li>其余 ⇒ {@code PENDING}，等汇总任务按分级节奏发出去。</li>
 * </ul>
 * 注意「静默期」<b>不在这里</b>判定：静默期管的是「同一问题多久内不重复**告警**」，
 * 而每一次发生都必须落库（次数是排查的关键证据）。因此静默期在汇总任务里判定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionRecorder {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SENT = "SENT";
    public static final String STATE_SUPPRESSED = "SUPPRESSED";

    /** 堆栈落库的最大长度（与迁移脚本的注释一致） */
    private static final int MAX_STACK_LENGTH = 4000;

    private final ExceptionLogMapper exceptionLogMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 记录一条未预期异常。
     *
     * @param throwable 异常
     * @param request   当前请求（可为 null —— 定时任务里抛出的异常没有请求上下文）
     * @param module    来源模块标识（如 {@code GLOBAL} / {@code ORDER}）
     */
    public void record(Throwable throwable, HttpServletRequest request, String module) {
        try {
            if (throwable == null) {
                return;
            }
            ExceptionCategory category = ExceptionClassifier.classify(throwable);
            AlertLevel level = ExceptionClassifier.severityOf(category);

            ExceptionLog entity = new ExceptionLog();
            entity.setTraceId(TraceContext.getTraceId());
            entity.setCategory(category.name());
            entity.setSeverity(level.code());
            entity.setModule(ExceptionClassifier.truncate(module, 64));
            entity.setExceptionClass(ExceptionClassifier.truncate(throwable.getClass().getName(), 255));
            entity.setMessage(ExceptionClassifier.truncate(throwable.getMessage(),
                    ExceptionClassifier.MAX_MESSAGE_LENGTH));
            entity.setStackTrace(ExceptionClassifier.truncate(stackTraceOf(throwable), MAX_STACK_LENGTH));
            entity.setStackDigest(ExceptionClassifier.digest(throwable));
            entity.setOccurredAt(LocalDateTime.now());
            entity.setAlertState(decideState(category));
            fillRequestContext(entity, request);

            exceptionLogMapper.insert(entity);
        } catch (Exception e) {
            // 记录异常失败绝不影响响应 —— 见类注释
            log.warn("[异常日志] 落库失败：{}", e.getMessage());
        }
    }

    /**
     * 该不该告警 → 落库状态。
     *
     * <p>配置读取失败时**按「告警」处理**（fail-open）：读不到开关时，
     * 沉默地丢弃告警比多发一封邮件危险得多 —— 前者会让真故障无人知晓，
     * 后者只是多一封邮件。
     */
    private String decideState(ExceptionCategory category) {
        boolean enabled = true;
        String ignoreList = "";
        try {
            enabled = systemConfigService.exceptionAlertEnabled();
            ignoreList = systemConfigService.exceptionAlertIgnoreCategories();
        } catch (Exception e) {
            log.warn("[异常日志] 读取告警开关失败，按「开启」处理：{}", e.getMessage());
        }
        if (!enabled) {
            return STATE_SUPPRESSED;
        }
        if (ExceptionClassifier.isIgnored(category, ignoreList)) {
            return STATE_SUPPRESSED;
        }
        return STATE_PENDING;
    }

    /**
     * 补上请求上下文。
     *
     * <p>每一段都单独容错：定时任务 / 启动期异常可能没有请求上下文，
     * 而 {@code SecurityUtils} 在未认证时取值也可能抛异常。
     * 任何一项取不到都不该让整条记录丢失 —— 异常本身才是主要信息。
     */
    private void fillRequestContext(ExceptionLog entity, HttpServletRequest request) {
        if (request != null) {
            entity.setRequestUri(ExceptionClassifier.truncate(request.getRequestURI(), 255));
            entity.setHttpMethod(ExceptionClassifier.truncate(request.getMethod(), 16));
        }
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId != null && userId > 0) {
                entity.setUserId(userId);
            }
        } catch (Exception ignored) {
            // 匿名请求 / 非请求线程：user_id 留空是正确的结果，不是错误
        }
        try {
            String ip = SecurityUtils.getClientIp(request);
            entity.setIp(ExceptionClassifier.truncate(ip, 64));
        } catch (Exception ignored) {
            // 拿不到真实 IP 时留空，不影响记录本身
        }
    }

    private String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(writer)) {
            throwable.printStackTrace(printWriter);
        }
        return writer.toString();
    }
}
