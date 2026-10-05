package com.enterprise.ticket.module.flowmonitor.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorFlowVO;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorNodeVO;
import com.enterprise.ticket.module.flowmonitor.service.FlowMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程监控接口（ · M7）
 *
 * <h2>权限：独立的 {@code flow_monitor:view}，而不是复用 {@code approval_flow:view}</h2>
 * <p>两者受众重叠但语义不同：{@code approval_flow:view} 是"能看流程**配置**"，
 * 本接口是"能看流程**运行数据**"。将来若出现"只看配置不看数据"的分工需求
 * （例如让流程设计者配流程、但不让他看到各节点实际耗时），复用同一个码就必须改代码；
 * 独立成码则只需调整授权。这也是项目一贯的做法：权限码对应实现，而不是对应页面。
 *
 * <p>直调接口必须被拦 —— 前端菜单隐藏只是体验，不构成权限。
 *
 * <h2>审计</h2>
 * <p>两个端点都记 {@code FLOW_MONITOR_VIEW}（NORMAL 级），与 {@code ReportController}
 * 同口径：分析类只读视图频次低（仅管理员），留痕有诊断价值，不会淹没日志。
 * 刻意<b>不</b>记 REQUEST 参数 —— 路径里的 {@code flowId} 是主键，不构成敏感信息，
 * 但 {@code recordArgs = false} 能免掉一次序列化开销，而这条路径可能被频繁翻页。
 */
@RestController
@RequestMapping("/api/flow-monitor")
@RequiredArgsConstructor
public class FlowMonitorController {

    private final FlowMonitorService flowMonitorService;

    /**
     * 模板维度汇总。
     *
     * <p>返回值恒包含「未归属」行（{@code flowId = 0}）排在最后，除非系统里一张走流程的单都没有。
     */
    @GetMapping("/flows")
    @PreAuthorize("@perm.has('flow_monitor:view')")
    @AuditLog(module = "FLOW_MONITOR", action = "FLOW_MONITOR_VIEW", risk = RiskLevel.NORMAL,
            description = "查看流程监控汇总", recordArgs = false)
    public ApiResponse<List<FlowMonitorFlowVO>> flows() {
        return ApiResponse.success(flowMonitorService.flows());
    }

    /**
     * 某模板（或未归属桶）的节点维度明细。
     *
     * @param flowId 模板 id；{@code 0} 表示未归属桶（见 {@link FlowMonitorService#UNATTRIBUTED_FLOW_ID}）
     */
    @GetMapping("/flows/{flowId}/nodes")
    @PreAuthorize("@perm.has('flow_monitor:view')")
    @AuditLog(module = "FLOW_MONITOR", action = "FLOW_MONITOR_VIEW", risk = RiskLevel.NORMAL,
            description = "查看流程节点耗时明细", recordArgs = false)
    public ApiResponse<List<FlowMonitorNodeVO>> nodes(@PathVariable Long flowId) {
        return ApiResponse.success(flowMonitorService.nodes(flowId));
    }
}
