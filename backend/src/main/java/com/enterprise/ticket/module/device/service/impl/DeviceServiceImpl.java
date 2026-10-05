package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.BatchLimits;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.device.dto.DeviceBatchCategoryRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceLockVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceUsageVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 设备台账实现（ /  / ）
 *
 * <p>关键约束：
 * <ul>
 *   <li>资产编号<b>全局唯一</b>且<b>不可复用</b>：唯一性校验按物理行进行（含已软删除设备），
 *       因此走 {@link DeviceMapper#selectAnyIdByAssetNo} 的显式 SQL，而非会被逻辑删除过滤的 count 查询；
 *       预检之后仍可能被并发写入抢先，故写操作再捕获 {@link DuplicateKeyException} 兜底为同一错误码
 *       （否则数据库唯一索引报错会变成 500 INTERNAL_ERROR）；</li>
 *   <li>管理员手动状态变更只认「报废 / 维修完成」两类，其余流转由工单模块驱动，
 *       避免台账界面成为绕过状态机的后门；操作理由只进审计日志，不写回设备备注；</li>
 *   <li>软删除用 {@code deleteById}（{@code @TableLogic} 自动改写成 UPDATE deleted = 1）；</li>
 *   <li><b>临时锁</b>：写操作全部带状态前置条件（乐观并发），
 *       并发下只有一个请求能把 AVAILABLE 改成 LOCKED；释放必须比对 {@code lockToken}，
 *       防止旧页面释放后来属于其他用户的新锁。</li>
 * </ul>
 *
 * <p><b>跨模块约定</b>：本类读取工单占用信息时直接注入 {@link OrderMapper}，
 * 而不是 OrderService —— 跨模块调用走 Mapper 可避免 Service 之间形成循环依赖
 * （工单模块同样需要读设备表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    /** 单页上限，与 OperationLogController 保持一致的防护口径 */
    private static final long MAX_PAGE_SIZE = 200L;

    /**
     * 仍占用设备的工单状态（非终态）
     *
     * <p>由 {@link OrderStatus#isOccupiesDevice()} 派生，新增状态时无需手工维护两份清单。
     */
    private static final List<String> OCCUPYING_ORDER_STATUSES = Arrays.stream(OrderStatus.values())
            .filter(OrderStatus::isOccupiesDevice)
            .map(Enum::name)
            .toList();

    private final DeviceMapper deviceMapper;
    private final DeviceCategoryMapper categoryMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;

    @Override
    public PageResult<DeviceVO> page(long page, long size, String keyword,
                                     Long primaryCategoryId, Long secondaryCategoryId, String status) {
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);

        String trimmedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        String normalizedStatus = StringUtils.hasText(status) ? status.trim() : null;
        if (normalizedStatus != null && !DeviceStatus.isValid(normalizedStatus)) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_INVALID, "设备状态筛选值不合法：" + normalizedStatus);
        }

        IPage<Device> query = new Page<>(safePage, safeSize);
        IPage<Device> resultPage = deviceMapper.selectPage(query, Wrappers.<Device>lambdaQuery()
                .eq(primaryCategoryId != null, Device::getPrimaryCategoryId, primaryCategoryId)
                .eq(secondaryCategoryId != null, Device::getSecondaryCategoryId, secondaryCategoryId)
                .eq(normalizedStatus != null, Device::getStatus, normalizedStatus)
                .and(trimmedKeyword != null, wrapper -> wrapper
                        .like(Device::getDeviceName, trimmedKeyword)
                        .or().like(Device::getAssetNo, trimmedKeyword)
                        .or().like(Device::getSerialNo, trimmedKeyword))
                .orderByDesc(Device::getId));

        Map<Long, String> categoryNames = categoryNameMap();
        Map<Long, DeviceUsageVO> usages = usageMap(resultPage.getRecords());
        Map<Long, String> lockHolders = lockHolderNameMap(resultPage.getRecords());
        return PageResult.of(resultPage, device -> toVO(device, categoryNames,
                usages.get(device.getId()), lockHolders.get(device.getId())));
    }

    @Override
    public DeviceVO getDetail(Long id) {
        Device device = requireDevice(id);
        List<Device> single = List.of(device);
        return toVO(device, categoryNameMap(), usageMap(single).get(id), lockHolderNameMap(single).get(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DeviceSaveRequest request) {
        CategoryRef ref = assertCreatable(request);
        String assetNo = request.getAssetNo().trim();

        Device device = new Device();
        device.setDeviceName(request.getDeviceName().trim());
        device.setAssetNo(assetNo);
        device.setPrimaryCategoryId(ref.primary().getId());
        device.setSecondaryCategoryId(ref.secondary() == null ? null : ref.secondary().getId());
        device.setBrand(trimToNull(request.getBrand()));
        device.setModel(trimToNull(request.getModel()));
        device.setSerialNo(trimToNull(request.getSerialNo()));
        device.setStorageLocation(trimToNull(request.getStorageLocation()));
        device.setPurchaseDate(request.getPurchaseDate());
        // ：设备金额（选填）。原样落库，不做「空字符串转 0」的兜底 ——
        // NULL 与 0 在审批分档里语义不同（未录入 ⇒ 走三级；金额 0 ⇒ 也是小额），
        // 但把 NULL 偷偷变成 0 会让「还没录金额的设备」在台账里查不出来。
        device.setAmount(request.getAmount());
        // ：新设备一律从「可用」起步，状态变更只能走状态操作接口或后续工单流程
        device.setStatus(DeviceStatus.AVAILABLE.name());
        device.setRemark(trimToNull(request.getRemark()));
        device.setDeleted(false);
        try {
            deviceMapper.insert(device);
        } catch (DuplicateKeyException e) {
            // 预检通过后被并发请求抢先写入：由唯一索引 uk_device_asset_no 兜底，转成 错误码
            throw duplicateAssetNo(assetNo);
        }
        log.info("设备已创建 id={} assetNo={}", device.getId(), assetNo);
        return device.getId();
    }

    @Override
    public void validateNewDevice(DeviceSaveRequest request) {
        assertCreatable(request);
    }

    /**
     * 「新增设备」的业务规则（资产编号唯一 + 分类存在性与归属）。
     *
     * <p>{@link #create(DeviceSaveRequest)} 与 {@link #validateNewDevice(DeviceSaveRequest)}
     * 共用本方法，保证「单条新增」与「批量导入预览」的判定完全一致。
     *
     * @return 解析后的分类引用，供调用方直接复用，避免重复查库
     */
    private CategoryRef assertCreatable(DeviceSaveRequest request) {
        String assetNo = request.getAssetNo() == null ? "" : request.getAssetNo().trim();
        if (assetNo.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "资产编号不能为空");
        }
        assertAssetNoAvailable(assetNo, null);
        return resolveCategories(request.getPrimaryCategoryId(), request.getSecondaryCategoryId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DeviceSaveRequest request) {
        requireDevice(id);
        String assetNo = request.getAssetNo().trim();
        assertAssetNoAvailable(assetNo, id);
        CategoryRef ref = resolveCategories(request.getPrimaryCategoryId(), request.getSecondaryCategoryId());

        // 显式 set 才能把可空字段写回 NULL（实体式 updateById 受 update-strategy=not_null 影响会跳过 null）
        LambdaUpdateWrapper<Device> update = Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, id)
                .set(Device::getDeviceName, request.getDeviceName().trim())
                .set(Device::getAssetNo, assetNo)
                .set(Device::getPrimaryCategoryId, ref.primary().getId())
                .set(Device::getSecondaryCategoryId, ref.secondary() == null ? null : ref.secondary().getId())
                .set(Device::getBrand, trimToNull(request.getBrand()))
                .set(Device::getModel, trimToNull(request.getModel()))
                .set(Device::getSerialNo, trimToNull(request.getSerialNo()))
                .set(Device::getStorageLocation, trimToNull(request.getStorageLocation()))
                .set(Device::getPurchaseDate, request.getPurchaseDate())
                .set(Device::getAmount, request.getAmount())
                .set(Device::getRemark, trimToNull(request.getRemark()));
        try {
            deviceMapper.update(null, update);
        } catch (DuplicateKeyException e) {
            throw duplicateAssetNo(assetNo);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long id) {
        requireDevice(id);
        // @TableLogic：deleteById 实际执行 UPDATE device SET deleted = 1
        deviceMapper.deleteById(id);
        log.info("设备 id={} 已软删除（记录保留，资产编号不可复用）", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, DeviceStatusRequest request) {
        Device device = requireDevice(id);
        DeviceStatus from = DeviceStatus.of(device.getStatus());
        DeviceStatus to = DeviceStatus.of(request.getTargetStatus());
        if (to == null) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_INVALID,
                    "设备状态取值不合法：" + request.getTargetStatus());
        }
        // ：使用中（IN_USE）的设备禁止直接报废，必须先归还或走异常结案流程
        if (from == DeviceStatus.IN_USE && to == DeviceStatus.SCRAPPED) {
            throw new BusinessException(ErrorCode.DEVICE_IN_BORROWED, "设备正在使用中，禁止报废，请先归还设备");
        }
        if (!DeviceStatus.canManualTransfer(from, to)) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "当前状态「" + (from == null ? device.getStatus() : from.getLabel())
                            + "」不允许手动变更为「" + to.getLabel() + "」");
        }
        // 只改 status：操作理由（request.reason）由 @AuditLog(risk = HIGH) 的入参摘要留痕，
        // 不能写进 device.remark，否则会覆盖设备台账自身的备注
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, id)
                .set(Device::getStatus, to.name()));
        log.info("设备 id={} 状态变更 {} → {}，理由={}", id, device.getStatus(), to.name(), request.getReason());
    }

    // ------------------------------------------------------------------
    // 临时锁
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceLockVO lock(Long deviceId) {
        Long currentUserId = requireCurrentUserId();
        Device device = requireDevice(deviceId);
        DeviceStatus status = DeviceStatus.of(device.getStatus());
        if (status == null) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_INVALID, "设备状态数据异常：" + device.getStatus());
        }

        int timeoutMinutes = systemConfigService.lockTimeoutMinutes();
        LocalDateTime now = LocalDateTime.now();

        if (status == DeviceStatus.LOCKED) {
            boolean expired = isLockExpired(device, timeoutMinutes, now);
            boolean mine = Objects.equals(device.getLockedBy(), currentUserId);
            if (!expired) {
                if (mine) {
                    // 本人重复点同一设备：返回原令牌并顺延计时，避免用户切换设备再来回点就报错
                    return new DeviceLockVO(device.getId(), device.getDeviceName(), device.getLockToken(),
                            device.getLockedAt(), device.getLockedAt().plusMinutes(timeoutMinutes), timeoutMinutes);
                }
                throw new BusinessException(ErrorCode.DEVICE_UNAVAILABLE,
                        "该设备正被其他同事填写申请，请稍后再试或选择其它设备");
            }
            // 已超时：允许接管（「超时 LOCKED → AVAILABLE」，此处直接原地接管，避免中间态）
            log.info("设备 id={} 的临时锁已超时（lockedBy={}），由 user={} 接管",
                    deviceId, device.getLockedBy(), currentUserId);
        } else if (status != DeviceStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.DEVICE_UNAVAILABLE,
                    "设备当前状态为「" + status.getLabel() + "」，不可申请");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        // 条件更新：前置条件 = 读取到的状态 + 读取到的令牌。
        //
        // 带令牌条件是让「超时锁接管」也具备原子性：若仅以状态为前置条件，
        // 两个用户同时接管同一个已超时的锁会**都**更新成功（后写覆盖前写），
        // 双方都收到「锁定成功」，直到其中一方提交申请时才被令牌校验拒绝 ——
        // 感知与语义都不正确。加上令牌条件后，只有一个请求能改到该行（受影响行数 = 1），
        // 另一个得到「设备刚被其他同事锁定」（ / ）。
        //
        // 注意：AVAILABLE 设备的 lock_token 为 NULL，而 SQL 里 `lock_token = NULL` 恒不成立，
        // 因此按旧令牌是否为空分别用 isNull / eq 生成条件。
        LambdaUpdateWrapper<Device> claim = Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, deviceId)
                .eq(Device::getStatus, device.getStatus());
        if (device.getLockToken() == null) {
            claim.isNull(Device::getLockToken);
        } else {
            claim.eq(Device::getLockToken, device.getLockToken());
        }
        int updated = deviceMapper.update(null, claim
                .set(Device::getStatus, DeviceStatus.LOCKED.name())
                .set(Device::getLockedBy, currentUserId)
                .set(Device::getLockedAt, now)
                .set(Device::getLockToken, token));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.DEVICE_UNAVAILABLE, "设备刚被其他同事锁定，请重新选择设备");
        }
        log.info("设备 id={} 已被 user={} 临时锁定，Timeout={} 分钟", deviceId, currentUserId, timeoutMinutes);
        return new DeviceLockVO(deviceId, device.getDeviceName(), token, now,
                now.plusMinutes(timeoutMinutes), timeoutMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlock(Long deviceId, String lockToken) {
        Long currentUserId = requireCurrentUserId();
        Device device = requireDevice(deviceId);
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.LOCKED) {
            // 幂等：锁已被提交/超时释放时不再报错，否则前端在页面卸载钩子里释放会误弹提示
            log.debug("设备 id={} 当前非锁定态（{}），跳过释放", deviceId, device.getStatus());
            return;
        }
        if (!Objects.equals(device.getLockToken(), lockToken) || !Objects.equals(device.getLockedBy(), currentUserId)) {
            // ：令牌不匹配说明当前锁属于别人的新一轮锁定，绝不能释放
            throw new BusinessException(ErrorCode.DEVICE_LOCK_TOKEN_INVALID);
        }
        clearLock(deviceId, DeviceStatus.LOCKED.name());
        log.info("设备 id={} 的临时锁已由 user={} 释放", deviceId, currentUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceUnlock(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.LOCKED) {
            throw new BusinessException(ErrorCode.DEVICE_NOT_LOCKED);
        }
        clearLock(deviceId, DeviceStatus.LOCKED.name());
        log.warn("设备 id={} 的临时锁被管理员强制解除（原持有人 lockedBy={}）", deviceId, device.getLockedBy());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseExpiredLocks() {
        int timeoutMinutes = systemConfigService.lockTimeoutMinutes();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
        return clearLockBatch(threshold);
    }

    /** 清空临时锁并把设备放回 AVAILABLE（单设备，带状态前置条件） */
    private void clearLock(Long deviceId, String expectedStatus) {
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, deviceId)
                .eq(Device::getStatus, expectedStatus)
                .set(Device::getStatus, DeviceStatus.AVAILABLE.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
    }

    /** 批量释放超时锁（定时任务，「临时锁超时释放，每 5 分钟」） */
    private int clearLockBatch(LocalDateTime threshold) {
        return deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getStatus, DeviceStatus.LOCKED.name())
                .lt(Device::getLockedAt, threshold)
                .set(Device::getStatus, DeviceStatus.AVAILABLE.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
    }

    /** 临时锁是否已超时；lockedAt 缺失视为已失效（脏数据不应永久占位） */
    private boolean isLockExpired(Device device, int timeoutMinutes, LocalDateTime now) {
        if (device.getLockedAt() == null) {
            return true;
        }
        return device.getLockedAt().plusMinutes(timeoutMinutes).isBefore(now);
    }

    /** 超时时间点；lockedAt 为空时返回 null（前端不显示倒计时） */
    private LocalDateTime lockExpiresAt(Device device, int timeoutMinutes) {
        return device.getLockedAt() == null ? null : device.getLockedAt().plusMinutes(timeoutMinutes);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Device requireDevice(Long id) {
        Device device = id == null ? null : deviceMapper.selectById(id);
        if (device == null) {
            throw new BusinessException(ErrorCode.DEVICE_NOT_FOUND);
        }
        return device;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 资产编号唯一性校验。
     *
     * <p>按物理行判断（含已软删除设备）： 要求资产编号全局唯一、历史设备必须保留，
     * 若旧设备软删除后编号可被复用，历史工单追溯将指向错误的设备。
     */
    private void assertAssetNoAvailable(String assetNo, Long selfId) {
        Long existingId = deviceMapper.selectAnyIdByAssetNo(assetNo);
        if (existingId != null && !existingId.equals(selfId)) {
            throw duplicateAssetNo(assetNo);
        }
    }

    private BusinessException duplicateAssetNo(String assetNo) {
        return new BusinessException(ErrorCode.DEVICE_ASSET_NO_EXISTS, "资产编号「" + assetNo + "」已存在，请更换");
    }

    /** 解析并校验分类挂接：一级必填且为 level=1；二级可空且必须归属所选一级 */
    private CategoryRef resolveCategories(Long primaryCategoryId, Long secondaryCategoryId) {
        DeviceCategory primary = primaryCategoryId == null ? null : categoryMapper.selectById(primaryCategoryId);
        if (primary == null) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_NOT_FOUND, "所选一级分类不存在，请刷新后重试");
        }
        if (primary.getLevel() == null || primary.getLevel() != DeviceCategory.LEVEL_PRIMARY) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_LEVEL_INVALID, "所选一级分类层级不正确");
        }
        if (secondaryCategoryId == null) {
            return new CategoryRef(primary, null);
        }
        DeviceCategory secondary = categoryMapper.selectById(secondaryCategoryId);
        if (secondary == null) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_NOT_FOUND, "所选二级分类不存在，请刷新后重试");
        }
        if (secondary.getLevel() == null || secondary.getLevel() != DeviceCategory.LEVEL_SECONDARY) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_LEVEL_INVALID, "所选二级分类层级不正确");
        }
        if (!Objects.equals(secondary.getParentId(), primary.getId())) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_MISMATCH);
        }
        return new CategoryRef(primary, secondary);
    }

    /** 分类ID → 名称（一次查询，避免逐行查库造成 N+1） */
    private Map<Long, String> categoryNameMap() {
        return categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery()
                        .select(DeviceCategory::getId, DeviceCategory::getCategoryName))
                .stream()
                .collect(Collectors.toMap(DeviceCategory::getId, DeviceCategory::getCategoryName, (a, b) -> a));
    }

    /**
     * 批量构建「设备 → 当前使用信息」映射（需求方 ）
     *
     * <p>一次查询搞定整页，避免逐行查工单造成 N+1。设备表不冗余使用人信息，
     * 使用信息始终以「占用中的工单」为唯一事实来源。
     */
    private Map<Long, DeviceUsageVO> usageMap(Collection<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            return Map.of();
        }
        List<Long> deviceIds = devices.stream().map(Device::getId).filter(Objects::nonNull).toList();
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .in(Order::getDeviceId, deviceIds)
                .in(Order::getStatus, OCCUPYING_ORDER_STATUSES)
                .orderByDesc(Order::getId));
        if (orders.isEmpty()) {
            return Map.of();
        }

        // 正常数据下一台设备至多一笔占用工单；若存在历史脏数据（多笔），保留最新一笔，避免相互覆盖
        Map<Long, Order> latestByDevice = new LinkedHashMap<>();
        for (Order order : orders) {
            latestByDevice.putIfAbsent(order.getDeviceId(), order);
        }
        Map<Long, String> userNames = userNameMap(latestByDevice.values().stream()
                .map(Order::getApplicantId).collect(Collectors.toSet()));

        Map<Long, DeviceUsageVO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Order> entry : latestByDevice.entrySet()) {
            Order order = entry.getValue();
            DeviceUsageVO usage = new DeviceUsageVO();
            usage.setOrderId(order.getId());
            usage.setOrderNo(order.getOrderNo());
            usage.setUserId(order.getApplicantId());
            usage.setUserName(userNames.get(order.getApplicantId()));
            usage.setUseType(order.getUseType());
            usage.setUseTypeLabel(UseType.labelOf(order.getUseType()));
            // 到期日仅短期借用展示；长期领用本就无固定归还日期
            usage.setExpectedReturnDate(order.getExpectedReturnDate());
            usage.setOrderStatus(order.getStatus());
            usage.setOrderStatusLabel(OrderStatus.labelOf(order.getStatus()));
            result.put(entry.getKey(), usage);
        }
        return result;
    }

    /** 锁定人姓名（仅 LOCKED 状态的设备需要） */
    private Map<Long, String> lockHolderNameMap(Collection<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            return Map.of();
        }
        Set<Long> holderIds = devices.stream()
                .filter(device -> DeviceStatus.LOCKED.name().equals(device.getStatus()))
                .map(Device::getLockedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return userNameMap(holderIds);
    }

    private Map<Long, String> userNameMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    private DeviceVO toVO(Device device, Map<Long, String> categoryNames,
                          DeviceUsageVO usage, String lockedByName) {
        DeviceVO vo = new DeviceVO();
        vo.setId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setAssetNo(device.getAssetNo());
        vo.setPrimaryCategoryId(device.getPrimaryCategoryId());
        vo.setPrimaryCategoryName(categoryNames.get(device.getPrimaryCategoryId()));
        vo.setSecondaryCategoryId(device.getSecondaryCategoryId());
        vo.setSecondaryCategoryName(device.getSecondaryCategoryId() == null
                ? null : categoryNames.get(device.getSecondaryCategoryId()));
        vo.setBrand(device.getBrand());
        vo.setModel(device.getModel());
        vo.setSerialNo(device.getSerialNo());
        vo.setStorageLocation(device.getStorageLocation());
        vo.setPurchaseDate(device.getPurchaseDate());
        vo.setAmount(device.getAmount());

        DeviceStatus status = DeviceStatus.of(device.getStatus());
        vo.setStatus(device.getStatus());
        vo.setStatusLabel(status == null ? device.getStatus() : status.getLabel());
        vo.setApplicable(status != null && status.isApplicable());

        vo.setUsage(usage);
        if (status == DeviceStatus.LOCKED) {
            vo.setLockedByName(lockedByName);
            vo.setLockExpiresAt(lockExpiresAt(device, systemConfigService.lockTimeoutMinutes()));
        }

        vo.setRemark(device.getRemark());
        vo.setCreatedAt(device.getCreatedAt());
        vo.setUpdatedAt(device.getUpdatedAt());
        return vo;
    }

    // ------------------------------------------------------------------
    // 批量操作（P3）
    // ------------------------------------------------------------------

    @Override
    public BatchResultVO batchChangeStatus(DeviceBatchStatusRequest request) {
        List<Long> ids = distinctIds(request.getIds());
        DeviceStatus target = DeviceStatus.of(request.getTargetStatus());
        if (target == null) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_INVALID,
                    "设备状态取值不合法：" + request.getTargetStatus());
        }

        List<BatchResultVO.Failure> failures = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            try {
                // 逐条复用单条变更的**全部**校验：设备存在 / 取值合法 / 使用中禁报废 / 手工流转白名单。
                //
                // ⚠️ 这里是**自调用**：changeStatus 上的 @Transactional 不生效（未经代理）。
                // 结果恰好就是这里要的语义 —— 每次 UPDATE 各自自动提交，一条失败不连累其它条目。
                // 之所以敢这么用，是因为 changeStatus 只含**一次**写操作，不存在「写了一半」的中间态；
                // 若将来它变成多步写，就必须改成注入自身代理或显式分事务。
                DeviceStatusRequest single = new DeviceStatusRequest();
                single.setTargetStatus(request.getTargetStatus());
                single.setReason(request.getReason());
                changeStatus(id, single);
                succeeded++;
            } catch (BusinessException e) {
                failures.add(failureOf(id, e.getMessage()));
            } catch (RuntimeException e) {
                // 非业务异常（如数据库约束）同样逐条兜住：让它冒出去会中断整批，
                // 用户看到的将是「失败」而不知道前面已改了几台
                failures.add(failureOf(id, "处理失败：" + e.getMessage()));
                log.warn("批量变更状态时设备 id={} 处理失败", id, e);
            }
        }
        log.info("批量变更设备状态 → {}：共 {} 条，成功 {}，失败 {}",
                target.name(), ids.size(), succeeded, failures.size());
        return BatchResultVO.of(ids.size(), succeeded, failures);
    }

    @Override
    public BatchResultVO batchChangeCategory(DeviceBatchCategoryRequest request) {
        List<Long> ids = distinctIds(request.getIds());
        // 分类必须先校验存在：否则会批量写入一个悬空 id，台账列表里这些设备的分类名会变成空白
        DeviceCategory category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_NOT_FOUND, "设备分类不存在");
        }

        List<BatchResultVO.Failure> failures = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            try {
                requireDevice(id);
                // 只 set 主分类。**不要**在这里复用单条 update：那是「整体替换」语义，
                // 批量套用会把几十台设备的品牌 / 型号 / 存放位置一并清空。
                deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                        .eq(Device::getId, id)
                        .set(Device::getPrimaryCategoryId, category.getId()));
                succeeded++;
            } catch (BusinessException e) {
                failures.add(failureOf(id, e.getMessage()));
            } catch (RuntimeException e) {
                failures.add(failureOf(id, "处理失败：" + e.getMessage()));
                log.warn("批量修改分类时设备 id={} 处理失败", id, e);
            }
        }
        log.info("批量修改设备分类 → {}：共 {} 条，成功 {}，失败 {}",
                category.getCategoryName(), ids.size(), succeeded, failures.size());
        return BatchResultVO.of(ids.size(), succeeded, failures);
    }

    /**
     * 去重 + 条目数校验。
     *
     * <p>去重不是洁癖：重复 id 会被处理两次，「成功 3 条」里可能只有 2 台设备，
     * 而用户核对时会以为改了 3 台。
     *
     * <p>超限时<b>先拒绝、一条都不处理</b>（见 {@link BatchLimits}）。
     */
    private List<Long> distinctIds(List<Long> raw) {
        List<Long> ids = (raw == null ? List.<Long>of() : raw).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要操作的记录");
        }
        if (ids.size() > BatchLimits.MAX_SIZE) {
            throw new BusinessException(ErrorCode.BATCH_SIZE_EXCEEDED,
                    "已选择 " + ids.size() + " 条，单次最多 " + BatchLimits.MAX_SIZE + " 条，请分批处理");
        }
        return ids;
    }

    /** 失败明细：设备已不存在时用 id 兜底，避免明细行显示成空白让用户无从定位 */
    private BatchResultVO.Failure failureOf(Long id, String reason) {
        BatchResultVO.Failure failure = new BatchResultVO.Failure();
        failure.setId(id);
        Device device = deviceMapper.selectById(id);
        failure.setName(device == null ? ("id=" + id) : device.getAssetNo());
        failure.setReason(reason);
        return failure;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 分类解析结果：一级必填、二级可空 */
    private record CategoryRef(DeviceCategory primary, DeviceCategory secondary) {
    }
}
