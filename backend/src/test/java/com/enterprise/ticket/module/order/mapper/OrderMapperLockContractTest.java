package com.enterprise.ticket.module.order.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code OrderMapper#lockById} 的 SQL 契约测试（Phase 16 Wave 4 · W4-F）
 *
 * <p>为什么要专门钉一条 SQL：{@code lockById} 是「同单并发写串行化」的唯一入口，
 * 而它坏起来的表现是<b>什么都不发生</b> —— 把 {@code FOR UPDATE} 删掉，
 * 它仍然是一条合法、能跑、返回同一个 id 的查询，只是不再加锁；
 * 于是会签并发死单（W4-F 抓到的 F-1）会原样回来，且**没有任何报错或日志**。
 * 用 mock 打桩的单测也抓不到这一层（它们只验证「方法被调用过」）。
 *
 * <p>因此这里用反射读 {@code @Select} 的原文做断言 —— 与
 * {@code FlowStepOrderContractTest}「跨模块钉 SQL 契约」是同一种手法。
 */
class OrderMapperLockContractTest {

    private static String lockSql() {
        try {
            Method method = OrderMapper.class.getMethod("lockById", Long.class);
            Select select = method.getAnnotation(Select.class);
            assertNotNull(select, "lockById 必须带 @Select —— 否则它根本不是一条自定义 SQL");
            return String.join(" ", select.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "OrderMapper#lockById(Long) 不存在：同单并发写的串行化入口被删掉了", e);
        }
    }

    @Test
    @DisplayName("lockById 必须是 SELECT ... FOR UPDATE（少了它只是普通查询，串行化静默失效）")
    void lockByIdUsesForUpdate() {
        assertTrue(lockSql().toUpperCase(Locale.ROOT).contains("FOR UPDATE"),
                "缺少 FOR UPDATE ⇒ 不加锁 ⇒ 会签并发死单回归，且不会有任何报错：" + lockSql());
    }

    @Test
    @DisplayName("lockById 锁的是 orders 的单行主键（不能漏 id 条件或退化成全表范围）")
    void lockByIdTargetsSingleOrderRow() {
        String sql = lockSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.startsWith("select"), "应为查询语句：" + lockSql());
        assertTrue(sql.contains("from borrow_order"), "应锁 orders 表：" + lockSql());
        assertTrue(sql.contains("id = #{orderid}"),
                "必须按工单主键定位单行，否则锁的粒度就不是「这一张工单」：" + lockSql());
    }

    @Test
    @DisplayName("对照：同类但不带 FOR UPDATE 的 SQL 不应被判为加锁（说明断言有辨别力）")
    void assertionIsDiscriminating() {
        String plainSelect = "SELECT id FROM borrow_order WHERE id = #{orderId}";
        assertFalse(plainSelect.toUpperCase(Locale.ROOT).contains("FOR UPDATE"),
                "普通查询必须被判为「未加锁」，否则本测试的断言等于恒真");
    }
}
