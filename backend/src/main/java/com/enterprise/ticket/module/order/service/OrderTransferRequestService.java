package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.module.order.dto.TransferRequestCreateRequest;
import com.enterprise.ticket.module.order.dto.TransferRequestDecideRequest;
import com.enterprise.ticket.module.order.dto.vo.TransferGroupOptionVO;
import com.enterprise.ticket.module.order.dto.vo.TransferRequestVO;

import java.util.List;

/**
 * 转单申请服务（V55 / D5.7）。
 *
 * <h2>它和「转交」（{@link OrderTransferService#transfer}）是什么关系</h2>
 * <p>{@code transfer} 是**立即生效**的一步操作（执行人自己交出去，或超管强制改派）；
 * 本服务是**有审批 / 接收环节**的转单：同组要本组管理员点头，跨组要对方管理员接收并分配。
 * 两者都写同一张历史表（{@code order_handler_transfer}），因此「工单详情里的转交时间线」
 * 不需要区分来源就能看全。
 *
 * <h2>为什么不把 transfer 改造成本服务的一部分</h2>
 * <p>超管强制改派、离职自动转交都是**没有审批环节**的场景，硬塞进三态流转会多出
 * 「自己批自己」的假审批记录。保持两条路径各自简单，代价只是两个入口（前端本来就是两个按钮）。
 */
public interface OrderTransferRequestService {

    /**
     * 发起转单申请。
     *
     * @return 申请单 id；若发起人本人就是本组管理员（同组转单），申请**当场生效**并直接返回 ACCEPTED 记录
     */
    Long create(Long orderId, TransferRequestCreateRequest request);

    /**
     * 处理转单申请：同组审批通过 / 驳回，跨组接收并分配 / 拒绝。
     *
     * <p>只有 {@code to_admin_id} 本人（或 super_admin）能处理；非 PENDING 状态一律拒绝。
     */
    void decide(Long requestId, TransferRequestDecideRequest request);

    /** 某工单的转单申请记录（工单详情时间线，按时间升序） */
    List<TransferRequestVO> listByOrder(Long orderId);

    /** 「待我处理」的转单申请（我是审批人 / 接收人，且仍待处理） */
    List<TransferRequestVO> myTodo();

    /**
     * 跨组转单可选的目标小组（含各自管理员）。
     *
     * <p>不含「未启用的部门」；未配置管理员的小组会带上 {@code selectable=false}，
     * 由前端置灰说明原因，而不是让发起人选中后才被拒。
     */
    List<TransferGroupOptionVO> transferGroups();

    /**
     * 某处理小组的在职成员（跨组「接收并分配」时的执行人下拉）。
     *
     * <p>为什么需要它：跨组转单的接收人必须在自己组里点名一个执行人，
     * 而现有的 {@code transfer-candidates} 只返回**本工单当前小组**的成员 ——
     * 少了这个接口，目标管理员只能手填一个他不知道 id 的人。
     */
    List<com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO> groupMembers(Long departmentId);
}
