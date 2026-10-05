package com.enterprise.ticket.common.log;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解（：高风险操作必须可靠、同步地写入审计日志）
 *
 * <p>用法：
 * <pre>
 * &#64;AuditLog(module = "AUTH", action = "LOGIN", risk = RiskLevel.NORMAL, description = "员工登录")
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 模块：AUTH / USER / SYSTEM / DEVICE / ORDER 等 */
    String module();

    /** 动作：LOGIN / CHANGE_PASSWORD / RESET_PASSWORD 等 */
    String action();

    /** 中文描述，用于日志详情与运维排查 */
    String description() default "";

    /** 风险级别，默认普通（可异步落库） */
    RiskLevel risk() default RiskLevel.NORMAL;

    /** 是否记录方法入参（默认记录，密码类参数由切面自动脱敏） */
    boolean recordArgs() default true;
}
