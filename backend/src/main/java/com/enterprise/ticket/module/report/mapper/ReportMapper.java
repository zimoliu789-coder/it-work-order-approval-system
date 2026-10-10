package com.enterprise.ticket.module.report.mapper;

import com.enterprise.ticket.module.report.dto.vo.ApprovalEfficiencyReportVO;
import com.enterprise.ticket.module.report.dto.vo.DashboardOverviewVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceFaultReportVO;
import com.enterprise.ticket.module.report.dto.vo.DeviceUsageReportVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计报表查询
 *
 * <h2>为什么用聚合 SQL 而不是把数据捞进内存再算</h2>
 * <p>报表是<b>全量聚合</b>（「哪类设备最紧缺」要看所有历史工单），若先 {@code selectList}
 * 再在 Java 里 group by，等于把整张 orders 表读进堆内存——数据量一涨就崩，
 * 而且统计口径会被分页/上限悄悄截断。聚合下沉到数据库，由索引承担扫描成本。
 *
 * <h2>时间区间统一左闭右开 {@code [from, to)}</h2>
 * <p>由 {@code ReportQuery} 换算好再传入，SQL 只做比较，不做日期函数换算 ——
 * 这样 {@code created_at} 上的索引可用（对列用函数会导致索引失效）。
 * 也不使用 {@code BETWEEN}：它是闭区间，会漏掉「月底最后一秒之后、次日零点之前」的数据。
 *
 * <h2>「借用」一律限定 {@code order_type = 'BORROW'}</h2>
 * <p>orders 表后续会承载归还单 / 维修单（{@code OrderType} 已登记），
 * 若不加这一条件，未来上线独立归还单会让「借用次数」被算重。
 */
@Mapper
public interface ReportMapper {

    // ==================================================================
    // 一、设备借用频次
    // ==================================================================

    /** 区间内借用申请总数 */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
            """)
    long countBorrowOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内被借用过的设备数（去重） */
    @Select("""
            SELECT COUNT(DISTINCT o.device_id)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
            """)
    long countBorrowDevices(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按设备统计借用次数（降序） */
    @Select("""
            SELECT d.id            AS deviceId,
                   d.device_name   AS deviceName,
                   d.asset_no      AS assetNo,
                   pc.category_name AS primaryCategoryName,
                   COUNT(o.id)     AS borrowCount
              FROM borrow_order o
              JOIN device d ON d.id = o.device_id
              LEFT JOIN device_category pc ON pc.id = d.primary_category_id
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY d.id, d.device_name, d.asset_no, pc.category_name
             ORDER BY borrowCount DESC, d.id ASC
            """)
    List<DeviceUsageReportVO.DeviceItem> borrowByDevice(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /** 按一级分类统计借用次数（降序） */
    @Select("""
            SELECT COALESCE(pc.category_name, '未分类') AS categoryName,
                   COUNT(o.id)                          AS borrowCount,
                   COUNT(DISTINCT o.device_id)          AS deviceCount
              FROM borrow_order o
              JOIN device d ON d.id = o.device_id
              LEFT JOIN device_category pc ON pc.id = d.primary_category_id
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY COALESCE(pc.category_name, '未分类')
             ORDER BY borrowCount DESC
            """)
    List<DeviceUsageReportVO.CategoryItem> borrowByCategory(@Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to);

    // ==================================================================
    // 二、工单审批时效
    // ==================================================================

    /** 区间内提交的借用工单数（分母） */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
            """)
    long countSubmittedOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 区间内已完成审批的工单数
     *
     * <p>「已完成审批」以工单状态判定（待交付 / 使用中 / 待收回 / 已归还），
     * 而不是「所有节点都 APPROVED」：被驳回或撤回的工单同样走完了审批流程，
     * 但它们没有「审批通过」这一终点，纳入耗时统计会让数字失真。
     */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.status IN ('PENDING_DELIVERY', 'BORROWED', 'PENDING_RETURN', 'RETURNED')
               AND o.created_at >= #{from} AND o.created_at < #{to}
            """)
    long countApprovedOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 平均审批耗时（分钟）
     *
     * <p>终点取该工单<b>最后一个 APPROVED 节点的操作时间</b>（{@code MAX(action_time)}），
     * 起点取工单提交时间。只用已完成审批的工单做平均 —— 未完成的没有终点，
     * 若把它们按「至今」计算，会把平均值整体拉高；若直接排除又需明示，故用状态过滤。
     *
     * @return 无数据时返回 {@code null}（由服务层回落 0）
     */
    @Select("""
            SELECT AVG(TIMESTAMPDIFF(MINUTE, o.created_at, ap.approved_at))
              FROM borrow_order o
              JOIN (SELECT order_id, MAX(action_time) AS approved_at
                      FROM order_approval_node
                     WHERE status = 'APPROVED'
                     GROUP BY order_id) ap ON ap.order_id = o.id
             WHERE o.order_type = 'BORROW'
               AND o.status IN ('PENDING_DELIVERY', 'BORROWED', 'PENDING_RETURN', 'RETURNED')
               AND o.created_at >= #{from} AND o.created_at < #{to}
            """)
    Double avgApprovalMinutes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 审批耗时超过阈值的工单数（历史慢审批） */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
              JOIN (SELECT order_id, MAX(action_time) AS approved_at
                      FROM order_approval_node
                     WHERE status = 'APPROVED'
                     GROUP BY order_id) ap ON ap.order_id = o.id
             WHERE o.order_type = 'BORROW'
               AND o.status IN ('PENDING_DELIVERY', 'BORROWED', 'PENDING_RETURN', 'RETURNED')
               AND o.created_at >= #{from} AND o.created_at < #{to}
               AND TIMESTAMPDIFF(MINUTE, o.created_at, ap.approved_at) > #{thresholdMinutes}
            """)
    long countOvertimeOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                             @Param("thresholdMinutes") long thresholdMinutes);

    /** 审批时效明细（含未完成审批的工单，便于页面标出「卡在哪」） */
    @Select("""
            SELECT o.id             AS orderId,
                   o.order_no       AS orderNo,
                   u.display_name   AS applicantName,
                   CONCAT(d.device_name, '（', d.asset_no, '）') AS deviceName,
                   o.created_at     AS submittedAt,
                   (SELECT MAX(n.action_time) FROM order_approval_node n
                     WHERE n.order_id = o.id AND n.status = 'APPROVED') AS approvedAt
              FROM borrow_order o
              JOIN employee u  ON u.id = o.applicant_id
              JOIN device d ON d.id = o.device_id
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
             ORDER BY o.created_at DESC
             LIMIT #{limit}
            """)
    List<ApprovalEfficiencyReportVO.Item> approvalItems(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        @Param("limit") int limit);

    /** 当前仍待处理的审批节点数（不限区间，反映此刻积压） */
    @Select("""
            SELECT COUNT(*)
              FROM order_approval_node n
              JOIN borrow_order o ON o.id = n.order_id
             WHERE n.status = 'PENDING'
               AND o.status = 'PENDING_APPROVAL'
            """)
    long countPendingNodes();

    /**
     * 当前待处理且已超时的审批节点数
     *
     * <p>两个限定条件缺一不可：
     * <ol>
     *   <li>{@code step_order} 必须是该工单<b>当前最小</b>的待审步骤 —— 多步审批里
     *       后续步骤本来就在等前一步，把它们算作「超时」会凭空制造积压；</li>
     *   <li>起点用工单提交时间（{@code borrow_order.created_at}）而非节点创建时间：
     *       节点创建时间等于快照生成时间，用它会把「轮到我这步时才开始等」这件事
     *       误算成「节点一创建就在等」，两者在会说工单上差别很大。
     *       对第一步审批这是精确值，对后续步骤是保守（偏大）估计，故本指标用于发现
     *       明显积压，不做精确计量。</li>
     * </ol>
     */
    @Select("""
            SELECT COUNT(*)
              FROM order_approval_node n
              JOIN borrow_order o ON o.id = n.order_id
             WHERE n.status = 'PENDING'
               AND o.status = 'PENDING_APPROVAL'
               AND n.step_order = (SELECT MIN(n2.step_order)
                                     FROM order_approval_node n2
                                    WHERE n2.order_id = n.order_id AND n2.status = 'PENDING')
               AND o.created_at < DATE_SUB(NOW(), INTERVAL #{hours} HOUR)
            """)
    long countPendingOverdueNodes(@Param("hours") int hours);

    // ==================================================================
    // 三、设备故障统计
    // ==================================================================

    /** 区间内故障总数（按发生时间） */
    @Select("""
            SELECT COUNT(*)
              FROM device_fault f
             WHERE f.occurred_at >= #{from} AND f.occurred_at < #{to}
            """)
    long countFaults(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内指定状态的故障数 */
    @Select("""
            SELECT COUNT(*)
              FROM device_fault f
             WHERE f.status = #{status}
               AND f.occurred_at >= #{from} AND f.occurred_at < #{to}
            """)
    long countFaultsByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                             @Param("status") String status);

    /** 按设备统计故障分布（降序） */
    @Select("""
            SELECT d.id            AS deviceId,
                   d.device_name   AS deviceName,
                   d.asset_no      AS assetNo,
                   pc.category_name AS primaryCategoryName,
                   COUNT(f.id)     AS faultCount,
                   SUM(CASE WHEN f.status = 'PENDING_REPAIR' THEN 1 ELSE 0 END) AS pendingCount
              FROM device_fault f
              JOIN device d ON d.id = f.device_id
              LEFT JOIN device_category pc ON pc.id = d.primary_category_id
             WHERE f.occurred_at >= #{from} AND f.occurred_at < #{to}
             GROUP BY d.id, d.device_name, d.asset_no, pc.category_name
             ORDER BY faultCount DESC, d.id ASC
             LIMIT #{limit}
            """)
    List<DeviceFaultReportVO.DeviceItem> faultsByDevice(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        @Param("limit") int limit);

    /** 按月份统计故障分布（升序，形如 2026-09） */
    @Select("""
            SELECT DATE_FORMAT(f.occurred_at, '%Y-%m') AS month,
                   COUNT(*)                            AS faultCount
              FROM device_fault f
             WHERE f.occurred_at >= #{from} AND f.occurred_at < #{to}
             GROUP BY DATE_FORMAT(f.occurred_at, '%Y-%m')
             ORDER BY month ASC
            """)
    List<DeviceFaultReportVO.MonthItem> faultsByMonth(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

    // ==================================================================
    // 四、管理概览（P3 管理层数据看板）
    // ==================================================================

    /**
     * 借用趋势（按天或按月分桶）
     *
     * <p>分桶格式由调用方传入（{@code %Y-%m-%d} / {@code %Y-%m}）而不是在 SQL 里按跨度判断：
     * 「跨度多长才换成按月」属展示层决策，写进 SQL 会让这个规则难以追踪与调整。
     *
     * <p>按别名 {@code bucket} 分组：SELECT 里已用 {@code DATE_FORMAT} 算好，
     * 再对列做一次函数会让代码重复且容易两处不一致。
     */
    @Select("""
            SELECT DATE_FORMAT(o.created_at, #{format}) AS bucket,
                   COUNT(*)                             AS count
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY bucket
             ORDER BY bucket ASC
            """)
    List<DashboardOverviewVO.TrendItem> borrowTrend(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    @Param("format") String format);

    /**
     * 逾期主口径：系统超时标记且<b>仍在借</b>
     *
     * <p>为什么要加状态限定而不只看 {@code borrow_timeout}：归还流程会把该标记清零，
     * 但异常路径或历史数据下仍可能残留 {@code true}，只看标记位会把已归还的单算成
     * 「现在逾期」。加上 {@code status IN ('BORROWED','PENDING_RETURN')} 后，
     * 指标语义才严格等于「当下逾期」。
     *
     * <p>不加时间区间：本指标问的是「此刻有多少逾期」，不是「某月内有多少逾期」。
     */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.borrow_timeout = 1
               AND o.status IN ('BORROWED', 'PENDING_RETURN')
            """)
    long countOverdueTimeout();

    /**
     * 逾期副口径：仍在借且计划归还时间已过（<b>含顺延宽限期内</b>）
     *
     * <p>与主口径的差 = 「宽限期内、系统还没正式判定超时」的工单数，恒为非负。
     * 用 {@code NOW()} 而非传入时间，同样是因为它描述的是「此刻」。
     */
    @Select("""
            SELECT COUNT(*)
              FROM borrow_order o
             WHERE o.order_type = 'BORROW'
               AND o.status IN ('BORROWED', 'PENDING_RETURN')
               AND o.planned_end_time IS NOT NULL
               AND o.planned_end_time < NOW()
            """)
    long countOverdueGrace();

    /**
     * 设备状态分布（一次扫表出全部计数）
     *
     * <p>用一条 SQL 的 {@code SUM(CASE ...)} 而不是 6 条独立 COUNT，两个理由：
     * <ol>
     *   <li>6 次扫同一张表纯属浪费；</li>
     *   <li>更关键 —— 同一条 SQL 内的计数<b>天然处于同一时刻</b>。6 条独立查询之间
     *       设备状态可能变化，会出现「各状态之和 ≠ 总数」这种读者一眼就发现的对不上账。</li>
     * </ol>
     *
     * <p>显式写 {@code deleted = 0}：{@code Device} 上的 {@code @TableLogic} 只作用于
     * MyBatis-Plus 自动生成的 SQL，手写 SQL 不享该过滤。
     */
    @Select("""
            SELECT COUNT(*)                                                AS total,
                   SUM(CASE WHEN status = 'AVAILABLE'   THEN 1 ELSE 0 END) AS available,
                   SUM(CASE WHEN status = 'IN_USE'      THEN 1 ELSE 0 END) AS inUse,
                   SUM(CASE WHEN status = 'IN_APPROVAL' THEN 1 ELSE 0 END) AS inApproval,
                   SUM(CASE WHEN status = 'MAINTENANCE' THEN 1 ELSE 0 END) AS maintenance,
                   SUM(CASE WHEN status = 'LOST'        THEN 1 ELSE 0 END) AS lost,
                   SUM(CASE WHEN status = 'SCRAPPED'    THEN 1 ELSE 0 END) AS scrapped
              FROM device
             WHERE deleted = 0
            """)
    DashboardOverviewVO.DeviceStatusCount deviceStatusCounts();

    /**
     * 部门借用排行（按申请人所属部门聚合，降序）
     *
     * <p>用 {@code LEFT JOIN} + {@code COALESCE} 兜底「未分配」：账号可能没有部门
     * （超管的 {@code department_id} 就是 {@code NULL}）。若用内连接，这部分工单会从
     * 排行里消失，导致「各部门之和 ≠ 区间总借出数」—— 读者对不上账就会怀疑整张报表。
     */
    @Select("""
            SELECT u.department_id                   AS departmentId,
                   COALESCE(dep.dept_name, '未分配') AS departmentName,
                   COUNT(o.id)                       AS borrowCount
              FROM borrow_order o
              JOIN employee u ON u.id = o.applicant_id
              LEFT JOIN department dep ON dep.id = u.department_id
             WHERE o.order_type = 'BORROW'
               AND o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY u.department_id, COALESCE(dep.dept_name, '未分配')
             ORDER BY borrowCount DESC, u.department_id ASC
             LIMIT #{limit}
            """)
    List<DashboardOverviewVO.DepartmentItem> borrowByDepartment(@Param("from") LocalDateTime from,
                                                                @Param("to") LocalDateTime to,
                                                                @Param("limit") int limit);
}
