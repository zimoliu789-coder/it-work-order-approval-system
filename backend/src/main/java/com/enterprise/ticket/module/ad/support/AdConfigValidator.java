package com.enterprise.ticket.module.ad.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AD 配置校验（；同步节奏校验 ）—— <b>纯函数</b>，便于单测穷尽覆盖
 *
 * <h2>为什么校验必须拆出来、且必须存在</h2>
 * <p>AD 配置是「填错了不会报错，只会表现为登录一直失败」的典型场景：
 * 基础 DN 写少一级、过滤器少一个括号、端口与 SSL 不匹配 —— 绝大多数组合
 * 都不会让应用启动失败，只会在用户登录时变成一句「账号或密码错误」。
 * 这类问题的定位成本极高（要从应用日志一路查到域控事件查看器），
 * 因此在保存入口把可判定的问题一次性拦住，是本模块性价比最高的一段代码。
 *
 * <h2>两层校验</h2>
 * <ol>
 *   <li><b>格式校验</b>（总是执行）：端口范围、过滤器占位符与括号配平、DN 形状、
 *       属性名合法性、同步时刻范围；</li>
 *   <li><b>完整性校验</b>（仅当「启用」或「测试连接」时执行）：服务器地址、基础 DN、
 *       绑定 DN、绑定密码必须齐备。
 *       <p>之所以允许不完整地保存：现场的域控信息往往要到 IT 那边去要，
 *       强制「一次填全才能保存」会让管理员无法暂存草稿，只能拿记事本记着。</li>
 * </ol>
 *
 * <h2>刻意不做的校验</h2>
 * <p><b>不</b>在「启用 LDAPS + 端口 389」时报错。虽然这几乎总是笔误，但确实存在
 * 域控被改成非标准端口的现场；把它判成非法会让这类环境永远配不上。
 * 前端的做法是「打开 LDAPS 开关时自动把端口从 389 切到 636」——
 * 用交互消除笔误，比用校验拒绝配置更合适。
 *
 * <p>同样地，<b>不</b>校验「属性映射是否指向了目录里真实存在的属性」——
 * 这需要连一次域控才知道，属于「测试连接」的职责（探测时读不到登录名会直接暴露）。
 */
public final class AdConfigValidator {

    private AdConfigValidator() {
    }

    public static final int DEFAULT_PORT = 389;
    public static final int DEFAULT_LDAPS_PORT = 636;
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 30;

    /** 每日同步时刻的默认值：凌晨 2 点（避开上班时间） */
    public static final int DEFAULT_SYNC_HOUR = 2;
    public static final int MIN_SYNC_HOUR = 0;
    public static final int MAX_SYNC_HOUR = 23;

    /** 过滤器必须包含账号占位符，否则无法按登录名精确定位用户 */
    private static final String PLACEHOLDER = AdConnection.ACCOUNT_PLACEHOLDER;

    /**
     * 校验（含完整性）
     *
     * @param request             提交的配置
     * @param requireComplete     是否要求配置完整（启用 / 测试连接时为 true）
     * @param hasStoredPassword   库中是否已有绑定密码密文
     * @param clearingPassword   本次是否要求清空密码
     * @throws BusinessException {@link ErrorCode#AD_CONFIG_INCOMPLETE} 或 {@code PARAM_INVALID}
     */
    public static void validate(AdConfigRequest request, boolean requireComplete,
                                boolean hasStoredPassword, boolean clearingPassword) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 AD 配置内容");
        }

        // ---------------- 格式校验 ----------------
        int port = request.getServerPort() == null ? DEFAULT_PORT : request.getServerPort();
        if (port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "端口取值范围为 1-65535");
        }
        if (request.getConnectTimeoutSeconds() != null
                && (request.getConnectTimeoutSeconds() < MIN_TIMEOUT_SECONDS
                || request.getConnectTimeoutSeconds() > MAX_TIMEOUT_SECONDS)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "连接超时取值范围为 " + MIN_TIMEOUT_SECONDS + "-" + MAX_TIMEOUT_SECONDS + " 秒");
        }
        if (request.getSyncHour() != null
                && (request.getSyncHour() < MIN_SYNC_HOUR || request.getSyncHour() > MAX_SYNC_HOUR)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "每日同步时刻取值范围为 " + MIN_SYNC_HOUR + "-" + MAX_SYNC_HOUR + " 时");
        }

        String filter = trimToNull(request.getUserFilter());
        if (filter != null) {
            if (!hasBalancedParens(filter)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "用户搜索过滤器的括号不配平：" + filter);
            }
            if (!filter.contains(PLACEHOLDER)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "用户搜索过滤器必须包含账号占位符 " + PLACEHOLDER + "，例如 "
                                + AdConnection.DEFAULT_FILTER);
            }
        } else if (requireComplete) {
            filter = AdConnection.DEFAULT_FILTER;
        }

        String baseDn = trimToNull(request.getBaseDn());
        if (baseDn != null && !looksLikeDn(baseDn)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "基础 DN 格式不正确（应形如 DC=company,DC=com）：" + baseDn);
        }
        String bindDn = trimToNull(request.getBindDn());
        if (bindDn != null && !looksLikeBindIdentity(bindDn)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "绑定账号格式不正确：可填完整 DN（CN=ldapquery,CN=Users,DC=company,DC=com）、"
                            + "下行式（company\\query）或 UPN（query@company.com）");
        }

        String attrLogin = trimToNull(request.getAttrLogin());
        if (attrLogin != null && !isValidAttributeName(attrLogin)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "登录名属性名不合法（只允许字母、数字、连字符）：" + attrLogin);
        }
        for (String attr : new String[]{request.getAttrName(), request.getAttrEmail(),
                request.getAttrPhone(), request.getAttrDept(), request.getAttrStatus()}) {
            String value = trimToNull(attr);
            if (value != null && !isValidAttributeName(value)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "属性名不合法：" + value);
            }
        }

        String serverUrls = trimToNull(request.getServerUrls());
        if (serverUrls != null && AdConnection.parseHosts(serverUrls).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "服务器地址解析为空，请检查填写内容");
        }

        // ---------------- 完整性校验 ----------------
        if (!requireComplete) {
            return;
        }
        if (clearingPassword && !hasStoredPassword) {
            // 本来就没有密码可清，属于前端状态错乱；直接放行即可，不算错误
            return;
        }

        List<String> missing = new ArrayList<>();
        if (serverUrls == null) {
            missing.add("服务器地址");
        }
        if (baseDn == null) {
            missing.add("基础 DN");
        }
        if (bindDn == null) {
            missing.add("绑定 DN");
        }
        // 密码：本次没填且库里也没有 → 缺失。刻意不把「密码有值」当作可回显的判断依据 ——
        // 接口只发明文，库里的密文不能也不该拿来当「已配置」的判据之外的用途
        boolean passwordProvided = StringUtils.hasText(request.getBindPassword());
        if (!clearingPassword && !passwordProvided && !hasStoredPassword) {
            missing.add("绑定密码");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.AD_CONFIG_INCOMPLETE,
                    "启用 AD 认证前请先补全：" + String.join("、", missing));
        }
    }

    /** 过滤器括号是否配平（同时拒绝出现反括号先于正括号的情况） */
    public static boolean hasBalancedParens(String filter) {
        if (!StringUtils.hasText(filter)) {
            return false;
        }
        int depth = 0;
        for (char c : filter.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /**
     * 是否「看起来像」一个 DN。
     *
     * <p>只做形状判断（含 {@code =} 且以属性名开头），不解析 RDN 序列 ——
     * 严格的 DN 语法校验需要完整的 RFC 4514 解析器，而现场 DN 里的转义写法
     * （如 {@code CN=张\, 三,OU=研发,DC=company,DC=com}）用正则误判的概率
     * 远高于漏判。真正的验证交给「测试连接」去连一次。
     */
    public static boolean looksLikeDn(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String dn = value.trim();
        int eq = dn.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String firstAttr = dn.substring(0, eq).trim();
        return isValidAttributeName(firstAttr);
    }

    /** 属性名合法性：字母 / 数字 / 连字符（覆盖 sAMAccountName、userAccountControl、objectGUID 等） */
    public static boolean isValidAttributeName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        return name.trim().matches("[A-Za-z][A-Za-z0-9-]*");
    }

    /**
     * 绑定身份是否可用作 LDAP 绑定。
     *
     * <p>比 {@link #looksLikeDn} <b>刻意宽松</b>：AD 接受三种写法，
     * 而绑定身份只是 JNDI 的 {@code SECURITY_PRINCIPAL}，三种都原生支持：
     * <ul>
     *   <li>完整 DN：{@code CN=ldapquery,CN=Users,DC=company,DC=com}</li>
     *   <li>下行式：{@code company\query}</li>
     *   <li>UPN：{@code query@company.com}</li>
     * </ul>
     *
     * <p>基础 DN 则必须严格保持 DN 形状 —— 它是搜索起点（{@code search(baseObject)}），
     * 非 DN 的值在 LDAP 协议层面就是错的。
     */
    public static boolean looksLikeBindIdentity(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String v = value.trim();
        if (looksLikeDn(v)) {
            return true;
        }
        int backslash = v.indexOf('\\');
        if (backslash > 0 && backslash < v.length() - 1) {
            // DOMAIN\account：两侧都必须有内容，避免把单个 "\" 放过去
            return true;
        }
        int at = v.indexOf('@');
        return at > 0 && at < v.length() - 1;
    }

    /** 端口兜底：未填时按是否 SSL 取默认值 */
    public static int normalizePort(Integer port, boolean ssl) {
        if (port != null) {
            return port;
        }
        return ssl ? DEFAULT_LDAPS_PORT : DEFAULT_PORT;
    }

    /** 超时兜底（并钳制到合法区间，防止历史脏数据让每次登录都卡 10 分钟） */
    public static int normalizeTimeout(Integer seconds) {
        if (seconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(seconds, MAX_TIMEOUT_SECONDS));
    }

    /**
     * 每日同步时刻兜底（钳到 0-23）。
     *
     * <p><b>它作用于「从库里读出来的值」，不作用于「提交上来的值」</b> ——
     * 提交值是先过 {@link #validate} 的：越界会被明确拒绝（与端口 / 超时同口径），
     * 因此这里不需要、也不应该替提交值做静默修正。
     *
     * <p>为什么读库路径必须钳制：库里若残留 {@code 99}（人工 SQL、旧版本写入），
     * 定时任务「当前小时 == 配置时刻」的判定将<b>永远不成立</b>，
     * 表现为「开关是开的，却从来没有自动同步过」——
     * 静默失效，且排查时没有任何一条日志能指向它。
     */
    public static int normalizeSyncHour(Integer hour) {
        if (hour == null) {
            return DEFAULT_SYNC_HOUR;
        }
        return Math.max(MIN_SYNC_HOUR, Math.min(hour, MAX_SYNC_HOUR));
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
