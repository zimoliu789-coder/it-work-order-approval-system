package com.enterprise.ticket.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机 —— 「可转交状态」单一事实来源回归测试（Phase 7，规范 §16.4）
 *
 * <p>存在的理由：转交的「能否」判定有两处消费方 —— ① 单笔校验读 {@link OrderStatus#isTransferable()}；
 * ② 批量查询用 {@link OrderStatus#transferableNames()} 拼 {@code WHERE status IN (...)}。
 * 二者一旦不一致，就会出现「前端按钮可点、后端接口拒绝」（或反过来）的错位，
 * 而这类缺陷不会让任何单测变红。故此处把「谓词 ⇄ 投影」的等价关系固化成断言。
 *
 * <p>本测试为纯枚举逻辑，不依赖 Spring / Mockito / MyBatis。
 */
class OrderStatusTest {

    @Test
    @DisplayName("transferableNames 与 isTransferable 谓词逐项等价（谓词 ⇄ SQL 条件不漂移）")
    void transferableNames_matchesPredicate() {
        List<String> names = OrderStatus.transferableNames();

        List<String> expected = java.util.Arrays.stream(OrderStatus.values())
                .filter(OrderStatus::isTransferable)
                .map(Enum::name)
                .toList();

        assertEquals(expected, names, "投影集合必须等于谓词筛选结果");
        assertFalse(names.isEmpty(), "至少应有一个可转交状态");
    }

    @Test
    @DisplayName("可转交状态恰好为「待交付 / 使用中 / 待收回」，顺序与枚举声明一致")
    void transferableNames_containsExactlyThreeStates() {
        assertEquals(
                List.of("PENDING_DELIVERY", "BORROWED", "PENDING_RETURN"),
                OrderStatus.transferableNames());
    }

    @Test
    @DisplayName("终态一律不可转交（已归还 / 已驳回 / 已撤回）")
    void terminalStatuses_areNotTransferable() {
        assertTrue(OrderStatus.RETURNED.isTerminal());
        assertTrue(OrderStatus.REJECTED.isTerminal());
        assertTrue(OrderStatus.CANCELLED.isTerminal());

        for (OrderStatus status : List.of(OrderStatus.RETURNED, OrderStatus.REJECTED, OrderStatus.CANCELLED)) {
            assertFalse(status.isTransferable(), status + " 为终态，不应可转交");
            assertFalse(OrderStatus.transferableNames().contains(status.name()),
                    status + " 不应出现在可转交状态名集合中");
        }
    }

    @Test
    @DisplayName("审批中不可转交（尚无实际执行人）")
    void pendingApproval_isNotTransferable() {
        assertFalse(OrderStatus.PENDING_APPROVAL.isTransferable());
        assertFalse(OrderStatus.transferableNames().contains("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("transferableNames 返回只读集合，调用方无法篡改事实来源")
    void transferableNames_isUnmodifiable() {
        List<String> names = OrderStatus.transferableNames();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> names.add("PENDING_APPROVAL"));
    }
}
