package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.dto.OverdueApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工单审批快照 Mapper
 */
@Mapper
public interface OrderApprovalNodeMapper extends BaseMapper<OrderApprovalNode> {

    /**
     * 查询「长时间未处理」的当前审批节点（需求方三波·第二波·； 扩展）
     *
     * <h2>为什么必须限定「当前最小待审步骤」</h2>
     * <p>多步审批里，第二步的审批人本来就在等第一步 —— 一旦把后续步骤也算作超时，
     * 每个节点一创建就会被判定为超时，提醒变成刷屏，「卡在谁那里」的信息反而被淹没。
     * 因此只取每个工单<b>当前最小 step_order 的 PENDING 节点</b>
     * （与报表模块的「当前待处理且已超时节点数」口径一致，避免两个数字互相打架）。
     *
     * <h2>两种判定口径</h2>
     * <ul>
     *   <li><b>有 {@code deadline_at}</b>（FLOW 流程里配了审批时限的节点）：以「已过截止时间」为准，
     *       文案是「已超过约定审批时限 X 小时」；</li>
     *   <li><b>无 {@code deadline_at}</b>（借用单 / GROUP 单 / 未配时限的 FLOW 节点）：
     *       回落既有口径 「{@code orders.created_at} + 全局阈值 {@code hours}」——
     *       这一支<b>行为与改造前完全一致</b>，保证既有提醒零回归。</li>
     * </ul>
     *
     * <h2>起算点为什么不用节点创建时间</h2>
     * <p>节点创建时间等于审批快照生成时间（提交瞬间），同一单的节点几乎全相同；
     * 用它会让「刚轮到我」的节点显得已经等了很久。用提交时间衡量「这单在我这儿卡了多久」
     * 更贴近用户感知；对后续步骤是保守（偏大）估计，用于发现明显积压足够。
     *
     * <h2>为什么是 LEFT JOIN device（ 修复）</h2>
     * <p>原实现用 {@code JOIN device}，而 {@code device_id} 为空的自定义申请 / FLOW 单
     * <b>根本进不了结果集</b> —— 也就是说这些工单从来没被超时提醒覆盖过。
     * 改用 LEFT JOIN 后，设备名缺失的工单同样会被捞出来（文案里设备回落为「-」）。
     *
     * <h2>幂等条件</h2>
     * <p>有 deadline 的节点按 {@code remindWindowHours}（固定 24 = 每天一次）去重；
     * 无 deadline 的节点沿用 {@code hours} 作为去重窗口（与改造前一致）。
     * 另外排除 {@code approver_id IS NULL} 的行：那是 「待上一节点指定」的占位节点，
     * 此刻还没有具体审批人可提醒（它们会在上一节点通过时被指派）。
     */
    @Select("""
            SELECT n.id           AS nodeId,
                   n.order_id     AS orderId,
                   n.approver_id  AS approverId,
                   n.step_order   AS stepOrder,
                   n.deadline_at  AS deadlineAt,
                   o.order_no     AS orderNo,
                   o.applicant_id AS applicantId,
                   o.created_at   AS submittedAt,
                   u.display_name AS applicantName,
                   d.device_name  AS deviceName
              FROM order_approval_nodes n
              JOIN orders o ON o.id = n.order_id
              JOIN users  u ON u.id = o.applicant_id
              LEFT JOIN device d ON d.id = o.device_id
             WHERE n.status = 'PENDING'
               AND n.approver_id IS NOT NULL
               AND o.status = 'PENDING_APPROVAL'
               AND n.step_order = (SELECT MIN(n2.step_order)
                                     FROM order_approval_nodes n2
                                    WHERE n2.order_id = n.order_id AND n2.status = 'PENDING')
               AND (
                     (n.deadline_at IS NOT NULL
                        AND n.deadline_at < NOW()
                        AND (n.last_remind_at IS NULL
                             OR n.last_remind_at < DATE_SUB(NOW(), INTERVAL #{remindWindowHours} HOUR)))
                  OR (n.deadline_at IS NULL
                        AND o.created_at < DATE_SUB(NOW(), INTERVAL #{hours} HOUR)
                        AND (n.last_remind_at IS NULL
                             OR n.last_remind_at < DATE_SUB(NOW(), INTERVAL #{hours} HOUR)))
                   )
             ORDER BY o.created_at ASC
             LIMIT #{limit}
            """)
    List<OverdueApprovalNode> findOverdueNodes(@Param("hours") int hours,
                                               @Param("remindWindowHours") int remindWindowHours,
                                               @Param("limit") int limit);
}
