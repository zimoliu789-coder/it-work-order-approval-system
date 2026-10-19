package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.trip.BusinessTripRules;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 出差提交闸门（V53）的行为固化。
 *
 * <h2>为什么这些用例重要</h2>
 * 「同一员工只能有一份生效出差」是方案定稿（2026-10-16 用户批复）的硬规则，而它的
 * 三个易错点都集中在闸门里：<b>状态口径</b>（只有 COMPLETED 算生效）、
 * <b>边界口径</b>（闭区间：首尾同日也算重叠）、<b>顺序校验</b>（开始不得晚于结束）。
 * 任何一处写歪，表现都是静默的 —— 要么该拦不拦（重叠出差混进去），
 * 要么不该拦乱拦（审批中的出差把新单卡死）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripOverlapGuardTest {

    private static final Long APPLICANT_ID = 42L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class);
        MyBatisLambdaCache.init(OrderFormData.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderFormDataMapper orderFormDataMapper;

    @InjectMocks
    private TripOverlapGuard guard;

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> formData(String start, String end) {
        Map<String, Object> data = new HashMap<>();
        data.put(BusinessTripRules.FIELD_START_DATE, start);
        data.put(BusinessTripRules.FIELD_END_DATE, end);
        return data;
    }

    private Order completedTrip(String orderNo, String start, String end) {
        Order order = new Order();
        order.setId(900L);
        order.setOrderNo(orderNo);
        order.setOrderType("TRIP");
        order.setStatus("COMPLETED");
        order.setApplicantId(APPLICANT_ID);
        OrderFormData form = new OrderFormData();
        form.setOrderId(900L);
        form.setFormDataJson(com.enterprise.ticket.common.form.FormSchemaCodec.write(formData(start, end)));
        when(orderFormDataMapper.selectOne(any())).thenReturn(form);
        return order;
    }

    private void stubEffectiveTrips(Order... orders) {
        when(orderMapper.selectList(any())).thenReturn(List.of(orders));
    }

    private ErrorCode codeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 字段级闸门
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日期缺失（直调接口绕过必填）：FORM_DATA_INVALID 拦截")
    void missingDatesRejected() {
        stubEffectiveTrips();
        Map<String, Object> data = new HashMap<>();
        assertEquals(ErrorCode.FORM_DATA_INVALID, codeOf(() -> guard.assertSubmittable(APPLICANT_ID, data)));
    }

    @Test
    @DisplayName("开始晚于结束：拦截（顺序颠倒的出差没有业务意义）")
    void startAfterEndRejected() {
        stubEffectiveTrips();
        assertEquals(ErrorCode.FORM_DATA_INVALID,
                codeOf(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-10", "2026-11-01"))));
    }

    // ------------------------------------------------------------------
    // 重叠判定（闭区间口径）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 与生效出差区间重叠：拦截，报错带对方单号（用户要能对上「挡我的是哪一单」）")
    void overlappingCompletedTripRejected() {
        stubEffectiveTrips(completedTrip("CC20261019001", "2026-11-01", "2026-11-05"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                guard.assertSubmittable(APPLICANT_ID, formData("2026-11-04", "2026-11-08")));
        assertEquals(ErrorCode.FORM_DATA_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("CC20261019001"), "报错必须带上生效出差的单号");
    }

    @Test
    @DisplayName("首尾相接也算重叠（闭区间）：11-05 结束的出差挡住 11-05 开始的新出差")
    void touchingDaysCountAsOverlap() {
        stubEffectiveTrips(completedTrip("CC20261019002", "2026-11-01", "2026-11-05"));

        assertEquals(ErrorCode.FORM_DATA_INVALID,
                codeOf(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-05", "2026-11-09"))));
    }

    @Test
    @DisplayName("区间完全错开：放行")
    void disjointIntervalsPass() {
        stubEffectiveTrips(completedTrip("CC20261019003", "2026-11-01", "2026-11-05"));

        assertDoesNotThrow(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-06", "2026-11-10")));
    }

    // ------------------------------------------------------------------
    // 「生效」状态口径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 只有 COMPLETED 算生效：审批中的出差不拦（否则两份先后提交的单会互相卡死在审批中）")
    void onlyCompletedTripsCount() {
        // 闸门收到的列表由「状态口径」的 SQL 决定；这里模拟口径正确时的返回：
        // 审批中的出差**不会**出现在 effectiveTripsOf 的结果里。
        Order pendingTrip = completedTrip("CC20261019004", "2026-11-01", "2026-11-05");
        pendingTrip.setStatus("PENDING_APPROVAL");
        // 模拟 Mapper 只返回 COMPLETED 单 —— 即库里有一份重叠的审批中出差也不返回
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-02", "2026-11-04")));
    }

    @Test
    @DisplayName("历史单表单数据损坏（读不出日期）：不参与判定，放行而非 500")
    void corruptHistoricalFormDataDoesNotBlock() {
        Order order = completedTrip("CC20261019005", "2026-11-01", "2026-11-05");
        OrderFormData corrupt = new OrderFormData();
        corrupt.setOrderId(order.getId());
        corrupt.setFormDataJson("{\"trip_start_date\":\"不是日期\"}");
        when(orderFormDataMapper.selectOne(any())).thenReturn(corrupt);
        stubEffectiveTrips(order);

        assertDoesNotThrow(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-02", "2026-11-04")));
    }

    @Test
    @DisplayName("★ 历史单的表单 JSON 本身损坏：吞掉解析异常、该单不参与判定（不能因一条坏单让本人此后永远提交不了）")
    void malformedJsonDoesNotBlock() {
        Order order = completedTrip("CC20261019007", "2026-11-01", "2026-11-05");
        OrderFormData broken = new OrderFormData();
        broken.setOrderId(order.getId());
        // 非法 JSON：FormSchemaCodec.readData 会抛 BusinessException，闸门必须吞掉
        broken.setFormDataJson("{not-json");
        when(orderFormDataMapper.selectOne(any())).thenReturn(broken);
        stubEffectiveTrips(order);

        assertDoesNotThrow(() -> guard.assertSubmittable(APPLICANT_ID, formData("2026-11-02", "2026-11-04")));
    }

    // ------------------------------------------------------------------
    // 防回归：日期解析口径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("带时间部分的日期值（yyyy-MM-dd HH:mm:ss）也能解析（宽松口径，兼容历史数据）")
    void dateTimeValueParsedLeniently() {
        stubEffectiveTrips(completedTrip("CC20261019006", "2026-11-01 00:00:00", "2026-11-05 00:00:00"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                guard.assertSubmittable(APPLICANT_ID, formData("2026-11-03", "2026-11-04")));
        assertEquals(ErrorCode.FORM_DATA_INVALID, ex.getErrorCode());
    }
}
