package com.enterprise.ticket.module.export.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 导出条件
 *
 * <p>设计成「一个对象容纳多类筛选 + 一个 scope」而不是每类导出各一个 DTO：
 * 导出接口对前端是<b>一个入口</b>（{@code POST /api/exports}），条件由 {@code type} 决定
 * 取用哪些字段；拆成多个 DTO 只会让 Controller 多一层类型判断。
 *
 * <p><b>越权防护不在本对象里</b>：{@code scope} 只是前端意图，服务端一律按
 * 「当前登录人 + 角色」重新裁定（非 super_admin/admin 强制 MINE），
 * 因此前端改这个字段拿不到别人的数据。
 */
@Data
public class ExportQuery {

    /**
     * 工单导出范围：{@code MINE} 我的工单 / {@code ALL} 全部工单（默认）
     *
     * <p>「普通 user 仅可导出自己的工单」——服务端对非 super_admin/admin
     * 一律按 {@code MINE} 处理，忽略前端传值。
     *
     * <p>注意：本字段只服务于 {@code ORDER} 导出。使用记录有自己的维度概念
     * （{@code DEVICE} / {@code USER}），放在 {@link UsageFilter#getScope()} 里，
     * 两者语义不同、不能共用同一字段，否则「按设备」会被误写成导出范围。
     */
    private String scope;

    /** 设备台账筛选（仅 {@code DEVICE} 导出取用） */
    private DeviceFilter device;

    /** 工单筛选（仅 {@code ORDER} 导出取用） */
    private OrderFilter order;

    /** 使用记录筛选（仅 {@code USAGE} 导出取用） */
    private UsageFilter usage;

    /** 自定义表单数据筛选（仅 {@code CUSTOM_FORM} 导出取用） */
    private CustomFormFilter customForm;

    /** 操作日志筛选（P2）：仅 {@code ExportType.LOG} 使用 */
    private LogFilter log;

    /** 报表年份（仅报表导出取用；为空表示不限年份） */
    private Integer year;

    /** 报表月份 1-12（仅报表导出取用；为空表示整年） */
    private Integer month;

    /**
     * 设备台账筛选条件，字段与设备列表接口保持一致（导出内容 = 列表所见）
     */
    @Data
    public static class DeviceFilter {
        /** 设备名称 / 资产编号模糊关键词 */
        private String keyword;
        private Long primaryCategoryId;
        private Long secondaryCategoryId;
        /** 设备状态码（AVAILABLE/LOCKED/…），空表示不限 */
        private String status;
    }

    /**
     * 工单筛选条件，字段与「全部工单」列表接口保持一致（导出内容 = 列表所见）
     */
    @Data
    public static class OrderFilter {
        private String status;
        /**
         * 工单号模糊关键词（「我的工单」列表的搜索框；{@code MINE} 范围取用）
         *
         * <p>与 {@code deviceKeyword} / {@code applicantKeyword} 分开：我的工单接口只按
         * 工单号模糊，全局视图才按申请人 / 设备名模糊。字段各归其位，避免「传了但没生效」。
         */
        private String keyword;
        private String applicantKeyword;
        private String deviceKeyword;
        private String useType;
        private LocalDate submitTimeFrom;
        private LocalDate submitTimeTo;
        private Long departmentId;
        /** 仅超时工单（标记位，非状态） */
        private Boolean borrowTimeout;
        /** 仅发生过转交的工单 */
        private Boolean transferred;
        /**
         * 自定义申请类型 id。
         *
         * <p>该列仅自定义工单有值，因此这个条件天然只命中自定义申请 ——
         * 与 {@code OrderAllQuery.applyTypeId} 同一口径，保证「导出内容 = 列表所见」。
         */
        private Long applyTypeId;
    }

    /**
     * 使用记录筛选条件，字段与「使用记录」列表接口（{@code UsageQuery}）保持一致
     * （导出内容 = 列表所见）。
     *
     * <p>时间条件直接用字符串（{@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:mm:ss}）：
     * 与列表接口同一套解析逻辑在 {@code UsageServiceImpl} 里，导出不重复实现，
     * 避免「列表按整天算、导出按时刻算」这类口径漂移。
     */
    @Data
    public static class UsageFilter {
        /** 视角：DEVICE 按设备 / USER 按员工；为空表示全部 */
        private String scope;
        /** 目标主键：scope=DEVICE 时为设备 ID，scope=USER 时为员工 ID */
        private Long targetId;
        /** 关键词：工单号 / 设备名称 / 资产编号 / 借用人工号姓名 模糊匹配 */
        private String keyword;
        /** 工单状态精确筛选，取值见 {@code OrderStatus} */
        private String status;
        /** 借用类型：SHORT_TERM / LONG_TERM */
        private String useType;
        /** 起始时间（含），按工单提交时间过滤 */
        private String startTime;
        /** 结束时间（不含），按工单提交时间过滤 */
        private String endTime;
    }

    /**
     * 自定义表单数据筛选条件（ · M6）
     *
     * <p>只有四个字段，其中 {@link #applyTypeId} <b>必填</b>：一次导出一个申请类型。
     * 这不是为了简化实现，而是列爆炸的唯一有效闸门 —— 列 = 「本批工单引用过的
     * 表单版本的字段并集」，跨类型导出会让并集随租户里所有类型累加。
     * 缺省时服务端直接拒绝（{@code EXPORT_QUERY_INVALID}），
     * <b>不静默退化为「导出全部自定义工单」</b>。
     *
     * <p>其余三个字段与「全部工单」列表口径一致（{@code OrderAllQuery}），
     * 保证「导出内容 = 列表所见」这条既有铁律不被打破。
     */
    @Data
    public static class CustomFormFilter {

        /**
         * 申请类型 id（{@code apply_type.id}）—— <b>必填</b>
         *
         * <p>注意与 {@code OrderFilter.applyTypeId} 的区别：那个是「可选筛选」，
         * 为空表示不限类型；这里是「导出范围的唯一定义」，为空即无法确定列集合。
         */
        private Long applyTypeId;

        /** 工单状态码，空表示不限 */
        private String status;

        /** 提交时间起（含），按 {@code borrow_order.created_at} 的日期过滤 */
        private LocalDate submitTimeFrom;

        /** 提交时间止（含当天），按 {@code borrow_order.created_at} 的日期过滤 */
        private LocalDate submitTimeTo;
    }

    /**
     * 操作日志筛选（P2）
     *
     * <p>字段与 {@code GET /api/logs} 列表接口逐个对应，装载时由
     * {@code OperationLogQuerySupport#wrapper} 统一构造条件 ——
     * 列表与导出共用同一处，避免出现「导出比列表多几行」这类无法解释的差异
     * （用户不会想到是两处条件写得不一样，只会怀疑数据本身）。
     */
    @Data
    public static class LogFilter {

        /** 模块编码（等值，可空） */
        private String module;

        /** 动作编码（等值，可空） */
        private String action;

        /** 结果：SUCCESS / FAILED（等值，可空） */
        private String result;

        /** 操作人（模糊匹配，可空） */
        private String operatorName;

        /**
         * 起始时间（含），{@code yyyy-MM-dd HH:mm:ss}
         *
         * <p>与 {@code UsageFilter} 同样用字符串而不是 {@code LocalDateTime}：
         * 导出请求体是 JSON，前端传的就是这个格式；改成时间类型会要求 ISO 8601，
         * 与前端既有实现不符，且解析失败时报的是反序列化错误、不易定位。
         */
        private String startTime;

        /** 结束时间（含），{@code yyyy-MM-dd HH:mm:ss} */
        private String endTime;
    }
}
