package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.constant.ScanMatchType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫码内容解析器（P1 扫码借还）
 *
 * <h2>为什么需要一个独立的解析器</h2>
 * <p>「码里到底是什么」完全取决于标签是谁印的，实际会遇到至少三种形态：
 * <ol>
 *   <li>纯编号：{@code IT-2024-0001}、{@code BORROW-20260101-0007}</li>
 *   <li>带前缀：{@code ASSET:IT-2024-0001}、{@code ORDER=BORROW-20260101-0007}</li>
 *   <li>URL：{@code https://itsm.example.com/scan?assetNo=IT-2024-0001}</li>
 * </ol>
 * 若直接把整串拿去查库，第 2/3 种必然查不到，而报错只会是「未识别到设备」——
 * <b>看不出是解析错了</b>（这与 P0 的 JDBC URL 解析踩的是同一个坑：
 * 当时按「第一个 {@code /}」切库名，结果库名解析成 {@code /localhost:3306/ticket_system}）。
 * 所以把解析单独抽出来，并让每一条规则都有逐字段断言。
 *
 * <h2>解析原则</h2>
 * <ul>
 *   <li><b>绝不猜测</b>：只有前缀在已知白名单内才切分，否则原文返回 ——
 *       资产编号里若真含 {@code :} 或 {@code =}，宁可原文去查（大概率能查到），
 *       也不能被切成一个不存在的短串。</li>
 *   <li><b>URL 按 scheme / authority / path 结构化拆解</b>，不靠「找第几个斜杠」这类
 *       位置假设：{@code ://} 之后的第一个 {@code /} 才是 path 的起点，
 *       它之前是 host（含端口）。不这样拆，{@code https://host/} 会把 host 当成编码。</li>
 *   <li><b>参数名比内容更权威</b>：{@code ?assetNo=} 里的值即使不带前缀，
 *       也能确定它是资产编号 —— 这个信息在提取时就要带出来，不能丢。</li>
 * </ul>
 *
 * <p>纯函数、无副作用、无 Spring 依赖，便于穷尽覆盖。
 */
public final class ScanCodeParser {

    private ScanCodeParser() {
    }

    /** 已知的资产编号前缀（比较时统一大写并把 {@code -} 归一为 {@code _}） */
    private static final Set<String> ASSET_PREFIXES =
            Set.of("ASSET", "ASSETNO", "ASSET_NO", "SN", "DEVICE", "DEVICENO", "DEVICE_NO");

    /** 已知的工单号前缀 */
    private static final Set<String> ORDER_PREFIXES =
            Set.of("ORDER", "ORDERNO", "ORDER_NO", "TICKET", "TICKETNO", "TICKET_NO");

    /** 无语义前缀：拆掉壳但不知道是哪种码，交由调用方两张表都试 */
    private static final Set<String> GENERIC_PREFIXES = Set.of("CODE", "QR", "CONTENT");

    /** URL query 里承载编码的参数名（按优先级）及其隐含的编码类型 */
    private record QueryKey(String name, String hint) {
    }

    private static final List<QueryKey> QUERY_KEYS = List.of(
            new QueryKey("assetNo", ScanMatchType.ASSET_NO),
            new QueryKey("asset_no", ScanMatchType.ASSET_NO),
            new QueryKey("assetno", ScanMatchType.ASSET_NO),
            new QueryKey("orderNo", ScanMatchType.ORDER_NO),
            new QueryKey("order_no", ScanMatchType.ORDER_NO),
            new QueryKey("orderno", ScanMatchType.ORDER_NO),
            new QueryKey("code", ""));

    /**
     * 解析结果。
     *
     * @param code 去掉外壳后的纯编码；无法解析出任何编码时为 {@code null}
     * @param hint 对编码类型的判断（{@link ScanMatchType#ASSET_NO} /
     *             {@link ScanMatchType#ORDER_NO} / 空串=只知道有壳 / {@code null}=完全未知）
     */
    public record ParsedScan(String code, String hint) {

        /** 是否解析出了可用的编码 */
        public boolean hasCode() {
            return code != null && !code.isBlank();
        }
    }

    /**
     * 把扫码原始内容解析为纯编码。
     *
     * @param raw 扫码枪/摄像头读到的原始字符串（可能为 {@code null}）
     */
    public static ParsedScan parse(String raw) {
        if (raw == null) {
            return new ParsedScan(null, null);
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return new ParsedScan(null, null);
        }

        int schemeAt = text.indexOf("://");
        if (schemeAt >= 0) {
            return parseUrl(text, schemeAt + 3);
        }
        return splitPrefixed(text);
    }

    /**
     * URL 形态：{@code scheme://authority/path?query#fragment}
     *
     * <p>取值优先级：query 里的语义参数 &gt; path 最后一段。
     */
    private static ParsedScan parseUrl(String text, int afterSchemeAt) {
        String rest = text.substring(afterSchemeAt);

        int queryAt = rest.indexOf('?');
        String beforeQuery = queryAt >= 0 ? rest.substring(0, queryAt) : rest;

        // fragment：可能出现在 query 之后，也可能没有 query
        int hashAt = beforeQuery.indexOf('#');
        if (hashAt >= 0) {
            beforeQuery = beforeQuery.substring(0, hashAt);
        }

        if (queryAt >= 0) {
            String query = rest.substring(queryAt + 1);
            int queryHashAt = query.indexOf('#');
            if (queryHashAt >= 0) {
                query = query.substring(0, queryHashAt);
            }
            ParsedScan fromQuery = pickQuery(query);
            if (fromQuery != null) {
                return fromQuery;
            }
        }

        // path：authority 之后才可以开始；`://` 后第一个 '/' 之前是 host[:port]
        int pathAt = beforeQuery.indexOf('/');
        if (pathAt < 0) {
            // 只有 host 没有 path ⇒ 链接里没有承载编码
            return new ParsedScan(null, null);
        }
        String path = beforeQuery.substring(pathAt + 1);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlashAt = path.lastIndexOf('/');
        if (lastSlashAt >= 0) {
            path = path.substring(lastSlashAt + 1);
        }
        if (path.isBlank()) {
            return new ParsedScan(null, null);
        }
        return splitPrefixed(path);
    }

    /**
     * 从 query 串里按优先级取第一个命中的语义参数。
     *
     * <p>返回的 {@code hint} 来自<b>参数名</b>而非参数值：{@code ?assetNo=IT-1} 里
     * {@code IT-1} 本身不带前缀，但参数名已经确定它是资产编号了。
     */
    private static ParsedScan pickQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        String[] pairs = query.split("&");
        for (QueryKey key : QUERY_KEYS) {
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                if (pair.substring(0, eq).trim().equalsIgnoreCase(key.name())) {
                    String value = pair.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        // 仍剥一次外壳：兼容 assetNo=ASSET:xxx 这类冗余写法
                        ParsedScan inner = splitPrefixed(value);
                        return new ParsedScan(inner.code(), key.hint());
                    }
                }
            }
        }
        return null;
    }

    /**
     * 处理 {@code 前缀:值} / {@code 前缀=值} 形态。
     *
     * <p>只有前缀命中白名单才切分；否则**原文返回**并把 hint 置空。
     */
    private static ParsedScan splitPrefixed(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            return new ParsedScan(null, null);
        }

        int cut = firstSeparator(text);
        if (cut > 0) {
            String head = normalizePrefix(text.substring(0, cut));
            String tail = text.substring(cut + 1).trim();
            if (!tail.isEmpty()) {
                String hint = hintOf(head);
                if (hint != null) {
                    return new ParsedScan(tail, hint);
                }
            }
        }
        return new ParsedScan(text, null);
    }

    /** 返回第一个分隔符（{@code :} 或 {@code =}）的下标；不存在或位于首位则返回 -1 */
    private static int firstSeparator(String text) {
        int colon = text.indexOf(':');
        int equals = text.indexOf('=');
        if (colon <= 0 && equals <= 0) {
            return -1;
        }
        if (colon <= 0) {
            return equals;
        }
        if (equals <= 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    /** 前缀归一：转大写 + 去掉空白 + {@code -} 折叠为 {@code _} */
    private static String normalizePrefix(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * 前缀 → 编码类型提示。
     *
     * @return 具体类型；{@code ""} 表示「剥了壳但类型未知」（如 {@code CODE:xxx}）；
     *         {@code null} 表示「不是已知前缀 ⇒ 不要切分」
     */
    private static String hintOf(String normalizedHead) {
        if (ASSET_PREFIXES.contains(normalizedHead)) {
            return ScanMatchType.ASSET_NO;
        }
        if (ORDER_PREFIXES.contains(normalizedHead)) {
            return ScanMatchType.ORDER_NO;
        }
        if (GENERIC_PREFIXES.contains(normalizedHead)) {
            return "";
        }
        return null;
    }
}
