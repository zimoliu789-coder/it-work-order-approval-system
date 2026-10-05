package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.flow.NodeActivation;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自选审批人**范围校验**的性能改造（Wave 4 · W4-D）。
 *
 * <h2>本类锁定的那条线</h2>
 * <p>{@code chosenApprovers} 是自选审批人**唯一的真校验点**（请求体客户端完全可控，
 * 预览只是 UI 便利）。原实现拿范围池做包含判定：{@code choosablePool(rule).contains(id)}，
 * 于是一次提交就把「全部在职员工」拉进内存 —— 只为回答「这个 id 在不在池子里」。
 *
 * <p>W4-D 改为逐个 {@code isChoosable(rule, id)}（COUNT 判定）。本类同时钉住两件事：
 * <ul>
 *   <li><b>性能形态</b>：不再调用 {@code choosablePool}（防止有人"顺手改回去"）；</li>
 *   <li><b>判定语义不变</b>：范围外照旧抛 {@code FLOW_APPROVER_SELECTION_INVALID}，
 *       人数区间校验也照旧 —— 性能改造绝不能放宽准入。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FlowNodeMaterializerChoosableTest {

    private static final Long PICKED_ID = 11L;
    private static final Long OUTSIDE_ID = 99L;

    @Mock
    private ApproverRuleResolver approverRuleResolver;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private FlowNodeMaterializer materializer;

    @Test
    @DisplayName("范围校验走 isChoosable（COUNT），不再物化整个候选池")
    void chosenApprovers_usesIsChoosableInsteadOfMaterializingPool() {
        ApproverRule choose = chooseRule();
        when(approverRuleResolver.isChoosable(choose, PICKED_ID)).thenReturn(true);

        List<Long> picked = materializer.chosenApprovers(node("a1"), List.of(choose),
                Map.of("a1", List.of(PICKED_ID)));

        assertEquals(List.of(PICKED_ID), picked);
        verify(approverRuleResolver, never()).choosablePool(any());
    }

    @Test
    @DisplayName("所选人在范围外 → 仍然拒绝（性能改造不放宽准入）")
    void chosenApprovers_outOfScopeIsStillRejected() {
        ApproverRule choose = chooseRule();
        when(approverRuleResolver.isChoosable(choose, OUTSIDE_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                materializer.chosenApprovers(node("a1"), List.of(choose),
                        Map.of("a1", List.of(OUTSIDE_ID))));

        assertEquals(ErrorCode.FLOW_APPROVER_SELECTION_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("人数不足/超限仍在范围判定之前拦下（不产生任何候选查询）")
    void chosenApprovers_countViolationShortCircuits() {
        ApproverRule choose = chooseRule();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                materializer.chosenApprovers(node("a1"), List.of(choose), Map.of("a1", List.of())));

        assertEquals(ErrorCode.FLOW_APPROVER_SELECTION_INVALID, ex.getErrorCode());
        verify(approverRuleResolver, never()).isChoosable(any(), any());
    }

    @Test
    @DisplayName("节点上没有自选规则 → 空结果，且不做任何范围判定")
    void chosenApprovers_noChooseRuleReturnsEmpty() {
        ApproverRule roleRule = new ApproverRule();
        roleRule.setType(ApproverRuleType.ROLE.name());
        roleRule.setRoleCode("admin");

        assertEquals(List.of(), materializer.chosenApprovers(node("a1"), List.of(roleRule),
                Map.of("a1", List.of(PICKED_ID))));
        verify(approverRuleResolver, never()).isChoosable(any(), any());
        verify(approverRuleResolver, never()).choosablePool(any());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static ApproverRule chooseRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.APPLICANT_CHOOSE.name());
        rule.setScope("ALL");
        rule.setMinCount(1);
        rule.setMaxCount(2);
        return rule;
    }

    /** 命中路径上的审批节点（其余运行期属性与本组用例无关，一律给 null） */
    private static FlowPathResolver.ResolvedNode node(String key) {
        return new FlowPathResolver.ResolvedNode(key, "主管审批", SignType.ANY_SIGN,
                List.of(), 1, NodeActivation.ACTIVE, null, FlowNodeType.APPROVAL.name(),
                null, null, null);
    }
}
