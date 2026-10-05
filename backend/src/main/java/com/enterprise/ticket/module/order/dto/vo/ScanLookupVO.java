package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

/**
 * 扫码查询结果（P1 扫码借还）
 *
 * <p>一次请求回答「这个码是什么 + 我现在能对它做什么 + 该跳哪」。
 * 前端按 {@code action} 三选一：
 * <ul>
 *   <li>{@code BORROW} → 跳借用申请页并预选 {@code device}</li>
 *   <li>{@code RETURN} → 跳归还确认（{@code order} 即我的在借工单）</li>
 *   <li>{@code VIEW} → 跳工单详情（{@code order}）</li>
 *   <li>{@code UNAVAILABLE} / {@code NONE} → 就地展示 {@code reason}</li>
 * </ul>
 *
 * <p>⚠️ 字段为 {@code null} 时**不会出现在 JSON 里**（全局 Jackson {@code non_null} 配置）——
 * 前端一律按「可选字段」处理，不要断言 {@code "device":null}。
 */
@Data
public class ScanLookupVO {

    /** 是否识别到目标（设备或工单） */
    private Boolean matched;

    /** 原始扫码内容回显（便于用户核对手里那个码是不是本该扫的那个） */
    private String code;

    /** 命中类型：见 {@code ScanMatchType}（ASSET_NO / ORDER_NO / NONE） */
    private String matchType;

    /** 建议动作：见 {@code ScanAction}（BORROW / RETURN / VIEW / UNAVAILABLE / NONE） */
    private String action;

    /** 动作的中文说明，**直接展示给用户**（不在前端二次拼文案，避免两处口径漂移） */
    private String actionLabel;

    /** 不可操作 / 未识别的原因（action 为 UNAVAILABLE 或 NONE 时有值） */
    private String reason;

    /** 命中的设备（扫资产编号时；扫工单号且工单有设备时也会带上） */
    private DeviceOptionVO device;

    /** 命中的工单：归还场景为「我的在借工单」，查看场景为「被扫到的工单」 */
    private ScanOrderBriefVO order;
}
