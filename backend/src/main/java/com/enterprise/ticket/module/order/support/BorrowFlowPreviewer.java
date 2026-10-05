package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.BorrowFieldCatalog;
import com.enterprise.ticket.common.flow.BorrowFlowCatalog;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.order.dto.vo.BorrowFlowPreviewVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 借用单审批路径预览（，承接原 {@code BizGroupServiceImpl#previewBorrowFlowForCurrentUser}）。
 *
 * <h2>它回答的问题</h2>
 * <p>申请人还没提交时，界面要先把「这笔单会经过哪几级审批」画出来。答案有两个来源：
 * 申请人所在**部门**绑定的流程版本（存量部门的既有配置），或**系统预置流程**
 * （{@link BorrowFlowCatalog}， 起未绑流程的部门统一走它）。
 * 两条来源都不再返回空路径，因此预览块始终能画出真实链路。
 *
 * <h2>为什么它必须是唯一实现</h2>
 * <p>「预览走的路径」与「提交时真正物化的路径」若各写一份，迟早漂移：预览说走三级、
 * 提交却生成两级。因此这里与提交侧**共用** {@link FlowPathResolver}、
 * {@link ApproverRuleResolver} 与 {@link BorrowFlowCatalog} 三个内核 ——
 * 只喂不同的输入，不写第二套判定。
 *
 * <h2>预览失败为什么不抛错</h2>
 * <p>预览是「提交前的提示」，不是提交本身。部门绑定的流程被删/被下线时，
 * 让预览直接 500 会让申请人**连申请页都打不开**。因此这里降级为
 * {@code bound=false}（前端不展示路径块），并记 warn 日志留痕。
 * 真正的拦截发生在提交时（{@code OrderServiceImpl#create}），那里才该硬失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BorrowFlowPreviewer {

    private final DepartmentMapper departmentMapper;
    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final ApprovalFlowService approvalFlowService;
    private final ApproverRuleResolver approverRuleResolver;
    private final SystemConfigService systemConfigService;

    /**
     * 预览当前登录用户的借用审批路径。
     *
     * @param useType            借用类型枚举名（SHORT_TERM / LONG_TERM）
     * @param expectedReturnDate 期望归还日期；长期领用为 null
     * @param deviceId           申请的设备 id；可为 null（未选设备时也能预览）
     */
    public BorrowFlowPreviewVO preview(String useType, LocalDate expectedReturnDate, Long deviceId) {
        BorrowFlowPreviewVO vo = new BorrowFlowPreviewVO();
        vo.setBound(false);

        Long userId = SecurityUtils.getCurrentUserId();
        User applicant = userId == null ? null : userMapper.selectById(userId);
        if (applicant == null || applicant.getDepartmentId() == null) {
            // 未登录 / 无部门：不是错误，只是「还没到能算路径的阶段」
            return vo;
        }
        Department department = departmentMapper.selectById(applicant.getDepartmentId());
        if (department == null) {
            return vo;
        }

        Map<String, Object> formData = formData(useType, expectedReturnDate, deviceId);
        FlowDefinition definition;
        String label;
        if (department.getApprovalFlowVersionId() != null) {
            try {
                definition = approvalFlowService.requirePublishedDefinition(department.getApprovalFlowVersionId());
                label = approvalFlowService.describeVersion(department.getApprovalFlowVersionId());
            } catch (RuntimeException e) {
                // 部门绑定的版本被删/被下线：降级为「不展示路径块」，并记 warn 留痕。
                // 真正的拦截发生在提交时（OrderServiceImpl#create），那里才该硬失败。
                log.warn("部门「{}」绑定的流程版本 {} 不可用，借用路径预览降级为不展示：{}",
                        department.getDeptName(), department.getApprovalFlowVersionId(), e.getMessage());
                return vo;
            }
        } else {
            // 未绑定自定义流程 → 走**系统预置流程**（，）。
            // 与提交侧共用 BorrowFlowCatalog，只有金额阈值是从系统参数现读的同一份值 ——
            // 因此「预览说走三级、提交却生成四级」这种事在结构上不可能发生。
            definition = BorrowFlowCatalog.preset(systemConfigService.approvalDeviceAmountThreshold());
            label = BorrowFlowCatalog.PRESET_FLOW_NAME;
        }

        FormSchema schema = BorrowFieldCatalog.schema();
        FlowPathResolver.Result path = FlowPathResolver.resolve(definition, schema, formData);
        vo.setBound(true);
        vo.setFlowVersionLabel(label);
        vo.setNodes(path.onPathNodes().stream().map(node -> toNode(node, applicant, formData)).toList());
        vo.setSkippedNodes(path.skippedNodes().stream().map(node -> toNode(node, applicant, formData)).toList());
        return vo;
    }

    /** 把内核节点转成 API 节点，顺带解析出审批人姓名 */
    private BorrowFlowPreviewVO.PreviewNode toNode(FlowPathResolver.ResolvedNode resolved,
                                                   User applicant, Map<String, Object> formData) {
        BorrowFlowPreviewVO.PreviewNode node = new BorrowFlowPreviewVO.PreviewNode();
        node.setNodeKey(resolved.nodeKey());
        node.setNodeName(resolved.nodeName());
        node.setNodeType(resolved.nodeType());
        node.setConditionDesc(resolved.conditionDesc());
        node.setTimeLimitHours(resolved.timeLimitHours());
        List<Long> approverIds = approverRuleResolver.resolve(resolved.approverRules(), applicant, formData);
        node.setApproverNames(approverIds.stream().map(this::displayNameOf).toList());
        return node;
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return "用户#" + userId;
        }
        return user.getDisplayName() == null ? user.getUsername() : user.getDisplayName();
    }

    /** 借用提交上下文 → 条件求值器的扁平 map（与提交侧同一套 key） */
    private Map<String, Object> formData(String useType, LocalDate expectedReturnDate, Long deviceId) {
        Integer expectedDays = BorrowFieldCatalog.expectedDays(LocalDate.now(), expectedReturnDate);
        Long categoryId = null;
        BigDecimal deviceAmount = null;
        if (deviceId != null) {
            Device device = deviceMapper.selectById(deviceId);
            if (device != null) {
                categoryId = device.getPrimaryCategoryId();
                // 设备金额决定走三级还是四级：不取它，预览永远只能画出一条不含
                // 「上级部门主管」的路径，与提交后的实际路径不符。
                deviceAmount = device.getAmount();
            }
        }
        return BorrowFieldCatalog.formData(useType, expectedDays, categoryId, null, deviceAmount);
    }
}
