package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.DivisionSource;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.trip.BusinessTripRules;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.order.dto.vo.DivisionPrefillVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「当前所在分部」预填与来源判定单测（V54 / D5）。
 *
 * <h2>为什么这段逻辑必须钉住</h2>
 * <p>它同时决定两件后果不小的事：
 * <ol>
 *   <li><b>派给谁</b> —— 预填出来的分部是「匹配处理小组」的起点，错了就是北京的单子
 *       派给上海的 IT 组，而且<b>不报错</b>，只表现为「设备一直没人送」；</li>
 *   <li><b>审计怎么记</b> —— 来源（人事主部门 / 出差自动带入 / 手动修改）由服务端比对得出。
 *       一旦这个比对写错，运营看到的「多少单是员工手动改的」就全是假的。</li>
 * </ol>
 *
 * <p>覆盖五类分支：出差命中、无出差回退、<b>出差目标分部失效时回退</b>、
 * 提交时的来源判定（未改 / 未传 / 改了）、以及非法分部的拒绝。<b>历史坏 JSON 必须被跳过</b>
 * 也在其中 —— 那是「打开提交页」的链路，一条坏单不该让员工连表单都打不开。
 */
@ExtendWith(MockitoExtension.class)
class DivisionPrefillServiceTest {

    /** 固定「今天」，避免用 now() 写跨零点会抖的断言 */
    private static final LocalDate TODAY = LocalDate.now();

    @Mock
    private UserMapper userMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderFormDataMapper orderFormDataMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentService departmentService;

    @InjectMocks
    private DivisionPrefillService service;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, OrderFormData.class, Department.class, User.class);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static Department dept(long id, String name, int depth, boolean enabled) {
        Department d = new Department();
        d.setId(id);
        d.setDeptName(name);
        d.setDepth(depth);
        d.setStatus(enabled);
        d.setSortOrder(0);
        d.setHandlerGroup(false);
        return d;
    }

    private static Order trip(long id, String orderNo) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNo(orderNo);
        return o;
    }

    private static OrderFormData formData(long orderId, LocalDate start, LocalDate end, Long targetDeptId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(BusinessTripRules.FIELD_START_DATE, start == null ? null : start.toString());
        data.put(BusinessTripRules.FIELD_END_DATE, end == null ? null : end.toString());
        data.put(BusinessTripRules.FIELD_TARGET_DEPT, targetDeptId == null ? null : String.valueOf(targetDeptId));
        OrderFormData row = new OrderFormData();
        row.setOrderId(orderId);
        row.setFormDataJson(FormSchemaCodec.write(data));
        return row;
    }

    private static OrderFormData brokenFormData(long orderId) {
        OrderFormData row = new OrderFormData();
        row.setOrderId(orderId);
        // 半截 JSON：模拟人工改库 / 迁移中断留下的坏数据
        row.setFormDataJson("{\"trip_start_date\": ");
        return row;
    }

    private static User user(long id, long departmentId) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName("员工" + id);
        u.setDepartmentId(departmentId);
        u.setEnabled(true);
        u.setDimission(false);
        return u;
    }

    /** 让人事主部门回退这条路走通：user(1) 主部门 11 → 所属分部 10 */
    private void stubHrPrimary() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, 11L));
        when(departmentService.rootDivisionIdOf(11L)).thenReturn(10L);
        when(departmentMapper.selectById(10L)).thenReturn(dept(10L, "北京分部", 1, true));
    }

    private static ErrorCode errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 预填
    // ------------------------------------------------------------------

    @Test
    @DisplayName("今天正在出差：预填出差目标分部，来源=出差自动带入，并带上出差单号")
    void prefillFromTrip() {
        when(orderMapper.selectList(any())).thenReturn(List.of(trip(100L, "TRIP-2026-0001")));
        when(orderFormDataMapper.selectList(any())).thenReturn(List.of(
                formData(100L, TODAY.minusDays(1), TODAY.plusDays(1), 20L)));
        when(departmentMapper.selectById(20L)).thenReturn(dept(20L, "上海分部", 1, true));
        when(departmentMapper.selectList(any())).thenReturn(List.of(
                dept(10L, "北京分部", 1, true), dept(20L, "上海分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        assertEquals(20L, vo.getDivisionId());
        assertEquals("上海分部", vo.getDivisionName());
        assertEquals(DivisionSource.TRIP_PREFILL.name(), vo.getSource());
        assertEquals("出差自动带入", vo.getSourceLabel());
        assertEquals("TRIP-2026-0001", vo.getTripOrderNo());
        assertEquals(2, vo.getOptions().size());
        // 出差命中时不该再去查主部门：查了说明「预填优先级」被改反了
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("没有出差：回退人事主部门所属一级分部，来源=人事主部门")
    void prefillFallsBackToHrPrimary() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        assertEquals(10L, vo.getDivisionId());
        assertEquals("北京分部", vo.getDivisionName());
        assertEquals(DivisionSource.HR_PRIMARY.name(), vo.getSource());
        assertEquals("人事主部门", vo.getSourceLabel());
        assertNull(vo.getTripOrderNo());
    }

    @Test
    @DisplayName("出差单今天不覆盖：不算出差，仍回退人事主部门")
    void prefillIgnoresTripOutsideRange() {
        when(orderMapper.selectList(any())).thenReturn(List.of(trip(100L, "TRIP-2026-0002")));
        when(orderFormDataMapper.selectList(any())).thenReturn(List.of(
                formData(100L, TODAY.plusDays(3), TODAY.plusDays(5), 20L)));
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        // 关键：只有「今天落在起止区间内」才算出差中（闭区间），
        // 否则「下周要去上海」会让人今天就把单子派到上海去
        assertEquals(10L, vo.getDivisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), vo.getSource());
    }

    @Test
    @DisplayName("出差目标分部已停用：回退人事主部门（不继续找别的出差单）")
    void prefillFallsBackWhenTripTargetDivisionDisabled() {
        when(orderMapper.selectList(any())).thenReturn(List.of(trip(100L, "TRIP-2026-0003")));
        when(orderFormDataMapper.selectList(any())).thenReturn(List.of(
                formData(100L, TODAY.minusDays(1), TODAY.plusDays(1), 20L)));
        // 目标分部已停用 → 数据异常，该降级
        when(departmentMapper.selectById(20L)).thenReturn(dept(20L, "上海分部", 1, false));
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        assertEquals(10L, vo.getDivisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), vo.getSource());
    }

    @Test
    @DisplayName("出差目标不是一级分部：同样回退人事主部门")
    void prefillFallsBackWhenTripTargetNotDivision() {
        when(orderMapper.selectList(any())).thenReturn(List.of(trip(100L, "TRIP-2026-0004")));
        when(orderFormDataMapper.selectList(any())).thenReturn(List.of(
                formData(100L, TODAY.minusDays(1), TODAY.plusDays(1), 21L)));
        // depth=2：不是分部，不能当成派工起点
        when(departmentMapper.selectById(21L)).thenReturn(dept(21L, "研发一组", 2, true));
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        assertEquals(10L, vo.getDivisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), vo.getSource());
    }

    @Test
    @DisplayName("历史坏 JSON：跳过该单而不是让整页打不开")
    void prefillSkipsBrokenFormData() {
        when(orderMapper.selectList(any())).thenReturn(List.of(trip(100L, "TRIP-2026-0005")));
        when(orderFormDataMapper.selectList(any())).thenReturn(List.of(brokenFormData(100L)));
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        // 不抛异常是重点：预填只是「打开提交页」的一个输入
        DivisionPrefillVO vo = service.prefill(1L);

        assertEquals(10L, vo.getDivisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), vo.getSource());
    }

    @Test
    @DisplayName("申请人没有部门：预填为空，下拉仍可列出分部（不阻断填单）")
    void prefillWithoutDepartment() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectById(1L)).thenReturn(user(1L, 0L));
        // 主部门 id=0 时不去查上级（rootDivisionIdOf 由实现判空短路，这里用 0 模拟"查不到"）
        when(departmentService.rootDivisionIdOf(0L)).thenReturn(null);
        when(departmentMapper.selectList(any())).thenReturn(List.of(dept(10L, "北京分部", 1, true)));

        DivisionPrefillVO vo = service.prefill(1L);

        assertNull(vo.getDivisionId());
        assertNull(vo.getSource());
        assertEquals(1, vo.getOptions().size());
    }

    // ------------------------------------------------------------------
    // 提交时的来源判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未传分部：用预填值，来源仍是预填来源")
    void resolveUsesPrefillWhenAbsent() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();

        DivisionPrefillService.ResolvedDivision resolved = service.resolve(1L, null);

        assertEquals(10L, resolved.divisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), resolved.source());
    }

    @Test
    @DisplayName("传了与预填相同的分部：来源仍记为预填来源（员工没改）")
    void resolveKeepsPrefillSourceWhenUnchanged() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();

        DivisionPrefillService.ResolvedDivision resolved = service.resolve(1L, 10L);

        assertEquals(10L, resolved.divisionId());
        assertEquals(DivisionSource.HR_PRIMARY.name(), resolved.source());
    }

    @Test
    @DisplayName("传了不同的分部：来源记为手动修改")
    void resolveMarksManualWhenChanged() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectById(30L)).thenReturn(dept(30L, "广州分部", 1, true));

        DivisionPrefillService.ResolvedDivision resolved = service.resolve(1L, 30L);

        assertEquals(30L, resolved.divisionId());
        assertEquals(DivisionSource.MANUAL.name(), resolved.source());
    }

    @Test
    @DisplayName("传了非一级分部：拒绝（下拉只该给一级分部，绕过前端也拦得住）")
    void resolveRejectsNonDivision() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectById(40L)).thenReturn(dept(40L, "研发一组", 2, true));

        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.resolve(1L, 40L)));
    }

    @Test
    @DisplayName("传了已停用分部：拒绝")
    void resolveRejectsDisabledDivision() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectById(41L)).thenReturn(dept(41L, "上海分部", 1, false));

        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.resolve(1L, 41L)));
    }

    @Test
    @DisplayName("传了不存在的分部：拒绝")
    void resolveRejectsUnknownDivision() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectById(42L)).thenReturn(null);

        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.resolve(1L, 42L)));
    }

    @Test
    @DisplayName("下拉只含启用的一级分部（钉住查询口径，防止有人把过滤条件优化掉）")
    void enabledDivisionsAreLevel1Only() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        stubHrPrimary();
        when(departmentMapper.selectList(any())).thenReturn(List.of(
                dept(10L, "北京分部", 1, true), dept(30L, "广州分部", 1, true)));

        List<DepartmentOptionVO> options = service.prefill(1L).getOptions();

        assertEquals(2, options.size());
        // 服务端下发的 deptName 直接就是分部名 —— 不再拼「公司 / 分部」前缀
        assertTrue(options.stream().allMatch(option -> option.getDeptName().endsWith("分部")));

        // 真正要守住的是 SQL 条件：只列 depth = 1 且 status = 1。
        // 若哪天有人把这两个 eq 删掉，下拉里就会出现二三级部门或停用分部 ——
        // 员工选了之后提交才被拒，而界面上看不出为什么。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Department>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departmentMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("depth"), "缺少 depth = 1 过滤：" + sql);
        assertTrue(sql.contains("status"), "缺少 status = 1 过滤：" + sql);
    }
}
