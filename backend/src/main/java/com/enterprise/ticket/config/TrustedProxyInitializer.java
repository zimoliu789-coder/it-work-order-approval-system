package com.enterprise.ticket.config;

import com.enterprise.ticket.common.util.SecurityUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可信反向代理网段初始化（：Nginx 反向代理 / 群晖反代）
 *
 * <p>把 {@code app.security.trusted-proxies} 注入到 {@link SecurityUtils} 的静态匹配器。
 * 放在独立的 {@code @Component} 里而不是 {@code AppProperties} 的 {@code @PostConstruct}，
 * 是为了让「配置绑定」与「运行时生效」两件事各自可读、可测。
 *
 * <p><b>为什么必须在启动期就失败得足够响</b>：若生产忘记配置可信代理，
 * 系统不会报错，只会把「所有用户都算作同一个 IP」——表现为限流误伤与审计 IP 全变成
 * 代理地址。这类问题不会崩，只会静默错，所以这里必须打 WARN 提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustedProxyInitializer {

    private final AppProperties appProperties;

    @PostConstruct
    void init() {
        List<String> configured = appProperties.getSecurity().getTrustedProxies();
        SecurityUtils.configureTrustedProxies(configured);
        int count = SecurityUtils.trustedProxyCount();
        if (count == 0) {
            log.warn("未配置可信反向代理网段（app.security.trusted-proxies）："
                    + "所有 X-Forwarded-For / X-Real-IP 将被忽略，客户端 IP 一律取 TCP 对端地址。"
                    + "若部署在 Nginx / 群晖反向代理之后，请配置该网段，否则限流维度会退化为「代理 IP」");
        } else {
            log.info("可信反向代理网段已加载：{} 条（{}）", count, configured);
        }
    }
}
