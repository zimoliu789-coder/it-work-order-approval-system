package com.enterprise.ticket.common.log;

import com.enterprise.ticket.module.log.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * 审计切面：拦截 {@link AuditLog} 注解方法，自动记录操作人与结果
 *
 * <p>敏感字段（password / secret / token）在入参序列化时统一脱敏，禁止写入日志明文。
 *
 * <p><b>适用边界</b>：本切面适合「一进一出、成败都要留痕」的常规业务操作 ——  起的
 * 设备 / 部门 / 审批节点等 CRUD 一律加本注解即可。认证模块因需要记录注解无法表达的自定义
 * 详情（失败次数、客户端 IP、登录来源等），改由 {@code OperationLogService.recordCurrent} 显式调用。
 * 两条路径共用同一落库与脱敏链路（HIGH 风险 = REQUIRES_NEW 独立事务）。
 */
@Slf4j
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class AuditLogAspect {

    private static final String MASK = "******";
    private static final int MAX_DETAIL_LENGTH = 2000;
    /** 单个参数值写入日志的最大长度，避免 DTO 全量 toString 撑爆审计详情 */
    private static final int MAX_ARG_TEXT_LENGTH = 300;

    private static final String[] SENSITIVE_KEYWORDS = {"password", "passwd", "secret", "token", "credential"};

    private final OperationLogService operationLogService;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startedAt = System.currentTimeMillis();
        boolean success = true;
        String failureMessage = null;
        Object result;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            success = false;
            failureMessage = e.getMessage();
            throw e;
        } finally {
            try {
                String details = buildDetails(joinPoint, auditLog, success, failureMessage,
                        System.currentTimeMillis() - startedAt);
                operationLogService.recordCurrent(auditLog.module(), auditLog.action(),
                        details, success, auditLog.risk());
            } catch (Exception e) {
                // 审计写入失败不能吞掉业务异常，但必须留下痕迹
                log.error("写入审计日志失败 module={} action={}", auditLog.module(), auditLog.action(), e);
            }
        }
    }

    private String buildDetails(ProceedingJoinPoint joinPoint, AuditLog auditLog,
                                boolean success, String failureMessage, long costMs) {
        StringJoiner joiner = new StringJoiner(" | ");
        if (!auditLog.description().isEmpty()) {
            joiner.add("desc=" + auditLog.description());
        }
        joiner.add("method=" + signatureOf(joinPoint));
        if (auditLog.recordArgs()) {
            joiner.add("args=" + serializeArgs(joinPoint));
        }
        joiner.add("cost=" + costMs + "ms");
        if (!success && failureMessage != null) {
            joiner.add("error=" + failureMessage);
        }
        String details = joiner.toString();
        return details.length() > MAX_DETAIL_LENGTH ? details.substring(0, MAX_DETAIL_LENGTH) + "...(truncated)" : details;
    }

    private String signatureOf(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private String serializeArgs(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return "[]";
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < args.length; i++) {
            String name = (parameterNames != null && i < parameterNames.length) ? parameterNames[i] : ("arg" + i);
            joiner.add(name + "=" + safeValue(name, args[i]));
        }
        return joiner.toString();
    }

    private String safeValue(String name, Object value) {
        if (value == null) {
            return "null";
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerName.contains(keyword)) {
                return MASK;
            }
        }
        // 不反射展开复杂对象，避免日志爆炸与潜在敏感信息泄露
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?>) {
            String text = String.valueOf(value);
            if (containsSensitiveKeyword(text)) {
                return MASK;
            }
            return text.length() > 200 ? text.substring(0, 200) + "..." : text;
        }
        if (value.getClass().isArray()) {
            return value.getClass().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        // DTO / 集合等：记录其 toString（Lombok 已生成），使审计日志能体现「改了什么」。
        // 若为未重写的 Object#toString（形如 Class@1a2b3c）则无信息量，退回类名。
        String text = String.valueOf(value);
        if (text.startsWith(value.getClass().getName() + "@")) {
            return value.getClass().getSimpleName();
        }
        if (containsSensitiveKeyword(text)) {
            return MASK;
        }
        return text.length() > MAX_ARG_TEXT_LENGTH
                ? text.substring(0, MAX_ARG_TEXT_LENGTH) + "..."
                : text;
    }

    private boolean containsSensitiveKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
