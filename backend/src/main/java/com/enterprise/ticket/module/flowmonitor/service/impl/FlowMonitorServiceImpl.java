package com.enterprise.ticket.module.flowmonitor.service.impl;

import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorFlowVO;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorNodeVO;
import com.enterprise.ticket.module.flowmonitor.mapper.FlowMonitorMapper;
import com.enterprise.ticket.module.flowmonitor.service.FlowMonitorService;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 流程监控实现（ · M7）
 *
 * <h2>服务层只做三件事</h2>
 * <ol>
 *   <li><b>补展示信息</b>：未归属桶的文案、名字快照缺失时的兜底；</li>
 *   <li><b>挑瓶颈</b>：从节点聚合结果里选出"平均耗时最大"与"超时率最高"各一个；</li>
 *   <li><b>排序</b>：让"最该看的"排在前面。</li>
 * </ol>
 * 数值本身全部由 SQL 算好（见 {@code FlowMonitorMapper}），服务层不做二次统计 ——
 * 在 Java 里重算一遍会让"汇总页"与"明细页"存在两条计算路径，那正是要避免的东西。
 *
 * <h2>两个端点共用一次聚合</h2>
 * <p>{@link #flows()} 与 {@link #nodes(Long)} 都取同一份 {@code nodeSummary}，
 * 后者只是过滤。这保证了汇总行上的瓶颈节点，在任何情况下都能在明细里原样找到
 * （数字一致、名字一致）—— 若两条路径各查各的，用户点进去发现对不上就会开始怀疑整套数字。
 */
@Service
@RequiredArgsConstructor
public class FlowMonitorServiceImpl implements FlowMonitorService {

    /**
     * 判定「运行期激活」的宽限秒数。
     *
     * <p>提交时物化的节点，其 {@code activated_at} 与工单 {@code created_at} 是同一事务里的
     * 两个时间点，可能有毫秒到秒级的先后差。不设宽限会把它们误判成"运行期激活"，
     * 于是每个普通节点的 {@code runtimeActivatedCount} 都等于样本数，这个指标就废了。
     * 60 秒足够吸收事务内抖动，又远小于任何真实审批等待。
     */
    private static final int ACTIVATION_GRACE_SECONDS = 60;

    /** 未归属桶的展示名（模板名与实际都不存在时使用） */
    private static final String UNATTRIBUTED_NAME = "未归属（无模板 / 模板已删除）";

    /** 模板名与订单快照名都取不到时的兜底（例如模板被删且快照列为空的历史行） */
    private static final String UNKNOWN_FLOW_NAME = "未知流程";

    private final FlowMonitorMapper flowMonitorMapper;

    @Override
    public List<FlowMonitorFlowVO> flows() {
        List<FlowMonitorFlowVO> raw = flowMonitorMapper.flowSummary();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // 必须拷进可变列表再排序：**不能依赖 mapper 返回的是不是可变集合**。
        // 生产环境 MyBatis 恰好给 ArrayList，测试桩给 List.of —— 直接 sort() 会在后者上
        // 抛 UnsupportedOperationException。这种"只在某一种实现下能跑"的代码，
        // 一旦将来换掉 mapper 实现或加了缓存包装就会在运行期炸，而且是偶发。
        List<FlowMonitorFlowVO> rows = new ArrayList<>(raw);
        Map<Long, List<FlowMonitorNodeVO>> nodesByFlow = nodes().stream()
                .collect(Collectors.groupingBy(node -> flowIdOf(node.getFlowId())));

        for (FlowMonitorFlowVO row : rows) {
            Long flowId = flowIdOf(row.getFlowId());
            row.setFlowId(flowId);
            row.setUnattributed(flowId == UNATTRIBUTED_FLOW_ID);
            row.setFlowName(resolveFlowName(row));
            // 版本号为 "1,2" 形式，补上 v 前缀让界面直接可读；未归属桶没有版本行，保持 null
            row.setVersionLabels(prefixVersions(row.getVersionLabels()));
            List<FlowMonitorNodeVO> nodes = nodesByFlow.getOrDefault(flowId, List.of());
            row.setBottleneckByDuration(pick(nodes, FlowMonitorNodeVO::getAvgHours,
                    FlowMonitorNodeVO::getSampleCount));
            row.setBottleneckByOverdue(pick(nodes, FlowMonitorNodeVO::getOverdueRate,
                    FlowMonitorNodeVO::getWithDeadlineCount));
        }

        // 未归属桶恒排最后：它的数字天然是"剩下的"，摆在模板之间会打断阅读顺序
        rows.sort(Comparator
                .comparing(FlowMonitorFlowVO::isUnattributed)
                .thenComparing(Comparator.comparingLong(FlowMonitorFlowVO::getOrderCount).reversed())
                .thenComparing(row -> row.getFlowName() == null ? "" : row.getFlowName()));
        return rows;
    }

    @Override
    public List<FlowMonitorNodeVO> nodes(Long flowId) {
        long target = flowId == null ? UNATTRIBUTED_FLOW_ID : flowId;
        return nodes().stream()
                // 与 flows() 用同一个归一函数：SQL 已用 COALESCE 保证不为 null，
                // 但两个入口必须给出**同一种**兜底，否则"汇总行点进去是空的"会变成偶发问题
                .filter(node -> flowIdOf(node.getFlowId()) == target)
                .sorted(Comparator
                        // 最慢的排最前；无耗时的（理论上不会出现，AVG 有样本就非空）排最后
                        .comparing(FlowMonitorNodeVO::getAvgHours,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(FlowMonitorNodeVO::getSampleCount).reversed())
                        .thenComparing(FlowMonitorNodeVO::getNodeKey,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** 取全部节点聚合（两个公开方法共用，见类注释） */
    private List<FlowMonitorNodeVO> nodes() {
        List<FlowMonitorNodeVO> rows = flowMonitorMapper.nodeSummary(
                FlowActivationService.ADDSIGN_KEY_MARKER, ACTIVATION_GRACE_SECONDS);
        return rows == null ? List.of() : rows;
    }

    /**
     * flowId 归一：{@code null → 0}（未归属桶）。
     *
     * <p>SQL 侧已经用 {@code COALESCE(v.flow_id, 0)} 做过一次，这里是第二道 ——
     * 两个公开入口（汇总 / 明细）必须用**同一个**函数做兜底，
     * 否则会出现"汇总页有这一行、点进去节点明细却是空的"这种只在特定数据下复现的问题。
     */
    private static long flowIdOf(Long flowId) {
        return flowId == null ? UNATTRIBUTED_FLOW_ID : flowId;
    }

    /**
     * 模板名的取值顺序：现存模板名 → 提交时的名字快照 → 兜底文案。
     *
     * <p>SQL 已经把前两者 {@code COALESCE} 成了一个字段，这里只负责处理"两者都为空"
     * （模板与快照列双双缺失的历史行）以及未归属桶的固定文案。
     */
    private String resolveFlowName(FlowMonitorFlowVO row) {
        if (row.isUnattributed()) {
            return UNATTRIBUTED_NAME;
        }
        String name = row.getFlowName();
        return (name == null || name.isBlank()) ? UNKNOWN_FLOW_NAME : name;
    }

    /** {@code "1,2"} → {@code "v1, v2"}；空值原样返回（未归属桶没有版本行） */
    private String prefixVersions(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                parts.add("v" + trimmed);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * 在节点明细里挑"最该被关注的那一个"。
     *
     * <p>用显式循环而不是 {@code Comparator} 链，是为了让**并列规则一眼可见**：
     * 指标相同 → 样本多者优先（样本多的那个更可信）；样本也相同 → {@code nodeKey}
     * 字典序小者优先。最后这条不是业务规则，而是为了让结果**稳定**：
     * 没有它，并列时取哪个取决于 SQL 返回顺序，同一份数据可能两次给出不同答案，
     * 测试也没法断言。
     *
     * <p>指标为 {@code null} 的节点直接跳过 —— 那是"无法判定"（例如所有节点都没设时限时，
     * 超时率全是 null），不是 0。把 null 当 0 会把一个"没时限"的节点选成"超时率最高的瓶颈"。
     */
    private FlowMonitorNodeVO pick(List<FlowMonitorNodeVO> nodes,
                                   Function<FlowMonitorNodeVO, Double> metric,
                                   Function<FlowMonitorNodeVO, Long> weight) {
        FlowMonitorNodeVO best = null;
        Double bestMetric = null;
        long bestWeight = 0L;
        for (FlowMonitorNodeVO node : nodes) {
            Double current = metric.apply(node);
            if (current == null) {
                continue;
            }
            long currentWeight = weight.apply(node);
            if (best == null) {
                best = node;
                bestMetric = current;
                bestWeight = currentWeight;
                continue;
            }
            int cmp = Double.compare(current, bestMetric);
            if (cmp < 0) {
                continue;
            }
            if (cmp == 0) {
                if (currentWeight < bestWeight) {
                    continue;
                }
                if (currentWeight == bestWeight
                        && !isKeySmaller(node.getNodeKey(), best.getNodeKey())) {
                    continue;
                }
            }
            best = node;
            bestMetric = current;
            bestWeight = currentWeight;
        }
        return best;
    }

    private boolean isKeySmaller(String candidate, String current) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.compareTo(current) < 0;
    }
}
