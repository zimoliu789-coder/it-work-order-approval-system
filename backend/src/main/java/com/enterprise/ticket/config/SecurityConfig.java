package com.enterprise.ticket.config;

import com.enterprise.ticket.security.ApiRateLimitFilter;
import com.enterprise.ticket.security.CsrfHeaderFilter;
import com.enterprise.ticket.security.JwtAuthenticationFilter;
import com.enterprise.ticket.security.RestAccessDeniedHandler;
import com.enterprise.ticket.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（ 角色权限、 登录安全、 统一错误码、 限流）
 *
 * <p>设计要点：
 * <ol>
 *   <li>无状态：不使用服务端 Session，会话完全由 JWT Cookie 承载；</li>
 *   <li>关闭 Spring Security 自带 CSRF（前后端分离 + 无表单页），
 *       改由 {@link CsrfHeaderFilter} 的「自定义请求头」方案防护；</li>
 *   <li>授权双层校验：URL 级（本类）+ 方法级（{@code @PreAuthorize}）；</li>
 *   <li>认证失败 401、越权 403、限流 429，响应体统一为 格式；</li>
 *   <li>过滤器顺序：CSRF 校验 → JWT 认证 → <b>全局限流</b>。
 *       限流必须排在认证之后，才能按「匿名 / 已登录」分流并拿到用户维度；
 *       但它又必须早于业务代码，才能真正起到兜底作用。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppProperties appProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CsrfHeaderFilter csrfHeaderFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    registry.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    List<String> permitAll = appProperties.getSecurity().getPermitAll();
                    if (permitAll != null && !permitAll.isEmpty()) {
                        registry.requestMatchers(permitAll.toArray(String[]::new)).permitAll();
                    }
                    registry.anyRequest().authenticated();
                })
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                // CSRF 请求头校验放在最前，非法请求不进入认证流程
                .addFilterBefore(csrfHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, CsrfHeaderFilter.class)
                // 限流放在认证之后：这样才能区分匿名与已登录并取到用户维度
                .addFilterAfter(apiRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 跨域配置：开发阶段前端 5173 直连后端 8080 时需要；生产由 Nginx 同源代理，可留空关闭。
     * 注意：携带 Cookie 时必须 allowCredentials=true，且不能使用通配 Origin。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String allowedOrigins = appProperties.getCors().getAllowedOrigins();
        if (!StringUtils.hasText(allowedOrigins)) {
            // 返回空配置：不注册任何跨域规则，浏览器预检将被拒绝
            return source;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(configuration::addAllowedOrigin);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
