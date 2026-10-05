package com.enterprise.ticket.module.ad.ldap;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AD 属性映射（「属性映射」）—— <b>纯函数</b>，便于单测穷尽覆盖
 *
 * <p>把「LDAP 属性值（{@code Map<属性名, List<String>>}）→ {@link AdUser}」这段转换
 * 独立出来，原因有三：
 * <ol>
 *   <li>不同企业的 AD 属性自定义程度极高（姓名可能放 {@code displayName}、{@code cn}、
 *       也可能是自建属性），映射逻辑必须可配置、可回归验证；</li>
 *   <li>{@code userAccountControl} 的位运算、{@code objectGUID} 的二进制转十六进制
 *       都是有明确正确性要求的细节，值得用测试钉死；</li>
 *   <li>把它留在客户端里，将来任何人想验证「改了属性映射会不会写错库」都得先搭一个 AD。</li>
 * </ol>
 *
 * <p>属性名比较<b>大小写不敏感</b>：AD 的属性名本身大小写不敏感，
 * 且不同 LDAP 客户端实现返回的大小写并不统一（有的回 {@code sAMAccountName}，
 * 有的回 {@code samaccountname}）。若在这里做严格匹配，
 * 「测试连接成功、正式同步却读不到登录名」这种缺陷会随机出现。
 *
 * @param attrLogin  登录名属性（默认 sAMAccountName）
 * @param attrName   姓名属性（默认 displayName；取不到时回退 cn）
 * @param attrEmail  邮箱属性（默认 mail）
 * @param attrPhone  手机号属性（默认 telephoneNumber；为空表示不同步手机号）
 * @param attrDept   部门属性（默认 department）
 * @param attrStatus 账号状态属性（默认 userAccountControl）
 */
public record AdAttributeMapping(String attrLogin,
                                 String attrName,
                                 String attrEmail,
                                 String attrPhone,
                                 String attrDept,
                                 String attrStatus) {

    /** AD 的 ACCOUNTDISABLE 位：该位为 1 表示账号已禁用 */
    private static final int UAC_ACCOUNT_DISABLE = 0x0002;

    /** 姓名属性的兜底候选：displayName 常为空（未维护），此时 cn 几乎总是有值 */
    private static final String FALLBACK_NAME_ATTR = "cn";

    /**
     * 把原始属性表映射成 {@link AdUser}
     *
     * @param dn       条目 DN
     * @param rawAttrs 目录返回的属性表（属性名大小写不敏感）
     */
    public AdUser map(String dn, Map<String, List<String>> rawAttrs) {
        String account = firstValue(rawAttrs, attrLogin);
        String name = firstValue(rawAttrs, attrName);
        if (!StringUtils.hasText(name)) {
            name = firstNonBlank(firstValue(rawAttrs, FALLBACK_NAME_ATTR), account);
        }
        return new AdUser(
                dn,
                account,
                name,
                firstValue(rawAttrs, attrEmail),
                firstValue(rawAttrs, attrPhone),
                firstValue(rawAttrs, attrDept),
                isDisabled(firstValue(rawAttrs, attrStatus)),
                normalizeGuid(firstValue(rawAttrs, "objectGUID")));
    }

    /**
     * 按 AD 的 {@code userAccountControl} 判定账号是否禁用。
     *
     * <p>该属性是一个位掩码而非布尔值（典型值 {@code 512} = 正常账号，
     * {@code 514} = 已禁用）。<b>不能用「值是否为 0」判断</b> ——
     * 512 是非零却是正常账号，这样写会把所有正常账号都判成禁用，
     * 后果是同步一次就把全公司账号禁用掉。
     *
     * @param raw 属性原值；空或非数字时返回 false（<b>倾向「未禁用」</b>）
     */
    public static boolean isDisabled(String raw) {
        if (!StringUtils.hasText(raw)) {
            // 拿不到状态属性时按「未禁用」处理：误禁用一个在职员工（他立刻无法登录）
            // 远比漏禁用严重，因此不确定时选择不动作。
            return false;
        }
        try {
            int uac = Integer.parseInt(raw.trim());
            return (uac & UAC_ACCOUNT_DISABLE) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 取属性的第一个非空值（大小写不敏感）；取不到返回 null */
    private static String firstValue(Map<String, List<String>> attrs, String attributeName) {
        if (attrs == null || attrs.isEmpty() || !StringUtils.hasText(attributeName)) {
            return null;
        }
        String wanted = attributeName.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : attrs.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                return null;
            }
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
            return null;
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * objectGUID 规范化。
     *
     * <p>JNDI 在未声明属性类型时可能把二进制值读成「单字符一字节」的字符串
     * （每个字节被当作 Latin-1 字符），也可能直接给 {@code byte[]}。
     * 这里只做「去空白 + 去掉 {@code -} 花括号」的规范化，
     * 二进制 → 十六进制的转换在客户端读取阶段完成（那里拿得到原始字节）。
     */
    private static String normalizeGuid(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String cleaned = raw.trim().replace("{", "").replace("}", "").replace("-", "");
        return cleaned.isEmpty() ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    /** 二进制 objectGUID → 32 位十六进制字符串（客户端读取阶段调用） */
    public static String hexOf(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 把 JNDI 读回的 {@code Object} 值转成字符串（处理 byte[] 的情况） */
    public static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            // 极少数属性（如 objectSid）是纯二进制，按 ISO-8859-1 还原避免丢字节；
            // 十六进制转换仅在明确知道是 objectGUID 时做
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return String.valueOf(value);
    }
}
