package com.enterprise.ticket.module.system.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.trace.TraceContext;
import com.enterprise.ticket.common.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口（免认证，用于本地联调与容器健康探针）
 *
 * <p>除存活状态外，额外回显 <b>客户端 IP</b> 与 <b>原始协议</b> ——
 * 这两项是反向代理部署后最容易配错、又最难自查的地方：
 * 用浏览器或 curl 经群晖反代 / 宿主 Nginx 访问本接口，即可直接确认
 * 「后端是否拿到了真实客户端 IP」「是否识别到外层是 HTTPS」，
 * 无需登录、无需翻日志。这也是「X-Forwarded-* 头正确传递」的现场验收手段。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Environment environment;

    @Value("${spring.application.name:ticket-system}")
    private String applicationName;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("application", applicationName);
        data.put("profile", String.join(",", environment.getActiveProfiles()));
        data.put("time", LocalDateTime.now().format(FORMATTER));
        // 真实客户端 IP（按可信代理链解析）与原始协议：用于确认反代头是否传递正确
        data.put("clientIp", SecurityUtils.getClientIp(request));
        data.put("scheme", SecurityUtils.getOriginalScheme(request));
        data.put("secure", SecurityUtils.isOriginalRequestSecure(request));
        data.put("trustedProxyRules", SecurityUtils.trustedProxyCount());
        data.put("traceId", TraceContext.getTraceId());
        return ApiResponse.success(data);
    }
}
