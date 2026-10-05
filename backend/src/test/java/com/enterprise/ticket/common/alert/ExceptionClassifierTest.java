package com.enterprise.ticket.common.alert;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常分类 / 分级 / 去重指纹（P4-C2）—— 纯函数单测。
 *
 * <h2>为什么这些断言必须有</h2>
 * 本类决定的三件事**错了都不会报错**，只会在事后才暴露：
 * <ol>
 *   <li><b>该不该告警</b> —— 判错要么把邮箱打爆、要么真故障静默；</li>
 *   <li><b>用什么等级</b> —— 判错会半夜被叫醒、或事故被攒进日报；</li>
 *   <li><b>两条异常算不算同一个问题</b> —— 指纹算错 ⇒ 去重失效，同类异常逐条发邮件。</li>
 * </ol>
 * 这三件事在集成测试里都很难覆盖（要造真实异常 + 真实时间流逝），
 * 而作为纯函数可以逐条钉死。
 *
 * <h2>栈帧用 setStackTrace 显式构造</h2>
 * 指纹依赖「第一个业务栈帧」。测试类自身的包名恰好落在 {@link ExceptionClassifier}
 * 的排除前缀里，若用自然栈，实际取到的帧会随 JUnit 内部实现漂移。
 * 因此这里一律**显式 setStackTrace**，让「业务帧是哪一帧」完全可控。
 */
@DisplayName("异常分类 / 分级 / 指纹 / 静默时段（纯函数）")
class ExceptionClassifierTest {

    /** 本地类名含 "MailSendException" ⇒ 命中第三方通道标记（不依赖真实邮件类是否在类路径） */
    private static class MailSendException extends RuntimeException {
        MailSendException(String message) {
            super(message);
        }
    }

    /** 类名不含任何标记 ⇒ UNKNOWN */
    private static class WeirdFailure extends RuntimeException {
        WeirdFailure(String message) {
            super(message);
        }
    }

    /** 造一个栈帧可控的异常 */
    private static <T extends Throwable> T withFrame(T throwable, String className, String method) {
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(className, method, "Source.java", 42)});
        return throwable;
    }

    // ==================================================================
    // 分类
    // ==================================================================

    @Test
    @DisplayName("数据库异常（SQLException）→ DATABASE")
    void sqlIsDatabase() {
        assertEquals(ExceptionCategory.DATABASE,
                ExceptionClassifier.classify(new SQLException("Table 'orders' doesn't exist")));
    }

    @Test
    @DisplayName("网络异常（ConnectException）→ NETWORK")
    void connectIsNetwork() {
        assertEquals(ExceptionCategory.NETWORK, ExceptionClassifier.classify(new ConnectException("拒绝连接")));
    }

    @Test
    @DisplayName("第三方通道异常（邮件）→ THIRD_PARTY")
    void mailIsThirdParty() {
        assertEquals(ExceptionCategory.THIRD_PARTY,
                ExceptionClassifier.classify(new MailSendException("SMTP 认证失败")));
    }

    @Test
    @DisplayName("参数异常（IllegalArgumentException）→ PARAM")
    void illegalArgumentIsParam() {
        assertEquals(ExceptionCategory.PARAM,
                ExceptionClassifier.classify(new IllegalArgumentException("id 不能为空")));
    }

    @Test
    @DisplayName("业务异常 → BUSINESS")
    void businessIsBusiness() {
        assertEquals(ExceptionCategory.BUSINESS,
                ExceptionClassifier.classify(new BusinessException(ErrorCode.PARAM_INVALID, "参数不合法")));
    }

    @Test
    @DisplayName("无法归类 → UNKNOWN（最值得看的那些，不能被悄悄归到别的桶）")
    void unrecognizedIsUnknown() {
        assertEquals(ExceptionCategory.UNKNOWN, ExceptionClassifier.classify(new WeirdFailure("说不清")));
        assertEquals(ExceptionCategory.UNKNOWN, ExceptionClassifier.classify(null));
    }

    @Test
    @DisplayName("★ 包装异常沿因果链归类：外层是普通异常、底层是 SQL ⇒ 报 DATABASE")
    void classificationWalksCauseChain() {
        RuntimeException wrapper = new RuntimeException("保存失败", new SQLException("Deadlock found"));
        assertEquals(ExceptionCategory.DATABASE, ExceptionClassifier.classify(wrapper),
                "真实原因在 cause 里，只看最外层会把数据库故障误报成「未知异常」");
    }

    @Test
    @DisplayName("★ 因果链同时含数据库与网络标记 ⇒ 取数据库（越靠近数据层越优先）")
    void databaseOutranksNetwork() {
        // 网络在外层、数据库在更深的 cause：只看最外层会把「连接池耗尽」误报成网络问题
        Throwable nested = new RuntimeException("复合", new ConnectException("连接超时"));
        nested.getCause().initCause(new SQLException("连接池耗尽"));
        assertEquals(ExceptionCategory.DATABASE, ExceptionClassifier.classify(nested));

        // 只有网络标记时按网络归类
        assertEquals(ExceptionCategory.NETWORK, ExceptionClassifier.classify(new ConnectException("连接超时")));
    }

    // ==================================================================
    // 分级
    // ==================================================================

    @Test
    @DisplayName("分级口径：数据库 P0；网络 / 未知 P1；第三方 / 参数 / 业务 P2")
    void severityMapping() {
        assertEquals(AlertLevel.P0, ExceptionClassifier.severityOf(ExceptionCategory.DATABASE));
        assertEquals(AlertLevel.P1, ExceptionClassifier.severityOf(ExceptionCategory.NETWORK));
        assertEquals(AlertLevel.P1, ExceptionClassifier.severityOf(ExceptionCategory.UNKNOWN));
        assertEquals(AlertLevel.P2, ExceptionClassifier.severityOf(ExceptionCategory.THIRD_PARTY));
        assertEquals(AlertLevel.P2, ExceptionClassifier.severityOf(ExceptionCategory.PARAM));
        assertEquals(AlertLevel.P2, ExceptionClassifier.severityOf(ExceptionCategory.BUSINESS));
        // null 不能 NPE，且要落在「不会半夜叫醒人」的那一档
        assertEquals(AlertLevel.P1, ExceptionClassifier.severityOf(null));
    }

    @Test
    @DisplayName("未知短码回落 P2（库里一个脏值不该把半夜的告警升级成 P0）")
    void unknownLevelCodeFallsBack() {
        assertEquals(AlertLevel.P0, AlertLevel.fromCode("p0"));
        assertEquals(AlertLevel.P2, AlertLevel.fromCode("P9"));
        assertEquals(AlertLevel.P2, AlertLevel.fromCode(null));
    }

    // ==================================================================
    // 忽略清单
    // ==================================================================

    @Test
    @DisplayName("忽略清单：逗号分隔、大小写不敏感、空值不忽略任何分类")
    void ignoreListParsing() {
        assertTrue(ExceptionClassifier.isIgnored(ExceptionCategory.THIRD_PARTY, "THIRD_PARTY,PARAM"));
        assertTrue(ExceptionClassifier.isIgnored(ExceptionCategory.PARAM, " third_party , param "));
        assertFalse(ExceptionClassifier.isIgnored(ExceptionCategory.DATABASE, "THIRD_PARTY,PARAM"));
        assertFalse(ExceptionClassifier.isIgnored(ExceptionCategory.DATABASE, ""));
        assertFalse(ExceptionClassifier.isIgnored(ExceptionCategory.DATABASE, null));
        assertFalse(ExceptionClassifier.isIgnored(null, "DATABASE"));
    }

    // ==================================================================
    // 指纹与消息归一化
    // ==================================================================

    @Test
    @DisplayName("★ 归一化：数字 / 长十六进制 / UUID / 引号内容一律抹掉，只留结构")
    void normalizeMessageStripsVolatileParts() {
        String a = ExceptionClassifier.normalizeMessage("设备 123 不存在");
        String b = ExceptionClassifier.normalizeMessage("设备 456789 不存在");
        assertEquals(a, b, "只有数字不同 ⇒ 必须归一到同一个结构");

        assertEquals(ExceptionClassifier.normalizeMessage("用户 '张三' 无权限"),
                ExceptionClassifier.normalizeMessage("用户 '李四' 无权限"),
                "引号内容是可变的（人名 / 设备名），必须抹掉");

        assertEquals(ExceptionClassifier.normalizeMessage("trace 550e8400-e29b-41d4-a716-446655440000 失败"),
                ExceptionClassifier.normalizeMessage("trace 550e8400-e29b-41d4-a716-446655440001 失败"),
                "UUID 必须整体抹掉（否则逐个数字替换会留下残渣）");
    }

    @Test
    @DisplayName("归一化不炸 null / 空白，且折叠多余空白")
    void normalizeMessageHandlesBlank() {
        assertEquals("", ExceptionClassifier.normalizeMessage(null));
        assertEquals("", ExceptionClassifier.normalizeMessage("   "));
        assertEquals("a b", ExceptionClassifier.normalizeMessage("a    b"));
    }

    @Test
    @DisplayName("★ 指纹：同结构不同数字 ⇒ 相同（去重生效）")
    void digestIgnoresVolatileParts() {
        RuntimeException one = withFrame(new RuntimeException("设备 123 不存在"), "com.acme.Svc", "load");
        RuntimeException two = withFrame(new RuntimeException("设备 999 不存在"), "com.acme.Svc", "load");
        assertEquals(ExceptionClassifier.digest(one), ExceptionClassifier.digest(two),
                "同类异常必须能合并，否则去重完全失效、邮箱会被打爆");
    }

    @Test
    @DisplayName("★ 指纹：同一个类与消息、但发生位置不同 ⇒ 不同（否则一处 bug 会掩盖另一处）")
    void digestSeparatesByFrame() {
        RuntimeException here = withFrame(new RuntimeException("空指针"), "com.acme.A", "a");
        RuntimeException there = withFrame(new RuntimeException("空指针"), "com.acme.B", "b");
        assertNotEquals(ExceptionClassifier.digest(here), ExceptionClassifier.digest(there));
    }

    @Test
    @DisplayName("指纹恒为 32 位十六进制（落库列宽是 CHAR(32)）")
    void digestIsMd5Hex() {
        String digest = ExceptionClassifier.digest(withFrame(new RuntimeException("x"), "com.acme.A", "a"));
        assertEquals(32, digest.length());
        assertTrue(digest.matches("[0-9a-f]{32}"), digest);
    }

    @Test
    @DisplayName("拿不到栈时仍有稳定指纹（不能返回 null，否则落库直接失败）")
    void digestWithoutStackTraceIsStillStable() {
        RuntimeException bare = new RuntimeException("没有栈");
        bare.setStackTrace(new StackTraceElement[0]);
        String first = ExceptionClassifier.digest(bare);
        String second = ExceptionClassifier.digest(bare);
        assertEquals(first, second);
        assertEquals(32, first.length());
        assertEquals(32, ExceptionClassifier.digest(null).length());
    }

    @Test
    @DisplayName("顶层业务帧跳过横切层与框架层（否则所有异常会算成同一个指纹）")
    void topFrameSkipsInfrastructure() {
        RuntimeException e = new RuntimeException("x");
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.enterprise.ticket.common.exception.GlobalExceptionHandler", "handle", "G.java", 1),
                new StackTraceElement("com.enterprise.ticket.common.alert.ExceptionRecorder", "record", "E.java", 1),
                new StackTraceElement("org.springframework.web.Foo", "bar", "F.java", 1),
                new StackTraceElement("com.acme.order.OrderService", "submit", "O.java", 1),
                new StackTraceElement("com.acme.other.Other", "x", "X.java", 1)});
        assertEquals("com.acme.order.OrderService#submit", ExceptionClassifier.topBusinessFrame(e));
    }

    // ==================================================================
    // 静默时段
    // ==================================================================

    @Test
    @DisplayName("静默时段解析：合法 / 非法 / 零长度")
    void parseQuietWindow() {
        ExceptionClassifier.QuietWindow window = ExceptionClassifier.parseQuietWindow("22:00-08:00");
        assertEquals(LocalTime.of(22, 0), window.start());
        assertEquals(LocalTime.of(8, 0), window.end());

        assertNull(ExceptionClassifier.parseQuietWindow(null));
        assertNull(ExceptionClassifier.parseQuietWindow(""));
        assertNull(ExceptionClassifier.parseQuietWindow("22:00"));
        assertNull(ExceptionClassifier.parseQuietWindow("22:00~08:00"));
        assertNull(ExceptionClassifier.parseQuietWindow("25:00-08:00"));
        // 零长度窗口按「没有静默时段」处理 —— 否则会变成全天静默这种危险配置
        assertNull(ExceptionClassifier.parseQuietWindow("08:00-08:00"));
    }

    @Test
    @DisplayName("★ 跨零点的静默时段：23:00 在窗口内（写成「且」会让静默形同虚设）")
    void quietHoursWrapAroundMidnight() {
        String spec = "22:00-08:00";
        assertTrue(ExceptionClassifier.inQuietHours(LocalTime.of(23, 0), spec), "23:00 应静默");
        assertTrue(ExceptionClassifier.inQuietHours(LocalTime.of(22, 0), spec), "左闭：22:00 应静默");
        assertTrue(ExceptionClassifier.inQuietHours(LocalTime.of(3, 30), spec), "凌晨应静默");
        assertTrue(ExceptionClassifier.inQuietHours(LocalTime.of(7, 59), spec), "07:59 应静默");
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(8, 0), spec), "右开：08:00 不静默");
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(14, 0), spec), "白天不静默");
    }

    @Test
    @DisplayName("不跨零点的静默时段按普通区间判断")
    void quietHoursNonWrapping() {
        String spec = "12:00-13:00";
        assertTrue(ExceptionClassifier.inQuietHours(LocalTime.of(12, 30), spec));
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(11, 59), spec));
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(13, 0), spec));
    }

    @Test
    @DisplayName("★ 静默时段解析失败一律「不静默」（宁可多发一封，也不要静默了却无人知晓）")
    void invalidQuietSpecNeverSilences() {
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(23, 0), "写错了"));
        assertFalse(ExceptionClassifier.inQuietHours(LocalTime.of(23, 0), null));
        assertFalse(ExceptionClassifier.inQuietHours(null, "22:00-08:00"));
    }

    // ==================================================================
    // 截断
    // ==================================================================

    @Test
    @DisplayName("截断到列宽以内，且保留结尾标记（超长消息不能把 insert 打挂）")
    void truncateKeepsWithinLimit() {
        assertEquals("abc", ExceptionClassifier.truncate("abc", 10));
        assertEquals(5, ExceptionClassifier.truncate("abcdefghij", 5).length());
        assertNull(ExceptionClassifier.truncate(null, 5));
        assertTrue(ExceptionClassifier.truncate("abcdefghij", 5).endsWith("…"));
    }
}
