package com.enterprise.ticket.module.order.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 「全部工单」全局视图查询条件（ 工单管理 /  状态机）
 *
 * <p>仅 super_admin / admin 可用；默认按<b>提交时间倒序</b>。
 * 排序字段与方向均在服务层做白名单校验（禁止把前端值直接拼进 SQL）。
 */
@Data
public class OrderAllQuery {

    private long page = 1;

    private long size = 10;

    /** 工单状态，空表示不限 */
    private String status;

    /** 申请人姓名（模糊） */
    private String applicantKeyword;

    /** 设备名称或资产编号（模糊） */
    private String deviceKeyword;

    /** 借用类型：SHORT_TERM / LONG_TERM，空表示不限 */
    private String useType;

    /** 提交时间（含）起始日 */
    private LocalDate submitTimeFrom;

    /** 提交时间（含）截止日 */
    private LocalDate submitTimeTo;

    /** 部门（快照 department_id） */
    private Long departmentId;

    /** 排序字段白名单：createdAt（默认）/ expectedReturnDate / id */
    private String sortBy;

    /** 排序方向：asc / desc（默认 desc） */
    private String sortOrder;

    /**
     * 只看超时工单（「全部工单」筛选增加「已超时」，需求方  ）
     *
     * <p>超时是<b>标记位而非工单状态</b>（：{@code borrow_timeout} 仅描述「是否超时」，
     * 主状态仍是使用中），所以它不能复用 {@code status} 参数表达，必须独立成一个筛选条件。
     * {@code null} = 不限，{@code true} = 仅超时。
     */
    private Boolean borrowTimeout;

    /**
     * 只看发生过转交的工单（，需求方  ）
     *
     * <p>与 {@code borrowTimeout} 同理：「发生过转交」是历史事实而非工单状态，
     * 不能用 {@code status} 表达。{@code null} = 不限，{@code true} = 仅已转交过的。
     */
    private Boolean transferred;

    /**
     * 申请类型筛选。
     *
     * <p>只对自定义申请有意义：现有三种类型（借用 / 归还 / 维修 / 换货）的
     * {@code apply_type_id} 恒为 {@code NULL}，因此这个条件天然只会命中自定义工单。
     * 传 null 表示不限。
     */
    private Long applyTypeId;
}
