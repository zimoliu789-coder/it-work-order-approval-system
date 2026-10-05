package com.enterprise.ticket.module.export.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.FormFieldType.ValueKind;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.Column;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.VersionSchema;
import com.enterprise.ticket.module.export.excel.CustomFormExportExcel;
import com.enterprise.ticket.module.export.excel.CustomFormExportExcel.AttachmentRow;
import com.enterprise.ticket.module.export.excel.CustomFormExportExcel.FormOrderRow;
import com.enterprise.ticket.module.export.excel.DeviceExportExcel;
import com.enterprise.ticket.module.export.excel.LogExportExcel;
import com.enterprise.ticket.module.export.excel.OrderExportExcel;
import com.enterprise.ticket.module.export.excel.ReportExcelBuilder;
import com.enterprise.ticket.module.export.excel.UsageExportExcel;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.report.dto.ReportQuery;
import com.enterprise.ticket.module.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导出内容装配（ / ）
 *
 * <p>把「导出类型 → 工作表」的映射集中在一处，<b>同步路径与异步路径共用同一个方法</b>：
 * 如果同步与异步各写一份，迟早出现「同一个导出，同步出来的和异步出来的列不一样」。
 *
 * <p>本类只做「装载 + 拼装」，不碰文件、不碰数据库事务、不碰消息；
 * 写盘与通知在 {@code ExportTaskRunner} / {@code ExportServiceImpl} 里。
 *
 * <p>记录类导出（设备 / 工单 / 使用记录）各自复用对应的<b>列表 Service</b>取数，
 * 因此「页面上的列表」与「导出文件里的行」不会出现两个口径；
 * 报表类导出（{@code REPORT_*}）复用 {@link ReportService} 的同一份聚合结果，
 * 因此「页面上的报表」与「导出文件里的报表」也不会出现两个口径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportSheetBuilder {

    private final ExportDataLoader loader;
    private final ExportStorage storage;
    private final ReportService reportService;

    /**
     * 记录类导出的行数预检（用于决定同步/异步）
     *
     * <p>报表类导出返回 0：报表的行数由「设备数 / 分类数 / 月份数」决定，量级天然很小，
     * 且报表导出一律走同步（见 {@link ExportType#isReport()}），因此不需要计数，
     * 也不需要做行数上限校验 —— 对报表做「先计数再决定」纯属多余。
     *
     * @throws BusinessException 记录类导出超出单次行数上限（{@code EXPORT_ROW_LIMIT_EXCEEDED}）
     */
    public long count(ExportType type, ExportQuery query, boolean allScope) {
        if (type.isReport()) {
            return 0L;
        }
        long total = switch (type) {
            case DEVICE -> loader.countDevices(query.getDevice());
            case ORDER -> loader.countOrders(query, allScope);
            case USAGE -> loader.countUsages(query.getUsage());
            // 注意：本 switch 有 default，因此漏加分支**不会编译报错**，只会在运行时
            // 抛「导出类型与数据源不匹配」。新增记录类导出时必须同时改这里与 build()。
            case CUSTOM_FORM -> loader.countCustomFormOrders(requireCustomFormFilter(query));
            case LOG -> loader.countLogs(query.getLog());
            default -> throw new BusinessException(ErrorCode.EXPORT_TYPE_INVALID,
                    "导出类型与数据源不匹配：" + type.name());
        };
        int maxRows = storage.maxRows();
        if (total > maxRows) {
            throw new BusinessException(ErrorCode.EXPORT_ROW_LIMIT_EXCEEDED,
                    "本次导出 " + total + " 行，超过单次上限 " + maxRows + " 行，请先按时间或分类缩小范围");
        }
        return total;
    }

    /** 装配工作表（按导出类型分派） */
    public List<SheetSpec> build(ExportType type, ExportQuery query, boolean allScope) {
        return switch (type) {
            case DEVICE -> List.of(buildDevices(query));
            case ORDER -> buildOrders(query, allScope);
            case USAGE -> List.of(UsageExportExcel.build(
                    loader.loadUsages(query.getUsage(), storage.maxRows())));
            // 自定义表单导出不使用 allScope：数据范围由 pageAllOrders 内部的管理员判定收口
            // （与「全部工单」列表同一道防线），再传一次只会多出一个可能与它不一致的输入
            case CUSTOM_FORM -> buildCustomForm(query);
            case LOG -> List.of(LogExportExcel.build(
                    loader.loadLogs(query.getLog(), storage.maxRows())));
            case REPORT_DEVICE_USAGE -> ReportExcelBuilder.usage(
                    reportService.deviceUsage(reportQuery(query)));
            case REPORT_APPROVAL_EFFICIENCY -> ReportExcelBuilder.approvalEfficiency(
                    reportService.approvalEfficiency(reportQuery(query)));
            case REPORT_DEVICE_FAULT -> ReportExcelBuilder.deviceFault(
                    reportService.deviceFault(reportQuery(query)));
        };
    }

    /**
     * 任务的「数据行数」取值
     *
     * <p>记录类用计数结果（不含表头）；报表类取<b>首个工作表</b>的行数（汇总表），
     * 用于在导出记录与完成消息里给用户一个「导出了多少条」的直观数字。
     */
    public int totalRowsOf(ExportType type, long counted, List<SheetSpec> sheets) {
        if (!type.isReport()) {
            return (int) Math.min(counted, Integer.MAX_VALUE);
        }
        if (sheets == null || sheets.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = sheets.get(0).rows();
        return rows == null ? 0 : rows.size();
    }

    // ------------------------------------------------------------------
    // 设备台账
    // ------------------------------------------------------------------

    private SheetSpec buildDevices(ExportQuery query) {
        List<DeviceVO> devices = loader.loadDevices(query.getDevice(), storage.maxRows());
        return DeviceExportExcel.build(devices);
    }

    // ------------------------------------------------------------------
    // 工单记录
    // ------------------------------------------------------------------

    /**
     * 工单导出产出两个工作表：工单记录 + 审批记录（ 明确要求包含审批记录）
     *
     * <p>审批记录只取<b>本次导出的这批工单</b>的节点，用 {@code IN} 一次查回，
     * 避免逐单查询造成 N+1。
     */
    private List<SheetSpec> buildOrders(ExportQuery query, boolean allScope) {
        List<OrderVO> orders = loader.loadOrders(query, allScope, storage.maxRows());

        Map<Long, String> orderNoById = new LinkedHashMap<>();
        Set<Long> orderIds = new LinkedHashSet<>();
        for (OrderVO order : orders) {
            orderIds.add(order.getId());
            orderNoById.put(order.getId(), order.getOrderNo());
        }

        List<OrderApprovalNode> nodes = loader.loadApprovalNodes(orderIds);
        Set<Long> approverIds = new LinkedHashSet<>();
        for (OrderApprovalNode node : nodes) {
            approverIds.add(node.getApproverId());
        }
        Map<Long, String> approverNames = loader.userNames(approverIds);

        List<SheetSpec> sheets = new ArrayList<>(2);
        sheets.add(OrderExportExcel.buildOrders(orders));
        sheets.add(OrderExportExcel.buildApprovals(nodes, orderNoById, approverNames));
        return sheets;
    }

    // ------------------------------------------------------------------
    // 自定义表单数据（ · M6）
    // ------------------------------------------------------------------

    /**
     * 自定义表单数据导出：两个工作表（表单数据 + 附件清单）
     *
     * <h2>列的来源</h2>
     * <p>列 = <b>本批工单实际引用过的表单版本</b>的字段并集，而不是「申请类型当前绑定的版本」。
     * 申请类型换绑表单后，历史工单用的是旧版本 —— 只看当前版本会让它们的字段整列消失，
     * 那不叫「完整导出」。
     *
     * <h2>取数一律复用列表 Service</h2>
     * <p>工单行来自 {@code OrderService#pageAllOrders}（与「全部工单」列表同一口径与同一道权限判定），
     * 因此导出内容 = 列表所见、越权无从发生；表单数据 / 附件 / 版本定义按
     * {@code IN} 批量取回，全链路没有 N+1。
     */
    private List<SheetSpec> buildCustomForm(ExportQuery query) {
        ExportQuery.CustomFormFilter filter = requireCustomFormFilter(query);
        List<OrderVO> orders = loader.loadCustomFormOrders(filter, storage.maxRows());

        Map<Long, OrderVO> orderById = new LinkedHashMap<>();
        Set<Long> orderIds = new LinkedHashSet<>();
        Set<Long> applyTypeIds = new LinkedHashSet<>();
        for (OrderVO order : orders) {
            orderById.put(order.getId(), order);
            orderIds.add(order.getId());
            applyTypeIds.add(order.getApplyTypeId());
        }
        Map<Long, String> applyTypeNames = loader.applyTypeNames(applyTypeIds);

        // 表单数据：orderId → 行；同时收集这批工单引用过的版本 id
        Map<Long, OrderFormData> formDataByOrderId = new LinkedHashMap<>();
        Set<Long> versionIds = new LinkedHashSet<>();
        for (OrderFormData row : loader.loadFormDataRows(orderIds)) {
            formDataByOrderId.put(row.getOrderId(), row);
            versionIds.add(row.getFormTemplateVersionId());
        }

        List<VersionSchema> versions = loader.loadVersionSchemas(versionIds);
        List<Column> columns = FormDataFlattenSupport.resolveColumns(versions);

        int maxColumns = storage.maxColumns();
        if (columns.size() > maxColumns) {
            throw new BusinessException(ErrorCode.EXPORT_ROW_LIMIT_EXCEEDED,
                    "本次导出的字段并集共 " + columns.size() + " 列，超过上限 " + maxColumns
                            + " 列，请按时间范围缩小导出（或先归档旧版本表单的字段）");
        }

        // 版本行被物理删除的工单：其字段无从得知，无法进入列集合 —— 记日志留痕而不是静默略过
        if (versions.size() < versionIds.size()) {
            log.warn("自定义表单导出：部分表单版本已不存在，相关字段无法进入列集合：期望 {} 个，实际 {} 个",
                    versionIds.size(), versions.size());
        }

        Map<Long, FormSchema> schemaByVersionId = new LinkedHashMap<>();
        for (VersionSchema version : versions) {
            schemaByVersionId.put(version.versionId(), version.schema());
        }

        // 表单值：orderId → {字段key: 值}
        Map<Long, Map<String, Object>> valuesByOrderId = new LinkedHashMap<>();
        for (OrderFormData row : formDataByOrderId.values()) {
            valuesByOrderId.put(row.getOrderId(), FormSchemaCodec.readData(row.getFormDataJson()));
        }

        References references = loadReferences(versions, valuesByOrderId);

        List<FormOrderRow> rows = new ArrayList<>(orders.size());
        for (OrderVO order : orders) {
            OrderFormData formData = formDataByOrderId.get(order.getId());
            FormSchema schema = formData == null ? null
                    : schemaByVersionId.get(formData.getFormTemplateVersionId());
            rows.add(new FormOrderRow(
                    order.getOrderNo(),
                    applyTypeNameOf(applyTypeNames, order.getApplyTypeId()),
                    order.getApplicantName(),
                    order.getStatusLabel(),
                    order.getCreatedAt(),
                    schema,
                    valuesByOrderId.getOrDefault(order.getId(), Map.of())));
        }

        List<AttachmentRow> attachmentRows = new ArrayList<>();
        for (Attachment attachment : loader.loadCustomOrderAttachments(orderIds)) {
            OrderVO order = orderById.get(attachment.getBizId());
            attachmentRows.add(new AttachmentRow(
                    order == null ? "#" + attachment.getBizId() : order.getOrderNo(),
                    order == null ? "" : applyTypeNameOf(applyTypeNames, order.getApplyTypeId()),
                    order == null ? "" : order.getApplicantName(),
                    attachment.getFileName(),
                    attachment.getFileSize(),
                    attachment.getCreatedAt()));
        }

        List<SheetSpec> sheets = new ArrayList<>(2);
        sheets.add(CustomFormExportExcel.buildFormData(columns, rows, references));
        sheets.add(CustomFormExportExcel.buildAttachments(attachmentRows));
        return sheets;
    }

    /**
     * 批量解析引用类字段的名称（人员 / 设备 / 分组各一次 {@code IN} 查询）
     *
     * <p>三步走：① 由版本定义得出「哪些 key 是引用类」；② 遍历本批数据把这些 key 的 id
     * 收集成三个集合；③ 每类一次查询。任何一步换成「逐行逐字段查库」都会变成 N+1
     * （100 单 × 3 个引用字段 = 300 次查询）。
     */
    private References loadReferences(List<VersionSchema> versions, Map<Long, Map<String, Object>> valuesByOrderId) {
        Map<ValueKind, Set<String>> keysByKind = FormDataFlattenSupport.keysByValueKind(versions);
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> deviceIds = new LinkedHashSet<>();
        Set<Long> groupIds = new LinkedHashSet<>();
        for (Map<String, Object> values : valuesByOrderId.values()) {
            collectIds(values, keysByKind.get(ValueKind.USER_REF), userIds);
            collectIds(values, keysByKind.get(ValueKind.DEVICE_REF), deviceIds);
            collectIds(values, keysByKind.get(ValueKind.GROUP_REF), groupIds);
        }
        return loader.references(userIds, deviceIds, groupIds);
    }

    /** 把某个 key 集合上的值解析成 id 并收进目标集合（非 id 的脏数据直接跳过） */
    private static void collectIds(Map<String, Object> values, Set<String> keys, Set<Long> target) {
        if (values == null || keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            Long id = FormDataFlattenSupport.idOf(values.get(key));
            if (id != null) {
                target.add(id);
            }
        }
    }

    /** 申请类型名；类型已被删除时回落 {@code #id}（与引用类字段同一处理手法，不抹掉信息） */
    private static String applyTypeNameOf(Map<Long, String> names, Long applyTypeId) {
        if (applyTypeId == null) {
            return "";
        }
        String name = names.get(applyTypeId);
        return name == null ? "#" + applyTypeId : name;
    }

    /**
     * 取自定义表单筛选条件，缺 {@code applyTypeId} 时<b>拒绝</b>
     *
     * <p>「必填」在这里是唯一判定点（{@code count} 与 {@code build} 都调本方法），
     * 因此不可能出现「预检放过、生成时才失败」的两端不一致。
     *
     * <p>刻意<b>不</b>退化为「导出全部自定义工单」：那样列并集会随租户里所有申请类型累加，
     * 产出一张既不可读、也可能撑爆 Excel 列上限的宽表。
     */
    private ExportQuery.CustomFormFilter requireCustomFormFilter(ExportQuery query) {
        ExportQuery.CustomFormFilter filter = query == null ? null : query.getCustomForm();
        if (filter == null || filter.getApplyTypeId() == null) {
            throw new BusinessException(ErrorCode.EXPORT_QUERY_INVALID,
                    "自定义表单导出必须指定申请类型（一次只导出一个类型）");
        }
        return filter;
    }

    /** 报表查询条件：年 / 月（「时间筛选：按年月筛选」） */
    private ReportQuery reportQuery(ExportQuery query) {
        ReportQuery report = new ReportQuery();
        report.setYear(query.getYear());
        report.setMonth(query.getMonth());
        return report;
    }
}
