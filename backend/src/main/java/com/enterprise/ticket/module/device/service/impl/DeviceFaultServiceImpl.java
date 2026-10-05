package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.FaultStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceFaultService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;

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
            Long id = insert(device, orderId, currentUserId, request, now);
            log.info("工单 {} 上报设备 {} 故障，记录 id={}（设备保持使用中）",
                    order.getOrderNo(), device.getId(), id);
            return id;
        }

        // 台账直接登记：仅管理员 / 最终处理人；设备必须处于「可用」
        if (!RoleCode.isAdminOrAbove(role)) {
            throw new BusinessException(ErrorCode.FAULT_REPORTER_NOT_ALLOWED,
                    "无工单故障登记仅管理员或最终处理人可操作");
        }
        if (deviceStatus != DeviceStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus())
                            + "」，仅「可用」设备可直接登记故障（使用中的设备请从工单上报）");
        }
        changeDeviceStatus(device, DeviceStatus.AVAILABLE, DeviceStatus.MAINTENANCE);
        Long id = insert(device, null, currentUserId, request, now);
        log.info("台账直接登记设备 {} 故障，记录 id={}，设备 可用 → 维修中", device.getId(), id);
        return id;
    }

    private Long insert(Device device, Long orderId, Long reporterId, DeviceFaultRequest request,
                        LocalDateTime fallbackOccurredAt) {
        DeviceFault fault = new DeviceFault();
        fault.setDeviceId(device.getId());
        fault.setOrderId(orderId);
        fault.setReporterId(reporterId);
        fault.setFaultDescription(request.getFaultDescription().trim());
        // 采用用户填报的「故障发生时间」；缺失时才用登记时刻兜底（否则上报时间会退化成登记时间）
        fault.setOccurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : fallbackOccurredAt);
        fault.setStatus(FaultStatus.PENDING_REPAIR.name());
        faultMapper.insert(fault);
        return fault.getId();
    }

    // ------------------------------------------------------------------
    // 处理
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRepaired(Long faultId, DeviceFaultHandleRequest request) {
        requireAdmin();
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
                .eq(DeviceFault::getStatus, FaultStatus.PENDING_REPAIR.name())
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
                .eq(DeviceFault::getStatus, FaultStatus.PENDING_REPAIR.name())
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
        requireAdmin();
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

        IPage<DeviceFault> resultPage = faultMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<DeviceFault>lambdaQuery()
                        .eq(status != null, DeviceFault::getStatus, status)
                        .in(deviceKeyword != null, DeviceFault::getDeviceId, deviceIds)
                        .orderByDesc(DeviceFault::getId));
        List<DeviceFault> records = resultPage.getRecords();
        Map<Long, Device> devices = devicesById(records);
        Map<Long, Order> orders = ordersById(records);
        Map<Long, String> userNames = userNameMap(reporterAndHandlerIds(records));
        return PageResult.of(resultPage, fault -> toVO(fault, devices, orders, userNames));
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
        return faults.stream().map(fault -> toVO(fault, devices, orders, userNames)).toList();
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

    /** 下拉展示文案：设备名（资产编号）；工单路径追加「· 使用中：申请人」 */
    private String optionLabel(Device device, Order order, String applicantName) {
        StringBuilder sb = new StringBuilder(device.getDeviceName() == null ? "" : device.getDeviceName());
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
                               Map<Long, Order> orders, Map<Long, String> userNames) {
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
        vo.setCreatedAt(fault.getCreatedAt());
        return vo;
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
        if (!FaultStatus.PENDING_REPAIR.name().equals(fault.getStatus())) {
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
