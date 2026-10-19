package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.FaultStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.device.support.FaultRepairAssigneeResolver;
import com.enterprise.ticket.module.message.service.UserNotificationService;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceLookupVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceFaultService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.support.DivisionPrefillService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备故障服务实现
 *
 * <p>状态一致性设计：设备状态与故障记录状态总是**成对推进**，且设备侧的 UPDATE 带前置状态条件
 * （{@code WHERE status = 期望的旧状态}），命中 0 行即抛错回滚 —— 避免并发下把设备改成未预期的状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceFaultServiceImpl implements DeviceFaultService {

    private static final long MAX_PAGE_SIZE = 100L;

    private final DeviceFaultMapper faultMapper;
    private final DeviceMapper deviceMapper;
    /** 分类名解析（V57：报修两种填报模式都要让用户确认「扫到的是不是那一类东西」） */
    private final DeviceCategoryMapper categoryMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    /** 报修「通知谁」+「谁能接单」的唯一判定来源（两处必须同源，见其类注释） */
    private final FaultRepairAssigneeResolver faultRepairAssigneeResolver;
    /** 处理流程的站内通知（报修待处理 / 已接单 / 已处理） */
    private final UserNotificationService userNotificationService;
    /**
     * 「当前所在分部」预填与来源判定（V54 / D5）。
     *
     * <p>报修**不按分部派单**（维修人由「报修处理设置」决定，V50 的设计不动），
     * 但分部必须留痕：否则「各分部报修量」「某分部设备故障率」这些报表没有数据源。
     * 方向是 device → order.support 的单向依赖（本类原本就已依赖 order 的实体与 Mapper）。
     */
    private final DivisionPrefillService divisionPrefillService;
    /**
     * 内置申请类型的表单版本指针（V59 / 批次 3.5）：报修表单放开后，
     * 自定义字段要按内置「故障报修」当前绑定的那一版 schema 校验并随记录存档。
     */
    private final com.enterprise.ticket.module.applytype.service.ApplyTypeService applyTypeService;
    /** 自定义字段的逐字段校验（与自定义申请同一套规则，避免两处口径漂移） */
    private final com.enterprise.ticket.common.form.FormDataValidator formDataValidator;
    /** 按版本 id 取已发布 schema（提交时必须用发布版，不能用草稿） */
    private final com.enterprise.ticket.module.form.service.FormTemplateService formTemplateService;

    // ------------------------------------------------------------------
    // 上报
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long report(DeviceFaultRequest request) {
        Long currentUserId = requireCurrentUserId();
        String role = currentRole();
        Device device = requireDevice(request.getDeviceId());
        DeviceStatus deviceStatus = DeviceStatus.of(device.getStatus());
        LocalDateTime now = LocalDateTime.now();
        if (request.getOccurredAt() != null && request.getOccurredAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.FAULT_OCCURRED_TIME_INVALID);
        }

        // V59 / 批次 3.5：报修表单放开后管理员新增的**自定义字段**。
        // 位置有讲究：放在所有业务校验**之前**还是之后？放在这里（设备 / 权限 / 状态
        // 都校验完、正要写库）—— 表单校验的错误是「字段级」的，比「设备状态不符」
        // 更具体，而非法请求根本走不到这一步，不会白跑一次 schema 校验。
        ResolvedFaultForm form = resolveFaultForm(request.getFormData());

        Long orderId = request.getOrderId();
        if (orderId != null) {
            // 工单内上报：只有该工单借用人或管理员可上报
            Order order = requireOrder(orderId);
            if (!Objects.equals(order.getDeviceId(), device.getId())) {
                throw new BusinessException(ErrorCode.FAULT_ORDER_MISMATCH);
            }
            boolean isApplicant = Objects.equals(order.getApplicantId(), currentUserId);
            if (!isApplicant && !RoleCode.isAdminOrAbove(role)) {
                throw new BusinessException(ErrorCode.FAULT_REPORTER_NOT_ALLOWED,
                        "只有该工单借用人或管理员可以上报故障");
            }
            if (!OrderStatus.BORROWED.name().equals(order.getStatus())) {
                throw new BusinessException(ErrorCode.FAULT_ORDER_MISMATCH,
                        "工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，仅「使用中」可关联上报故障");
            }
            if (deviceStatus != DeviceStatus.IN_USE) {
                throw new BusinessException(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                        "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus()) + "」，与使用中的工单不一致");
            }
            // 工单内上报**不改变设备状态**：设备仍在借用中，去向（维修 / 可用）由归还登记决定
            Long id = insert(device, orderId, currentUserId, request, now,
                    resolveDivision(currentUserId, request), form);
            log.info("工单 {} 上报设备 {} 故障，记录 id={}（设备保持使用中）",
                    order.getOrderNo(), device.getId(), id);
            return id;
        }

        // 无工单登记（V57 / 第 10 项「公共设备」模式起对普通员工开放）：
        //   · 管理员 / 超管 —— 可对任意「可用」设备登记；
        //   · 普通员工 —— 可对「可用」设备报修（会议室打印机、公共显示器这类
        //     根本没有负责任人的设备，非要找管理员才能报，等于把这个入口废掉）。
        // 唯一的硬约束仍然是「设备必须可用」：使用中的设备必须走工单路径，
        // 否则设备状态会绕过借用单被改成维修中，那台设备的工单就永远收不回来了。
        if (deviceStatus != DeviceStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus())
                            + "」，仅「可用」设备可直接登记故障（使用中的设备请从工单上报）");
        }
        changeDeviceStatus(device, DeviceStatus.AVAILABLE, DeviceStatus.MAINTENANCE);
        Long id = insert(device, null, currentUserId, request, now,
                resolveDivision(currentUserId, request), form);
        log.info("台账直接登记设备 {} 故障，记录 id={}，设备 可用 → 维修中", device.getId(), id);
        return id;
    }

    /**
     * 报修表单解析结果（V59 / 批次 3.5）：版本指针 + 自定义字段值。
     *
     * <p>用一个 record 而不是两个返回值，是因为这两样东西必须**同生共死**：
     * 有版本指针却没有值（或反之）会让「按哪一版回显」这个问题没有答案。
     */
    private record ResolvedFaultForm(Long formTemplateVersionId, Map<String, Object> formData) {

        static final ResolvedFaultForm EMPTY = new ResolvedFaultForm(null, Map.of());
    }

    /**
     * 校验并归一化报修的自定义字段（V59）。
     *
     * <h2>没绑表单版本时怎么办</h2>
     * 与借用单同一取向（见 {@code OrderServiceImpl#resolveBorrowExtraFormData}）：
     * **空 formData 照常放行，非空才拒绝**。存量调用方（台账页登记、归还时自动生成）
     * 不传这个字段；升级未跑播种的部署也不会因此连普通报修都提交不了。
     *
     * <p>注意这里是 device 模块反向依赖 applytype 模块的**服务接口**而不是 Mapper ——
     * 「内置报修类型绑在哪一版表单」是 applytype 域的知识，与它依赖 order 的
     * {@code DivisionPrefillService} 同一模式（单向、经服务边界）。
     */
    private ResolvedFaultForm resolveFaultForm(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return ResolvedFaultForm.EMPTY;
        }
        Long formVersionId = applyTypeService.builtInFormVersionId(
                com.enterprise.ticket.module.applytype.support.BuiltInApplyCatalog.CODE_FAULT_REPORT);
        if (formVersionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "故障报修尚未配置表单版本，暂不支持提交自定义字段");
        }
        FormSchema schema = formTemplateService.requirePublishedSchema(formVersionId);
        Map<String, Object> data = new LinkedHashMap<>(raw);
        formDataValidator.validate(schema, data);
        return new ResolvedFaultForm(formVersionId, data);
    }

    /**
     * 判定「当前所在分部」并落库（V54 / D5）。
     *
     * <p><b>只在真正要写库的那一刻调用</b>：它要查用户主部门、甚至回看出差单，
     * 而绝大多数非法请求（越权 / 状态不符 / 设备不匹配）在那之前就被拒了 ——
     * 把判定提前，等于让每个被拒绝的请求也白查一遍库。放在写入前一刻还有一个好处：
     * 这里抛出的「分部非法」错误是最后一道门，前面所有业务校验的错误码都不会被它盖掉。
     */
    private DivisionPrefillService.ResolvedDivision resolveDivision(Long currentUserId,
                                                                    DeviceFaultRequest request) {
        return divisionPrefillService.resolve(currentUserId, request.getCurrentDivisionId());
    }

    private Long insert(Device device, Long orderId, Long reporterId, DeviceFaultRequest request,
                        LocalDateTime fallbackOccurredAt,
                        DivisionPrefillService.ResolvedDivision division,
                        ResolvedFaultForm form) {
        DeviceFault fault = new DeviceFault();
        fault.setDeviceId(device.getId());
        fault.setOrderId(orderId);
        fault.setReporterId(reporterId);
        fault.setFaultDescription(request.getFaultDescription().trim());
        // 采用用户填报的「故障发生时间」；缺失时才用登记时刻兜底（否则上报时间会退化成登记时间）
        fault.setOccurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : fallbackOccurredAt);
        fault.setStatus(FaultStatus.PENDING_REPAIR.name());
        fault.setCurrentDivisionId(division.divisionId());
        fault.setDivisionSource(division.source());
        // V59：自定义字段的版本指针与值。没配表单时两列留空（语义 = 没有自定义字段）。
        fault.setFormTemplateVersionId(form.formTemplateVersionId());
        fault.setFormDataJson(form.formData().isEmpty()
                ? null : FormSchemaCodec.write(form.formData()));
        faultMapper.insert(fault);
        // V50：登记即通知维修人。改造前报修提交后**没有任何通知** ——
        // 维修人只能靠不断刷新列表才发现有新单，等于整条处理流程没有起点。
        // 放在 insert 之内（而不是两个调用点各写一次），保证「工单上报」与「台账登记」
        // 两条入口的行为一致 —— 漏掉一条的表现是「从某个入口报修不会通知」。
        notifyAssignees(device, fault);
        return fault.getId();
    }

    // ------------------------------------------------------------------
    // 处理
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRepaired(Long faultId, DeviceFaultHandleRequest request) {
        // V50：**先鉴权、再读记录**。顺序很重要 —— 反过来会让无权限的人拿到
        // 「这条记录存不存在」这类信息（FAULT_NOT_FOUND vs FORBIDDEN 的差别就是答案），
        // 也让越权请求白白走一遍查询。
        requireAdminOrRepairer(faultId);
        DeviceFault fault = requireOpenFault(faultId);
        Device device = requireDevice(fault.getDeviceId());
        // P2：校验维修人。请求体完全由客户端可控 ——「前端是选择器所以不会传错」不构成保证，
        // 而一个不存在的 user id 会**静默落库**：那台设备的「维修人」永远显示为空且不报任何错。
        // 这类「不报错的数据损坏」正是最该拦在入口的。
        Long repairerId = request == null ? null : request.getRepairerId();
        if (repairerId != null) {
            User repairer = userMapper.selectById(repairerId);
            if (repairer == null || !Boolean.TRUE.equals(repairer.getEnabled())) {
                throw new BusinessException(ErrorCode.FAULT_REPAIRER_INVALID);
            }
        }
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.MAINTENANCE) {
            throw new BusinessException(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus())
                            + "」，仅「维修中」的设备可登记维修完成");
        }
        LocalDateTime now = LocalDateTime.now();
        changeDeviceStatus(device, DeviceStatus.MAINTENANCE, DeviceStatus.AVAILABLE);
        faultMapper.update(null, Wrappers.<DeviceFault>lambdaUpdate()
                .eq(DeviceFault::getId, faultId)
                // V50：待处理与处理中都可标完成（接单后状态已是「处理中」）
                .in(DeviceFault::getStatus,
                        FaultStatus.PENDING_REPAIR.name(), FaultStatus.IN_PROGRESS.name())
                .set(DeviceFault::getStatus, FaultStatus.REPAIRED.name())
                .set(DeviceFault::getHandledBy, requireCurrentUserId())
                .set(DeviceFault::getHandledAt, now)
                .set(DeviceFault::getHandleRemark, trimToNull(request == null ? null : request.getRemark()))
                // P2 维修过程三项：只有「维修完成」会写它们（报废走 scrap，不碰这三列）
                .set(DeviceFault::getRepairerId, request == null ? null : request.getRepairerId())
                .set(DeviceFault::getRepairCost, request == null ? null : request.getRepairCost())
                .set(DeviceFault::getReplacedParts,
                        trimToNull(request == null ? null : request.getReplacedParts())));
        log.info("故障记录 {} 维修完成，设备 {} 维修中 → 可用", faultId, device.getId());
    }

    /**
     * 接单（V50）：把「待处理」推进到「处理中」，并记下是谁接的。
     *
     * <h2>为什么需要一个独立的「接单」动作</h2>
     * <p>没有它，报修单在维修人眼里与「一堆待办」没有区别：谁去修、修到哪一步，
     * 系统里都没有痕迹，同一台设备可能两个人同时去修。
     * 接单把「责任」这件事明确下来，也让报修人看到「已经有人接手了」。
     *
     * <h2>为什么它能绕过 {@code device:fault:manage}</h2>
     * <p>那个权限码按现有角色配置只发给管理员，而接单的人是一线维修人员。
     * 硬把它加进权限目录等于为了一个动作重排整套授权。这里的口径是：
     * <b>管理员，或落在「处理流程」配置的通知范围内的人</b> —— 而这套范围本来就是
     * 管理员自己配的，因此并没有放宽任何人的权限，只是让配置生效。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long faultId) {
        DeviceFault fault = requireOpenFault(faultId);
        Long me = requireCurrentUserId();
        if (!RoleCode.isAdminOrAbove(currentRole()) && !faultRepairAssigneeResolver.covers(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "你不在这条报修的处理范围内，无法接单；请联系管理员在「申请类型与流程 → 故障报修 → 处理流程」中配置");
        }
        FaultStatus status = FaultStatus.of(fault.getStatus());
        if (status == null || !status.isAcceptable()) {
            throw new BusinessException(ErrorCode.FAULT_ALREADY_HANDLED,
                    "该报修单已被接单（当前「" + FaultStatus.labelOf(fault.getStatus()) + "」），无需重复接单");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = faultMapper.update(null, Wrappers.<DeviceFault>lambdaUpdate()
                .eq(DeviceFault::getId, faultId)
                .eq(DeviceFault::getStatus, FaultStatus.PENDING_REPAIR.name())
                .set(DeviceFault::getStatus, FaultStatus.IN_PROGRESS.name())
                .set(DeviceFault::getHandledBy, me)
                .set(DeviceFault::getHandledAt, now)
                // 顺手把维修人也填上：接单的人就是维修人。留空会让「谁在修」在列表里显示为空，
                // 而那正是这条流程最需要被看见的信息。
                .set(DeviceFault::getRepairerId, me));
        if (updated == 0) {
            // 并发接单：两个人同时点，只有一个人能成功。后者看到的是明确提示而不是静默覆盖。
            throw new BusinessException(ErrorCode.FAULT_ALREADY_HANDLED, "该报修单刚被他人接单，请刷新后查看");
        }
        userNotificationService.notify(fault.getReporterId(), MessageType.FAULT_REPAIR_ACCEPTED,
                "你的报修已被接单",
                "你提交的设备故障报修已由「" + currentDisplayName() + "」接单，正在处理中。",
                fault.getOrderId());
        log.info("故障记录 {} 已由 {} 接单，待处理 → 处理中", faultId, me);
    }

    /**
     * 判定「无法修复」（V50）。
     *
     * <p>与「已报废」分开：这是**维修结论**，不是**资产决策**。设备保持「维修中」状态，
     * 由资产管理员另行决定报废或调拨 —— 不能让维修人顺手替别人做报废决定。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markUnrepairable(Long faultId, DeviceFaultHandleRequest request) {
        requireAdminOrRepairer(faultId);
        DeviceFault fault = requireOpenFault(faultId);
        LocalDateTime now = LocalDateTime.now();
        // 设备状态**不动**：它仍是 MAINTENANCE（不能用），等资产决策。
        // 若这里顺手改成 AVAILABLE，就等于把一台修不好的设备放回可用池。
        faultMapper.update(null, Wrappers.<DeviceFault>lambdaUpdate()
                .eq(DeviceFault::getId, faultId)
                .in(DeviceFault::getStatus,
                        FaultStatus.PENDING_REPAIR.name(), FaultStatus.IN_PROGRESS.name())
                .set(DeviceFault::getStatus, FaultStatus.UNREPAIRABLE.name())
                .set(DeviceFault::getHandledBy, requireCurrentUserId())
                .set(DeviceFault::getHandledAt, now)
                .set(DeviceFault::getHandleRemark, trimToNull(request == null ? null : request.getRemark()))
                .set(DeviceFault::getRepairerId,
                        request != null && request.getRepairerId() != null
                                ? request.getRepairerId() : fault.getRepairerId()));
        userNotificationService.notify(fault.getReporterId(), MessageType.FAULT_REPAIR_HANDLED,
                "你的报修已判定为「无法修复」",
                "你提交的设备故障报修已处理：无法修复。"
                        + (request != null && trimToNull(request.getRemark()) != null
                        ? "维修说明：" + request.getRemark() : "后续由资产管理员决定报废或调拨。"),
                fault.getOrderId());
        log.info("故障记录 {} 判定无法修复（设备保持维修中，待资产决策）", faultId);
    }

    // ------------------------------------------------------------------
    // 处理流程的通知与授权（V50）
    // ------------------------------------------------------------------

    /**
     * 登记后通知配置范围内的维修人。
     *
     * <p>一个人都没命中时**只记 WARN 不抛错**：报修是员工描述故障的唯一途径，
     * 不该因为运维侧「通知谁」没配好而提交不了。真正需要被发现的是「配了但没人」，
     * 那由这条 WARN 和页面上的配置项一起暴露。
     */
    private void notifyAssignees(Device device, DeviceFault fault) {
        List<Long> assignees = faultRepairAssigneeResolver.resolve();
        if (assignees.isEmpty()) {
            log.warn("[报修] 故障记录 {} 没有命中任何通知对象（请检查「申请类型与流程 → 故障报修 → 处理流程」"
                    + "的通知配置，以及最终处理部门是否有在职成员）", fault.getId());
            return;
        }
        String title = "有新的设备报修待处理";
        String body = "设备「" + device.getDeviceName() + "（" + device.getAssetNo() + "）」被上报故障："
                + fault.getFaultDescription();
        for (Long assignee : assignees) {
            userNotificationService.notify(assignee, MessageType.FAULT_REPAIR_TODO, title, body, fault.getOrderId());
        }
        log.info("故障记录 {} 已通知 {} 名维修人", fault.getId(), assignees.size());
    }

    /**
     * 管理员，或该故障的维修人 / 通知范围内的人。
     *
     * <p>「已接单人」与「配置范围内的人」都放行是刻意的：前者覆盖「接了单但配置被改过」，
     * 后者覆盖「还没来得及接单就直接修完了」（小团队里很常见，硬要求先接单只会被绕过）。
     */
    private void requireAdminOrRepairer(Long faultId) {
        if (RoleCode.isAdminOrAbove(currentRole())) {
            return;
        }
        Long me = requireCurrentUserId();
        if (faultRepairAssigneeResolver.covers(me)) {
            return;
        }
        // 「已接单人」同样放行：配置若在这之后被改过，也不该让接了单的人反而收不了尾。
        // 只在粗判不通过时才查这一行 —— 正常路径上不需要这次查询。
        DeviceFault fault = faultId == null ? null : faultMapper.selectById(faultId);
        if (fault != null && Objects.equals(fault.getHandledBy(), me)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员或该报修的维修人可操作");
    }

    /** 当前用户显示名（用于通知正文；取不到时退化成「维修人」而不是 null） */
    private String currentDisplayName() {
        Long me = SecurityUtils.getCurrentUserId();
        User user = me == null ? null : userMapper.selectById(me);
        if (user == null) {
            return "维修人";
        }
        String name = user.getDisplayName();
        return (name == null || name.isBlank()) ? user.getUsername() : name;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void scrap(Long faultId, DeviceFaultHandleRequest request) {
        requireAdmin();
        DeviceFault fault = requireOpenFault(faultId);
        Device device = requireDevice(fault.getDeviceId());
        DeviceStatus from = DeviceStatus.of(device.getStatus());
        if (from == DeviceStatus.IN_USE) {
            // ：使用中的设备禁止直接报废，必须先归还
            throw new BusinessException(ErrorCode.DEVICE_IN_BORROWED, "设备正在使用中，禁止报废，请先归还设备");
        }
        if (!DeviceStatus.canManualTransfer(from, DeviceStatus.SCRAPPED)) {
            throw new BusinessException(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus()) + "」，不可报废");
        }
        LocalDateTime now = LocalDateTime.now();
        changeDeviceStatus(device, from, DeviceStatus.SCRAPPED);
        faultMapper.update(null, Wrappers.<DeviceFault>lambdaUpdate()
                .eq(DeviceFault::getId, faultId)
                // V50：已接单的记录同样可以报废（报废是资产决策，与谁接的单无关）
                .in(DeviceFault::getStatus,
                        FaultStatus.PENDING_REPAIR.name(), FaultStatus.IN_PROGRESS.name())
                .set(DeviceFault::getStatus, FaultStatus.SCRAPPED.name())
                .set(DeviceFault::getHandledBy, requireCurrentUserId())
                .set(DeviceFault::getHandledAt, now)
                .set(DeviceFault::getHandleRemark, trimToNull(request == null ? null : request.getRemark())));
        log.info("故障记录 {} 判定报废，设备 {} {} → 已报废", faultId, device.getId(), from);
    }

    /**
     * 设备状态推进：UPDATE 带前置状态条件，命中 0 行说明设备状态已被并发改动 → 抛错回滚。
     */
    private void changeDeviceStatus(Device device, DeviceStatus expectFrom, DeviceStatus to) {
        int updated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, device.getId())
                .eq(Device::getStatus, expectFrom.name())
                .set(Device::getStatus, to.name()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "设备状态已被其他操作变更，请刷新后重试");
        }
    }

    // ------------------------------------------------------------------
    // 归还登记故障自动建档
    // ------------------------------------------------------------------

    @Override
    public Long recordReturnFault(Long deviceId, Long orderId, Long reporterId, String description,
                                  LocalDateTime occurredAt) {
        // 同一工单/设备下若已有「待维修」记录（借用人此前已上报过），不重复建档
        Long existing = faultMapper.selectCount(Wrappers.<DeviceFault>lambdaQuery()
                .eq(DeviceFault::getDeviceId, deviceId)
                .eq(orderId != null, DeviceFault::getOrderId, orderId)
                .eq(DeviceFault::getStatus, FaultStatus.PENDING_REPAIR.name()));
        if (existing != null && existing > 0) {
            log.info("设备 {}（工单 {}）已存在待维修记录，归还登记不再重复建档", deviceId, orderId);
            return null;
        }
        DeviceFault fault = new DeviceFault();
        fault.setDeviceId(deviceId);
        fault.setOrderId(orderId);
        fault.setReporterId(reporterId);
        fault.setFaultDescription(description == null || description.isBlank()
                ? "归还时登记设备故障" : description.trim());
        fault.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        fault.setStatus(FaultStatus.PENDING_REPAIR.name());
        faultMapper.insert(fault);
        log.info("归还登记故障：设备 {}（工单 {}）自动建档，记录 id={}", deviceId, orderId, fault.getId());
        return fault.getId();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<DeviceFaultVO> page(DeviceFaultQuery query) {
        // V50：准入从「仅管理员」放开为「管理员或处理范围内的人」。
        // 这不是把台账摊开给所有人 —— 紧接着的**强制数据隔离**才是关键：
        // 非管理员拿到的结果集与他自己调「待我处理」完全一样，
        // 且**不受查询参数影响**（传 onlyMine=false 也不会多出一行）。
        Long me = requireCurrentUserId();
        boolean admin = RoleCode.isAdminOrAbove(currentRole());
        boolean inScope = admin || faultRepairAssigneeResolver.covers(me);
        if (!inScope && !hasHandledFault(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "你不在这条报修的处理范围内，无法查看故障记录；"
                            + "请联系管理员在「申请类型与流程 → 故障报修 → 处理流程」中配置");
        }
        long safePage = Math.max(query.getPage(), 1L);
        long safeSize = Math.min(Math.max(query.getSize(), 1L), MAX_PAGE_SIZE);

        String status = trimToNull(query.getStatus());
        if (status != null && FaultStatus.of(status) == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "故障状态筛选值不合法：" + status);
        }
        String deviceKeyword = trimToNull(query.getDeviceKeyword());
        Set<Long> deviceIds = matchDeviceIds(deviceKeyword);
        if (deviceKeyword != null && deviceIds.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }

        // 「待我处理」：未结束 + 归我管。
        //
        // 「归我管」= 我接的单，或**还没人接的单**。后者是关键：维修人的待办列表里
        // 最主要的就是「还没人认领的新单」——若只算「我接的」，这个筛选在接单之前永远是空的，
        // 而它存在的意义恰恰是让人看到「有什么等着我接」。
        //
        // 已被别人接走的单不出现在我的待办里（在别人手上），这是与「全部未结束」的唯一差别。
        //
        // V50 的两点收紧：
        //  1. **非管理员恒为 true**（`!admin` 直接把它钉死）—— 数据隔离不能靠调用方守法：
        //     查询参数是客户端可控的，若只在 admin 分支之外「尊重」它，
        //     非管理员传 `onlyMine=false` 就能拿到全量台账；
        //  2. 「没人接的单」只对**有资格接**的人可见（管理员 / 在处理范围内）。
        //     一个「配置被改走、但历史上接过单」的维修人仍能进这个页面（他要能收尾），
        //     却不该因此看到一堆他接不了的新单。
        boolean scoped = !admin || Boolean.TRUE.equals(query.getOnlyMine());
        boolean seeUnclaimed = inScope;

        IPage<DeviceFault> resultPage = faultMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<DeviceFault>lambdaQuery()
                        .eq(status != null, DeviceFault::getStatus, status)
                        .in(deviceKeyword != null, DeviceFault::getDeviceId, deviceIds)
                        .in(scoped, DeviceFault::getStatus,
                                FaultStatus.PENDING_REPAIR.name(), FaultStatus.IN_PROGRESS.name())
                        .and(scoped, w -> {
                            w.eq(DeviceFault::getHandledBy, me);
                            if (seeUnclaimed) {
                                w.or().isNull(DeviceFault::getHandledBy);
                            }
                        })
                        .orderByDesc(DeviceFault::getId));
        List<DeviceFault> records = resultPage.getRecords();
        Map<Long, Device> devices = devicesById(records);
        Map<Long, Order> orders = ordersById(records);
        Map<Long, String> userNames = userNameMap(reporterAndHandlerIds(records));
        Map<Long, FormSchema> schemas = formSchemasById(records);
        return PageResult.of(resultPage, fault -> toVO(fault, devices, orders, userNames, schemas));
    }

    @Override
    public List<DeviceFaultVO> listByDevice(Long deviceId) {
        requireAdmin();
        requireDevice(deviceId);
        List<DeviceFault> faults = faultMapper.selectList(Wrappers.<DeviceFault>lambdaQuery()
                .eq(DeviceFault::getDeviceId, deviceId)
                .orderByDesc(DeviceFault::getId));
        if (faults.isEmpty()) {
            return List.of();
        }
        Map<Long, Device> devices = devicesById(faults);
        Map<Long, Order> orders = ordersById(faults);
        Map<Long, String> userNames = userNameMap(reporterAndHandlerIds(faults));
        Map<Long, FormSchema> schemas = formSchemasById(faults);
        return faults.stream()
                .map(fault -> toVO(fault, devices, orders, userNames, schemas))
                .toList();
    }

    @Override
    public List<FaultDeviceOptionVO> selectableDevices() {
        Long currentUserId = requireCurrentUserId();
        boolean admin = RoleCode.isAdminOrAbove(currentRole());

        List<FaultDeviceOptionVO> options = new ArrayList<>();
        Set<Long> seenDeviceIds = new LinkedHashSet<>();

        // ①「使用中」工单对应的设备：普通用户只看自己的，管理员/超管看全部（可代借用人上报）
        List<Order> borrowedOrders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getId, Order::getOrderNo, Order::getDeviceId, Order::getApplicantId, Order::getUseType)
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .eq(!admin, Order::getApplicantId, currentUserId)
                .orderByDesc(Order::getId));
        if (!borrowedOrders.isEmpty()) {
            Map<Long, Device> devices = devicesByIds(borrowedOrders.stream()
                    .map(Order::getDeviceId).filter(Objects::nonNull).toList());
            Map<Long, String> applicantNames = userNameMap(borrowedOrders.stream()
                    .map(Order::getApplicantId).filter(Objects::nonNull).toList());
            for (Order order : borrowedOrders) {
                Device device = devices.get(order.getDeviceId());
                if (device == null) {
                    continue;
                }
                FaultDeviceOptionVO vo = new FaultDeviceOptionVO();
                vo.setDeviceId(device.getId());
                vo.setDeviceName(device.getDeviceName());
                vo.setAssetNo(device.getAssetNo());
                vo.setDeviceStatus(device.getStatus());
                vo.setDeviceStatusLabel(DeviceStatus.labelOf(device.getStatus()));
                vo.setOrderId(order.getId());
                vo.setOrderNo(order.getOrderNo());
                vo.setUseType(order.getUseType());
                vo.setUseTypeLabel(UseType.labelOf(order.getUseType()));
                vo.setApplicantName(applicantNames.get(order.getApplicantId()));
                vo.setLabel(optionLabel(device, order, applicantNames.get(order.getApplicantId())));
                options.add(vo);
                seenDeviceIds.add(device.getId());
            }
        }

        // ② 管理员/超管追加「可用」设备（无工单，走台账直接登记路径，设备 可用 → 维修中）
        if (admin) {
            List<Device> availableDevices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                    .select(Device::getId, Device::getDeviceName, Device::getAssetNo, Device::getStatus)
                    .eq(Device::getStatus, DeviceStatus.AVAILABLE.name())
                    .orderByAsc(Device::getDeviceName));
            for (Device device : availableDevices) {
                if (!seenDeviceIds.add(device.getId())) {
                    continue;
                }
                FaultDeviceOptionVO vo = new FaultDeviceOptionVO();
                vo.setDeviceId(device.getId());
                vo.setDeviceName(device.getDeviceName());
                vo.setAssetNo(device.getAssetNo());
                vo.setDeviceStatus(device.getStatus());
                vo.setDeviceStatusLabel(DeviceStatus.labelOf(device.getStatus()));
                vo.setLabel(optionLabel(device, null, null));
                options.add(vo);
            }
        }
        return options;
    }

    // ------------------------------------------------------------------
    // 第 10 项两种填报模式（V57）
    // ------------------------------------------------------------------

    @Override
    public List<FaultDeviceLookupVO> ownedDevices() {
        Long currentUserId = requireCurrentUserId();
        // 「我的设备」= 两类的并集，少查任何一类都会让人找不到自己手上那台：
        //   · owner_id = 我 —— 我是这台资产的负责人（长期固定，由资产管理员维护）；
        //   · 我在用的设备 —— 我有一笔「使用中」的借用单。这类设备的负责人通常是 IT，
        //     不是我。只按 owner_id 查，普通员工看到的是一个空列表 —— 报修入口等于不存在。
        Map<Long, Order> myBorrowed = myBorrowedOrders(currentUserId);
        LambdaQueryWrapper<Device> wrapper = Wrappers.lambdaQuery();
        if (myBorrowed.isEmpty()) {
            wrapper.eq(Device::getOwnerId, currentUserId);
        } else {
            wrapper.and(w -> w.eq(Device::getOwnerId, currentUserId)
                    .or().in(Device::getId, myBorrowed.keySet()));
        }
        List<Device> devices = deviceMapper.selectList(wrapper.orderByAsc(Device::getDeviceName));
        if (devices.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = categoryNameMap(devices);
        return devices.stream()
                .map(device -> toLookup(device, myBorrowed.get(device.getId()), categoryNames))
                .toList();
    }

    @Override
    public FaultDeviceLookupVO lookupByAssetNo(String assetNo) {
        // 先取当前用户：本接口的「能不能报」判定依赖「我是不是这台的使用中借用人」
        Long currentUserId = requireCurrentUserId();
        String trimmed = assetNo == null ? null : assetNo.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return notFound();
        }
        Device device = deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                .eq(Device::getAssetNo, trimmed)
                .last("LIMIT 1"));
        if (device == null) {
            return notFound();
        }
        Map<Long, String> categoryNames = categoryNameMap(List.of(device));
        Order myBorrowed = myBorrowedOrders(currentUserId).get(device.getId());
        FaultDeviceLookupVO vo = toLookup(device, myBorrowed, categoryNames);
        vo.setFound(true);
        return vo;
    }

    /** 查不到资产编号时的返回体（不抛错：输错编号是这个入口的日常动作） */
    private FaultDeviceLookupVO notFound() {
        FaultDeviceLookupVO vo = new FaultDeviceLookupVO();
        vo.setFound(false);
        vo.setReportable(false);
        return vo;
    }

    /**
     * 「当前用户正处在使用中的那些设备」—— key 为设备 id。
     *
     * <p>过滤条件不含「全部使用中」，只留 {@code applicant_id = 我}：报修带 orderId
     * 会走工单路径，而服务端只允许该工单借用人（或管理员）关联。查全再判断等于
     * 让前端拿到一个「看起来能关联、提交必被拒」的 orderId。
     */
    private Map<Long, Order> myBorrowedOrders(Long currentUserId) {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getId, Order::getOrderNo, Order::getDeviceId, Order::getApplicantId)
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .eq(Order::getApplicantId, currentUserId));
        return orders.stream()
                .filter(order -> order.getDeviceId() != null)
                // 同一台设备理论上只会有一笔使用中工单；万一有脏数据，取 id 最大的那笔（最新）
                .collect(Collectors.toMap(Order::getDeviceId, Function.identity(), (a, b) -> a.getId() >= b.getId() ? a : b));
    }

    /** 分类名映射（一次查库，避免逐台查）；分类缺失时返回空 map，VO 里对应字段为空 */
    private Map<Long, String> categoryNameMap(Collection<Device> devices) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Device device : devices) {
            if (device.getPrimaryCategoryId() != null) {
                ids.add(device.getPrimaryCategoryId());
            }
            if (device.getSecondaryCategoryId() != null) {
                ids.add(device.getSecondaryCategoryId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .filter(category -> category.getCategoryName() != null)
                .collect(Collectors.toMap(DeviceCategory::getId, DeviceCategory::getCategoryName, (a, b) -> a));
    }

    /**
     * 从分类名映射里取名字。
     *
     * <p>存在的唯一理由：{@link #categoryNameMap} 在「一个分类 id 都没有」时返回 {@code Map.of()}，
     * 而<b>不可变集合的 {@code get(null)} 会抛 NPE</b>（HashMap 不会，所以这个坑只在空表时出现）。
     * 未归类的设备恰好就是这种情况 —— 不打这个补丁，接口会对着一条正常数据 500。
     */
    private String categoryNameOf(Map<Long, String> names, Long categoryId) {
        return categoryId == null ? null : names.get(categoryId);
    }

    /**
     * 设备 → 报修判定（两个填报模式共用的唯一口径）。
     *
     * <p>可报修只有两种情形：
     * <ol>
     *   <li><b>设备可用</b> ⇒ 走台账登记路径（无 orderId），报修后设备 可用 → 维修中；</li>
     *   <li><b>设备使用中且正是我在用</b> ⇒ 走工单路径（带 orderId），设备保持使用中。</li>
     * </ol>
     * <p>其余一律不可报，并给出**能照着做**的原因：被别人用着的要去找使用人，
     * 已报废/丢失的要走资产处置 —— 「不可报修」四个字本身没有任何指导意义。
     */
    private FaultDeviceLookupVO toLookup(Device device, Order myBorrowedOrder, Map<Long, String> categoryNames) {
        FaultDeviceLookupVO vo = new FaultDeviceLookupVO();
        vo.setDeviceId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setAssetNo(device.getAssetNo());
        // 分类 id 允许为空（历史台账可能没归类），而 categoryNameMap 在「一个 id 都没有」时
        // 返回的是 Map.of() —— **不可变集合不接受 null 键，get(null) 直接抛 NPE**。
        // 这里必须先用 null 短路，否则「没归类的设备」会让整个下拉接口 500
        // （toVO 里同类踩过一次，见其 orderId 处的注释）。
        vo.setPrimaryCategoryName(categoryNameOf(categoryNames, device.getPrimaryCategoryId()));
        vo.setSecondaryCategoryName(categoryNameOf(categoryNames, device.getSecondaryCategoryId()));
        vo.setDeviceStatus(device.getStatus());
        vo.setDeviceStatusLabel(DeviceStatus.labelOf(device.getStatus()));

        DeviceStatus status = DeviceStatus.of(device.getStatus());
        if (status == DeviceStatus.AVAILABLE) {
            vo.setReportable(true);
            return vo;
        }
        if (status == DeviceStatus.IN_USE) {
            if (myBorrowedOrder != null) {
                vo.setOrderId(myBorrowedOrder.getId());
                vo.setOrderNo(myBorrowedOrder.getOrderNo());
                vo.setReportable(true);
                return vo;
            }
            vo.setReason("该设备正在被他人使用，请让实际使用人从「提交申请」页报修（会关联到他的借用单）");
            return vo;
        }
        if (status == DeviceStatus.MAINTENANCE) {
            vo.setReason("该设备已在维修中，无需重复报修");
            return vo;
        }
        if (status == DeviceStatus.SCRAPPED) {
            vo.setReason("该设备已报废，无需报修");
            return vo;
        }
        if (status == DeviceStatus.LOST) {
            vo.setReason("该设备已标记丢失，请找资产管理员处理");
            return vo;
        }
        // LOCKED（历史遗留）/ IN_APPROVAL：都在流程中间态，交给流程走完
        vo.setReason("该设备正在流程处理中（" + DeviceStatus.labelOf(device.getStatus()) + "），请稍后再试");
        return vo;
    }

    /** 下拉展示文案：设备名（资产编号）；工单路径追加「· 使用中：申请人」 */
    private String optionLabel(Device device, Order order, String applicantName) {        StringBuilder sb = new StringBuilder(device.getDeviceName() == null ? "" : device.getDeviceName());
        if (device.getAssetNo() != null && !device.getAssetNo().isBlank()) {
            sb.append("（").append(device.getAssetNo()).append('）');
        }
        if (order != null) {
            sb.append(" · ").append(OrderStatus.labelOf(order.getStatus()));
            if (applicantName != null) {
                sb.append("：").append(applicantName);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 视图装配
    // ------------------------------------------------------------------

    /**
     * 故障视图：批量加载设备 / 工单 / 用户字典，避免 N+1。
     *
     * @param devices 预取设备映射；为 null 时按本条记录自行查询
     * @param orders  预取工单映射；为 null 时按本条记录自行查询
     * @param userNames 预取姓名映射；为 null 时按本条记录自行查询
     */
    private DeviceFaultVO toVO(DeviceFault fault, Map<Long, Device> devices,
                               Map<Long, Order> orders, Map<Long, String> userNames,
                               Map<Long, FormSchema> schemas) {
        Device device = devices == null
                ? deviceMapper.selectById(fault.getDeviceId())
                : devices.get(fault.getDeviceId());
        // orderId 允许为空（台账直接登记、无关联工单）：必须先用空值短路，
        // 否则对不可变 Map（Map.of()）调用 get(null) 会抛 NPE（Java 不可变集合不允许 null 键）。
        Order order = fault.getOrderId() == null
                ? null
                : (orders == null ? orderMapper.selectById(fault.getOrderId()) : orders.get(fault.getOrderId()));
        // userNames 为空时按本条记录现查：不能写 List.of(reporterId, handledBy) ——
        // 待维修记录的 handledBy 为空，而 List.of 不接受 null 元素（会抛 NPE）。
        // 改用 null 容忍的 reporterAndHandlerIds(...)（内部为 LinkedHashSet）承载，再由 userNameMap 过滤空值。
        Map<Long, String> names = userNames == null
                ? userNameMap(reporterAndHandlerIds(List.of(fault)))
                : userNames;

        DeviceFaultVO vo = new DeviceFaultVO();
        vo.setId(fault.getId());
        vo.setDeviceId(fault.getDeviceId());
        vo.setDeviceName(device == null ? null : device.getDeviceName());
        vo.setAssetNo(device == null ? null : device.getAssetNo());
        vo.setDeviceStatus(device == null ? null : device.getStatus());
        vo.setDeviceStatusLabel(device == null ? null : DeviceStatus.labelOf(device.getStatus()));
        vo.setOrderId(fault.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setReporterId(fault.getReporterId());
        vo.setReporterName(names.get(fault.getReporterId()));
        vo.setFaultDescription(fault.getFaultDescription());
        vo.setOccurredAt(fault.getOccurredAt());
        vo.setStatus(fault.getStatus());
        vo.setStatusLabel(FaultStatus.labelOf(fault.getStatus()));
        vo.setHandledBy(fault.getHandledBy());
        vo.setHandledByName(names.get(fault.getHandledBy()));
        vo.setHandledAt(fault.getHandledAt());
        vo.setHandleRemark(fault.getHandleRemark());
        vo.setRepairerId(fault.getRepairerId());
        vo.setRepairerName(names.get(fault.getRepairerId()));
        vo.setRepairCost(fault.getRepairCost());
        vo.setReplacedParts(fault.getReplacedParts());
        // V59：自定义字段。版本指针 + 值 + 当初那一版 schema 三者一起下发，
        // 前端才做得「label → 值」的回显；缺 schema 就只能显示 key，等于没回显。
        vo.setFormTemplateVersionId(fault.getFormTemplateVersionId());
        vo.setFormData(fault.getFormDataJson() == null
                ? Map.of() : FormSchemaCodec.readData(fault.getFormDataJson()));
        vo.setFormSchema(fault.getFormTemplateVersionId() == null
                ? null : schemas.get(fault.getFormTemplateVersionId()));
        vo.setCreatedAt(fault.getCreatedAt());
        return vo;
    }

    /**
     * 批量取「故障记录当初用的那一版」表单 schema（V59）。
     *
     * <p>按版本 id 去重后一次查完，避免逐行 N+1 —— 故障列表最多 100 行，
     * 而同一批记录大概率共用同一个版本（都是同一个内置报修类型）。
     */
    private Map<Long, FormSchema> formSchemasById(Collection<DeviceFault> faults) {
        Set<Long> versionIds = faults.stream()
                .map(DeviceFault::getFormTemplateVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FormSchema> result = new HashMap<>();
        for (Long versionId : versionIds) {
            try {
                result.put(versionId, formTemplateService.requirePublishedSchema(versionId));
            } catch (BusinessException ex) {
                // 版本被删 / 未发布是配置问题，不该让整张故障列表打不开：
                // 这条记录的 schema 留空，前端按「无自定义字段」渲染，其余记录正常。
                log.warn("故障记录引用的表单版本不可用，跳过 schema 加载 versionId={} msg={}",
                        versionId, ex.getMessage());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 台账级设备筛选：先按名称/资产编号查出 id 集合再过滤，不拼字符串 SQL */
    private Set<Long> matchDeviceIds(String keyword) {
        if (keyword == null) {
            return Set.of();
        }
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .select(Device::getId)
                        .and(w -> w.like(Device::getDeviceName, keyword).or().like(Device::getAssetNo, keyword)))
                .stream()
                .map(Device::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 批量加载故障涉及的设备（按设备 id 去重），避免逐行查询 */
    private Map<Long, Device> devicesById(Collection<DeviceFault> faults) {
        Set<Long> deviceIds = faults.stream()
                .map(DeviceFault::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity(), (a, b) -> a));
    }

    /** 按设备 id 集合批量加载设备（去重、过滤空值），供可选设备下拉复用 */
    private Map<Long, Device> devicesByIds(Collection<Long> rawIds) {
        Set<Long> deviceIds = rawIds == null ? Set.of() : rawIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 批量加载故障关联的工单（按 orderId 去重），避免逐行查询。
     *
     * <p>无关联工单（台账直接登记）时返回空表；此时 {@code toVO} 内对 {@code orderId == null}
     * 已先行短路，不会对空表做 {@code get(null)}。
     */
    private Map<Long, Order> ordersById(Collection<DeviceFault> faults) {
        Set<Long> orderIds = faults.stream()
                .map(DeviceFault::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity(), (a, b) -> a));
    }

    private Set<Long> reporterAndHandlerIds(Collection<DeviceFault> faults) {
        Set<Long> ids = new LinkedHashSet<>();
        faults.forEach(fault -> {
            ids.add(fault.getReporterId());
            ids.add(fault.getHandledBy());
            // P2：维修人也要解析展示名；漏了它，列表里的「维修人」会永远是空 ——
            // 而这类「字段加了但没纳入批量解析」的漏，接口返回 null 不会报错，最难发现。
            ids.add(fault.getRepairerId());
        });
        return ids;
    }

    private Map<Long, String> userNameMap(Collection<Long> userIds) {
        Collection<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    private DeviceFault requireOpenFault(Long faultId) {
        DeviceFault fault = faultId == null ? null : faultMapper.selectById(faultId);
        if (fault == null) {
            throw new BusinessException(ErrorCode.FAULT_NOT_FOUND);
        }
        // V50：判据改成「是否还在处理流程里」而不是「是否等于待处理」。
        // 加了「处理中」之后，若仍按等值判，已接单的记录会变得谁也不能再操作
        // （既不能标修完，也不能标无法修复），而且提示是「已是『处理中』，不可重复操作」——
        // 一句看起来合理、实际把维修人锁死的话。
        FaultStatus current = FaultStatus.of(fault.getStatus());
        if (current == null || !current.isOpen()) {
            throw new BusinessException(ErrorCode.FAULT_ALREADY_HANDLED,
                    "该故障记录已是「" + FaultStatus.labelOf(fault.getStatus()) + "」，不可重复操作");
        }
        return fault;
    }

    private Device requireDevice(Long deviceId) {
        Device device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(ErrorCode.DEVICE_NOT_FOUND);
        }
        return device;
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private void requireAdmin() {
        if (!RoleCode.isAdminOrAbove(currentRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员及以上角色可操作设备故障记录");
        }
    }

    /**
     * 该用户是否**经手过**报修（即曾经接过单）。
     *
     * <p>用途只有一个：让「接了单、后来『处理流程』配置被改走」的人仍能进列表把单收掉。
     * 若不放行，那条单会卡在「处理中」——接单的人打不开页面，
     * 而新配置里的人又不是接单人（看不到它）。同一取向见 {@code requireAdminOrRepairer}。
     */
    private boolean hasHandledFault(Long userId) {
        if (userId == null) {
            return false;
        }
        return faultMapper.selectOne(Wrappers.<DeviceFault>lambdaQuery()
                .eq(DeviceFault::getHandledBy, userId)
                .last("LIMIT 1")) != null;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
