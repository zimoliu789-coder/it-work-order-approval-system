package com.enterprise.ticket.module.backup.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JDBC URL 解析（P0）
 *
 * <h2>为什么这个纯函数值得单测穷尽</h2>
 * 它最初的实现是「按第一个分隔符取段」，在 {@code jdbc:mysql://localhost:3306/db} 上
 * 把端口解析成 {@code jdbc}、把库名解析成 {@code /localhost:3306/db}。
 * 这条错误一路输出到 mysqldump 的 stderr，报的是
 * {@code Unknown database '/localhost:3306/db'} —— 从这句根本看不出是解析错了，
 * 看起来像「库不存在」，于是排查方向会跑到数据库上去。
 *
 * <p>⇒ 凡是「拼接外部命令参数」的字符串解析，都要有能一眼看出「解析成了什么」的测试。
 * 这里对每一种 URL 形态断言<b>三个字段的完整结果</b>，而不是只断言其中一个。
 */
class DatabaseDumperJdbcUrlTest {

    @Test
    @DisplayName("★ 标准形态：jdbc:mysql://host:port/db?params（最初的 bug 就出在这里）")
    void standardForm() {
        // 逐字段断言：只断言 host 的话，port=jdbc / database=/localhost:3306/ticket_system
        // 这两个错误都会漏过去 —— 而它们才是真正导致 mysqldump 失败的那两个。
        var target = DatabaseDumper.parseJdbcUrl(
                "jdbc:mysql://localhost:3306/ticket_system?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");

        assertEquals("localhost", target.host());
        assertEquals("3306", target.port());
        assertEquals("ticket_system", target.database(), "库名必须只是库名，不能带上 /host:port 前缀");
    }

    @Test
    @DisplayName("带参数与不带参数都要正确")
    void withAndWithoutParams() {
        assertEquals("ticket_system",
                DatabaseDumper.parseJdbcUrl("jdbc:mysql://localhost:3306/ticket_system").database());
        assertEquals("ticket_system",
                DatabaseDumper.parseJdbcUrl("jdbc:mysql://localhost:3306/ticket_system?a=1").database());
    }

    @Test
    @DisplayName("非默认端口与远程主机")
    void customHostAndPort() {
        var target = DatabaseDumper.parseJdbcUrl("jdbc:mysql://10.20.30.40:3307/ticket_prod?x=1");

        assertEquals("10.20.30.40", target.host());
        assertEquals("3307", target.port());
        assertEquals("ticket_prod", target.database());
    }

    @Test
    @DisplayName("缺端口时回落 3306；缺库名时回落 ticket_system")
    void fallbacks() {
        var noPort = DatabaseDumper.parseJdbcUrl("jdbc:mysql://dbhost/ticket_system");
        assertEquals("dbhost", noPort.host());
        assertEquals("3306", noPort.port());
        assertEquals("ticket_system", noPort.database());

        var noDb = DatabaseDumper.parseJdbcUrl("jdbc:mysql://dbhost:3306");
        assertEquals("dbhost", noDb.host());
        assertEquals("3306", noDb.port());
        assertEquals("ticket_system", noDb.database());
    }

    @Test
    @DisplayName("空/null 不抛异常（备份宁可报「连不上」，也不要报「解析崩了」）")
    void nullSafe() {
        for (String url : new String[]{null, "", "   "}) {
            var target = DatabaseDumper.parseJdbcUrl(url);
            assertEquals("localhost", target.host());
            assertEquals("3306", target.port());
            assertEquals("ticket_system", target.database());
        }
    }

    @Test
    @DisplayName("连接串里的库名参与拼接时不得含查询串")
    void databaseNeverContainsQuery() {
        assertEquals("ticket_system", DatabaseDumper.parseJdbcUrl(
                "jdbc:mysql://localhost:3306/ticket_system?rewriteBatchedStatements=true").database());
    }
}
