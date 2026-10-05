package com.enterprise.ticket.module.usage.dto;

import lombok.Data;

/**
 * 使用记录查询条件（需求方三波·第一波·）
 *
 * <p>两种视角共用同一个查询对象，而不是两个接口：
 * <ul>
 *   <li>{@code scope=DEVICE} + {@code targetId=deviceId} —— 某台设备被谁借过；</li>
 *   <li>{@code scope=USER} + {@code targetId=userId} —— 某个人借过哪些设备；</li>
 *   <li>不传 scope —— 全量浏览（「使用记录」列表页的默认形态）。</li>
 * </ul>
 * 之所以合而为一：三者的列集完全相同，差别只是 WHERE 条件。
 * 拆成三个接口会让「分页 / 排序 / 权限范围」三段逻辑各写三遍，一处改动忘同步就是行为漂移。
 */
@Data
public class UsageQuery {

    /** 视角：DEVICE / USER；为空表示不限 */
    private String scope;

    /** 目标主键：scope=DEVICE 时为设备 ID，scope=USER 时为员工 ID */
    private Long targetId;

    /** 关键词：工单号 / 设备名称 / 资产编号 / 借用人工号姓名 模糊匹配 */
    private String keyword;

    /** 工单状态精确筛选，取值见 {@code OrderStatus} */
    private String status;

    /** 借用类型：SHORT_TERM / LONG_TERM */
    private String useType;

    /** 起始时间（含），按工单提交时间过滤，格式 yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 结束时间（不含），按工单提交时间过滤 */
    private String endTime;

    private long page = 1L;

    private long size = 20L;
}
