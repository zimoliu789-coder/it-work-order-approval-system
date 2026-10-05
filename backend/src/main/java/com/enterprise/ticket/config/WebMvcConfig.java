package com.enterprise.ticket.config;

import com.enterprise.ticket.security.SubmitRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 扩展配置
 *
 * <p>目前只挂一件事：工单提交 / 延期提交的接口级限流。
 * 独立成配置类而不用 {@code @Component} + {@code WebMvcConfigurer} 二合一，
 * 是为了让「限流规则挂在哪些路径上」集中可见 —— 路径写错会让限流静默失效，
 * 这种配置必须一眼能看全、易于核对。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final SubmitRateLimitInterceptor submitRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(submitRateLimitInterceptor)
                // 借用申请提交（POST /api/orders）
                .addPathPatterns("/api/orders")
                // 延期申请提交（POST /api/orders/{orderId}/extends）
                .addPathPatterns("/api/orders/*/extends");
    }
}
