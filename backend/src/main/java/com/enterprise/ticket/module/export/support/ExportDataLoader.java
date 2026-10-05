package com.enterprise.ticket.module.export.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.VersionSchema;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.log.dto.vo.OperationLogVO;
import com.enterprise.ticket.module.log.entity.OperationLog;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.log.support.OperationLogQuerySupport;
import com.enterprise.ticket.module.order.dto.OrderAllQuery;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.service.OrderService;
import com.enterprise.ticket.module.usage.dto.UsageQuery;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;
import com.enterprise.ticket.module.usage.service.UsageService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导出数据装载
 *
 * <h2>为什么「复用列表 Service」而不是自己写查询</h2>
 * <p>导出的正确性标准是<b>「导出文件里就是列表里看到的那批数据」</b>。如果导出另写一套
 * 查询条件，就会出现三类必然发生的漂移：
 * <ol>
 *   <li><b>筛选口径漂移</b>——列表的「申请人关键词」是先查用户 id 集合再 {@code IN}，
 *       自己写很容易退化成字符串子查询，结果集不同；</li>
 *   <li><b>权限漂移</b>——列表接口在服务层判了角色（例如「全部工单」仅管理员），
 *       导出若不共用这段判定，就等于给越权开了一扇后门；</li>
 *   <li><b>展示漂移</b>——状态中文名、设备「名称（资产编号）」等装配逻辑在 Service 里，
 *       复制一份必然出现两处文案不一致。</li>
 * </ol>
 * <p>因此本类只做「翻页把列表数据取全」这一件事：按列表接口的分页上限（200/页）循环拉取，
 * 直到取满 {@code total} 或达到行数上限。代价是 N/200 次查询，换来的是口径与权限的零漂移。
 *
 * <p>跨模块依赖这里破例使用 Service 而非 Mapper：本类的职责就是「复用列表语义」，
 * 而列表语义正是 Service 层的产出（筛选 + 权限 + VO 装配）。依赖方向是
 * export → device/order/usage，被依赖方不反向依赖 export，故不构成循环。
 */
@Component
@RequiredArgsConstructor
public class ExportDataLoader {

    /** 与列表接口的 {@code MAX_PAGE_SIZE} 对齐：请求更大也会被服务层夹到各自上限 */
    private static final long PAGE_SIZE = 200L;

    /** 单次导出装载的数据行数上限（防止把整库读进内存；由 Service 侧行数预检兜底） */
    private static final int LOAD_LIMIT = 200_000;

    /** 日志导出筛选时间的标准格式（与前端筛选控件的提交格式一致） */
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DeviceService deviceService;
    private final OrderService orderService;
    private final UsageService usageService;
    private final OrderApprovalNodeMapper approvalNodeMapper;
    private final UserMapper userMapper;

    /**
     * 操作日志查询（P2 导出）。
     *
     * <p>注入 Service 而非 Mapper：本类对记录类导出的既定取向是「复用列表语义」，
     * 而 {@code OperationLogService} 继承了 {@code IService}，它的 {@code page(IPage, Wrapper)}
     * 与列表 Controller 调的是同一个方法；筛选条件也来自同一个
     * {@code OperationLogQuerySupport} ⇒ 从查询到条件两层都不重复。
     */
    private final OperationLogService logService;

    // ------------------------------------------------------------------
    // M6（自定义表单导出）专用依赖：一次 IN 批量取「明细与名称」，不产生 N+1
    //
    // 这里用 Mapper 而不是 Service，与上面「复用列表 Service」的铁律并不矛盾：
    // 列表 Service 承载的是「筛选 + 权限 + VO 装配」三种语义，导出必须与它同源；
    // 而下面这些查询是纯「按 id 集合取行」——表单数据、附件明细、表单版本定义、
    // 以及三类名称映射（人员 / 设备 / 分组）。它们没有列表语义、没有权限判定，
    // 为导出在各自模块里硬造一批 Service 方法只会增加维护面而没有口径收益。
    // ------------------------------------------------------------------

    private final OrderFormDataMapper formDataMapper;
    private final AttachmentMapper attachmentMapper;
    private final FormTemplateVersionMapper versionMapper;
    private final ApplyTypeMapper applyTypeMapper;
    private final DeviceMapper deviceMapper;
    private final DepartmentMapper departmentMapper;

    // ------------------------------------------------------------------
    // 操作日志（P2）
    // ------------------------------------------------------------------

    /** 操作日志行数（条件与列表接口同源，见 OperationLogQuerySupport） */
    public long countLogs(ExportQuery.LogFilter filter) {
        return logService.count(logWrapper(filter));
    }

    /** 拉取操作日志全量（逐页循环，条件与列表接口同源） */
    public List<OperationLogVO> loadLogs(ExportQuery.LogFilter filter, long maxRows) {
        return loadAll(maxRows, (page, size) -> PageResult.of(
                logService.page(new Page<OperationLog>(page, size), logWrapper(filter)),
                OperationLogVO::of));
    }

    /**
     * 日志筛选 → 查询条件。
     *
     * <p>与 {@code OperationLogController#page} 共用 {@link OperationLogQuerySupport} ——
     * 这是本批刻意做的一处抽取：原本条件构造写在 Controller 里，导出若再写一份，
     * 只要有一处漏了某个筛选条件（比如导出忘了带 result），现象就是
     * 「导出的行数比列表多」，而没人会想到是两处条件写法不同。
     */
    private LambdaQueryWrapper<OperationLog> logWrapper(ExportQuery.LogFilter filter) {
        return OperationLogQuerySupport.wrapper(
                filter == null ? null : filter.getModule(),
                filter == null ? null : filter.getAction(),
                filter == null ? null : filter.getResult(),
                filter == null ? null : filter.getOperatorName(),
                parseLogTime(filter == null ? null : filter.getStartTime()),
                parseLogTime(filter == null ? null : filter.getEndTime()));
    }

    /**
     * 解析导出条件里的时间字符串。
     *
     * <p><b>解析失败一律抛错，绝不静默当作「不筛」</b>：时间条件被悄悄丢掉的效果是
     * 「用户以为导了某个月，实际导了全部」—— 而导出文件本身看不出这一点，
     * 数据一旦拿去对账，错误无从回溯。
     *
     * <p>同时接受 {@code yyyy-MM-dd HH:mm:ss}（前端筛选控件产出）与 ISO 形式
     * （{@code 2026-10-03T14:00:00}，手工调接口时常见），避免只因为一个空格就报错。
     */
    private LocalDateTime parseLogTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return text.contains("T")
                    ? LocalDateTime.parse(text)
                    : LocalDateTime.parse(text, LOG_TIME);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EXPORT_QUERY_INVALID,
                    "时间格式不正确：" + text + "（应为 yyyy-MM-dd HH:mm:ss）");
        }
    }

    // ------------------------------------------------------------------
    // 设备台账（：super_admin/admin 导出全部设备）
    // ------------------------------------------------------------------

    /** 设备台账总行数（用 1 条/页取 total，避免为了计数把数据全捞一遍） */
    public long countDevices(ExportQuery.DeviceFilter filter) {
        return deviceService.page(1L, 1L, keyword(filter), primaryCategoryId(filter),
                secondaryCategoryId(filter), status(filter)).getTotal();
    }

    /** 拉取设备台账全量（按列表口径与排序） */
    public List<DeviceVO> loadDevices(ExportQuery.DeviceFilter filter, long maxRows) {
        return loadAll(maxRows,
                (page, size) -> deviceService.page(page, size, keyword(filter), primaryCategoryId(filter),
                        secondaryCategoryId(filter), status(filter)));
    }

    // ------------------------------------------------------------------
    // 工单记录（：按筛选条件导出；普通 user 仅可导出自己的工单）
    // ------------------------------------------------------------------

    /** 工单总行数；{@code allScope=false} 时走「我的工单」口径（服务层按登录人过滤） */
    public long countOrders(ExportQuery query, boolean allScope) {
        return (allScope
                ? orderService.pageAllOrders(allQuery(query, 1L))
                : orderService.pageMyOrders(1L, 1L, orderStatus(query), orderKeyword(query))).getTotal();
    }

    /** 拉取工单全量 */
    public List<OrderVO> loadOrders(ExportQuery query, boolean allScope, long maxRows) {
        return loadAll(maxRows, (page, size) -> allScope
                ? orderService.pageAllOrders(allQuery(query, size, page))
                : orderService.pageMyOrders(page, size, orderStatus(query), orderKeyword(query)));
    }

    // ------------------------------------------------------------------
    // 使用记录（：字段与「使用记录」列表一致）
    // ------------------------------------------------------------------

    /**
     * 使用记录总行数
     *
     * <p>直接复用 {@code UsageService#page}：它已在内部分支里按当前登录人的
     * {@code data_scope}（SELF / GROUP / ALL）收窄查询，因此这里<b>不需要</b>再判权限 ——
     * 少一处判定就少一处与列表漂移的可能。
     */
    public long countUsages(ExportQuery.UsageFilter filter) {
        return usageService.page(usageQuery(filter, 1L, 1L)).getTotal();
    }

    /** 拉取使用记录全量（逐页循环，条件与列表接口同源） */
    public List<UsageRecordVO> loadUsages(ExportQuery.UsageFilter filter, long maxRows) {
        return loadAll(maxRows, (page, size) -> usageService.page(usageQuery(filter, page, size)));
    }

    /**
     * 审批记录（「导出工单列表、审批记录」）
     *
     * <p>用 {@code IN (orderIds)} 一次取回，避免逐单查询造成 N+1。
     * {@code orderIds} 为空时直接返回空列表 —— 不加这个短路，{@code IN ()} 会生成非法 SQL。
     */
    public List<OrderApprovalNode> loadApprovalNodes(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return approvalNodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .in(OrderApprovalNode::getOrderId, orderIds)
                .orderByAsc(OrderApprovalNode::getOrderId)
                .orderByAsc(OrderApprovalNode::getStepOrder));
    }

    /**
     * 审批人姓名映射（审批记录 sheet 用）
     *
     * <p>只查一次 {@code IN}，且用 {@code selectList} 取需要的列投影之外的整行 —— 用户表字段不多，
     * 为几十个 id 建投影反而增加维护面。
     */
    public Map<Long, String> userNames(Collection<Long> userIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .in(User::getId, userIds));
        for (User user : users) {
            names.put(user.getId(), displayNameOf(user));
        }
        return names;
    }

    /** 显示名优先取 displayName，回落 realName / username，与全局展示口径一致 */
    public static String displayNameOf(User user) {
        if (user == null) {
            return null;
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getRealName() != null && !user.getRealName().isBlank()) {
            return user.getRealName();
        }
        return user.getUsername();
    }

    // ------------------------------------------------------------------
    // 自定义表单数据（ · M6）
    // ------------------------------------------------------------------

    /**
     * 自定义表单导出的工单行数
     *
     * <p>复用「全部工单」口径（{@code OrderService#pageAllOrders}）：它内部已判
     * 「非管理员抛 {@code FORBIDDEN}」，因此数据范围天然限管理员，
     * 导出层不需要（也不应该）再写一份数据过滤 —— 那正是「权限漂移」的来源。
     *
     * <p>调用方必须已确认 {@code applyTypeId} 非空；这里不重复校验，
     * 保证「缺 applyTypeId 就拒绝」只有一处判定（见 {@code ExportSheetBuilder}）。
     */
    public long countCustomFormOrders(ExportQuery.CustomFormFilter filter) {
        return orderService.pageAllOrders(customFormQuery(filter, 1L, 1L)).getTotal();
    }

    /** 拉取自定义表单导出的工单全量（按「全部工单」的排序与分页上限循环取满） */
    public List<OrderVO> loadCustomFormOrders(ExportQuery.CustomFormFilter filter, long maxRows) {
        return loadAll(maxRows, (page, size) -> orderService.pageAllOrders(customFormQuery(filter, page, size)));
    }

    /**
     * 表单填写内容（按 {@code order_id IN} 一次取回）
     *
     * <p>与工单行数一一对应：没有 {@code order_form_data} 行的工单不可能出现在这里
     * （只有走自定义流程提交的单才会写这张表），因此不需要为「缺行」做兜底。
     */
    public List<OrderFormData> loadFormDataRows(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return formDataMapper.selectList(Wrappers.<OrderFormData>lambdaQuery()
                .in(OrderFormData::getOrderId, orderIds)
                .orderByAsc(OrderFormData::getOrderId));
    }

    /**
     * 自定义工单的附件明细（{@code biz_type='CUSTOM_ORDER'}）
     *
     * <p>按 {@code biz_id IN} 一次取回；软删除的行由 {@code @TableLogic} 自动过滤，
     * 因此这里拿到的就是「界面上还能看到的那些附件」。
     */
    public List<Attachment> loadCustomOrderAttachments(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getBizType, AttachmentBizType.CUSTOM_ORDER.name())
                .in(Attachment::getBizId, orderIds)
                .orderByAsc(Attachment::getBizId)
                .orderByAsc(Attachment::getId));
    }

    /**
     * 表单版本定义（按 {@code version_id IN} 一次取回，用于确定列并集与格式化规则）
     *
     * <p><b>schema 损坏时抛错而不是跳过</b>：跳过会让该版本工单的字段整列消失，
     * 用户看到的是一张「少了东西但看起来很正常」的表 —— 静默丢数据比拒绝服务更糟。
     * 抛出的 {@code FORM_SCHEMA_INVALID} 明确指向「某份表单定义存坏了」，
     * 运维可以据此定位修复。
     */
    public List<VersionSchema> loadVersionSchemas(Collection<Long> versionIds) {
        Set<Long> ids = cleanIds(versionIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return versionMapper.selectBatchIds(ids).stream()
                .map(version -> new VersionSchema(version.getId(), version.getVersionNo(),
                        FormSchemaCodec.readSchema(version.getSchemaJson())))
                .toList();
    }

    /** 申请类型名映射（主表与附件清单都要展示「申请类型」，一次 IN 取回） */
    public Map<Long, String> applyTypeNames(Collection<Long> applyTypeIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        Set<Long> ids = cleanIds(applyTypeIds);
        for (ApplyType type : applyTypeMapper.selectBatchIds(ids.isEmpty() ? List.of(-1L) : ids)) {
            names.put(type.getId(), type.getTypeName());
        }
        return names;
    }

    /**
     * 三类引用名称映射（人员 / 设备 / 分组）
     *
     * <p>展示文本与详情页 {@code FormRenderer.refLabel} 逐字对齐：
     * 人员「姓名」+ 离职后缀、设备「名称（资产编号）」、分组「分组名」。
     * 查不到的 id（被删除、无权限）由格式化侧回落 {@code #id}，这里不做兜底文案。
     */
    public References references(Collection<Long> userIds, Collection<Long> deviceIds, Collection<Long> groupIds) {
        return new References(userLabels(userIds), deviceLabels(deviceIds), groupLabels(groupIds));
    }

    /** 人员展示名；已离职追加「（离职）」—— 与详情页一致，避免导出后看不出人员已离职 */
    public Map<Long, String> userLabels(Collection<Long> userIds) {
        Map<Long, String> labels = new LinkedHashMap<>();
        Set<Long> ids = cleanIds(userIds);
        for (User user : userMapper.selectBatchIds(ids.isEmpty() ? List.of(-1L) : ids)) {
            String name = displayNameOf(user);
            String text = name == null ? "" : name;
            labels.put(user.getId(), Boolean.TRUE.equals(user.getDimission()) ? text + "（离职）" : text);
        }
        return labels;
    }

    /** 设备展示名；资产编号缺失时只显示名称（不产出「名称」这种残缺文本） */
    public Map<Long, String> deviceLabels(Collection<Long> deviceIds) {
        Map<Long, String> labels = new LinkedHashMap<>();
        Set<Long> ids = cleanIds(deviceIds);
        for (Device device : deviceMapper.selectBatchIds(ids.isEmpty() ? List.of(-1L) : ids)) {
            labels.put(device.getId(), deviceLabelOf(device));
        }
        return labels;
    }

    /** 部门名 */
    public Map<Long, String> groupLabels(Collection<Long> groupIds) {
        Map<Long, String> labels = new LinkedHashMap<>();
        Set<Long> ids = cleanIds(groupIds);
        for (Department group : departmentMapper.selectBatchIds(ids.isEmpty() ? List.of(-1L) : ids)) {
            labels.put(group.getId(), group.getDeptName());
        }
        return labels;
    }

    /** 设备展示文本（「名称（资产编号）」），与详情页同一口径 */
    public static String deviceLabelOf(Device device) {
        if (device == null) {
            return null;
        }
        String name = device.getDeviceName() == null ? "" : device.getDeviceName();
        String assetNo = device.getAssetNo();
        return assetNo == null || assetNo.isBlank() ? name : name + "（" + assetNo + "）";
    }

    /** 去掉 null 并去重（{@code IN} 查询前统一清洗，避免把 null 拼进 SQL） */
    private static Set<Long> cleanIds(Collection<Long> ids) {
        Set<Long> cleaned = new LinkedHashSet<>();
        if (ids == null) {
            return cleaned;
        }
        for (Long id : ids) {
            if (id != null) {
                cleaned.add(id);
            }
        }
        return cleaned;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 翻页装载：把某一页的查询抽象成函数，循环到取满或触顶 */
    private <T> List<T> loadAll(long maxRows, PageFetcher<T> fetcher) {
        List<T> all = new java.util.ArrayList<>();
        long page = 1L;
        while (true) {
            PageResult<T> result = fetcher.fetch(page, PAGE_SIZE);
            List<T> records = result.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            all.addAll(records);
            if (all.size() >= result.getTotal() || all.size() >= maxRows || all.size() >= LOAD_LIMIT) {
                break;
            }
            page++;
        }
        return all;
    }

    @FunctionalInterface
    private interface PageFetcher<T> {
        PageResult<T> fetch(long page, long size);
    }

    /**
     * 自定义表单导出的条件 → 「全部工单」列表查询对象
     *
     * <p>只映射 {@code CustomFormFilter} 的四个字段，逐字段对应、不新增语义。
     * 申请类型由调用方保证非空（空的场合在 {@code ExportSheetBuilder} 就被拒了，
     * 走不到这里）—— 若在这里再判一次，就出现了第二个「必填」判定点。
     */
    private OrderAllQuery customFormQuery(ExportQuery.CustomFormFilter filter, long page, long size) {
        OrderAllQuery all = new OrderAllQuery();
        all.setPage(page);
        all.setSize(size);
        if (filter == null) {
            return all;
        }
        all.setApplyTypeId(filter.getApplyTypeId());
        all.setStatus(filter.getStatus());
        all.setSubmitTimeFrom(filter.getSubmitTimeFrom());
        all.setSubmitTimeTo(filter.getSubmitTimeTo());
        return all;
    }

    private OrderAllQuery allQuery(ExportQuery query, long size) {
        return allQuery(query, size, 1L);
    }

    private OrderAllQuery allQuery(ExportQuery query, long size, long page) {
        ExportQuery.OrderFilter filter = query.getOrder();
        OrderAllQuery all = new OrderAllQuery();
        all.setPage(page);
        all.setSize(size);
        if (filter == null) {
            return all;
        }
        all.setStatus(filter.getStatus());
        all.setApplicantKeyword(filter.getApplicantKeyword());
        all.setDeviceKeyword(filter.getDeviceKeyword());
        all.setUseType(filter.getUseType());
        all.setSubmitTimeFrom(filter.getSubmitTimeFrom());
        all.setSubmitTimeTo(filter.getSubmitTimeTo());
        all.setDepartmentId(filter.getDepartmentId());
        all.setBorrowTimeout(filter.getBorrowTimeout());
        all.setTransferred(filter.getTransferred());
        // ：自定义申请类型筛选（与「全部工单」列表同口径，导出 = 列表所见）
        all.setApplyTypeId(filter.getApplyTypeId());
        return all;
    }

    /** 把导出的使用记录筛选映射为列表查询对象（字段一一对应，不新增语义） */
    private UsageQuery usageQuery(ExportQuery.UsageFilter filter, long page, long size) {
        UsageQuery query = new UsageQuery();
        query.setPage(page);
        query.setSize(size);
        if (filter == null) {
            return query;
        }
        query.setScope(filter.getScope());
        query.setTargetId(filter.getTargetId());
        query.setKeyword(filter.getKeyword());
        query.setStatus(filter.getStatus());
        query.setUseType(filter.getUseType());
        query.setStartTime(filter.getStartTime());
        query.setEndTime(filter.getEndTime());
        return query;
    }

    private String keyword(ExportQuery.DeviceFilter filter) {
        return filter == null ? null : filter.getKeyword();
    }

    private Long primaryCategoryId(ExportQuery.DeviceFilter filter) {
        return filter == null ? null : filter.getPrimaryCategoryId();
    }

    private Long secondaryCategoryId(ExportQuery.DeviceFilter filter) {
        return filter == null ? null : filter.getSecondaryCategoryId();
    }

    private String status(ExportQuery.DeviceFilter filter) {
        return filter == null ? null : filter.getStatus();
    }

    private String orderStatus(ExportQuery query) {
        return query.getOrder() == null ? null : query.getOrder().getStatus();
    }

    private String orderKeyword(ExportQuery query) {
        return query.getOrder() == null ? null : query.getOrder().getKeyword();
    }
}
