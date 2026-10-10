package com.enterprise.ticket.module.flowmonitor.mapper;

import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorFlowVO;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorNodeVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 流程监控聚合查询（ · M7）
 *
 * <h2>为什么是两条"全量聚合"方法，而不是按 flowId 逐次查</h2>
 * <p>汇总页天生要一次看全部模板，而明细页的"瓶颈 top1"必须与汇总页**同源** ——
 * 若两个端点各写一套 WHERE，两边口径迟早会漂移（M4a 已经为这类"同一份数据两个结论"
 * 付过一次代价）。这里让两个端点共用同样的聚合结果，服务层只做"取全部 / 取一个"的差别，
 * 从结构上杜绝了不一致。
 *
 * <p>数据量级也很友好：聚合后的行数 ≈ 模板数 × 节点数（几十行），
 * 不存在"读进内存再算会爆"的问题 —— 这与 {@code ReportMapper} 面对的全量明细场景不同。
 *
 * <h2>{@code COALESCE(v.flow_id, 0)} 是归属解析的核心</h2>
 * <p>工单只有版本指针，模板 id 靠 {@code approval_flow_version} 反查。该指针落空的三种情形
 * 被这个表达式统一归成 {@code 0}（未归属桶）：
 * <ol>
 *   <li>版本指针本就是 NULL —— 分组固定审批人表 / 借用单内置流程 / 存量历史单；</li>
 *   <li>版本行已被删除 —— {@code deleteFlow()} 会连版本行一起物理删除（需求方确认保持该语义）；</li>
 *   <li>版本行在但 {@code flow_id} 指向的模板行没了（人工改库）。</li>
 * </ol>
 * 三者都"归不到任何现存模板"，合并成一行正是需求方要的「未归属」分组。
 *
 * <h2>{@code GROUP_CONCAT} 取版本标签</h2>
 * <p>同一模板可能有多版且都跑过单，节点聚合是跨版本做的。把版本号拼成一个字符串
 * 随汇总行返回，管理员就能判断"这 40 单其实是 v1+v2 两拨"，不必再去翻版本历史。
 * 返回字符串而非数组是刻意的：省掉一层类型转换，且它本来就是给人看的标签。
 */
@Mapper
public interface FlowMonitorMapper {

    /**
     * 模板维度汇总：每个流程模板的工单量 / 平均审批时长。
     *
     * <p>已按 {@code flowId} 分组，行数 = 现存模板数 + （有未归属单时）1 行零号桶。
     * 平均时长的分子分母都在 SQL 里算完：{@code AVG} 会自然忽略"终点为 NULL"（审批未结束）的行，
     * 与 {@code approvedOrderCount} 给出的样本数严格对应。
     *
     * <p>{@code ROUND(..., 2)} 放在 SQL 而不是 Java：聚合结果只有几十行，
     * 精度在数据源统一收口后，前端/导出/测试三条路径拿到的是同一个数。
     */
    @Select("""
            SELECT COALESCE(v.flow_id, 0)                                                AS flowId,
                   COALESCE(MAX(f.flow_name), MAX(o.approval_flow_name))                 AS flowName,
                   GROUP_CONCAT(DISTINCT v.version_no ORDER BY v.version_no)             AS versionLabels,
                   COUNT(*)                                                              AS orderCount,
                   SUM(CASE WHEN ap.finished_at IS NOT NULL THEN 1 ELSE 0 END)            AS approvedOrderCount,
                   ROUND(AVG(TIMESTAMPDIFF(SECOND, o.created_at, ap.finished_at)) / 3600, 2) AS avgApprovalHours
              FROM borrow_order o
              LEFT JOIN approval_flow_version v ON v.id = o.approval_flow_version_id
              LEFT JOIN approval_flow f         ON f.id = v.flow_id
              LEFT JOIN (SELECT n.order_id, MAX(n.action_time) AS finished_at
                           FROM order_approval_node n
                          WHERE n.status IN ('APPROVED', 'REJECTED')
                            AND (n.node_type IS NULL OR n.node_type = 'APPROVAL')
                          GROUP BY n.order_id) ap ON ap.order_id = o.id
             WHERE o.approval_flow_json IS NOT NULL
             GROUP BY COALESCE(v.flow_id, 0)
            """)
    List<FlowMonitorFlowVO> flowSummary();

    /**
     * 节点维度明细：按 (模板, 节点, 节点名) 聚合平均耗时与超时率。
     *
     * <h2>为什么要按 {@code node_name} 一起分组</h2>
     * <p>节点名是提交时固化的定义快照。同名同 key 才是"同一个节点"；
     * 不同名（例如超时加签产生的「主管审批（超时加签）」）必须分开成行 ——
     * 否则加签行的耗时（从加签那一刻起算，通常很短）会把原节点的平均值拉低，
     * 而"这个节点到底慢不慢"恰恰是管理员唯一想知道的事。
     *
     * <h2>加签行折回父节点</h2>
     * <p>加签节点的 key 形如 {@code n1#addsign-<纳秒>}，每行都唯一。用
     * {@link com.enterprise.ticket.module.order.service.FlowActivationService#ADDSIGN_KEY_MARKER}
     * 截断到父 key 后，同一父节点的多次加签才会聚成一行，而不是在监控页刷出一批
     * 样本数恒为 1 的"幽灵节点"。标记由调用方以参数传入，SQL 里不重复写字面量。
     *
     * @param addSignMarker           加签 key 标记，传 {@code ADDSIGN_KEY_MARKER}
     * @param activationGraceSeconds  判定"运行期激活"的宽限秒数：{@code activated_at} 晚于
     *                                提交时刻超过该值才算运行期激活，用于吸收同一事务内的
     *                                时间戳微差（提交即物化的节点 activated_at ≈ created_at）
     */
    @Select("""
            SELECT COALESCE(v.flow_id, 0)                                       AS flowId,
                   SUBSTRING_INDEX(n.node_key, #{addSignMarker}, 1)             AS nodeKey,
                   n.node_name                                                  AS nodeName,
                   MAX(n.node_type)                                             AS nodeType,
                   COUNT(*)                                                     AS sampleCount,
                   ROUND(AVG(TIMESTAMPDIFF(SECOND,
                           COALESCE(n.activated_at, o.created_at), n.action_time)) / 3600, 2) AS avgHours,
                   SUM(CASE WHEN n.deadline_at IS NOT NULL AND n.action_time > n.deadline_at
                            THEN 1 ELSE 0 END)                                  AS overdueCount,
                   SUM(CASE WHEN n.deadline_at IS NOT NULL THEN 1 ELSE 0 END)   AS withDeadlineCount,
                   ROUND(100 * SUM(CASE WHEN n.deadline_at IS NOT NULL AND n.action_time > n.deadline_at
                                        THEN 1 ELSE 0 END)
                             / NULLIF(SUM(CASE WHEN n.deadline_at IS NOT NULL THEN 1 ELSE 0 END), 0), 2) AS overdueRate,
                   SUM(CASE WHEN n.activated_at IS NOT NULL
                             AND n.activated_at > DATE_ADD(o.created_at, INTERVAL #{activationGraceSeconds} SECOND)
                            THEN 1 ELSE 0 END)                                  AS runtimeActivatedCount
              FROM order_approval_node n
              JOIN borrow_order o ON o.id = n.order_id
              LEFT JOIN approval_flow_version v ON v.id = o.approval_flow_version_id
             WHERE o.approval_flow_json IS NOT NULL
               AND n.node_key IS NOT NULL
               AND n.action_time IS NOT NULL
               AND n.status IN ('APPROVED', 'REJECTED')
               AND (n.node_type IS NULL OR n.node_type = 'APPROVAL')
             GROUP BY COALESCE(v.flow_id, 0),
                      SUBSTRING_INDEX(n.node_key, #{addSignMarker}, 1),
                      n.node_name
             ORDER BY COALESCE(v.flow_id, 0) ASC, sampleCount DESC, nodeKey ASC
            """)
    List<FlowMonitorNodeVO> nodeSummary(@Param("addSignMarker") String addSignMarker,
                                       @Param("activationGraceSeconds") int activationGraceSeconds);
}
