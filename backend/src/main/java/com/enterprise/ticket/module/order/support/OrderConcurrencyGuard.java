package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;

/**
 * 并发写入的「认领」语义：受影响行数<b>必须被消费</b>（ · W4-F）。
 *
 * <h2>为什么把这两句判断单独提出来</h2>
 * <p>W4-F 并发压测抓到的第二类缺陷是「假成功」：{@code actionNode} 一直都带着
 * {@code WHERE status = 'PENDING'} 前置条件，但它的返回类型是 {@code void}，
 * <b>受影响行数被丢掉</b>，调用方照常往下走 —— 于是「一行都没改」的请求照样回
 * {@code SUCCESS}。它的 Javadoc 自称「最后一道数据库级防线」，而这道防线其实没有接线。
 *
 * <p>把判断收敛成两个具名方法，是为了让「有没有消费受影响行数」在代码评审时
 * <b>一眼可见</b>：调用点长成 {@code OrderConcurrencyGuard.requireXxx(update(...))}，
 * 漏掉它就不再是"少写一个 if"，而是一个显眼的缺环。
 *
 * <p>两者命中 0 行时都<b>抛异常让整笔事务回滚</b>（与 {@code OrderExtendServiceImpl#applyApproved}
 * 同口径），而不是静默跳过 —— 静默跳过会把「节点已写、工单没动」这种半截状态提交上去。
 */
public final class OrderConcurrencyGuard {

    private OrderConcurrencyGuard() {
    }

    /**
     * 节点审批结果的写入必须命中 1 行。
     *
     * <p>命中 0 行 = 该节点在我们判断之后已被别的事务处理（同节点重复提交，或或签下
     * 另一名审批人已通过并把它标成 SKIPPED）。 要求第二个提交者看到
     * 「节点已处理」，这里就是那句话在数据库层的兑现。
     */
    public static void requireNodeActionApplied(int updatedRows) {
        if (updatedRows <= 0) {
            throw new BusinessException(ErrorCode.APPROVAL_NODE_HANDLED);
        }
    }

    /**
     * 工单终态转移必须被本次事务认领到（恰好命中 1 行）。
     *
     * <p>命中 0 行 = 工单状态已被别的事务改掉。此时整笔事务回滚，
     * 让调用方看到「状态已变化，请刷新后重试」，而不是把一次无效操作当成成功。
     */
    public static void requireOrderTransitionClaimed(int updatedRows) {
        if (updatedRows <= 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "工单状态已变化，本次操作未生效，请刷新后重试");
        }
    }
}
