package com.enterprise.ticket.common.util;

import java.util.regex.Pattern;

/**
 * 账号与联系方式的格式规则（ / 三 / 九）。
 *
 * <h2>为什么把这些正则收在一个纯静态类里</h2>
 * <p>同一套规则要在<b>至少五处</b>被用到：新增员工、编辑员工、Excel 导入校验、
 * 找回密码的账号识别、首次绑定联系方式。若各处各写一遍正则，
 * 迟早出现「登录框认它是手机号、新增员工却不认」这类自相矛盾的行为，
 * 而这类缺陷的表现是「用户按提示填了却被拒绝」，排查成本极高。
 * 收成唯一出处后，规则只在一个地方定义、一个地方测试。
 *
 * <h2>姓名的规则为什么是「纯中文」</h2>
 * <p> 要求「必须是纯中文」并把姓名与登录名彻底分开：
 * 姓名是人看的（可重名），登录名是机器用的（唯一、纯数字）。
 * 早期实现让两者合流（「姓名即账号」），结果是中文姓名里混进了序号
 * （如「回归员工052207改」），既不能当登录名用、又占着姓名的唯一性校验。
 * 现在用正则从入口处把两类字符彻底分开。
 *
 * <p>长度上界取 20：真实姓名 2~4 字为主，少数民族姓名可能较长；
 * 20 已远超需要，只作为「不要把整段文本塞进姓名字段」的兜底。
 */
public final class AccountFormats {

    private AccountFormats() {
    }

    /** 纯数字登录名：至少 5 位（） */
    private static final Pattern USERNAME = Pattern.compile("^[0-9]{5,}$");

    /** 姓名：纯中文（含生僻字所在的 CJK 统一汉字区），2~20 字 */
    private static final Pattern CHINESE_NAME = Pattern.compile("^[\\u4e00-\\u9fa5]{2,20}$");

    /**
     * 手机号：中国大陆 11 位手机号。
     *
     * <p>第一位固定 1，第二位取 3~9（这就是当前所有号段的实际范围），
     * 后 9 位任意数字。刻意不枚举具体号段 —— 号段会随运营商调整，
     * 写死枚举只会让「新号段收不到验证码」变成一个需要改代码才能修的问题。
     */
    private static final Pattern PHONE = Pattern.compile("^1[3-9][0-9]{9}$");

    /**
     * 邮箱：不做 RFC 5322 全量校验，只拦「明显不是邮箱」的输入。
     *
     * <p>刻意宽松的两个地方：
     * <ul>
     *   <li>顶级域名不限制长度与字母表（真实存在 xn-- 形式的国际化域名）；</li>
     *   <li>允许 {@code +} 与 {@code _} 等常用字符。</li>
     * </ul>
     * 过严的邮箱正则会拒掉合法地址，而「邮箱是否真实存在」本来就得靠发验证码验证 ——
     * 前端正则只负责挡住「把姓名填进邮箱框」这类低级错误。
     */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    /** 输入形态：用来决定「按哪一列去查账号」 */
    public enum AccountShape {
        /** 纯数字登录名（5 位以上） */
        USERNAME,
        /** 手机号（11 位） */
        PHONE,
        /** 邮箱（含 @） */
        EMAIL,
        /** 姓名（其余一律按姓名处理） */
        NAME
    }

    public static boolean isUsername(String value) {
        return value != null && USERNAME.matcher(value).matches();
    }

    public static boolean isChineseName(String value) {
        return value != null && CHINESE_NAME.matcher(value).matches();
    }

    public static boolean isPhone(String value) {
        return value != null && PHONE.matcher(value).matches();
    }

    public static boolean isEmail(String value) {
        return value != null && EMAIL.matcher(value).matches();
    }

    /**
     * 判断输入形态。
     *
     * <h2>判定顺序为什么必须是「邮箱 → 手机号 → 纯数字 → 姓名」</h2>
     * <p>三类输入存在<b>真实的包含关系</b>，顺序错了就会把用户送到错误的查询上：
     * <pre>
     *   「13800001111」  既是「11 位纯数字」（会被当成登录名），也是合法手机号；
     *   「zhangwei@x.com」是邮箱，但它显然不该走「姓名」分支；
     *   纯数字 5 位以上   才是登录名。
     * </pre>
     * 因此：先认 {@code @}（邮箱的排他特征），再认 11 位手机号（比通用数字更具体），
     * 最后才轮到「纯数字登录名」。这样「13800001111」会走手机号查询 ——
     * 而它作为登录名存在的可能性并不存在：登录名从 10001 开始编号，
     * 前 5 位是 10000 段，与 1 开头的 11 位手机号不会混淆。
     *
     * <p>兜底一律归入 {@link AccountShape#NAME}：这样「用户输入了中文姓名」与
     * 「用户输入了格式不对的东西」走同一条查询路径，最终都会得到
     * 「账号不存在」或「姓名对应多个账号」这类明确答复，而不是一个格式报错 ——
     * 后者会让人以为是自己输错了格式，实际他输入的正是我们想支持的姓名。
     *
     * <p><b>调用方请注意</b>：{@link AccountShape#USERNAME} 与 {@link AccountShape#NAME}
     * 已被服务层<b>合并为同一条「先按登录名精确匹配、再按姓名」的路径</b>
     * （见 {@code UserServiceImpl#resolveByUsernameThenName}）。
     * 原因是存在两个不满足「纯数字」形态、却必须能登录的登录名：内置超管
     * {@code administrator} 与 AD 域账号名。因此本枚举在服务层只用于区分
     * 「该查 email / phone 列」还是「该查 username + real_name」——
     * 依赖 {@code USERNAME} 与 {@code NAME} 的差异去分派查询，会重现那个灾难性回归。
     */
    public static AccountShape shapeOf(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return AccountShape.NAME;
        }
        if (value.indexOf('@') >= 0) {
            return AccountShape.EMAIL;
        }
        if (isPhone(value)) {
            return AccountShape.PHONE;
        }
        if (isUsername(value)) {
            return AccountShape.USERNAME;
        }
        return AccountShape.NAME;
    }

    /**
     * 联系方式打码（<b>全库唯一实现</b>， ）。
     *
     * <h2>为什么必须收敛成一份</h2>
     * <p>本方法并入前，同一套规则在四处各写了一遍：{@code ProfileController#mask}、
     * {@code ForgotPasswordService#maskTarget}、{@code LoggingVerificationCodeSender#mask}、
     * {@code UserServiceImpl#maskPhone / #maskEmail}。它们已经<b>开始分叉</b>：
     * 判空一处用 {@code hasText}（trim 后判断）、一处用 {@code isEmpty}（不 trim），
     * 于是 {@code "  "} 在前者得到空串、在后者原样回显。这类分叉的共同特征是
     * 「输出看起来都对」，直到某天审计日志里出现一条不含掩码的联系方式。
     *
     * <h2>分段规则（与收敛前逐字一致，回归断言依赖它）</h2>
     * <pre>
     *   手机号  13900000001  →  139****0001      （前 3 + **** + 后 4）
     *   邮箱    zhangwei@x.com  →  z***@x.com    （首字符 + *** + @域名）
     *   单字符前缀的邮箱（a@x.com）  →  原样返回  （打码反而会暴露更多信息）
     *   长度 &lt; 7 且无 @ 的裸串  →  原样返回      （无处可掩，不硬拼）
     * </pre>
     *
     * <p>判空统一采用 trim 语义：空白串一律视为「没有填」并返回空串。
     * 这是收敛后唯一的行为变化，方向是更严格（不会把一段空格当成联系方式回显）。
     */
    public static String maskContact(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return "";
        }
        int at = v.indexOf('@');
        if (at > 0) {
            return at <= 1 ? v : v.charAt(0) + "***" + v.substring(at);
        }
        if (v.length() < 7) {
            return v;
        }
        return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
