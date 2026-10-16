package com.enterprise.ticket.module.system.support;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件模板渲染（V52）
 *
 * <h2>为什么把主题与正文抽成模板</h2>
 * 此前主题与正文**写死在代码里**（`sendVerificationCode` / 告警邮件各一份），
 * 管理员想改一句「请勿转发给他人」都得改代码重新部署 —— 而邮件是**对外的门面**，
 * 措辞往往属于业务方要自己定的东西。
 *
 * <h2>三条刻意的规则</h2>
 * <ol>
 *   <li><b>模板留空 ⇒ 回落默认文案</b>。默认值与抽模板之前的写死文案**逐字一致**，
 *       因此升级后行为完全不变（零回归）；管理员清空输入框即可「恢复默认」。</li>
 *   <li><b>未知占位符原样保留</b>。管理员把 {@code {code}} 写成 {@code {Code}} 时，
 *       邮件里会显示 {@code {Code}} —— 一眼能看出写错了。若静默替换成空串，
 *       表现是「邮件里凭空少了一段」，排查成本高得多。</li>
 *   <li><b>主题强制清除换行与控制字符</b>。主题是**邮件头**的一部分，
 *       管理员在主题栏粘贴一段带换行的文本就会造成<b>邮件头注入</b>
 *       （往邮件里塞进额外的头部字段）。这是邮件功能最典型的安全坑，
 *       必须在渲染层兜住，不能指望输入框不给粘贴。</li>
 * </ol>
 *
 * <h2>为什么正文不做 HTML 转义</h2>
 * <p>本系统发的是**纯文本邮件**（{@code MimeMessageHelper#setText(body, false)}），
 * 不存在 HTML 注入面；模板里的尖括号会原样显示给收件人，这是纯文本邮件的正常行为。
 */
public final class MailTemplateRenderer {

    private MailTemplateRenderer() {
    }

    /** 主题上限：过长会被各家邮箱客户端截断，且没有信息量 */
    public static final int MAX_SUBJECT_LENGTH = 200;

    /** 正文上限：防「一条配置把邮件撑成几 MB」；远超正常业务需要 */
    public static final int MAX_BODY_LENGTH = 20_000;

    /** 占位符形态：{name}，字母开头、其后字母数字 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /** 主题里必须清掉的字符：CR / LF / 制表符 / 其它控制字符（\u007f 是 DEL） */
    private static final Pattern SUBJECT_HAZARD = Pattern.compile("[\\r\\n\\t\\u0000-\\u001f\\u007f]+");

    /**
     * 渲染主题。
     *
     * @param template 管理员配置的模板（可空 —— 空则用 {@code fallback}）
     * @param fallback 默认模板
     * @param vars     占位符取值
     */
    public static String subject(String template, String fallback, Map<String, String> vars) {
        return sanitizeSubject(substitute(template, fallback, vars, MAX_SUBJECT_LENGTH));
    }

    /** 渲染正文（不做换行过滤 —— 正文本来就该是多行的） */
    public static String body(String template, String fallback, Map<String, String> vars) {
        return substitute(template, fallback, vars, MAX_BODY_LENGTH);
    }

    /**
     * 主题净化：把换行 / 制表符 / 控制字符压成单个空格。
     *
     * <p>对**渲染后的结果**再净化一次是必要的 —— 占位符的取值本身也可能带换行
     * （例如告警标题里被塞了换行），只净化模板挡不住。
     */
    public static String sanitizeSubject(String raw) {
        if (raw == null) {
            return "";
        }
        return trimTo(SUBJECT_HAZARD.matcher(raw).replaceAll(" ").trim(), MAX_SUBJECT_LENGTH);
    }

    /** 替换占位符；未知的**保留原样**（见类注释第 2 条） */
    private static String substitute(String template, String fallback, Map<String, String> vars, int maxLength) {
        String tpl = StringUtils.hasText(template) ? template : fallback;
        if (!StringUtils.hasText(tpl)) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(tpl);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = vars == null ? null : vars.get(matcher.group(1));
            // 取值可能含 $ 或 \，必须 quoteReplacement，否则会被当成正则的组引用
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(out);
        return trimTo(out.toString(), maxLength);
    }

    private static String trimTo(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…（内容过长已截断）";
    }
}
