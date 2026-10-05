package com.enterprise.ticket.module.flowmonitor.service.impl;

import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorFlowVO;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorNodeVO;
import com.enterprise.ticket.module.flowmonitor.mapper.FlowMonitorMapper;
import com.enterprise.ticket.module.flowmonitor.service.FlowMonitorService;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程监控服务层单测（Phase 16 Wave 2 · M7）
 *
 * <h2>这里锁定的是"读数字时的判断规则"，不是 SQL</h2>
 * <p>数值本身由 {@code FlowMonitorMapper} 的聚合 SQL 算，这里全部以桩数据喂入 ——
 * 因此本类回答的是「拿到聚合结果之后，展示层做的那些取舍对不对」：
 * <ul>
 *   <li><b>未归属桶</b>必须有明确标注且恒排最后，不能被当成"某个真模板"混在中间；</li>
 *   <li><b>瓶颈</b>的并列规则必须确定 —— 否则同一份数据两次打开可能给出不同的瓶颈，
 *       而管理员会据此去催人，选错了就是真实的工作量错配；</li>
 *   <li><b>null ≠ 0</b>：超时率无法判定（该节点没设时限）时不能显示成 0%，
 *       那会把"没人管"说成"很健康"。</li>
 * </ul>
 *
 * <p>最后一个用例锁定一个**容易漂移的耦合点**：加签标记与宽限秒数是由服务以参数传给 SQL 的
 * （SQL 里不再出现第二份字面量）。若有人改回在 SQL 里写死，这个用例会红。
 */
@ExtendWith(MockitoExtension.class)
class FlowMonitorServiceImplTest {

    @Mock
    private FlowMonitorMapper flowMonitorMapper;

    @InjectMocks
    private FlowMonitorServiceImpl service;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private FlowMonitorFlowVO flowRow(Long flowId, String flowName, String versionLabels,
                                      long orderCount, long approvedOrderCount, Double avgHours) {
        FlowMonitorFlowVO row = new FlowMonitorFlowVO();
        row.setFlowId(flowId);
        row.setFlowName(flowName);
        row.setVersionLabels(versionLabels);
        row.setOrderCount(orderCount);
        row.setApprovedOrderCount(approvedOrderCount);
        row.setAvgApprovalHours(avgHours);
        return row;
    }

    private FlowMonitorNodeVO node(Long flowId, String nodeKey, String nodeName,
                                   long sampleCount, Double avgHours,
                                   Double overdueRate, long withDeadlineCount) {
        FlowMonitorNodeVO node = new FlowMonitorNodeVO();
        node.setFlowId(flowId);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setSampleCount(sampleCount);
        node.setAvgHours(avgHours);
        node.setOverdueRate(overdueRate);
        node.setWithDeadlineCount(withDeadlineCount);
        return node;
    }

    /** 未归属桶的 flowId 必须是 0：前端要拿它拼 {@code /flows/0/nodes}，路径里放不下 null */
    @Test
    @DisplayName("未归属桶：flowId 归一为 0、标记 unattributed、使用固定文案")
    void unattributedBucketIsLabelled() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(null, null, null, 7, 5, 12.5)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of());

        List<FlowMonitorFlowVO> rows = service.flows();

        assertEquals(1, rows.size());
        FlowMonitorFlowVO row = rows.get(0);
        assertEquals(FlowMonitorService.UNATTRIBUTED_FLOW_ID, row.getFlowId());
        assertTrue(row.isUnattributed());
        assertEquals("未归属（无模板 / 模板已删除）", row.getFlowName());
        // 未归属行没有版本行，版本标签保持 null 而不是空串
        assertNull(row.getVersionLabels());
    }

    @Test
    @DisplayName("版本标签补 v 前缀，供界面直接显示「本行聚合了 v1, v2」")
    void versionLabelsGetVPrefix() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(9L, "采购流程", "1,2", 40, 38, 20.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of());

        List<FlowMonitorFlowVO> rows = service.flows();

        assertEquals("v1, v2", rows.get(0).getVersionLabels());
        assertFalse(rows.get(0).isUnattributed());
    }

    @Test
    @DisplayName("排序：真实模板按工单量降序，未归属桶恒排最后")
    void unattributedAlwaysLast() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(null, null, null, 100, 90, 5.0),
                flowRow(2L, "小流程", "1", 3, 3, 1.0),
                flowRow(1L, "大流程", "1", 30, 28, 8.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of());

        List<FlowMonitorFlowVO> rows = service.flows();

        assertEquals(List.of("大流程", "小流程"), List.of(
                rows.get(0).getFlowName(), rows.get(1).getFlowName()));
        assertTrue(rows.get(2).isUnattributed(),
                "未归属桶的工单量最大（100）也不能排到前面 —— 它不该打断模板之间的阅读顺序");
    }

    @Test
    @DisplayName("瓶颈（按耗时）：取平均耗时最大者，null 耗时的节点被跳过")
    void bottleneckByDurationPicksSlowest() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(1L, "采购流程", "1", 10, 10, 9.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n1", "主管审批", 10, 3.0, null, 0),
                        node(1L, "n2", "财务复核", 8, 11.5, null, 0),
                        // 理论上不会出现（有样本必有均值），但服务层不该假设 SQL 的完备性
                        node(1L, "n3", "归档确认", 1, null, null, 0)));

        FlowMonitorNodeVO bottleneck = service.flows().get(0).getBottleneckByDuration();

        assertNotNull(bottleneck);
        assertEquals("n2", bottleneck.getNodeKey());
    }

    @Test
    @DisplayName("瓶颈（按耗时）并列：样本多的胜出（更可信）")
    void bottleneckDurationTiesGoToMoreSamples() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(1L, "采购流程", "1", 10, 10, 9.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n1", "主管审批", 2, 6.0, null, 0),
                        node(1L, "n2", "财务复核", 9, 6.0, null, 0)));

        assertEquals("n2", service.flows().get(0).getBottleneckByDuration().getNodeKey());
    }

    @Test
    @DisplayName("瓶颈（按耗时）完全并列：取 nodeKey 字典序小者，保证两次查询结果一致")
    void bottleneckDurationTiesAreDeterministic() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(1L, "采购流程", "1", 10, 10, 9.0)));
        // 刻意把 n2 放在前面：若实现依赖"列表顺序"，这个用例就会挑到 n2
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n2", "财务复核", 5, 6.0, null, 0),
                        node(1L, "n1", "主管审批", 5, 6.0, null, 0)));

        assertEquals("n1", service.flows().get(0).getBottleneckByDuration().getNodeKey());
    }

    @Test
    @DisplayName("瓶颈（按超时率）：所有节点都没设时限时为 null —— 不把「无法判定」当成 0%")
    void bottleneckByOverdueIsNullWhenNothingHasDeadline() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(1L, "采购流程", "1", 10, 10, 9.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n1", "主管审批", 10, 3.0, null, 0),
                        node(1L, "n2", "财务复核", 10, 4.0, null, 0)));

        assertNull(service.flows().get(0).getBottleneckByOverdue(),
                "全 null 意味着「没有节点设了时限」，与「超时率都是 0」是相反的管理结论");
    }

    @Test
    @DisplayName("瓶颈（按超时率）：跳过无从判定的 null，在可判定者中取最高")
    void bottleneckByOverdueSkipsNulls() {
        when(flowMonitorMapper.flowSummary()).thenReturn(List.of(
                flowRow(1L, "采购流程", "1", 10, 10, 9.0)));
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n1", "主管审批", 10, 3.0, null, 0),
                        node(1L, "n2", "财务复核", 10, 4.0, 25.0, 8),
                        node(1L, "n3", "归档确认", 10, 5.0, 10.0, 10)));

        assertEquals("n2", service.flows().get(0).getBottleneckByOverdue().getNodeKey());
    }

    @Test
    @DisplayName("节点明细：只返回目标模板的行，并按平均耗时降序（最慢的排最前）")
    void nodesFilterAndSortByDuration() {
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(1L, "n1", "主管审批", 10, 3.0, null, 0),
                        node(2L, "x1", "别的模板", 5, 99.0, null, 0),
                        node(1L, "n2", "财务复核", 8, 11.5, null, 0)));

        List<FlowMonitorNodeVO> rows = service.nodes(1L);

        assertEquals(2, rows.size());
        assertEquals("n2", rows.get(0).getNodeKey());
        assertEquals("n1", rows.get(1).getNodeKey());
    }

    @Test
    @DisplayName("节点明细：flowId 传 null 等价于查未归属桶（0）")
    void nodesWithNullMeansUnattributed() {
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of(
                        node(null, "n1", "主管审批", 10, 3.0, null, 0),
                        node(1L, "n2", "财务复核", 8, 11.5, null, 0)));

        List<FlowMonitorNodeVO> rows = service.nodes(null);

        assertEquals(1, rows.size());
        assertEquals("n1", rows.get(0).getNodeKey());
    }

    /**
     * 加签标记与宽限秒数必须是**服务传参**，不能在 SQL 里另写一份字面量。
     *
     * <p>{@code ADDSIGN_KEY_MARKER} 同时被"生成加签 key""判断是否已加签（幂等位）""监控折回父节点"
     * 三处依赖。任何一处拼写漂移都不会报错，只会静默算错。因此这里钉住"服务确实把那个常量传下去了"。
     */
    @Test
    @DisplayName("聚合参数：加签标记取自共享常量、宽限 60 秒")
    void aggregationParametersComeFromSharedConstant() {
        when(flowMonitorMapper.nodeSummary(FlowActivationService.ADDSIGN_KEY_MARKER, 60))
                .thenReturn(List.of());

        service.nodes(FlowMonitorService.UNATTRIBUTED_FLOW_ID);

        verify(flowMonitorMapper).nodeSummary(eq(FlowActivationService.ADDSIGN_KEY_MARKER), eq(60));
        assertEquals("#addsign-", FlowActivationService.ADDSIGN_KEY_MARKER);
    }
}
