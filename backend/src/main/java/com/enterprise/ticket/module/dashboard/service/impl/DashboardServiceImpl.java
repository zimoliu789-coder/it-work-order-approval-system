package com.enterprise.ticket.module.dashboard.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.dashboard.dto.DashboardQuery;
import com.enterprise.ticket.module.dashboard.dto.vo.DashboardSummaryVO;
import com.enterprise.ticket.module.dashboard.mapper.DashboardMapper;
import com.enterprise.ticket.module.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统计仪表盘实现（ · M5）
 *
 * <h2>服务层只做「补文案 + 归一 null + 定序」三件事</h2>
 * <ol>
 *   <li><b>补展示文案</b>：类型 / 状态的中文名取自既有枚举，分组名缺失时给兜底文案。
 *       文案只在服务层出现一次，SQL 里不写中文 —— 否则改一个中文名要同时改两处。</li>
 *   <li><b>归一 null</b>：把 SQL 可能返回 {@code null} 的计数统一成 0，
 *       但<b>刻意保留</b> {@code avgApprovalHours} / {@code overdueRate} 的 {@code null}
 *       —— 那是「无法判定」，不是 0（见 {@link DashboardSummaryVO}）。</li>
 *   <li><b>定序</b>：未分组的工单排在最后，与 M7 把「未归属」桶摆最后同一取向 ——
 *       「剩下的」不应该打断真实分组之间的阅读顺序。</li>
 * </ol>
 * 数值本身全部由 SQL 算好，服务层不做二次统计：在 Java 里重算一遍就等于让同一指标
 * 存在两条计算路径，那正是 M7 与 M5 都刻意避免的东西。
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 未分组桶的哨兵 id（自增主键不会产出 0，作哨兵安全） */
    private static final long UNGROUPED_ID = 0L;

    /** {@code department_id} 为空时的展示名 */
    private static final String UNGROUPED_NAME = "未分组";

    /** 分组 id 存在但分组行取不到名字时的兜底（人工改库、脏数据） */
    private static final String UNKNOWN_GROUP_NAME = "未知分组";

    /** 类型 / 状态编码无法解析时的兜底文案 */
    private static final String UNKNOWN_LABEL = "未知";

    private final DashboardMapper dashboardMapper;

    @Override
    public DashboardSummaryVO summary(DashboardQuery query) {
        DashboardQuery q = query == null ? new DashboardQuery() : query;
        validate(q);

        LocalDateTime from = q.fromDateTime();
        LocalDateTime to = q.toDateTime();

        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setFrom(q.effectiveFrom());
        vo.setTo(q.effectiveTo());
        vo.setGranularity(q.isMonthly() ? DashboardQuery.GRANULARITY_MONTH : DashboardQuery.GRANULARITY_DAY);

        vo.setTotalOrders(nullToZero(dashboardMapper.countOrders(from, to)));
        vo.setByType(labelTypes(dashboardMapper.countByType(from, to)));
        vo.setByStatus(labelStatuses(dashboardMapper.countByStatus(from, to)));
        vo.setByTime(emptyIfNull(dashboardMapper.countByTime(from, to, q.datePattern())));
        vo.setByGroup(labelGroups(dashboardMapper.countByGroup(from, to)));

        applyApprovalStats(vo, dashboardMapper.approvalStats(from, to));
        applyOverdueStats(vo, dashboardMapper.overdueStats(from, to));
        return vo;
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * 校验粒度与区间方向。
     *
     * <p>粒度只接受 {@code DAY} / {@code MONTH}（大小写不敏感、空白容忍），
     * <b>未知值直接拒绝而不是回落按天</b>：前端若某天传了 {@code MONTHLY} 这种拼写，
     * 静默按天出图会让「为什么按月筛选没生效」变成一个要靠猜的问题。
     * （{@code null} / 空串是「没指定」，按默认 DAY，这是正常调用。）
     */
    private void validate(DashboardQuery q) {
        String granularity = q.getGranularity();
        if (granularity != null && !granularity.isBlank()
                && !DashboardQuery.GRANULARITY_DAY.equalsIgnoreCase(granularity.trim())
                && !DashboardQuery.GRANULARITY_MONTH.equalsIgnoreCase(granularity.trim())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "时间粒度只支持 DAY 或 MONTH");
        }
        if (q.isReversed()) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE, "开始日期不能晚于结束日期");
        }
    }

    // ------------------------------------------------------------------
    // 文案与归一
    // ------------------------------------------------------------------

    private List<DashboardSummaryVO.Bucket> labelTypes(List<DashboardSummaryVO.Bucket> rows) {
        List<DashboardSummaryVO.Bucket> list = emptyIfNull(rows);
        for (DashboardSummaryVO.Bucket row : list) {
            row.setLabel(labelOrUnknown(OrderType.of(row.getCode())));
            row.setCount(nullToZero(row.getCount()));
        }
        return list;
    }

    private List<DashboardSummaryVO.Bucket> labelStatuses(List<DashboardSummaryVO.Bucket> rows) {
        List<DashboardSummaryVO.Bucket> list = emptyIfNull(rows);
        for (DashboardSummaryVO.Bucket row : list) {
            OrderStatus status = OrderStatus.of(row.getCode());
            row.setLabel(status == null ? labelOrUnknown(null) : status.getLabel());
            row.setCount(nullToZero(row.getCount()));
        }
        return list;
    }

    /**
     * 给部门桶补名字并把「未分组」排到最后。
     *
     * <p>名字的三种来源：分组表里的名字 → 「未分组」（{@code department_id} 为空）
     * → 「未知分组」（有 id 但取不到名字）。三者都不是 null，界面因此不需要再处理空文案。
     */
    private List<DashboardSummaryVO.GroupBucket> labelGroups(List<DashboardSummaryVO.GroupBucket> rows) {
        List<DashboardSummaryVO.GroupBucket> list = new ArrayList<>(emptyIfNull(rows));
        for (DashboardSummaryVO.GroupBucket row : list) {
            long groupId = row.getGroupId() == null ? UNGROUPED_ID : row.getGroupId();
            row.setGroupId(groupId);
            if (groupId == UNGROUPED_ID) {
                row.setGroupName(UNGROUPED_NAME);
            } else {
                String name = row.getGroupName();
                row.setGroupName(name == null || name.isBlank() ? UNKNOWN_GROUP_NAME : name);
            }
            row.setCount(nullToZero(row.getCount()));
        }
        // 未分组恒排最后；其余保持 SQL 的计数降序（再用 groupId 做稳定次级键）
        list.sort(Comparator
                .comparing((DashboardSummaryVO.GroupBucket row) -> row.getGroupId() == UNGROUPED_ID)
                .thenComparing(DashboardSummaryVO.GroupBucket::getGroupId));
        return list;
    }

    /** 样本数 / 均值：无样本时计数为 0 且均值保持 null（不把「没数据」说成「0 小时」） */
    private void applyApprovalStats(DashboardSummaryVO vo, DashboardSummaryVO.ApprovalStats stats) {
        if (stats == null) {
            vo.setApprovedOrderCount(0L);
            vo.setAvgApprovalHours(null);
            return;
        }
        vo.setApprovedOrderCount(nullToZero(stats.getApprovedOrderCount()));
        vo.setAvgApprovalHours(stats.getAvgApprovalHours());
    }

    /** 超时率：分母为 0 时比值为 null，但两个计数照常返回，让界面能显示「0 / 15 有时限」 */
    private void applyOverdueStats(DashboardSummaryVO vo, DashboardSummaryVO.OverdueStats stats) {
        if (stats == null) {
            vo.setOverdueNodeCount(0L);
            vo.setWithDeadlineNodeCount(0L);
            vo.setOverdueRate(null);
            return;
        }
        vo.setOverdueNodeCount(nullToZero(stats.getOverdueNodeCount()));
        vo.setWithDeadlineNodeCount(nullToZero(stats.getWithDeadlineNodeCount()));
        vo.setOverdueRate(stats.getOverdueRate());
    }

    private static String labelOrUnknown(Enum<?> value) {
        if (value == null) {
            return UNKNOWN_LABEL;
        }
        // OrderType 有 getLabel()；此处通过 switch 显式取值，避免依赖反射
        if (value instanceof OrderType type) {
            return type.getLabel();
        }
        return UNKNOWN_LABEL;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static <T> List<T> emptyIfNull(List<T> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }
}
