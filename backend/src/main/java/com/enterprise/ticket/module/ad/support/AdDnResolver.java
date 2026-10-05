package com.enterprise.ticket.module.ad.support;

import com.enterprise.ticket.module.ad.ldap.AdConnection;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * AD 地址 / DN 自动推导（ —— 「系统自动处理」）—— <b>纯函数</b>
 *
 * <h2>它解决什么</h2>
 * 要求把 AD 配置从「十几个技术字段」压到「4 个必填项」，其余由系统自动处理：
 * <ul>
 *   <li><b>基础 DN 自动解析</b>：{@code dc01.company.com} → {@code DC=company,DC=com}；</li>
 *   <li><b>绑定账号自动转完整 DN</b>：{@code company\query} → {@code CN=query,CN=Users,DC=company,DC=com}。</li>
 * </ul>
 * 维护人员只需要知道「服务器叫什么、查询账号密码是什么」，
 * 不必去理解 DN 的 {@code DC=} 语法 —— 那正是「配错了不会报错、只表现为登录一直失败」
 * 的那类字段（见 {@link AdConfigValidator} 的注释）。
 *
 * <h2>为什么是纯函数、且必须落在服务端</h2>
 * <p>纯函数便于穷尽单测（推导规则错一位就会把 {@code DC=company} 写成 {@code DC=com}，
 * 而这种错误只有真连域控才暴露）。
 * <p>推导必须落在<b>服务端</b>而不是只在前端做：前端只能做「预览」，
 * 若服务端不归一化，手工构造的请求（或旧版前端）照样能把不合法的 DN 写进库。
 * 前端通过 {@code POST /api/ad/derive-dn} 复用同一份规则，因此两边永远一致。
 *
 * <h2>推导是「尽力而为」，不是「必须成功」</h2>
 * <p>域地址填 <b>IP</b>（明确允许 {@code 192.168.1.10}）时，
 * 目录里没有任何信息能推出域名 —— 此时一律返回 {@code null}，
 * 由「基础 DN 缺失」的完整性校验提示维护人员到「高级选项」手工填写。
 * <b>刻意不做</b>「猜一个大概的 DN」：猜错会变成一条看起来正常、连上去才失败的配置，
 * 比明确地说「推导不出来」难排查得多。
 */
public final class AdDnResolver {

    private AdDnResolver() {
    }

    /**
     * 服务账号在 AD 中的默认容器。
     *
     * <p>ADUC 新建用户对象的默认位置就是 {@code CN=Users}，因此它是「不额外提供 OU 信息时」
     * 唯一有依据的猜测。真实环境把服务账号放在自定义 OU 的情况很常见 ——
     * 所以这只是一个<b>可编辑的默认值</b>：维护人员在「高级选项 → 自定义绑定 DN」
     * 里填完整的 DN 即可覆盖（推导只在提交值为空时生效）。
     */
    private static final String DEFAULT_USER_CONTAINER = "CN=Users";

    /**
     * 「看起来像服务器主机名」的首段标签。
     *
     * <p>刻意<b>只匹配一个封闭集合</b>（dc / ad / ldap / ldaps / srv / domain 加可选数字），
     * 而不是「含数字的标签」之类的宽泛规则 —— 后者会把 {@code 365company.com}
     * 的首段误剥掉，推出 {@code DC=com} 这种能连上但查不到人的错误配置。
     * 宁可少剥（推成 {@code DC=dc01,DC=company,DC=com}，维护人员一眼看出不对并去高级选项改），
     * 也不要推出一条「看起来像对的」错误值。
     */
    private static final Pattern SERVER_LABEL =
            Pattern.compile("(?i)^(dc|ad|ldap|ldaps|srv|domain)\\d*$");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("^[A-Za-z0-9-]+$");

    /** 一次推导的全部产出（供接口返回与保存流程共用） */
    public record ResolvedDns(String baseDn, String bindDn) {
    }

    // ------------------------------------------------------------------
    // 基础 DN
    // ------------------------------------------------------------------

    /**
     * 从域地址推导基础 DN。
     *
     * @param serverUrls 形如 {@code dc01.company.com} / {@code ldap://dc01.company.com:389} /
     *                   {@code dc1.company.com,dc2.company.com}（取第一个）
     * @return 形如 {@code DC=company,DC=com}；无法推导（IP / 单段主机名 / 空）时返回 {@code null}
     */
    public static String deriveBaseDn(String serverUrls) {
        List<String> hosts = AdConnection.parseHosts(serverUrls);
        if (hosts.isEmpty()) {
            return null;
        }
        return deriveBaseDnFromHost(hosts.get(0));
    }

    /** 从单个主机名推导基础 DN（{@link #deriveBaseDn(String)} 的单主机版本，便于单测） */
    public static String deriveBaseDnFromHost(String host) {
        String value = trimToNull(host);
        if (value == null) {
            return null;
        }
        // IPv6 字面量（[::1]）与 IPv4 都没有域名信息
        if (value.startsWith("[") || IPV4.matcher(value).matches()) {
            return null;
        }
        String[] labels = value.split("\\.");
        // 单段主机名（NetBIOS 名，如 dc01）不含域名信息：拼出 DC=dc01 只会误导
        if (labels.length < 2) {
            return null;
        }
        boolean allNumeric = true;
        for (String label : labels) {
            if (!DIGITS.matcher(label).matches()) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            // 形如 192.168.1.10 或 10.0.0.1（且没被 IPv4 正则覆盖的写法）
            return null;
        }

        int start = 0;
        if (labels.length >= 2 && SERVER_LABEL.matcher(labels[0]).matches()) {
            // 剥掉「主机名前缀」，dc01.company.com → company.com
            start = 1;
        }
        if (start >= labels.length) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < labels.length; i++) {
            String label = labels[i].trim();
            if (label.isEmpty() || !DOMAIN_LABEL.matcher(label).matches()) {
                // 域名段含非法字符（空格 / 逗号 / 下划线…）→ 不猜，交给人工填
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("DC=").append(label);
        }
        return sb.toString();
    }

    /**
     * 生效的基础 DN：<b>显式配置优先</b>，否则由域地址推导。
     *
     * <p>「优先」是刻意的方向：推导只是降低填写门槛的默认值，
     * 一旦维护人员在高级选项里明确写了，系统绝不擅自改写 ——
     * 自研目录、多域森林等场景都依赖「我填什么就是什么」。
     */
    public static String resolveBaseDn(String serverUrls, String explicitBaseDn) {
        String explicit = trimToNull(explicitBaseDn);
        return explicit != null ? explicit : deriveBaseDn(serverUrls);
    }

    // ------------------------------------------------------------------
    // 绑定身份（DN / 下行式 / UPN）
    // ------------------------------------------------------------------

    /**
     * 把各种「绑定账号写法」归一成可直接用于 LDAP 绑定的身份。
     *
     * <p>AD 接受三种写法，本方法统一往 <b>完整 DN</b> 上归一（原文口径）：
     * <table border="1">
     *   <tr><th>输入</th><th>输出</th></tr>
     *   <tr><td>{@code company\query}</td><td>{@code CN=query,CN=Users,DC=company,DC=com}</td></tr>
     *   <tr><td>{@code query@company.com}</td><td>{@code CN=query,CN=Users,DC=company,DC=com}</td></tr>
     *   <tr><td>{@code query}（裸账号）</td><td>{@code CN=query,CN=Users,<baseDn>}</td></tr>
     *   <tr><td>已经是 DN</td><td>原样返回（去空白）</td></tr>
     * </table>
     *
     * <p><b>推不出 baseDn 时退回原样</b>（IP 域地址 + 没有自定义基础 DN）：
     * 此时 {@code company\query} / {@code query@company.com} 就是唯一 OU 无关的绑定标识，
     * AD 与 JNDI 都直接接受，强行拼一个半截 DN 反而会把「能用的写法」改坏。
     *
     * @param rawIdentity 维护人员填写的绑定账号（任意上述写法）
     * @param baseDn      已生效的基础 DN（可能为 {@code null}）
     * @return 归一后的绑定身份；输入为空时返回 {@code null}
     */
    public static String toBindDn(String rawIdentity, String baseDn) {
        String raw = trimToNull(rawIdentity);
        if (raw == null) {
            return null;
        }
        // 已经是 DN：不解析、不改写（RFC 4514 的转义写法太多，重写风险大于收益）
        if (AdConfigValidator.looksLikeDn(raw)) {
            return raw;
        }

        int backslash = raw.indexOf('\\');
        if (backslash >= 0) {
            // Windows 下行式：DOMAIN\account（域段只是提示，真正的归属靠 baseDn）
            String account = trimToNull(raw.substring(backslash + 1));
            if (account == null) {
                return raw;
            }
            String dn = withUserContainer(account, baseDn);
            return dn != null ? dn : raw;
        }

        int at = raw.indexOf('@');
        if (at > 0 && at < raw.length() - 1) {
            // UPN：account@domain —— 域段本身就能推出 baseDn，比参数里的更贴切
            String account = trimToNull(raw.substring(0, at));
            String domain = trimToNull(raw.substring(at + 1));
            String effectiveBase = deriveBaseDnFromHost(domain);
            if (effectiveBase == null) {
                effectiveBase = trimToNull(baseDn);
            }
            if (account == null) {
                return raw;
            }
            String dn = withUserContainer(account, effectiveBase);
            return dn != null ? dn : raw;
        }

        // 裸账号：完全依赖 baseDn
        String dn = withUserContainer(raw, baseDn);
        return dn != null ? dn : raw;
    }

    /**
     * 一次性推导（保存流程与预览接口共用同一入口，保证「预览 = 落库」）。
     *
     * @param serverUrls      本次提交的域地址
     * @param submittedBaseDn 本次提交的基础 DN（空 → 自动推导）
     * @param submittedBindDn 本次提交的绑定账号（任意写法）
     */
    public static ResolvedDns resolve(String serverUrls, String submittedBaseDn, String submittedBindDn) {
        String baseDn = resolveBaseDn(serverUrls, submittedBaseDn);
        String bindDn = toBindDn(submittedBindDn, baseDn);
        return new ResolvedDns(baseDn, bindDn);
    }

    /** 从 DN 里取出首个 RDN 的值（{@code CN=query,CN=Users,DC=...} → {@code query}） */
    public static String firstRdnValue(String dn) {
        String value = trimToNull(dn);
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = comma > 0 ? value.substring(0, comma) : value;
        int eq = first.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        return trimToNull(first.substring(eq + 1));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String withUserContainer(String account, String baseDn) {
        String base = trimToNull(baseDn);
        if (base == null) {
            return null;
        }
        // 账号里若出现 DN 分隔符/首尾空格（从 ADUC 复制粘贴时的常见形态），做最小转义
        String escaped = account.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=");
        return "CN=" + escaped + "," + DEFAULT_USER_CONTAINER + "," + base;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
