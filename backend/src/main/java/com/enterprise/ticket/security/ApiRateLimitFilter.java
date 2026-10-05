package com.enterprise.ticket.security;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.IpMatcher;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.common.web.ResponseWriter;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局 API 限流过滤器（；Docker 部署 + 限流加固阶段新增）
 *
 * <h2>分层策略：为什么是「IP + 身份」两条桶</h2>
 * <p>只按 IP 限流，同一出口 NAT 后的整个办公室会被合并成一个人；
 * 只按用户限流，未登录的扫描流量完全不设防。因此同时挂两条桶：
 * <ol>
 *   <li><b>来源 IP 桶</b>：无论是否登录都生效，兜住扫描 / 未认证流量；</li>
 *   <li><b>身份桶</b>：已登录按用户、未登录按 IP 且阈值更严（匿名流量没有业务
 *       正当性支撑，正常浏览器一次会话只需十几次请求）。</li>
 * </ol>
 *
 * <h2>与其他限流的边界</h2>
 * <p>登录（同 IP / 同账号）与工单提交（单用户）有各自的接口级限流，且阈值远严于本过滤器，
 * 因此这些路径被<b>显式排除</b>在本过滤器之外 —— 否则同一请求会被两套规则重复计数，
 * 排查限流问题时无法判断到底是哪一层拦的。
 *
 * <h2>故障策略</h2>
 * <p>Redis 不可用时由 {@link RateLimitGuard} 放行（fail-open），理由见该类注释。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String FIELD_RETRY_AFTER = "retryAfterSeconds";

    /**
     * 跳过的路径。
     *
     * <ul>
     *   <li>{@code /api/health} —— 容器健康探针每分钟都在打，被限流会让编排器误判容器不健康而重启，
     *       把「限流」变成「服务反复重启」；</li>
     *   <li>{@code /api/auth/login}、{@code /api/auth/csrf} —— 有专用且更严格的接口级限流；</li>
     *   <li>{@code /api/internal/alerts/backup} —— 备份失败告警通道。
     *       <b>告警通道绝不能被自己保护的机制挡住</b>，否则「备份失败」+「告警被限流」
     *       会叠加成完全的静默失败；</li>
     *   <li>{@code /api/internal/ha/report} —— 主备心跳 / 切换事件上报。
     *       与备份上报同属「脚本 → 应用」的回程通道，同样不该被按来源计的 API 限流统计
     *       ：心跳是每几秒一次的常态流量，被限流的表现是<b>节点状态永远不更新</b>、
     *       甚至被超时扫描判成断连并推出假告警，而管理员完全看不出是限流造成的。
     *       一旦把「匿名来源」阈值调低（该值可在系统参数页改），这个隐患就会立刻显形。</li>
     * </ul>
     */
    private static final Set<String> SKIP_PATHS = Set.of(
            "/api/health",
            "/api/auth/login",
            "/api/auth/csrf",
            "/api/internal/alerts/backup",
            "/api/internal/ha/report",
            // ：主备配置导出。新增内部端点必须同时加进 SecurityConfig 的 permit-all，
            // 否则请求会在鉴权层被 401 挡掉（现象像「令牌不对」）。
            "/api/internal/ha/config",
            // 初始化状态查询（本次新增）：登录页/路由守卫会在启动时探一次，
            // 未初始化的系统还会由向导页轮询。它无副作用、无鉴权，计入限流只会
            // 让「刚部署完打开系统」这一步莫名失败。注意 /api/setup/initialize **不在**跳过表里 ——
            // 那是一个写操作，应保留限流计数。
            "/api/setup/status");

    private static final String KEY_API_IP = "ticket:rate:api:ip:";
    private static final String KEY_API_ANON = "ticket:rate:api:anon:";
    private static final String KEY_API_USER = "ticket:rate:api:user:";
    private static final String KEY_LOG = "ticket:rate:log:";

    /** 同一来源的拦截日志最小间隔 */
    private static final Duration LOG_THROTTLE = Duration.ofMinutes(1);

    private final RateLimitGuard rateLimitGuard;
    private final SystemConfigService systemConfigService;
    private final OperationLogService operationLogService;

    /** 白名单原始串与其编译结果；串不变则复用，避免每请求重新解析 CIDR */
    private volatile String whitelistSource;
    private volatile IpMatcher whitelist = IpMatcher.empty();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path) || !path.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        // 预检与 HEAD 不消耗配额：它们不承载业务，计入只会让正常前端「莫名被限流」
        if ("OPTIONS".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return true;
        }
        return SKIP_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!systemConfigService.apiRateLimitEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String ip = SecurityUtils.getClientIp(request);
        if (isWhitelisted(ip)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitGuard.Decision decision = evaluate(request, ip);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        int retryAfterSeconds = (int) decision.retryAfterSeconds();
        recordBlocked(request, ip, retryAfterSeconds);
        // Retry-After 必须先于写出响应体设置：ResponseWriter 会立即 flush
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        ResponseWriter.write(response, ErrorCode.RATE_LIMITED.getHttpStatus(),
                ApiResponse.error(ErrorCode.RATE_LIMITED,
                        "操作过于频繁，请 " + retryAfterSeconds + " 秒后重试",
                        Map.of(FIELD_RETRY_AFTER, retryAfterSeconds)));
    }

    /** 依次判定「来源 IP 桶 → 身份桶」，任一拒绝即刻返回 */
    private RateLimitGuard.Decision evaluate(HttpServletRequest request, String ip) {
        RateLimitGuard.Decision byIp = rateLimitGuard.tryAcquireToken(KEY_API_IP + safe(ip),
                systemConfigService.apiRateLimitIpPerMinute(),
                systemConfigService.apiRateLimitIpBurst());
        if (!byIp.allowed()) {
            return byIp;
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return rateLimitGuard.tryAcquireToken(KEY_API_ANON + safe(ip),
                    systemConfigService.apiRateLimitAnonPerMinute(),
                    systemConfigService.apiRateLimitAnonBurst());
        }
        return rateLimitGuard.tryAcquireToken(KEY_API_USER + userId,
                systemConfigService.apiRateLimitUserPerMinute(),
                systemConfigService.apiRateLimitUserBurst());
    }

    /**
     * 写一条限流拦截审计（可能被节流丢弃）。
     *
     * <p>两处防自伤：
     * <ol>
     *   <li><b>按来源节流</b>：攻击者持续打接口时，若每次拦截都写库，
     *       审计表会被拦截记录写爆 —— 从「抗滥用」变成「被滥用写爆磁盘」，
     *       故同一来源同一路径 1 分钟内只留一条；</li>
     *   <li><b>吞掉自身异常</b>：审计失败绝不能让限流响应变成 500，
     *       否则「被限流」会被误报成「系统故障」。</li>
     * </ol>
     */
    private void recordBlocked(HttpServletRequest request, String ip, int retryAfterSeconds) {
        String path = request.getRequestURI();
        try {
            if (!rateLimitGuard.tryMarkOnce(KEY_LOG + safe(ip) + ":" + path, LOG_THROTTLE)) {
                return;
            }
            operationLogService.record(SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUsername(),
                    "SYSTEM", "API_RATE_LIMITED",
                    "全局限流拦截，method=" + request.getMethod() + " | path=" + path
                            + " | ip=" + ip + " | retryAfter=" + retryAfterSeconds + "s",
                    false, RiskLevel.NORMAL);
        } catch (Exception e) {
            log.warn("记录限流拦截日志失败（不影响响应）: path={} err={}", path, e.getMessage());
        }
    }

    private boolean isWhitelisted(String ip) {
        if (ip == null) {
            return false;
        }
        String source = systemConfigService.rateLimitWhitelist();
        return currentWhitelist(source).matches(ip);
    }

    /** 配置串未变时复用已编译的匹配器（配置项热更新后自动重建） */
    private IpMatcher currentWhitelist(String source) {
        String normalized = source == null ? "" : source.trim();
        if (!normalized.equals(whitelistSource)) {
            List<String> entries = new ArrayList<>();
            for (String part : normalized.split(",")) {
                if (StringUtils.hasText(part)) {
                    entries.add(part.trim());
                }
            }
            whitelist = IpMatcher.of(entries);
            whitelistSource = normalized;
            if (!whitelist.isEmpty()) {
                log.info("限流白名单已更新：{} 条", whitelist.size());
            }
        }
        return whitelist;
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }
}
