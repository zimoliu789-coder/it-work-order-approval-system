package com.enterprise.ticket.common.trip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出差规则单测（V53）
 *
 * <p>重点在**边界口径**：区间是<b>闭区间</b>，「同一天结束又开始」必须算重叠。
 * 这条口径一旦与预填侧的判断不一致，就会出现「提交时说不重叠、预填时说不生效」
 * 这种自相矛盾的组合 —— 所以两处共用本类，并把口径钉在测试里。
 */
class BusinessTripRulesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate D9 = LocalDate.of(2026, 10, 9);

    @Nested
    @DisplayName("区间重叠（闭区间）")
    class Overlap {

        @Test
        @DisplayName("完全分离 ⇒ 不重叠")
        void disjoint() {
            assertFalse(BusinessTripRules.overlaps(D1, D5, LocalDate.of(2026, 10, 6), D9));
            assertFalse(BusinessTripRules.overlaps(LocalDate.of(2026, 10, 6), D9, D1, D5));
        }

        @Test
        @DisplayName("★ 首尾相接（同一天结束又开始）⇒ **算重叠**")
        void touchingEndpointsOverlap() {
            // 一个人不可能当天在北京结束出差、同时在上海开始下一个出差
            assertTrue(BusinessTripRules.overlaps(D1, D5, D5, D9));
            assertTrue(BusinessTripRules.overlaps(D5, D9, D1, D5));
        }

        @Test
        @DisplayName("部分交叠 / 完全包含 / 完全相同 ⇒ 都算重叠")
        void variousOverlaps() {
            assertTrue(BusinessTripRules.overlaps(D1, D9, D5, LocalDate.of(2026, 10, 6)));
            assertTrue(BusinessTripRules.overlaps(D5, LocalDate.of(2026, 10, 6), D1, D9));
            assertTrue(BusinessTripRules.overlaps(D1, D5, D1, D5));
        }

        @Test
        @DisplayName("单日区间与单日区间：同一天算重叠，隔一天不算")
        void singleDayRanges() {
            assertTrue(BusinessTripRules.overlaps(D5, D5, D5, D5));
            assertFalse(BusinessTripRules.overlaps(D5, D5, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)));
        }

        @Test
        @DisplayName("任一日期缺失 ⇒ 不参与判定（交给必填校验去拦，不在这里当成重叠）")
        void nullSafe() {
            assertFalse(BusinessTripRules.overlaps(null, D5, D1, D9));
            assertFalse(BusinessTripRules.overlaps(D1, null, D1, D9));
            assertFalse(BusinessTripRules.overlaps(D1, D5, null, D9));
            assertFalse(BusinessTripRules.overlaps(D1, D5, D1, null));
        }
    }

    @Nested
    @DisplayName("某一日是否在出差中（预填用）")
    class Covers {

        @Test
        @DisplayName("首日与末日都算在出差中（闭区间）")
        void inclusiveEnds() {
            assertTrue(BusinessTripRules.covers(D1, D5, D1));
            assertTrue(BusinessTripRules.covers(D1, D5, D5));
            assertTrue(BusinessTripRules.covers(D1, D5, LocalDate.of(2026, 10, 3)));
        }

        @Test
        @DisplayName("区间外的两天都不算")
        void outside() {
            assertFalse(BusinessTripRules.covers(D1, D5, LocalDate.of(2026, 9, 30)));
            assertFalse(BusinessTripRules.covers(D1, D5, LocalDate.of(2026, 10, 6)));
        }

        @Test
        @DisplayName("含 null ⇒ false（不抛异常）")
        void nullSafe() {
            assertFalse(BusinessTripRules.covers(null, D5, D1));
            assertFalse(BusinessTripRules.covers(D1, null, D1));
            assertFalse(BusinessTripRules.covers(D1, D5, null));
        }
    }

    @Nested
    @DisplayName("表单取值解析（历史数据格式不保证）")
    class Parsing {

        @Test
        @DisplayName("标准日期串可解析")
        void standard() {
            assertEquals(D1, BusinessTripRules.parseDate("2026-10-01"));
            assertEquals(D1, BusinessTripRules.parseDate("  2026-10-01  "));
        }

        @Test
        @DisplayName("带时间的写法只取日期部分（改字段类型后的历史数据）")
        void withTime() {
            assertEquals(D1, BusinessTripRules.parseDate("2026-10-01 00:00:00"));
        }

        @Test
        @DisplayName("读不出来返回 null（**不抛异常** —— 否则「打开提交页」会直接 500）")
        void unparsable() {
            assertNull(BusinessTripRules.parseDate(null));
            assertNull(BusinessTripRules.parseDate(""));
            assertNull(BusinessTripRules.parseDate("下周一"));
            assertNull(BusinessTripRules.parseDate(12345));
        }

        @Test
        @DisplayName("目标分部取部门 id")
        void deptId() {
            assertEquals(7L, BusinessTripRules.parseDeptId("7"));
            assertEquals(7L, BusinessTripRules.parseDeptId(7));
            assertNull(BusinessTripRules.parseDeptId(null));
            assertNull(BusinessTripRules.parseDeptId(""));
            assertNull(BusinessTripRules.parseDeptId("上海分公司"));
        }
    }
}
