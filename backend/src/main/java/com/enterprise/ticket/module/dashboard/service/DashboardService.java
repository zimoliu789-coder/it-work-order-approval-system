package com.enterprise.ticket.module.dashboard.service;

import com.enterprise.ticket.module.dashboard.dto.DashboardQuery;
import com.enterprise.ticket.module.dashboard.dto.vo.DashboardSummaryVO;

/**
 * 统计仪表盘（ · M5）
 *
 * <p>纯只读聚合服务：不写任何数据、不发消息、不改状态。因此与 {@code FlowMonitorService} 一样
 * <b>只有 {@code view} 权限码，没有 {@code manage}</b> —— 页面上不存在可维护的东西。
 */
public interface DashboardService {

    /**
     * 取区间内的仪表盘聚合结果。
     *
     * @param query 查询条件；为 {@code null} 时按默认区间（近 30 天）+ 默认粒度（按天）。
     *              粒度非法或区间倒置会抛业务异常，<b>不做静默纠正</b>——
     *              「用户以为选了 A、系统按 B 执行」是比报错更难排查的问题。
     * @return 聚合结果；任何一项无数据都返回空集合或 {@code null}（见 {@link DashboardSummaryVO}），
     *         不返回 {@code null} 本身 —— 这是个视图，空视图是合法状态
     */
    DashboardSummaryVO summary(DashboardQuery query);
}
