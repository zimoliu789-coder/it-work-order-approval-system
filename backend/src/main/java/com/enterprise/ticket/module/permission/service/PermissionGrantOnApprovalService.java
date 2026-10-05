package com.enterprise.ticket.module.permission.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.module.message.service.UserNotificationService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 「权限申请审批通过 ⇒ 自动开通」的落点。
 *
 * <h2>为什么挂在「工单完成」这一个点上</h2>
 * 自定义工单「审批通过即流程终点」（{@code OrderServiceImpl#completeCustomOrder}）。
 * 把授权挂在同一个事务里 ⇒ 「工单完成」与「权限开通」要么都成、要么都不成；
 * 若放异步任务，会出现「工单显示已完成、权限却没开」的中断态 ——
 * 而申请人只会看到「批了但用不了」，无从判断该找谁。
 *
 * <h2>驳回路径为什么不需要写代码</h2>
 * 驳回不会走到本方法（{@code completeCustomOrder} 只在全部审批通过时调用）。
 * 「拒绝就不开通」因此是**结构上的保证**，而不是「调用后再回滚」——
 * 后者才有可能出错。
 *
 * <h2>三道服务端重算（都不信表单里的值）</h2>
 * <ol>
 *   <li>只认**表单里有 {@code permissionCodes} 字段**的工单 —— 不靠申请类型的名字；</li>
 *   <li>逐个码再查一次 {@code permission_apply_policy}：**不开放申请的码永不授予**。
 *       表单里的选项是提交那一刻生成的，管理员之后可能已经把某个码关掉了；</li>
 *   <li>授予动作本身幂等（{@code UserPermissionService#grant}），重复审批不会插重复行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionGrantOnApprovalService {

    /** 权限申请表单里承载「申请开通的权限」的字段 key（ 预置，见 PresetApplyCatalog） */
    public static final String FIELD_PERMISSION_CODES = "permissionCodes";

    private final OrderFormDataMapper orderFormDataMapper;
    private final PermissionApplyPolicyService policyService;
    private final UserPermissionService userPermissionService;
    private final UserNotificationService userNotificationService;

    /**
     * 若该工单是一次权限申请，则把申请的权限授予申请人。
     *
     * <p><b>必须在调用方的事务里执行</b>（不加 {@code @Transactional} 是有意的：
     * 加了会开一个新事务，反而破坏「与工单完成同生共死」这条约束）。
     *
     * @return 实际新开通的权限数（0 = 不是权限申请，或全部已开通）
     */
    public int grantIfPermissionApply(Order order) {
        if (order == null || order.getId() == null || order.getApplicantId() == null) {
            return 0;
        }
        List<String> applied = appliedCodes(order.getId());
        if (applied.isEmpty()) {
            // 不是权限申请（普通自定义申请没有这个字段）—— 正常路径，不打日志噪音
            return 0;
        }

        int granted = 0;
        List<String> skipped = new ArrayList<>();
        for (String code : applied) {
            if (!policyService.isApplicable(code)) {
                // 提交之后管理员把它关掉了 ⇒ 不授予，但必须留痕（否则申请人只会看到「批了却没用」）
                skipped.add(code);
                continue;
            }
            granted += userPermissionService.grant(order.getApplicantId(), code,
                    UserPermissionService.SOURCE_APPLY, order.getId(), null);
        }

        notifyApplicant(order, applied, granted, skipped);
        log.info("[权限开通] 工单 {} 审批通过：申请 {} 项，新开通 {} 项，跳过 {} 项",
                order.getOrderNo(), applied.size(), granted, skipped.size());
        return granted;
    }

    /** 读取工单表单里的 permissionCodes（非权限申请返回空列表） */
    private List<String> appliedCodes(Long orderId) {
        OrderFormData row = orderFormDataMapper.selectOne(Wrappers.<OrderFormData>lambdaQuery()
                .eq(OrderFormData::getOrderId, orderId)
                .last("LIMIT 1"));
        if (row == null || row.getFormDataJson() == null) {
            return List.of();
        }
        return readAppliedCodes(FormSchemaCodec.readData(row.getFormDataJson()));
    }

    /**
     * 从**表单数据**里读出申请开通的权限码。
     *
     * <p>提交时（尚未落库，只有 formData）与审批通过时（从 order_form_data 读回）
     * 都需要这个解析，因此抽成公开方法 —— 两处各写一遍必然漂移，
     * 而漂移的表现是「提交时判了高危、审批通过时判成普通」，超管那一级就白加了。
     *
     * <p>没有 {@code permissionCodes} 字段 ⇒ 不是权限申请，返回空列表
     * （刻意**不**靠申请类型的名字判断：类型名是可改的配置，字段才是结构）。
     */
    public List<String> readAppliedCodes(Map<String, Object> data) {
        if (data == null || !data.containsKey(FIELD_PERMISSION_CODES)) {
            return List.of();
        }
        Object raw = data.get(FIELD_PERMISSION_CODES);
        List<String> codes = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    codes.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            // 单选退化成字符串时也要能读出来（历史数据 / 手工改过 schema）
            codes.add(String.valueOf(raw).trim());
        }
        return codes;
    }

    /**
     * 通知申请人（**站内消息 + 邮件**，P2 修复）。
     *
     * <p>文案里**必须区分「已开通」与「被跳过」**：只说「已通过」而权限没开，
     * 申请人会反复重试直到找管理员 —— 而管理员也不知情。把跳过的码列出来，
     * 双方都能立刻知道该找谁。
     *
     * <p>提交时已对不可申请的码做了服务端拒绝（见 {@code OrderServiceImpl#assertPermissionCodesApplicable}），
     * 因此走到这里的「跳过」只可能来自「提交之后管理员关闭了该权限」这一种情况；
     * 邮件通道保证用户在无人登录时也能收到结果。
     */
    private void notifyApplicant(Order order, List<String> applied, int granted, List<String> skipped) {
        StringBuilder body = new StringBuilder();
        body.append("你提交的系统权限申请（").append(order.getOrderNo()).append("）已审批通过。\n");
        body.append("申请权限：").append(String.join("、", applied)).append('\n');
        body.append("已开通：").append(granted).append(" 项\n");
        if (!skipped.isEmpty()) {
            body.append("未开通：").append(String.join("、", skipped))
                    .append("（该权限已停止开放申请，请联系管理员）\n");
        }
        body.append("\n权限已立即生效，无需重新登录。");
        userNotificationService.notify(order.getApplicantId(), MessageType.APPROVAL_PASSED,
                "权限申请已通过", body.toString(), order.getId());
    }
}
