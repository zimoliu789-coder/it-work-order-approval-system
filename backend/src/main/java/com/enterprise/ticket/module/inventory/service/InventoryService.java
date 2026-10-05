package com.enterprise.ticket.module.inventory.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.inventory.dto.InventoryCheckRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskCreateRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskQuery;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryItemVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryReportVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryScopeOptionsVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryTaskVO;

/**
 * 设备盘点服务（P2）
 *
 * <p>一个盘点任务的生命周期：**创建（快照范围内全部设备）→ 逐台扫码核对 → 完成 → 出报告**。
 *
 * <p>两条贯穿全流程的取向：
 * <ol>
 *   <li><b>明细在创建时快照，之后不随台账变化</b> —— 盘点的本质是「以某个时点为准核对」，
 *       边盘边取实时值会让报告自相矛盾（盘点期间有人借用、有人搬位置）。</li>
 *   <li><b>不自动改设备状态</b> —— 盘到「缺失」不自动置「已丢失」。
 *       缺失可能是放错地方/被临时拿走，直接改状态太激进；报告只把事实摆清楚，
 *       确认找不回再由管理员手动标记（与 P0 的 LOST 语义一致）。</li>
 * </ol>
 */
public interface InventoryService {

    /** 任务列表（分页，可按状态与关键词筛） */
    PageResult<InventoryTaskVO> page(InventoryTaskQuery query);

    /** 任务详情 */
    InventoryTaskVO detail(Long taskId);

    /** 任务明细（分页；{@code checkResult} 为空表示不限，传 null 可筛「尚未核对」需用特殊值） */
    PageResult<InventoryItemVO> items(Long taskId, String checkResult, long page, long size);

    /**
     * 创建任务：按范围解析设备并**一次性快照**明细。
     *
     * @return 新任务 id
     */
    Long create(InventoryTaskCreateRequest request);

    /**
     * 扫码核对一台（按资产编号在本任务内定位）。
     *
     * <p>落库后重算任务计数，并返回该台的最新明细（前端据此就地更新那一行，
     * 不必整页刷新 —— 手机上每盘一台都重拉一次列表会很卡）。
     */
    InventoryItemVO check(Long taskId, InventoryCheckRequest request);

    /** 完成盘点（允许存在未核对项，报告会显示未核对台数） */
    void complete(Long taskId, String remark);

    /** 取消盘点 */
    void cancel(Long taskId, String remark);

    /** 盘点报告（含缺失 / 位置不符清单） */
    InventoryReportVO report(Long taskId);

    /** 范围可选值（分类 + 存放位置） */
    InventoryScopeOptionsVO scopeOptions();
}
