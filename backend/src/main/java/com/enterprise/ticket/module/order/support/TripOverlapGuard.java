package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.trip.BusinessTripRules;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 出差申请的提交闸门（V53）
 *
 * <h2>它拦什么</h2>
 * 方案定稿（2026-10-16 用户批复）：同一员工不允许存在时间区间重叠的**生效出差** ——
 * 「生效」= 审批已通过（{@code COMPLETED}）；草稿 / 审批中 / 驳回 / 撤回 / 过期
 * 都<b>不算生效</b>，不参与判定。另有两条顺带的字段级闸门：
 * 起止日期必须可解析、开始不得晚于结束。
 *
 * <h2>为什么做成独立组件而不是塞进 OrderServiceImpl</h2>
 * <ul>
 *   <li>查询口径自成一体：「按申请人 + TRIP + COMPLETED 捞历史单 → 逐单读表单数据 →
 *       闭区间重叠判定」。塞进 OrderServiceImpl（3000+ 行）只会让它更难找到；</li>
 *   <li>区间判定的<b>唯一实现</b>在 {@link BusinessTripRules#overlaps} ——
 *       本类只负责查数与拦截，绝不再写一遍区间比较（三处各写一遍迟早漂移，
 *       表现是「提交时说不重叠、预填时说不生效」的自相矛盾组合）；</li>
 *   <li>将来「当前所在分部」预填（批次 2 第 ③ 块）复用同一套「生效出差」查询口径时，
 *       这里就是现成的参照实现。</li>
 * </ul>
 *
 * <h2>为什么按 order_type = 'TRIP' 捞单而不是按 applyTypeId</h2>
 * 出差单在提交时落 {@code OrderType.TRIP}（见 {@code submitCustomOrder} 的分流），
 * 类型值即查询依据 —— 不必经 apply_type 反查，也不受「类型行被重播种拿到新 id」影响。
 *
 * <h2>性能口径</h2>
 * 目标规模约 100 名员工、出差单量级很小（每员工同时最多一份生效出差），
 * 「申请人的全部 COMPLETED 出差单 + 逐单读表单」的查法远够用，不需要 JSON 列索引。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripOverlapGuard {

    private final OrderMapper orderMapper;
    private final OrderFormDataMapper orderFormDataMapper;

    /**
     * 校验「当前用户提交的这单出差」是否可建。
     *
     * @param applicantId 申请人（= 当前登录用户；出差单恒由本人提交）
     * @param formData    已通过表单结构校验的表单数据
     * @throws BusinessException FORM_DATA_INVALID：日期缺失 / 顺序颠倒 / 与生效出差重叠
     */
    public void assertSubmittable(Long applicantId, Map<String, Object> formData) {
        LocalDate start = BusinessTripRules.parseDate(formData.get(BusinessTripRules.FIELD_START_DATE));
        LocalDate end = BusinessTripRules.parseDate(formData.get(BusinessTripRules.FIELD_END_DATE));
        if (start == null || end == null) {
            // 表单 schema 已把两格设为必填，正常提交走不到这里；这是直调接口的纵深防御
            throw new BusinessException(ErrorCode.FORM_DATA_INVALID, "请填写出差开始与结束日期");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.FORM_DATA_INVALID, "出差开始日期不能晚于结束日期");
        }
        for (Order order : effectiveTripsOf(applicantId)) {
            Map<String, Object> data = readFormData(order.getId());
            LocalDate otherStart = BusinessTripRules.parseDate(data.get(BusinessTripRules.FIELD_START_DATE));
            LocalDate otherEnd = BusinessTripRules.parseDate(data.get(BusinessTripRules.FIELD_END_DATE));
            if (BusinessTripRules.overlaps(start, end, otherStart, otherEnd)) {
                // 报错里带单号：管理员与员工都能直接对上「挡我的是哪一单」
                throw new BusinessException(ErrorCode.FORM_DATA_INVALID,
                        "你已有一份时间重叠的生效出差（单号 " + order.getOrderNo()
                                + "），同一时间只能有一份生效出差申请");
            }
        }
    }

    /**
     * 申请人的全部「生效出差」单。
     *
     * <p>状态口径刻意只取 {@code COMPLETED}（审批已通过）：审批中的出差单不拦 ——
     * 定稿明确「审批中不算生效」；若把审批中也算进去，会出现「两份先后提交的出差
     * 互相把对方卡死在审批中」的死锁面。
     */
    private List<Order> effectiveTripsOf(Long applicantId) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplicantId, applicantId)
                .eq(Order::getOrderType, OrderType.TRIP.name())
                .eq(Order::getStatus, OrderStatus.COMPLETED.name()));
    }

    /**
     * 读历史单的表单数据（一行一单；读不出来按空数据处理 —— 日期缺失自然不参与重叠判定）。
     *
     * <p><b>为什么这里必须吞掉 {@link BusinessException}</b>：
     * {@link FormSchemaCodec#readData} 对损坏的 JSON 是<b>抛异常</b>的（其类注释明确
     * 「用错的数据去校验用户输入比拒绝服务更糟」）。但本方法处在<b>一个闸门</b>里：
     * 若把异常放出去，一条历史上被人工改坏的老单会让该员工<b>此后所有</b>出差提交
     * 都以「表单数据格式不合法」失败 —— 提交人完全无从下手，且报错指向的还是别人的单。
     * 闸门的正确降级是「这条历史单不参与判定」：宁可漏拦一次，不可永久误杀。
     */
    private Map<String, Object> readFormData(Long orderId) {
        OrderFormData form = orderFormDataMapper.selectOne(Wrappers.<OrderFormData>lambdaQuery()
                .eq(OrderFormData::getOrderId, orderId)
                .last("LIMIT 1"));
        if (form == null) {
            return Map.of();
        }
        try {
            return FormSchemaCodec.readData(form.getFormDataJson());
        } catch (BusinessException e) {
            log.warn("出差重叠校验：历史单表单数据无法解析，跳过该单参与判定 orderId={}原因={}",
                    orderId, e.getMessage());
            return Map.of();
        }
    }
}
