package com.enterprise.ticket.common.constant;

/**
 * 导出类型（ Excel 导入导出与统计报表）
 *
 * <p>与 {@code export_tasks.export_type} 列的取值一一对应。分两大类：
 * <ul>
 *   <li><b>记录导出</b>（{@link #DEVICE} / {@link #ORDER} / {@link #USAGE} / {@link #CUSTOM_FORM}）——导出的是一行行业务记录，
 *       行数可能很大，因此受 {@code app.export.async-threshold}（：10000 条）
 *       约束：超过阈值改为异步生成 + 站内消息通知下载；</li>
 *   <li><b>报表导出</b>（{@code REPORT_*}）——导出的是聚合后的统计结果，
 *       行数由「设备数 / 分类数 / 月份数」决定，量级天然很小，一律同步返回。</li>
 * </ul>
 *
 * <p>{@link #filePrefixOf()} 与 {@link #sheetNameOf()} 集中在此，避免各导出 writer 各写一份文案
 * 后出现「同一个导出在不同入口叫不同名字」。
 */
public enum ExportType {

    /** 设备台账导出（：super_admin/admin 可导出全部设备，含分类、资产编号、状态、报废标记） */
    DEVICE("设备台账", "设备台账", "设备台账"),

    /** 工单记录导出（：按筛选条件导出工单列表；普通 user 仅可导出自己的工单） */
    ORDER("工单记录", "工单记录", "工单记录"),

    /**
     * 使用记录导出（「形成完整使用记录」）
     *
     * <p>字段与「使用记录」列表页完全一致（谁 / 借什么 / 何时→何时 + 顺延与超时标记）。
     * 属于<b>记录导出</b>：行数可能很大，同样受异步阈值约束 ——
     * 超过阈值转后台生成，完成后站内消息通知下载。
     *
     * <p>数据范围不靠参数收口：装载时复用 {@code UsageService#page}，
     * 由它按当前角色的 {@code data_scope} 自动收窄（SELF / GROUP / ALL），
     * 因此即便改 URL 参数也拿不到范围外的记录（与列表接口同一道防线）。
     */
    USAGE("使用记录", "使用记录", "使用记录"),

    /**
     * 自定义表单数据导出（ · M6）
     *
     * <p>导出某个申请类型下全部自定义工单的<b>表单填写内容</b>（一行一单，一列一字段）。
     * 与 {@link #ORDER} 的分工：后者导出的是「工单走到哪一步」（状态 / 执行 / 归还），
     * 本类导出的是「用户填了什么」，两者互补而不重叠。
     *
     * <p><b>为什么归入「记录导出」</b>：自定义工单会随时间持续增长，
     * 行数量级与工单记录同级，因此同样受 {@code export_async_threshold} 约束 ——
     * 超过阈值转后台生成 + 站内消息通知（{@link #isReport()} 对它返回 false）。
     *
     * <p><b>为什么必须指定申请类型</b>：列 = 「本批工单引用过的表单版本的字段并集」，
     * 跨类型会把所有类型的字段累加成一宽表。由 {@code ExportQuery.CustomFormFilter}
     * 的 {@code applyTypeId} 强制必填，缺省直接拒绝（{@code EXPORT_QUERY_INVALID}）。
     *
     * <p><b>为什么仅管理员</b>：导出内容 = 某个类型下<b>所有人</b>提交的表单数据，
     * 与「全部工单」同一数据边界，因此权限口径也一致（见 {@code ExportServiceImpl#assertExportable}）。
     */
    CUSTOM_FORM("自定义表单数据", "自定义表单数据", "表单数据"),

    /**
     * 操作日志导出（P2）
     *
     * <p>列与「操作日志」列表页对齐，并**多带一列技术详情** —— 列表页要点开「查看详情」
     * 才看得到原始详情，而导出是给审计与排障用的，摘要与详情都要。
     *
     * <p><b>权限比列表更严</b>： 原文是「只有 super_admin 可以查看日志」，
     * 而列表页的 {@code log:view} 实际上已授权给 admin（V34 补的）。
     * 导出会把日志整份<b>带出系统</b>，风险高于在线查看，因此本类型要求<b>仅超管</b>
     * （见 {@code ExportServiceImpl#assertExportable}）——
     * 「能在线看」与「能整体带走」本就是两种风险级别。
     *
     * <p>属记录类导出：行数可能很大（本机演示库已有 1 万余条），同样受异步阈值约束 ——
     * 超阈值转后台生成，完成后站内消息通知下载。
     */
    LOG("操作日志", "操作日志", "操作日志"),

    /** 统计报表 · 设备借用频次 */
    REPORT_DEVICE_USAGE("借用频次报表", "设备借用频次", "借用频次统计"),

    /** 统计报表 · 工单审批时效 */
    REPORT_APPROVAL_EFFICIENCY("审批时效报表", "工单审批时效", "审批时效统计"),

    /** 统计报表 · 设备故障 */
    REPORT_DEVICE_FAULT("故障统计报表", "设备故障统计", "故障统计");

    /** 中文名（用于审计日志详情、消息标题） */
    private final String label;

    /** 下载文件名前缀（最终形如「设备台账_20260919_1030.xlsx」） */
    private final String filePrefix;

    /** Excel 工作表名（Excel 限制 31 字符，本枚举值均远小于该限制） */
    private final String sheetName;

    ExportType(String label, String filePrefix, String sheetName) {
        this.label = label;
        this.filePrefix = filePrefix;
        this.sheetName = sheetName;
    }

    public String getLabel() {
        return label;
    }

    public String filePrefixOf() {
        return filePrefix;
    }

    public String sheetNameOf() {
        return sheetName;
    }

    /** 是否为统计报表类导出（报表行数天然很小，不需要异步分支） */
    public boolean isReport() {
        return name().startsWith("REPORT_");
    }

    public static ExportType of(String value) {
        if (value == null) {
            return null;
        }
        for (ExportType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ExportType type = of(value);
        return type == null ? value : type.getLabel();
    }
}
