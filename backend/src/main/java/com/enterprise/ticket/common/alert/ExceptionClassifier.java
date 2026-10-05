package com.enterprise.ticket.common.alert;

import com.enterprise.ticket.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 异常分类 / 分级 / 去重指纹 —— <b>纯函数</b>。
 *
 * <h2>为什么把这一层单独抽出来且做成纯函数</h2>
 * 它决定了三件「错了也不会报错」的事：
 * <ol>
 *   <li><b>该不该告警</b> —— 判错了要么把邮箱打爆、要么真故障静默；</li>
 *   <li><b>用什么等级告警</b> —— 判错了半夜会被叫醒、或事故被攒进日报；</li>
 *   <li><b>两条异常算不算「同一个问题」</b> —— 指纹算错 ⇒ 去重失效，同类异常逐条发邮件。</li>
 * </ol>
 * 这三件事在集成测试里都很难覆盖（要造真实异常 + 真实时间流逝），
 * 但作为纯函数可以逐条钉死。因此本类<b>不注入任何 Bean</b>、不读数据库、不碰时间源以外的外部状态。
 *
 * <h2>分类靠「类名 + 因果链」而不是靠类型</h2>
 * 兜底处理器拿到的是 {@code Exception}，具体类型五花八门（还可能被 Spring 包装过，
 * 真实原因在 {@code getCause()} 里）。因此本类**沿因果链逐层匹配**，
 * 并按「越靠近数据 / 越底层越优先」的顺序取分类 ——
 * 一个「邮件发送失败」如果底层其实是 SQL 异常，应该报成数据库问题。
 */
public final class ExceptionClassifier {

    private ExceptionClassifier() {
    }

    /** 因果链最多看 10 层：够覆盖 Spring / MyBatis / JDBC 的包装，又不会在自引用链上转圈 */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** 消息落库前的最大长度（与 {@code exception_log.message} 列宽一致） */
    public static final int MAX_MESSAGE_LENGTH = 1000;

    /**
     * 指纹计算时**必须跳过**的栈帧前缀。
     *
     * <p>这些是本项目自己的横切层（异常处理 / 告警记录 / Web 包装）与 Spring 框架层 ——
     * 它们出现在每一条异常的栈里。若不跳过，「业务代码哪里出的错」这个信息就被冲掉了，
     * 所有异常会算成同一个指纹、全部合并成一条告警。
     */
    private static final Set<String> DIGEST_EXCLUDED_PREFIXES = Set.of(
            "com.enterprise.ticket.common.exception.",
            "com.enterprise.ticket.common.alert.",
            "com.enterprise.ticket.common.web.",
            "org.springframework.",
            "java.lang.reflect.",
            "jdk.internal.",
            "jakarta.servlet.",
            "org.apache.catalina.");

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    /** 分类标记：类名包含任一子串即归入该分类 */
    private static final String[] DATABASE_MARKERS = {
            "java.sql.SQL", "SQLException", "SQLSyntaxErrorException", "SQLIntegrityConstraintViolationException",
            "org.springframework.dao.", "DataAccessException", "DuplicateKeyException",
            "DataIntegrityViolationException", "CannotGetJdbcConnectionException",
            "org.apache.ibatis.exceptions.", "PersistenceException", "MyBatisSystemException",
            "com.mysql.cj.jdbc.exceptions.", "BadSqlGrammarException"};

    private static final String[] NETWORK_MARKERS = {
            "java.net.SocketException", "java.net.ConnectException", "java.net.UnknownHostException",
            "java.net.SocketTimeoutException", "java.net.NoRouteToHostException", "java.net.PortUnreachableException",
            "java.nio.channels.ClosedChannelException", "HttpHostConnectException", "ConnectTimeoutException"};

    private static final String[] THIRD_PARTY_MARKERS = {
            "org.springframework.mail.", "jakarta.mail.", "javax.mail.", "MessagingException",
            "MailSendException", "MailAuthenticationException", "MailException",
            "org.springframework.data.redis.", "RedisConnectionFailureException", "RedisSystemException"};

    private static final String[] PARAM_MARKERS = {
            "IllegalArgumentException", "NumberFormatException",
            "org.springframework.web.bind.", "MethodArgumentNotValidException", "BindException",
            "jakarta.validation.ConstraintViolationException", "ConstraintViolationException",
            "HttpMessageNotReadableException", "MethodArgumentTypeMismatchException",
            "MissingServletRequestParameterException", "MissingServletRequestPartException",
            "HttpRequestMethodNotSupportedException", "com.fasterxml.jackson.",
            "JsonParseException", "MismatchedInputException"};

    /**
     * 判定异常分类。
     *
     * <p>沿因果链收集所有层级的类名，再按**优先级**（数据库 &gt; 网络 &gt; 第三方 &gt; 参数 &gt; 业务）
     * 取第一个命中的分类。优先级而不是「取最深层」，是因为最深层可能是 JDBC 驱动内部的
     * 某个通用异常，而中间层已经给出了更有意义的归因（例如 {@code DuplicateKeyException}）。
     */
    public static ExceptionCategory classify(Throwable throwable) {
        if (throwable == null) {
            return ExceptionCategory.UNKNOWN;
        }
        boolean business = false;
        boolean param = false;
        boolean thirdParty = false;
        boolean network = false;

        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            String name = current.getClass().getName();
            if (matches(name, DATABASE_MARKERS)) {
                return ExceptionCategory.DATABASE;
            }
            network = network || matches(name, NETWORK_MARKERS);
            thirdParty = thirdParty || matches(name, THIRD_PARTY_MARKERS);
            param = param || matches(name, PARAM_MARKERS);
            business = business || current instanceof BusinessException
                    || name.equals(BusinessException.class.getName());
            current = current.getCause();
            depth++;
        }
        if (network) {
            return ExceptionCategory.NETWORK;
        }
        if (thirdParty) {
            return ExceptionCategory.THIRD_PARTY;
        }
        if (param) {
            return ExceptionCategory.PARAM;
        }
        if (business) {
            return ExceptionCategory.BUSINESS;
        }
        return ExceptionCategory.UNKNOWN;
    }

    /**
     * 分类 → 告警等级。
     *
     * <p>推导依据是「这个故障会不会让业务停摆 / 数据受损」：
     * <ul>
     *   <li><b>数据库 → P0</b>：数据层故障会直接停摆，且可能损坏数据，必须立刻知道；</li>
     *   <li><b>网络 → P1</b>：通常是抖动，汇总一封即可（真的持续不通会不断累积，摘要里看得出来）；</li>
     *   <li><b>未知 → P1</b>：无法归类的最值得看，但也不必立刻叫醒（它可能只是一次性输入触发）；</li>
     *   <li><b>第三方 / 参数 / 业务 → P2</b>：邮件发不出去、用户填错字段，
     *       都不该在半夜把人叫起来 —— 攒进日报，第二天一起看。</li>
     * </ul>
     */
    public static AlertLevel severityOf(ExceptionCategory category) {
        if (category == null) {
            return AlertLevel.P1;
        }
        return switch (category) {
            case DATABASE -> AlertLevel.P0;
            case NETWORK, UNKNOWN -> AlertLevel.P1;
            case THIRD_PARTY, PARAM, BUSINESS -> AlertLevel.P2;
        };
    }

    /**
     * 该分类是否被管理员整体忽略。
     *
     * @param ignoreCsv 系统参数里的逗号分隔分类名（大小写不敏感）；空 / null 表示不忽略任何分类
     */
    public static boolean isIgnored(ExceptionCategory category, String ignoreCsv) {
        if (category == null || ignoreCsv == null || ignoreCsv.isBlank()) {
            return false;
        }
        for (String part : ignoreCsv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.equalsIgnoreCase(category.name())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 去重指纹
    // ------------------------------------------------------------------

    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_HEX = Pattern.compile("[0-9a-fA-F]{8,}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * 同类指纹：**异常类 + 归一化后的消息 + 顶层业务栈帧** 的 MD5。
     *
     * <p>「归一化」是把会变的部分抹掉，只留结构 —— 否则
     * 「设备 123 不存在」与「设备 456 不存在」会算成两个问题，去重完全失效。
     * 抹掉的东西：数字、长十六进制串、UUID、引号里的内容。
     *
     * <h2>为什么必须带上栈帧</h2>
     * 同一个 {@code NullPointerException} 可能来自完全不同的两处代码。
     * 只看类名 + 消息会把它们合并成一条告警，而其中一处可能被永远掩盖。
     * 取**第一个业务栈帧**（跳过横切层与框架层）作为「发生位置」的代表。
     */
    public static String digest(Throwable throwable) {
        if (throwable == null) {
            return md5("null");
        }
        String raw = throwable.getClass().getName()
                + "|" + normalizeMessage(throwable.getMessage())
                + "|" + topBusinessFrame(throwable);
        return md5(raw);
    }

    /** 归一化消息：抹掉可变内容，只留结构（见 {@link #digest}） */
    public static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String out = QUOTED.matcher(message).replaceAll("'…'");
        out = UUID.matcher(out).replaceAll("#");
        out = LONG_HEX.matcher(out).replaceAll("#");
        out = DIGITS.matcher(out).replaceAll("#");
        out = SPACES.matcher(out).replaceAll(" ").trim();
        return truncate(out, 200);
    }

    /**
     * 第一个「业务栈帧」（跳过横切层 / 框架层），用作异常发生位置的代表。
     *
     * <p>找不到业务帧时回落第一条栈帧，再回落空串 —— 三种情况都能算出稳定指纹，
     * 不会因为「拿不到栈」而让所有异常合并成一条。
     */
    public static String topBusinessFrame(Throwable throwable) {
        StackTraceElement[] trace = throwable.getStackTrace();
        if (trace == null || trace.length == 0) {
            return "";
        }
        for (StackTraceElement element : trace) {
            if (!isExcludedFrame(element.getClassName())) {
                return element.getClassName() + "#" + element.getMethodName();
            }
        }
        return trace[0].getClassName() + "#" + trace[0].getMethodName();
    }

    private static boolean isExcludedFrame(String className) {
        for (String prefix : DIGEST_EXCLUDED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 静默时段
    // ------------------------------------------------------------------

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 解析出的静默时段（左闭右开）；{@code start > end} 表示跨零点 */
    public record QuietWindow(LocalTime start, LocalTime end) {
    }

    /**
     * 解析 {@code "22:00-08:00"} 形式的静默时段。
     *
     * <p>解析失败一律返回 {@code null}（= 没有静默时段）。
     * 这是刻意的**保守**方向：宁可多发一封 P2 告警，也不要因为参数写错
     * 让告警被静默掉却毫无提示 —— 「静默了但没人知道」比「多收一封邮件」危险得多。
     */
    public static QuietWindow parseQuietWindow(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim(), HH_MM);
            LocalTime end = LocalTime.parse(parts[1].trim(), HH_MM);
            if (start.equals(end)) {
                // 零长度窗口按「没有静默时段」处理，避免全天静默这种危险配置
                return null;
            }
            return new QuietWindow(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前是否处于静默时段。
     *
     * <p>支持跨零点（{@code 22:00-08:00}）：此时 {@code start > end}，
     * 判据是「晚于 start <b>或</b> 早于 end」，而不是「晚于 start 且早于 end」——
     * 后者会让 23:00 落在窗口外，静默时段形同虚设。
     */
    public static boolean inQuietHours(LocalTime now, String spec) {
        QuietWindow window = parseQuietWindow(spec);
        if (window == null || now == null) {
            return false;
        }
        LocalTime start = window.start();
        LocalTime end = window.end();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 截断到指定长度（避免超出列宽被数据库静默截断或直接报错） */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static boolean matches(String className, String[] markers) {
        for (String marker : markers) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必备算法，走不到这里；兜底用 hashCode 保证「有指纹」而不是抛异常
            return String.format(Locale.ROOT, "%032x", input.hashCode());
        }
    }
}
