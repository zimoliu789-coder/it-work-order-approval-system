package com.enterprise.ticket.module.flowmonitor.service;

import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorFlowVO;
import com.enterprise.ticket.module.flowmonitor.dto.vo.FlowMonitorNodeVO;

import java.util.List;

/**
 * 流程监控（ · M7）
 *
 * <p>纯只读分析服务：不做任何写入、不发消息、不改工单状态。因此没有 {@code manage} 权限码，
 * 也不需要审计每一次浏览（报表类只读端点在本项目的口径是 NORMAL 级留痕，见各 Controller）。
 */
public interface FlowMonitorService {

    /**
     * 「未归属」桶的伪 flowId。
     *
     * <p>含义：这一组工单**归不到任何现存流程模板** —— 走分组固定审批人表 / 借用单内置流程的单
     * （本就没有模板）、本期上线前的存量单（明确不回填归属）、以及模板已被物理删除的历史单。
     *
     * <p>取值 0 是安全的哨兵：MySQL 自增主键不会产出 0。前端拿它去调
     * {@code /api/flow-monitor/flows/0/nodes} 即可下钻，路径里不需要为"空"设计特殊分支。
     */
    long UNATTRIBUTED_FLOW_ID = 0L;

    /**
     * 模板维度汇总（含未归属桶，恒排在最后一行）。
     *
     * <p>真实模板按工单量降序，便于先看跑得最多的流程。
     */
    List<FlowMonitorFlowVO> flows();

    /**
     * 某模板（或未归属桶）的节点维度明细，按平均耗时降序 —— 最慢的节点排在最前，
     * 这正是打开这一页的人想第一眼看到的东西。
     *
     * @param flowId 模板 id；{@code null} 或 {@link #UNATTRIBUTED_FLOW_ID} 表示未归属桶。
     *               模板不存在或无任何已决策节点时返回空列表（不是错误：这是个分析视图，
     *               "没有数据"是正常状态，报 404 只会让前端多写一段没意义的错误处理）
     */
    List<FlowMonitorNodeVO> nodes(Long flowId);
}
