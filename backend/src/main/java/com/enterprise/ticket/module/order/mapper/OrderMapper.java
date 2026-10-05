package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 借用工单 Mapper（ / ）
 *
 * <p>除下面这一条行锁外无自定义 SQL：设备占用、待处理计数等查询均可由 Lambda Wrapper 表达，
 * 且 {@code orders} 表不使用逻辑删除（历史工单必须永久保留， / ）。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 取工单行的排他锁，用于把<b>同一张工单</b>上的并发写串行化。
     *
     * <h2>为什么需要它（W4-F 并发压测抓出的死单）</h2>
     * <p>会签节点上两名审批人同时提交时，两个事务的<b>第一条读</b>都早于对方提交，
     * 于是各自都看到对方那一行仍是 {@code PENDING} ⇒ 双方都判「还有待办」
     * ⇒ <b>谁都不做终态转移</b>：两行都已记 APPROVED、待办为 0，而工单停在「审批中」，
     * 之后任何审批人都只会拿到「该工单没有待处理的审批节点」。这是典型的 write skew，
     * 条件 UPDATE 只能保证「不重复写」，挡不住「谁都不写」；只能靠<b>串行化</b>解决 ——
     * 先取本单行锁，后续所有读都发生在「对方已提交」之后。
     *
     * <h2>加锁顺序约定（改动前必读）</h2>
     * <p>凡是在同一事务内同时修改 {@code orders} 行与 {@code order_approval_nodes} 行的地方，
     * 都必须<b>先 orders 行、后 nodes 行</b>。当前满足该约定的只有
     * {@code OrderServiceImpl} 与 {@code OrderForceOperationServiceImpl}；
     * {@code FlowActivationService} / {@code ApprovalTimeoutJobService} 只写 nodes、
     * 不写 orders，因此只会等待、不会成环。{@code OrderExtendServiceImpl} 写的是
     * {@code order_extend_approval_nodes}（另一张表），也与本表的行锁无交集。
     * <b>若日后新增「先把节点改掉、再把工单改状态」的路径，必须一并加锁，否则会死锁。</b>
     *
     * <h2>为什么用独立的 {@code SELECT ... FOR UPDATE} 而不是把 {@code selectById} 换掉</h2>
     * <p>锁必须在<b>本事务第一条读之前</b>拿到，否则快照已经定在加锁之前，后面怎么读都是旧数据；
     * 但调用方后续仍需要那份常规快照读取。分成两句话职责更清楚，也让既有单测不必改桩。
     *
     * <p>返回值刻意<b>不消费</b>：行不存在时紧随其后的 {@code requireOrder} 会给出更准确的错误，
     * 本方法只负责拿锁。
     */
    @Select("SELECT id FROM orders WHERE id = #{orderId} FOR UPDATE")
    Long lockById(@Param("orderId") Long orderId);
}
