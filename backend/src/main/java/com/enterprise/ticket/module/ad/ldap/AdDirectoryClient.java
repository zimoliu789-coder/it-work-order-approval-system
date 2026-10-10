package com.enterprise.ticket.module.ad.ldap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.SizeLimitExceededException;
import javax.naming.TimeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InvalidSearchFilterException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AD / LDAP 目录访问客户端（；）
 *
 * <h2>为什么用 JDK 自带的 JNDI，而不引入 spring-ldap / UnboundID</h2>
 * <ul>
 *   <li><b>零新增依赖</b>：JNDI 的 LDAP provider（{@code com.sun.jndi.ldap}）是 JDK 内置的，
 *       本模块不需要往 {@code pom.xml} 里加任何东西，镜像体积与依赖审计面都不变；</li>
 *   <li><b>Spring LDAP 底层同样是 JNDI</b>：它提供的价值主要在 {@code LdapTemplate} 的
 *       对象映射与异常翻译，而本模块只有「绑定 + 搜索」两种操作，
 *       自己写反而更短、更好定位问题；</li>
 *   <li><b>动态配置</b>：AD 配置是运行期可改的（配置页保存即生效），
 *       Spring LDAP 的 {@code LdapContextSource} 是 Bean，改配置就得刷新 Bean；
 *       JNDI 每次请求新建上下文，天然支持热更新。</li>
 * </ul>
 *
 * <h2>本类负责什么、不负责什么</h2>
 * <p>只负责<b>协议层</b>：建立连接、绑定、搜索、把结果拆成 {@link AdUser}、把
 * {@link NamingException} 翻译成带 {@link AdDirectoryException.Kind} 的业务异常。
 * 与「配置从哪来」「同步怎么写库」「登录怎么分支」完全无关 ——
 * 后者分别在 {@code AdConfigService} / {@code AdUserSyncService} / {@code AuthService}。
 *
 * <h2>三个必须显式处理的坑</h2>
 * <ol>
 *   <li><b>空口令 = 匿名绑定成功</b>：JNDI 在 {@code SECURITY_CREDENTIALS} 为空时
 *       会退化为<b>匿名绑定</b>并"认证成功"。如果不拦住，用户只要提交空密码就能登录任意账号。
 *       {@link #verifyUserCredentials} 因此在建连接之前就把空口令判为失败；</li>
 *   <li><b>referral 导致假失败</b>：AD 在林 / 多域环境下会返回引用（referral），
 *       JNDI 默认会尝试跟随并可能以 {@code PartialResultException} 结束搜索。
 *       这里统一设 {@code Context.REFERRAL=ignore}，把「有结果但有引用」当作正常结束；</li>
 *   <li><b>AD 默认单次最多返回 1000 条</b>（MaxPageSize）。不启用分页时，
 *       超过 1000 人的目录同步会静默只同步前 1000 个 —— 且没有任何报错。
 *       这里用 {@code PagedResultsControl} 分页拉取（criticality=false，
 *       以便不支持分页的目录服务忽略该控制而不是直接报错）。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>无状态（不持有任何上下文或缓存），每次调用自建连接并关闭，可被并发调用。
 */
@Slf4j
@Component
public class AdDirectoryClient {

    private static final String CTX_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
    private static final String CONNECT_TIMEOUT_PROP = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT_PROP = "com.sun.jndi.ldap.read.timeout";
    private static final String ENDPOINT_ID_PROP = "com.sun.jndi.ldap.object.disableEndpointIdentification";

    private static final String GUID_ATTR = "objectGUID";

    /** 分页大小：500 是 AD 与 OpenLDAP 都稳定支持的经验值（均低于 AD 默认的 1000 上限） */
    private static final int PAGE_SIZE = 500;

    /** 分页循环上限：防止目录服务返回「永不变空」的 cookie 导致死循环 */
    private static final int MAX_PAGES = 400;

    /** 全量同步的硬上限：即便配置写了 0（不限），也不会把内存拉爆 */
    private static final int HARD_LIMIT = 100_000;

    /**
     * 单次连接尝试的最小超时预算（毫秒）。
     *
     * <p>当总预算快用完时，给最后一台服务器留一个「能连上就一定能返回」的最小时长，
     * 而不是 0 —— 传 0 给 JNDI 会被当作「无限等待」，那是与初衷完全相反的行为。
     * 200ms 不足以完成一次真实绑定，因此这种极端情况下必然失败并落入「域控不可用」，
     * 这正是我们要的结论：宁可快速降级到本地认证，也不要挂着不放。
     */
    private static final int MIN_ATTEMPT_MILLIS = 200;

    // ------------------------------------------------------------------
    // 对外操作
    // ------------------------------------------------------------------

    /**
     * 连接测试：逐个服务器尝试「建连 + 服务账号绑定 + 在基础 DN 下搜一条」。
     *
     * <p>三步缺一不可：只建连不绑定，测不出绑定账号写错（这是最常见的配置错误）；
     * 只绑定不搜索，测不出基础 DN 写错（第二常见）。
     *
     * @return 探测到的用户数（上限 {@code sampleLimit}）；失败抛 {@link AdDirectoryException}
     */
    public int probe(AdConnection conn, int sampleLimit) {
        List<AdUser> sample = fetch(conn, Math.max(sampleLimit, 1));
        return sample.size();
    }

    /**
     * 全量拉取用户（分页）
     *
     * @param limit 期望上限，{@code <= 0} 表示不限（仍受 {@link #HARD_LIMIT} 约束）
     */
    public List<AdUser> fetchAll(AdConnection conn, int limit) {
        return fetch(conn, limit);
    }

    /**
     * 按登录名查找用户
     *
     * @return 匹配到的第一个用户（登录名在 AD 内唯一）
     * @throws AdDirectoryException {@link AdDirectoryException.Kind#USER_NOT_FOUND} 表示目录中没有该账号
     */
    public AdUser findByAccount(AdConnection conn, String account) {
        if (!StringUtils.hasText(account)) {
            throw new AdDirectoryException(AdDirectoryException.Kind.USER_NOT_FOUND, "登录名为空，无法在域控中查找");
        }
        String filter = conn.accountFilter(account);
        List<AdUser> found = searchWithFailover(conn, filter, 2);
        if (found.isEmpty()) {
            throw new AdDirectoryException(AdDirectoryException.Kind.USER_NOT_FOUND,
                    "域控中不存在账号：" + account);
        }
        return found.get(0);
    }

    /**
     * 用「用户自己的 DN + 用户口令」做一次绑定，验证域口令是否正确。
     *
     * <p><b>这是整个 AD 登录的关键一步</b>：不掌握用户口令的服务账号无法代替用户验证口令，
     * 唯一的办法就是拿用户 DN 与口令真的去 bind 一次。
     *
     * @throws AdDirectoryException {@link AdDirectoryException.Kind#BAD_CREDENTIALS} 口令错误；
     *                              {@link AdDirectoryException.Kind#UNAVAILABLE} 域控不可达
     */
    public void verifyUserCredentials(AdConnection conn, String userDn, String password) {
        if (!StringUtils.hasText(userDn)) {
            throw new AdDirectoryException(AdDirectoryException.Kind.CONFIG, "用户 DN 为空，无法验证域口令");
        }
        // ⚠️ 空口令必须在此拦下：JNDI 在凭据为空时执行匿名绑定并会「成功」，
        // 那样任何人提交空密码都能通过域认证。这不是理论风险，是 LDAP 客户端的经典陷阱。
        if (!StringUtils.hasText(password)) {
            throw new AdDirectoryException(AdDirectoryException.Kind.BAD_CREDENTIALS, "域口令不能为空");
        }

        Exception lastError = null;
        long deadline = System.currentTimeMillis() + conn.timeoutMillis();
        for (String host : conn.hosts()) {
            int budget = remainingBudgetMillis(deadline);
            if (budget <= 0) {
                throw new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                        "域控认证整体超时（已用尽 " + conn.timeoutSeconds() + " 秒预算）");
            }
            LdapContext ctx = null;
            try {
                ctx = new InitialLdapContext(buildEnv(conn, host, userDn, password, budget), null);
                // 绑定成功即认证通过；此处立刻关闭，不做任何查询（用户可能无权查询目录）
                return;
            } catch (AuthenticationException e) {
                // 口令错 —— 与服务器无关，不必再试下一台
                throw new AdDirectoryException(AdDirectoryException.Kind.BAD_CREDENTIALS, "域口令校验失败", e);
            } catch (Exception e) {
                lastError = e;
                log.warn("域口令校验连接 {}:{} 失败：{}", host, conn.port(), e.getMessage());
            } finally {
                closeQuietly(ctx);
            }
        }
        throw new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                "域控服务器均不可用：" + (lastError == null ? "无可用地址" : lastError.getMessage()), lastError);
    }

    /**
     * 计算到统一截止时间为止还剩多少毫秒预算。
     *
     * @return 剩余预算；已耗尽返回 {@code 0}
     */
    private int remainingBudgetMillis(long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (int) remaining;
    }

    // ------------------------------------------------------------------
    // 内部：搜索
    // ------------------------------------------------------------------

    private List<AdUser> fetch(AdConnection conn, int limit) {
        int effectiveLimit = limit <= 0 ? HARD_LIMIT : Math.min(limit, HARD_LIMIT);
        return searchWithFailover(conn, conn.allFilter(), effectiveLimit);
    }

    /**
     * 带主备轮询的搜索：按配置顺序逐台尝试，第一台「连不上」就换下一台。
     *
     * <p>只对 {@link AdDirectoryException.Kind#UNAVAILABLE} 做故障转移：
     * 凭据错 / 配置错 / 用户不存在都是<b>与服务器无关</b>的结论，换一台照样失败，
     * 重试只会把超时乘以服务器台数，让登录卡得更久。
     *
     * <p>所有服务器共享同一个截止时间（见 {@link #buildEnv} 的说明），
     * 因此「配了 3 台备机」不会让单次登录的最坏耗时变成 3 倍。
     */
    private List<AdUser> searchWithFailover(AdConnection conn, String filter, int limit) {
        if (conn.hosts().isEmpty()) {
            throw new AdDirectoryException(AdDirectoryException.Kind.CONFIG, "未配置 AD 服务器地址");
        }
        AdDirectoryException lastFailure = null;
        long deadline = System.currentTimeMillis() + conn.timeoutMillis();
        for (String host : conn.hosts()) {
            int budget = remainingBudgetMillis(deadline);
            if (budget <= 0) {
                break;
            }
            try {
                return search(conn, host, filter, limit, budget);
            } catch (AdDirectoryException e) {
                if (e.getKind() != AdDirectoryException.Kind.UNAVAILABLE) {
                    throw e;
                }
                lastFailure = e;
                log.warn("AD 服务器 {}:{} 不可用，尝试下一台：{}", host, conn.port(), e.getMessage());
            }
        }
        throw lastFailure == null
                ? new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                        "域控服务器均不可用或已耗尽 " + conn.timeoutSeconds() + " 秒超时预算")
                : lastFailure;
    }

    private List<AdUser> search(AdConnection conn, String host, String filter, int limit, int timeoutMillis) {
        long started = System.currentTimeMillis();
        LdapContext ctx = null;
        try {
            ctx = new InitialLdapContext(
                    buildEnv(conn, host, conn.bindDn(), conn.bindPassword(), timeoutMillis), null);

            List<AdUser> users = new ArrayList<>();
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(wantedAttributes(conn.mapping()));
            // countLimit 用 remaining + 1：多取一条用于识别「被截断」，调用方可据此告警
            controls.setCountLimit(Math.min((long) limit + 1, HARD_LIMIT));

            byte[] cookie = null;
            int pages = 0;
            do {
                ctx.setRequestControls(new Control[]{
                        new PagedResultsControl(PAGE_SIZE, cookie, Control.NONCRITICAL)});
                collect(ctx.search(conn.baseDn(), filter, controls), conn.mapping(), users, limit);
                if (users.size() >= limit) {
                    break;
                }
                cookie = nextCookie(ctx.getResponseControls());
                pages++;
            } while (cookie != null && pages < MAX_PAGES);

            if (pages >= MAX_PAGES) {
                log.warn("AD 分页搜索达到页数上限 {}，结果可能不完整（查询 {}）", MAX_PAGES, filter);
            }
            log.debug("AD 搜索完成：host={} 命中 {} 条，耗时 {} ms", host, users.size(),
                    System.currentTimeMillis() - started);
            return users;
        } catch (AuthenticationException e) {
            // 服务账号绑定被拒 = 配置错（绑定 DN 或密码不对），不是「域控挂了」
            throw new AdDirectoryException(AdDirectoryException.Kind.CONFIG,
                    "绑定账号认证失败，请检查绑定 DN 与绑定密码", e);
        } catch (InvalidSearchFilterException e) {
            throw new AdDirectoryException(AdDirectoryException.Kind.CONFIG,
                    "用户搜索过滤器语法错误：" + e.getMessage(), e);
        } catch (PartialResultException e) {
            // 已设 REFERRAL=ignore，正常不应触发；一旦触发说明目录返回了无法解析的引用
            throw new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "域控返回了无法解析的引用（referral）", e);
        } catch (IOException e) {
            // PagedResultsControl 构造器声明的受检异常：仅在页大小非法或分页 cookie 过大时抛出。
            // 这两者都由本类的常量与上一轮响应决定，属「目录返回了无法处理的分页状态」，
            // 归为 UNAVAILABLE 让上层按常规降级处理，而不是把受检异常泄漏到调用方。
            throw new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "域控分页查询失败：" + e.getMessage(), e);
        } catch (NamingException e) {
            throw translate(e, host, conn.port());
        } finally {
            closeQuietly(ctx);
        }
    }

    /** 把搜索结果追加到 {@code employee}（上限 {@code limit}） */
    private void collect(NamingEnumeration<SearchResult> results, AdAttributeMapping mapping,
                         List<AdUser> users, int limit) throws NamingException {
        try {
            while (results != null && results.hasMore() && users.size() < limit) {
                SearchResult result = results.next();
                AdUser user = toAdUser(result, mapping);
                // 过滤掉没有登录名的条目：可能是计算机账号或组，
                // 它们能匹配到 objectClass=user 之外的过滤器（取决于现场配置），建成账号只会污染列表
                if (user.usable()) {
                    users.add(user);
                }
            }
        } catch (SizeLimitExceededException e) {
            // 目录侧限制了返回条数：已有结果照常使用，只是不完整 —— 由调用方决定是否告警
            log.warn("AD 搜索被服务端大小限制截断，已返回 {} 条", users.size());
        } catch (TimeLimitExceededException e) {
            throw new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE, "AD 搜索超时", e);
        } finally {
            try {
                if (results != null) {
                    results.close();
                }
            } catch (NamingException ignored) {
                // 关闭失败不影响已取到的结果
            }
        }
    }

    private AdUser toAdUser(SearchResult result, AdAttributeMapping mapping) throws NamingException {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        Attributes raw = result.getAttributes();
        if (raw != null) {
            NamingEnumeration<? extends Attribute> all = raw.getAll();
            while (all.hasMore()) {
                Attribute attribute = all.next();
                String name = attribute.getID();
                List<String> values = new ArrayList<>();
                NamingEnumeration<?> valueEnum = attribute.getAll();
                while (valueEnum.hasMore()) {
                    Object value = valueEnum.next();
                    if (value instanceof byte[] bytes && GUID_ATTR.equalsIgnoreCase(name)) {
                        // objectGUID 是 16 字节二进制：必须转十六进制，
                        // 直接按字符串读会得到一串不可读且可能丢失字节的乱码
                        values.add(AdAttributeMapping.hexOf(bytes));
                    } else {
                        values.add(AdAttributeMapping.textOf(value));
                    }
                }
                attrs.put(name, values);
            }
        }
        String dn = result.getNameInNamespace();
        if (!StringUtils.hasText(dn)) {
            dn = result.getName();
        }
        return mapping.map(dn, attrs);
    }

    /** 读取分页响应里的 cookie；无分页响应返回 null（表示已取完或服务端不支持分页） */
    private byte[] nextCookie(Control[] responseControls) {
        if (responseControls == null) {
            return null;
        }
        for (Control control : responseControls) {
            if (control instanceof PagedResultsResponseControl paged) {
                byte[] cookie = paged.getCookie();
                return cookie == null || cookie.length == 0 ? null : cookie;
            }
        }
        return null;
    }

    /** 需要向目录索取的属性集合（映射里配的属性 + cn 回退 + objectGUID） */
    private String[] wantedAttributes(AdAttributeMapping mapping) {
        Set<String> names = new LinkedHashSet<>();
        addIfPresent(names, mapping.attrLogin());
        addIfPresent(names, mapping.attrName());
        addIfPresent(names, mapping.attrEmail());
        addIfPresent(names, mapping.attrPhone());
        addIfPresent(names, mapping.attrDept());
        addIfPresent(names, mapping.attrStatus());
        names.add("cn");
        names.add(GUID_ATTR);
        return names.toArray(new String[0]);
    }

    private void addIfPresent(Set<String> target, String name) {
        if (StringUtils.hasText(name)) {
            target.add(name.trim());
        }
    }

    // ------------------------------------------------------------------
    // 内部：JNDI 环境与错误翻译
    // ------------------------------------------------------------------

    /**
     * 组装 JNDI 环境
     *
     * @param timeoutMillis <b>本次尝试</b>可用的超时预算（毫秒）。
     *                      注意它是「剩余总预算」而不是 {@code conn.timeoutMillis()}：
     *                      AD 配了 3 台服务器时，若每台都各给 5 秒，
     *                      一次登录最坏会挂 15 秒 —— 而需求明确要求
     *                      「AD 挂了不能把全站登录拖死（5 秒超时）」。
     *                      因此 {@link #searchWithFailover} / {@link #verifyUserCredentials}
     *                      按<b>统一截止时间</b>分摊预算，总耗时恒不超过配置的超时值。
     */
    private Hashtable<String, Object> buildEnv(AdConnection conn, String host, String principal,
                                              String credentials, int timeoutMillis) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, CTX_FACTORY);
        env.put(Context.PROVIDER_URL, buildUrl(conn.ssl(), host, conn.port()));

        if (!StringUtils.hasText(principal)) {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, principal);
            env.put(Context.SECURITY_CREDENTIALS, credentials == null ? "" : credentials);
        }

        String timeout = String.valueOf(Math.max(timeoutMillis, MIN_ATTEMPT_MILLIS));
        env.put(CONNECT_TIMEOUT_PROP, timeout);
        env.put(READ_TIMEOUT_PROP, timeout);

        // 不跟随 referral：AD 林 / 多域环境下引用会导致搜索以 PartialResultException 收场
        env.put(Context.REFERRAL, "ignore");

        if (conn.ssl() && !conn.strictCert()) {
            // 「跳过证书校验」模式：自定义 socket 工厂 + 关闭主机名校验。
            // 主机名校验是独立于信任链的第二步检查，只换 socket 工厂仍然会因
            // 「证书 CN 与连接主机名不一致」而握手失败，两者必须同时放开。
            applyEndpointIdentification(false);
            env.put("java.naming.ldap.factory.socket", TrustAllSocketFactory.class.getName());
        } else if (conn.ssl()) {
            // 恢复严格模式：上一次「跳过」若已把该属性置位，不能留在进程里影响本次连接
            applyEndpointIdentification(true);
        }
        return env;
    }

    private String buildUrl(boolean ssl, String host, int port) {
        String scheme = ssl ? "ldaps" : "ldap";
        return scheme + "://" + host + ":" + port;
    }

    /**
     * 切换 JNDI 的主机名校验开关。
     *
     * <p>这是一个<b>进程级</b>系统属性，不随上下文销毁而复位，所以必须显式双向设置：
     * 「跳过校验」用完若不复位，之后所有 LDAPS 连接都会带着被削弱的安全设置，
     * 包括管理员把配置改回严格校验之后的那些。
     */
    private void applyEndpointIdentification(boolean strict) {
        try {
            if (strict) {
                if (System.getProperty(ENDPOINT_ID_PROP) != null) {
                    System.clearProperty(ENDPOINT_ID_PROP);
                }
            } else if (!"true".equals(System.getProperty(ENDPOINT_ID_PROP))) {
                System.setProperty(ENDPOINT_ID_PROP, "true");
                log.warn("AD 配置已关闭证书严格校验，LDAPS 将不校验服务端证书与主机名 —— 存在中间人风险");
            }
        } catch (SecurityException e) {
            log.warn("无法设置 JNDI 主机名校验开关（安全策略限制）：{}", e.getMessage());
        }
    }

    /** 把 JNDI 异常翻译成带业务语义的 {@link AdDirectoryException} */
    private AdDirectoryException translate(NamingException e, String host, int port) {
        Throwable root = rootCause(e);
        String where = host + ":" + port;
        if (root instanceof UnknownHostException) {
            return new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "无法解析域控主机名：" + where, e);
        }
        if (root instanceof ConnectException) {
            return new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "无法连接域控：" + where + "（" + root.getMessage() + "）", e);
        }
        if (root instanceof SocketTimeoutException) {
            return new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "连接或读取域控超时：" + where, e);
        }
        if (e instanceof CommunicationException || e instanceof TimeLimitExceededException) {
            return new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                    "与域控通信失败：" + where + "（" + e.getMessage() + "）", e);
        }
        // 基础 DN 不存在 / 无权限搜索等：属于配置问题，但对登录而言同样只能降级处理
        return new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE,
                "域控访问失败：" + where + "（" + e.getMessage() + "）", e);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 10) {
            current = current.getCause();
        }
        return current;
    }

    private void closeQuietly(DirContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.close();
        } catch (NamingException e) {
            log.debug("关闭 LDAP 上下文失败（已忽略）：{}", e.getMessage());
        }
    }

    /** 供配置页展示：当前实现使用的分页大小（避免魔法数字散落） */
    public int pageSize() {
        return PAGE_SIZE;
    }

    /** 是否使用了「跳过证书校验」的宽松模式（供测试连接结果提示） */
    public static boolean isInsecure(AdConnection conn) {
        return conn.ssl() && !conn.strictCert();
    }

    /** 用于日志的脱敏地址（不打印 DN 与密码） */
    public static String describeHost(AdConnection conn) {
        return String.format(Locale.ROOT, "%s://%s:%d", conn.ssl() ? "ldaps" : "ldap",
                conn.primaryHost(), conn.port());
    }
}
