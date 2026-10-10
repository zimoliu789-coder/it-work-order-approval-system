package com.enterprise.ticket.module.device.mapper;

import com.enterprise.ticket.module.device.dto.vo.ReconcileIssueVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 设备与工单状态对账查询（需求方三波·第二波·）
 *
 * <h2>为什么要做对账</h2>
 * <p> 与  明确「设备状态机与工单状态机独立建模」，两者靠业务流程保持同步：
 * 交付时 工单→BORROWED 且 设备→IN_USE，归还时反过来。既然是两个独立的事实来源，
 * 就有可能出现「一边改了、另一边没改」的缝隙 —— 典型来源是历史 bug、人工直接改库、
 * 或事务边界外的异常。这类不一致不会立刻报错，但会让台账长期说谎
 * （设备显示使用中，却没有任何人在借），因此需要一个定期的「事实核对」。
 *
 * <h2>为什么只告警、不自动修复</h2>
 * <p>对账发现的是「两个事实源矛盾」，系统无法判断哪个才是对的：
 * 设备显示 IN_USE 但没工单，可能是工单被误删、也可能是设备被误标。
 * 自动「修」等于替人做了一次不可逆的业务决策。因此本任务只把矛盾摆出来，
 * 由管理员在台账上做正确的处理（该报废报废、该改状态改状态），并把处理过程留在审计里。
 *
 * <h2>四条检查规则</h2>
 * <ol>
 *   <li>设备 IN_USE，却没有任何占用中的工单（BORROWED / PENDING_RETURN）；</li>
 *   <li>工单 BORROWED / PENDING_RETURN（应占用设备），但设备状态不是 IN_USE；</li>
 *   <li>设备 IN_APPROVAL，却没有任何审批中 / 待交付的工单；</li>
 *   <li>工单 PENDING_APPROVAL / PENDING_DELIVERY（应处于占用态），但设备已 AVAILABLE。</li>
 * </ol>
 * 规则 2 与 4 是同一矛盾的两个方向，都会命中、都会报 —— 因为修法不同
 * （改工单还是改设备），运维需要知道具体是哪种。
 */
@Mapper
public interface DeviceReconcileMapper {

    /** 规则 1：设备在用，但无占用中的工单 */
    @Select("""
            SELECT d.id          AS deviceId,
                   d.device_name AS deviceName,
                   d.asset_no    AS assetNo,
                   d.status      AS deviceStatus,
                   NULL          AS orderId,
                   NULL          AS orderNo,
                   NULL          AS orderStatus
              FROM device d
             WHERE d.deleted = 0
               AND d.status = 'IN_USE'
               AND NOT EXISTS (SELECT 1 FROM borrow_order o
                                WHERE o.device_id = d.id
                                  AND o.status IN ('BORROWED', 'PENDING_RETURN'))
             ORDER BY d.id
             LIMIT 500
            """)
    List<ReconcileIssueVO> inUseWithoutOrder();

    /** 规则 2：工单占用设备，但设备状态不是使用中 */
    @Select("""
            SELECT d.id          AS deviceId,
                   d.device_name AS deviceName,
                   d.asset_no    AS assetNo,
                   d.status      AS deviceStatus,
                   o.id          AS orderId,
                   o.order_no    AS orderNo,
                   o.status      AS orderStatus
              FROM borrow_order o
              JOIN device d ON d.id = o.device_id
             WHERE d.deleted = 0
               AND o.status IN ('BORROWED', 'PENDING_RETURN')
               AND d.status <> 'IN_USE'
             ORDER BY o.id
             LIMIT 500
            """)
    List<ReconcileIssueVO> occupyingOrderDeviceMismatch();

    /** 规则 3：设备审批中，但无审批中 / 待交付的工单 */
    @Select("""
            SELECT d.id          AS deviceId,
                   d.device_name AS deviceName,
                   d.asset_no    AS assetNo,
                   d.status      AS deviceStatus,
                   NULL          AS orderId,
                   NULL          AS orderNo,
                   NULL          AS orderStatus
              FROM device d
             WHERE d.deleted = 0
               AND d.status = 'IN_APPROVAL'
               AND NOT EXISTS (SELECT 1 FROM borrow_order o
                                WHERE o.device_id = d.id
                                  AND o.status IN ('PENDING_APPROVAL', 'PENDING_DELIVERY'))
             ORDER BY d.id
             LIMIT 500
            """)
    List<ReconcileIssueVO> inApprovalWithoutOrder();

    /** 规则 4：工单在途中，但设备已回到可用 */
    @Select("""
            SELECT d.id          AS deviceId,
                   d.device_name AS deviceName,
                   d.asset_no    AS assetNo,
                   d.status      AS deviceStatus,
                   o.id          AS orderId,
                   o.order_no    AS orderNo,
                   o.status      AS orderStatus
              FROM borrow_order o
              JOIN device d ON d.id = o.device_id
             WHERE d.deleted = 0
               AND o.status IN ('PENDING_APPROVAL', 'PENDING_DELIVERY')
               AND d.status = 'AVAILABLE'
             ORDER BY o.id
             LIMIT 500
            """)
    List<ReconcileIssueVO> inFlightOrderReleasedDevice();
}
