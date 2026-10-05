package com.enterprise.ticket.security;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.common.web.ResponseWriter;
import com.enterprise.ticket.module.auth.service.LoginProtectionService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 工单提交 / 延期提交接口限流（：单用户 1 分钟最多 3 次提交）
 *
 * <h2>为什么做成 MVC 拦截器而不是塞进 Service</h2>
 * <p>规范把这条限制表述为<b>接口级</b>约束（「提交接口：单用户 1 分钟最多 3 次」）。
 * 放在拦截器上有两个实际好处：
 * <ul>
 *   <li>与业务事务解耦：限流拒绝发生在事务开启之前，不会出现「先开事务再被拒」的空转；</li>
 *   <li>作用面精确：只有用户直接调 HTTP 接口才会受限。将来若由定时任务或内部补偿逻辑
 *       代为创建工单，不会被这条「防用户连点」的规则误伤。</li>
 * </ul>
 *
 * <p>配合前端按钮禁用形成「体验 + 约束」双层：按钮禁用只是体验优化，
 *  明确「不能只依赖前端按钮禁用来解决并发」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitRateLimitInterceptor implements HandlerInterceptor {

    private static final String FIELD_RETRY_AFTER = "retryAfterSeconds";

    private final LoginProtectionService loginProtectionService;
    private final OperationLogService operationLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            // 未认证请求交给安全链返回 401；用限流去替代表达「没登录」会给出误导性提示
            return true;
        }
        try {
            loginProtectionService.checkSubmitRateLimit(userId);
            return true;
        } catch (BusinessException e) {
            return reject(request, response, userId, e);
        }
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response, Long userId, BusinessException e) {
        int retryAfterSeconds = e.getRetryAfterSeconds() == null ? 60 : e.getRetryAfterSeconds();
        recordBlocked(request, userId, retryAfterSeconds);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        ResponseWriter.write(response, e.getErrorCode().getHttpStatus(),
                ApiResponse.error(e.getErrorCode(), e.getMessage(),
                        Map.of(FIELD_RETRY_AFTER, retryAfterSeconds)));
        return false;
    }

    private void recordBlocked(HttpServletRequest request, Long userId, int retryAfterSeconds) {
        try {
            operationLogService.record(userId, SecurityUtils.getCurrentUsername(), "ORDER",
                    "ORDER_SUBMIT_RATE_LIMITED",
                    "提交限流拦截，path=" + request.getRequestURI()
                            + " | retryAfter=" + retryAfterSeconds + "s",
                    false, RiskLevel.NORMAL);
        } catch (Exception ex) {
            log.warn("记录提交限流日志失败（不影响响应）：{}", ex.getMessage());
        }
    }
}
