package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;

import java.util.Comparator;
import java.util.List;

/**
 * 审批节点（{@link OrderApprovalNode}）的通用判定（ ·  · W4-A2）。
 *
 * <h2>为什么单独成类</h2>
 * <p>「当前待办步骤」的算法原先在三个服务里各有一份：
 * {@code OrderServiceImpl}（操作 {@link OrderApprovalNode}）、
 * {@code OrderExtendServiceImpl} 与 {@code OrderForceOperationServiceImpl}
 * （操作延期节点实体）。同一口径散落三处，任何一次调整（例如从「最小 PENDING 步骤」
 * 改成「标注为 current 的步骤」）都必须同时改三处，漏一处就会出现
 * 「列表显示的当前步骤」与「催办按钮判定的当前步骤」不一致 —— 而这种不一致
 * 在界面上表现为"按钮该亮不亮"，非常难定位。
 *
 * <p>本次先把 {@code OrderServiceImpl} 的这一份抽出来作为唯一事实源。
 * 另外两处操作的是**不同实体类型**（延期审批节点），不能直接共用同一个方法，
 * 需要先统一实体或引入泛型接口 —— 属于更大范围的收敛，已记入  挂账。
 *
 * <p>纯静态、无 Spring 依赖：判定只依赖入参，便于单测直接构造节点列表断言。
 */
public final class OrderApprovalNodeSupport {

    private OrderApprovalNodeSupport() {
    }

    /**
     * 当前待办步骤 = 最小的「仍存在 PENDING 节点」的 stepOrder；无待办返回 null。
     *
     * <p>取 <b>min</b> 而不是 first：节点列表虽然按 stepOrder 升序查回，但
     * 「前面的步骤已全部完成、后面的步骤尚未激活」这种中间态下，
     * 第一个 PENDING 就是当前步骤 —— 用 min 表达这个语义，不依赖查询顺序。
     *
     * <p><b>本方法逐字搬自 {@code OrderServiceImpl#currentStepOrder}</b>（W4-A2 纯搬迁）。
     * 刻意不加「node != null / stepOrder != null」之类的额外过滤：那会改变
     * 「脏数据下报错 vs 静默跳过」的行为，而本次拆分的验收标准是零行为变更。
     */
    public static Integer currentStepOrder(List<OrderApprovalNode> nodes) {
        return nodes.stream()
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getStepOrder)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }
}
