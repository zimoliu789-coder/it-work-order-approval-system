package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.DivisionSource;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.trip.BusinessTripRules;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.order.dto.vo.DivisionPrefillVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 「当前所在分部」的预填与来源判定（V54 / D5.2、D5.3）。
 *
 * <h2>它回答两个问题</h2>
 * <ol>
 *   <li><b>打开表单时默认选哪个分部</b>（{@link #prefill(Long)}）：
 *       今天在出差中 ⇒ 出差单的目标分部；否则 ⇒ 人事主部门所属的一级分部。</li>
 *   <li><b>提交的值是哪来的</b>（{@link #resolve(Long, Long)}）：
 *       把员工回传的分部与「此刻的预填值」比对 ⇒ 相同记预填来源，不同记 {@code MANUAL}。</li>
 * </ol>
 *
 * <h2>为什么来源不让前端自报</h2>
 * <p>前端可以随便填一个 {@code source=HR_PRIMARY}，而实际选的是别的分部 ——
 * 审计数据一旦可被前端决定，它就不再是审计数据。因此前端只回传「最终选了哪个分部」，
 * 来源由服务端比对得出。
 *
 * <h2>为什么兼职部门不参与</h2>
 * <p>D5.4 明确：兼职部门只用于「谁能看这张单」，**不参与**分部预填与处理小组匹配。
 * 因此这里取的是 {@code employee.department_id}（主部门），
 * 刻意**不**使用 {@code DepartmentService#activeMemberIdsOf} 那套「主 ∪ 兼职」并集口径 ——
 * 两者回答的是不同问题（一个是「谁属于这个部门」，一个是「这个人此刻在哪个分部」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DivisionPrefillService {

    /**
     * 出差单最多回看多少张。
     *
     * <p>历史出差单可能很多，但「今天正在出差」的那一张一定在最近若干张里
     * （重叠校验保证同一时间只有一张生效）。取 20 张足够覆盖「刚结束几趟 + 当前一趟」
     * 的实际情况，又不会把几百张历史单全读出来解析 JSON。
     */
    private static final int TRIP_LOOKBACK_LIMIT = 20;

    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final OrderFormDataMapper orderFormDataMapper;
    private final DepartmentMapper departmentMapper;
    private final DepartmentService departmentService;

    /**
     * 计算预填值（含可选分部列表），供提交页展示。
     *
     * @param userId 当前登录用户
     */
    public DivisionPrefillVO prefill(Long userId) {
        DivisionPrefillVO vo = compute(userId);
        vo.setOptions(enabledDivisions());
        return vo;
    }

    /**
     * 判定最终分部与来源（提交时调用）。
     *
     * @param userId              当前登录用户
     * @param submittedDivisionId 前端回传的分部 id；null = 未选择（用预填值）
     * @return 最终分部 id 与来源；两者都可能为 null（取不到任何分部时由调用方兜底）
     * @throws BusinessException 回传的不是「启用中的一级分部」
     */
    public ResolvedDivision resolve(Long userId, Long submittedDivisionId) {
        DivisionPrefillVO prefill = compute(userId);
        if (submittedDivisionId == null) {
            return new ResolvedDivision(prefill.getDivisionId(), prefill.getSource());
        }
        Department division = departmentMapper.selectById(submittedDivisionId);
        if (division == null || !Objects.equals(division.getDepth(), 1)
                || !Boolean.TRUE.equals(division.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「当前所在分部」必须选择一个启用中的一级分部");
        }
        boolean sameAsPrefill = Objects.equals(submittedDivisionId, prefill.getDivisionId());
        return new ResolvedDivision(submittedDivisionId,
                sameAsPrefill ? prefill.getSource() : DivisionSource.MANUAL.name());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private DivisionPrefillVO compute(Long userId) {
        DivisionPrefillVO vo = new DivisionPrefillVO();

        DivisionHit trip = tripDivisionOf(userId, LocalDate.now());
        if (trip != null) {
            vo.setDivisionId(trip.divisionId());
            vo.setDivisionName(trip.divisionName());
            vo.setSource(DivisionSource.TRIP_PREFILL.name());
            vo.setSourceLabel(DivisionSource.TRIP_PREFILL.getLabel());
            vo.setTripOrderNo(trip.orderNo());
            return vo;
        }

        Long hrDivisionId = hrDivisionOf(userId);
        if (hrDivisionId != null) {
            Department division = departmentMapper.selectById(hrDivisionId);
            vo.setDivisionId(hrDivisionId);
            vo.setDivisionName(division == null ? null : division.getDeptName());
            vo.setSource(DivisionSource.HR_PRIMARY.name());
            vo.setSourceLabel(DivisionSource.HR_PRIMARY.getLabel());
        }
        return vo;
    }

    /**
     * 人事主部门所属的一级分部（兼职不参与，见类注释）。
     */
    private Long hrDivisionOf(Long userId) {
        User user = userMapper.selectById(userId);
        Long deptId = user == null ? null : user.getDepartmentId();
        if (deptId == null) {
            return null;
        }
        return departmentService.rootDivisionIdOf(deptId);
    }

    /**
     * 查「今天正在出差」的那张单，返回其目标分部。
     *
     * <h2>为什么是「已通过（COMPLETED）」而不是「审批中」</h2>
     * <p>D5 原文：预填依据是「审批已通过 且 当前日期落在起止区间内」的出差申请。
     * 审批中的单可能会被驳回 —— 拿它预填，员工会以为出差已获批。
     *
     * <h2>目标分部失效时为什么直接回退，而不是继续往上找</h2>
     * <p>一张正在生效的出差单指向已停用/已删除的分部，是<b>数据异常</b>而非正常状态。
     * 此时「继续用主部门」是正确的降级（单子还能提交），同时必须留一条 WARN ——
     * 否则「出差了一周，单子却派给了总部 IT」永远不会有人解释得清。
     * 这里不再往上找别的有效出差单：重叠校验保证同一时间只有一张生效出差，
     * 若它的目标分部坏了，就说明这个人的配置有问题，用主部门 + 告警最诚实。
     */
    private DivisionHit tripDivisionOf(Long userId, LocalDate today) {
        List<Order> trips = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplicantId, userId)
                .eq(Order::getOrderType, OrderType.TRIP.name())
                .eq(Order::getStatus, OrderStatus.COMPLETED.name())
                .orderByDesc(Order::getId)
                .last("LIMIT " + TRIP_LOOKBACK_LIMIT));
        if (trips.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (Order trip : trips) {
            ids.add(trip.getId());
        }
        Map<Long, Map<String, Object>> dataByOrder = formDataOf(ids);

        for (Order trip : trips) {
            Map<String, Object> data = dataByOrder.get(trip.getId());
            if (data == null) {
                continue;
            }
            LocalDate start = BusinessTripRules.parseDate(data.get(BusinessTripRules.FIELD_START_DATE));
            LocalDate end = BusinessTripRules.parseDate(data.get(BusinessTripRules.FIELD_END_DATE));
            if (!BusinessTripRules.covers(start, end, today)) {
                continue;
            }
            Long targetId = BusinessTripRules.parseDeptId(
                    data.get(BusinessTripRules.FIELD_TARGET_DEPT));
            Department target = targetId == null ? null : departmentMapper.selectById(targetId);
            if (target == null || !Objects.equals(target.getDepth(), 1)
                    || !Boolean.TRUE.equals(target.getStatus())) {
                log.warn("出差单 {} 的目标分部 id={} 已失效（不存在 / 非一级 / 已停用），"
                                + "「当前所在分部」预填回退为人事主部门", trip.getOrderNo(), targetId);
                return null;
            }
            return new DivisionHit(target.getId(), target.getDeptName(), trip.getOrderNo());
        }
        return null;
    }

    /**
     * 批量读表单数据。
     *
     * <p>单条 JSON 损坏时**跳过而不再抛出**：这是「打开提交页」的链路，
     * 一条历史坏单不该让员工连表单都打不开（TripOverlapGuard 踩过同一个坑）。
     */
    private Map<Long, Map<String, Object>> formDataOf(List<Long> orderIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        List<OrderFormData> rows = orderFormDataMapper.selectList(Wrappers.<OrderFormData>lambdaQuery()
                .in(OrderFormData::getOrderId, orderIds));
        for (OrderFormData row : rows) {
            try {
                result.put(row.getOrderId(), FormSchemaCodec.readData(row.getFormDataJson()));
            } catch (RuntimeException e) {
                log.warn("工单 {} 的表单数据无法解析，跳过（不参与分部预填）：{}",
                        row.getOrderId(), e.getMessage());
            }
        }
        return result;
    }

    /** 启用中的一级分部（下拉数据源） */
    private List<DepartmentOptionVO> enabledDivisions() {
        List<DepartmentOptionVO> options = new ArrayList<>();
        for (Department dept : departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .eq(Department::getDepth, 1)
                .eq(Department::getStatus, true)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId))) {
            // 一级分部的 displayPath 就是「公司 / 分部」两段，但下拉里只需要分部名 ——
            // 所有选项都已限定为一级，再加前缀只会把标签拉长到被截断。
            options.add(DepartmentOptionVO.of(dept, dept.getDeptName()));
        }
        return options;
    }

    /** 命中的一条出差分部 */
    private record DivisionHit(Long divisionId, String divisionName, String orderNo) {
    }

    /**
     * 提交时的最终结果。
     *
     * @param divisionId 最终分部 id；null = 取不到
     * @param source     来源枚举名；null = 没有分部
     */
    public record ResolvedDivision(Long divisionId, String source) {
    }
}
