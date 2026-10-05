package com.enterprise.ticket.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.InventoryCheckResult;
import com.enterprise.ticket.common.constant.InventoryScopeType;
import com.enterprise.ticket.common.constant.InventoryStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.inventory.dto.InventoryCheckRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskCreateRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskQuery;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryItemVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryReportVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryScopeOptionsVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryTaskVO;
import com.enterprise.ticket.module.inventory.entity.InventoryTask;
import com.enterprise.ticket.module.inventory.entity.InventoryTaskItem;
import com.enterprise.ticket.module.inventory.mapper.InventoryTaskItemMapper;
import com.enterprise.ticket.module.inventory.mapper.InventoryTaskMapper;
import com.enterprise.ticket.module.inventory.service.InventoryService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 设备盘点服务实现（P2）
 *
 * <p>设计要点见 {@link InventoryService} 与 V40 迁移的注释。这里只补充两处实现层面的取舍：
 *
 * <ol>
 *   <li><b>计数一律用 SQL 重算，不做内存累加</b>：同一个盘点任务会被多台手机同时核对
 *       （这正是「扫码盘点」的用法），内存累加会丢更新；重新数一遍天然幂等，
 *       且「计数与明细不一致」这种状态根本不会出现。</li>
 *   <li><b>状态推进用带前置条件的 UPDATE</b>：完成 / 取消都要求当前仍是进行中，
 *       命中 0 行说明已被并发地结束掉了 ⇒ 报错而不是静默覆盖。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    /** 任务编号里的日期部分 */
    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单页上限，与其它列表接口一致（请求更大也会被夹到这里） */
    private static final long MAX_PAGE_SIZE = 200L;

    /**
     * 单任务可盘点的设备台数上限。
     *
     * <p>明细是逐条 insert 的（一条 SQL 一台）：5000 台在事务里约几秒，而演示库全量也就
     * 1000 余台。设这个上限不是为了卡慢，而是防止「范围选错成全部」把几十万行写进明细 ——
     * 那时失败的不是速度，是事务日志与内存一起爆掉。
     */
    private static final int MAX_TASK_DEVICES = 5000;

    /** 「只看尚未核对」的筛选值（仅用于查询，不落库） */
    private static final String FILTER_UNCHECKED = "UNCHECKED";

    private final InventoryTaskMapper taskMapper;
    private final InventoryTaskItemMapper itemMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceCategoryMapper categoryMapper;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<InventoryTaskVO> page(InventoryTaskQuery query) {
        InventoryTaskQuery safe = query == null ? new InventoryTaskQuery() : query;
        long page = Math.max(safe.getPage(), 1L);
        long size = Math.min(Math.max(safe.getSize(), 1L), MAX_PAGE_SIZE);
        String keyword = trimToNull(safe.getKeyword());

        IPage<InventoryTask> result = taskMapper.selectPage(new Page<>(page, size),
                Wrappers.<InventoryTask>lambdaQuery()
                        .eq(StringUtils.hasText(safe.getStatus()), InventoryTask::getStatus, safe.getStatus())
                        .and(keyword != null, w -> w.like(InventoryTask::getTaskNo, keyword)
                                .or().like(InventoryTask::getTaskName, keyword))
                        .orderByDesc(InventoryTask::getId));

        Map<Long, String> names = userNameMap(result.getRecords().stream()
                .map(InventoryTask::getCreatedBy).toList());
        return PageResult.of(result, task -> toTaskVO(task, names));
    }

    @Override
    public InventoryTaskVO detail(Long taskId) {
        InventoryTask task = requireTask(taskId);
        return toTaskVO(task, userNameMap(List.of(task.getCreatedBy())));
    }

    @Override
    public PageResult<InventoryItemVO> items(Long taskId, String checkResult, long page, long size) {
        requireTask(taskId);
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);
        String filter = trimToNull(checkResult);

        IPage<InventoryTaskItem> result = itemMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<InventoryTaskItem>lambdaQuery()
                        .eq(InventoryTaskItem::getTaskId, taskId)
                        // UNCHECKED 是「尚未核对」的筛选值：核对结果本身用 null 表达未核对，
                        // 所以这里必须翻译成 isNull —— 直接把 "UNCHECKED" 当值去比会永远查不到
                        .isNull(FILTER_UNCHECKED.equals(filter), InventoryTaskItem::getCheckResult)
                        .eq(filter != null && !FILTER_UNCHECKED.equals(filter),
                                InventoryTaskItem::getCheckResult, filter)
                        .orderByAsc(InventoryTaskItem::getAssetNo));

        Map<Long, String> names = userNameMap(result.getRecords().stream()
                .map(InventoryTaskItem::getCheckedBy).toList());
        return PageResult.of(result, item -> toItemVO(item, names));
    }

    @Override
    public InventoryReportVO report(Long taskId) {
        InventoryTask task = requireTask(taskId);
        List<InventoryTaskItem> abnormal = itemMapper.selectList(Wrappers.<InventoryTaskItem>lambdaQuery()
                .eq(InventoryTaskItem::getTaskId, taskId)
                .in(InventoryTaskItem::getCheckResult, List.of(
                        InventoryCheckResult.MISSING.name(), InventoryCheckResult.WRONG_LOCATION.name()))
                .orderByAsc(InventoryTaskItem::getAssetNo));
        Map<Long, String> names = userNameMap(abnormal.stream()
                .map(InventoryTaskItem::getCheckedBy).toList());

        InventoryReportVO vo = new InventoryReportVO();
        vo.setTaskId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setTaskName(task.getTaskName());
        vo.setScopeLabel(task.getScopeLabel());
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(InventoryStatus.labelOf(task.getStatus()));
        vo.setTotalCount(task.getTotalCount());
        vo.setCheckedCount(task.getCheckedCount());
        int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
        int checked = task.getCheckedCount() == null ? 0 : task.getCheckedCount();
        vo.setUncheckedCount(Math.max(total - checked, 0));
        vo.setInPlaceCount(task.getInPlaceCount());
        vo.setMissingCount(task.getMissingCount());
        vo.setWrongLocationCount(task.getWrongLocationCount());
        vo.setProgressPercent(progressOf(checked, total));
        vo.setRemark(task.getRemark());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setCompletedAt(task.getCompletedAt());
        vo.setMissingItems(abnormal.stream()
                .filter(item -> InventoryCheckResult.MISSING.name().equals(item.getCheckResult()))
                .map(item -> toItemVO(item, names)).toList());
        vo.setWrongLocationItems(abnormal.stream()
                .filter(item -> InventoryCheckResult.WRONG_LOCATION.name().equals(item.getCheckResult()))
                .map(item -> toItemVO(item, names)).toList());
        return vo;
    }

    @Override
    public InventoryScopeOptionsVO scopeOptions() {
        List<InventoryScopeOptionsVO.Option> categories = categoryMapper.selectList(
                        Wrappers.<DeviceCategory>lambdaQuery().orderByAsc(DeviceCategory::getSortOrder))
                .stream()
                .map(category -> new InventoryScopeOptionsVO.Option(
                        String.valueOf(category.getId()), category.getCategoryName()))
                .toList();

        // 位置取自台账里实际用过的值（distinct），而不是维护一张位置字典：
        // 让用户从一个不存在的列表里挑位置，比让他手填一个拼错的更糟 ——
        // 结果都是「范围内 0 台设备」，而看起来像系统坏了。
        List<String> locations = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .select(Device::getStorageLocation)
                        .isNotNull(Device::getStorageLocation)
                        .ne(Device::getStorageLocation, "")
                        .groupBy(Device::getStorageLocation)
                        .orderByAsc(Device::getStorageLocation))
                .stream()
                .map(Device::getStorageLocation)
                .filter(StringUtils::hasText)
                .toList();

        InventoryScopeOptionsVO vo = new InventoryScopeOptionsVO();
        vo.setCategories(categories);
        vo.setLocations(locations.stream()
                .map(location -> new InventoryScopeOptionsVO.Option(location, location))
                .toList());
        return vo;
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(InventoryTaskCreateRequest request) {
        Long currentUserId = requireCurrentUserId();
        InventoryScopeType scopeType = InventoryScopeType.of(request.getScopeType());
        if (scopeType == null) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_INVALID, "盘点范围类型不正确");
        }
        String scopeValue = trimToNull(request.getScopeValue());
        if (scopeType == InventoryScopeType.CATEGORY && scopeValue == null) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_INVALID, "按分类盘点时必须选择分类");
        }
        if (scopeType == InventoryScopeType.LOCATION && scopeValue == null) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_INVALID, "按存放位置盘点时必须选择位置");
        }
        if (scopeType == InventoryScopeType.ALL) {
            scopeValue = null;
        }

        // 先计数再取数：范围内设备过多时**不把数据捞出来**就拒绝，
        // 否则「范围选成全部」会先把几十万行读进内存才报错。
        LambdaQueryWrapper<Device> scope = scopeWrapper(scopeType, scopeValue);
        long count = deviceMapper.selectCount(scope);
        if (count == 0) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_EMPTY);
        }
        if (count > MAX_TASK_DEVICES) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_TOO_LARGE,
                    "该范围内有 " + count + " 台设备，超过单次上限 " + MAX_TASK_DEVICES + " 台，请缩小范围后再创建");
        }
        List<Device> devices = deviceMapper.selectList(scope.orderByAsc(Device::getId));

        LocalDateTime now = LocalDateTime.now();
        InventoryTask task = new InventoryTask();
        task.setTaskNo(nextTaskNo(now));
        task.setTaskName(request.getTaskName().trim());
        task.setScopeType(scopeType.name());
        task.setScopeValue(scopeValue);
        task.setScopeLabel(scopeLabelOf(scopeType, scopeValue));
        task.setStatus(InventoryStatus.IN_PROGRESS.name());
        task.setTotalCount(devices.size());
        task.setCheckedCount(0);
        task.setInPlaceCount(0);
        task.setMissingCount(0);
        task.setWrongLocationCount(0);
        task.setCreatedBy(currentUserId);
        task.setRemark(trimToNull(request.getRemark()));
        taskMapper.insert(task);

        // 明细一次性快照：asset_no / device_name / storage_location / expected_status 都是
        // **创建那一刻的值**，之后设备改名、搬位置、被借走都不再影响本次盘点（见 V40 ③A）
        for (Device device : devices) {
            InventoryTaskItem item = new InventoryTaskItem();
            item.setTaskId(task.getId());
            item.setDeviceId(device.getId());
            item.setAssetNo(device.getAssetNo());
            item.setDeviceName(device.getDeviceName());
            item.setStorageLocation(device.getStorageLocation());
            item.setExpectedStatus(device.getStatus());
            itemMapper.insert(item);
        }
        log.info("创建盘点任务 id={} no={} 范围={} 设备数={}",
                task.getId(), task.getTaskNo(), task.getScopeLabel(), devices.size());
        return task.getId();
    }

    // ------------------------------------------------------------------
    // 核对
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryItemVO check(Long taskId, InventoryCheckRequest request) {
        Long currentUserId = requireCurrentUserId();
        InventoryTask task = requireTask(taskId);
        requireInProgress(task);

        InventoryCheckResult result = InventoryCheckResult.of(request.getCheckResult());
        if (result == null) {
            throw new BusinessException(ErrorCode.INVENTORY_CHECK_RESULT_INVALID);
        }
        String assetNo = request.getAssetNo().trim();
        InventoryTaskItem item = itemMapper.selectOne(Wrappers.<InventoryTaskItem>lambdaQuery()
                .eq(InventoryTaskItem::getTaskId, taskId)
                .eq(InventoryTaskItem::getAssetNo, assetNo)
                .last("LIMIT 1"));
        if (item == null) {
            // 明确区分「这台不归本次盘点」与「这台不存在」：前者是本任务范围问题，
            // 后者要去设备台账查。含糊地回一句「未找到」会让盘点人无从下手。
            throw new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                    "设备「" + assetNo + "」不在本次盘点范围内（请核对是否扫错了标签）");
        }

        LocalDateTime now = LocalDateTime.now();
        itemMapper.update(null, Wrappers.<InventoryTaskItem>lambdaUpdate()
                .eq(InventoryTaskItem::getId, item.getId())
                .set(InventoryTaskItem::getCheckResult, result.name())
                .set(InventoryTaskItem::getCheckedBy, currentUserId)
                .set(InventoryTaskItem::getCheckedAt, now)
                .set(InventoryTaskItem::getRemark, trimToNull(request.getRemark())));

        recount(task, now);
        log.info("盘点任务 {} 核对设备 {} → {}", taskId, assetNo, result.name());

        // 回读该台最新明细：前端据此就地更新那一行，不必整页刷新
        // （手机上每盘一台都重拉列表会很卡，且滚动位置会丢）
        InventoryTaskItem latest = itemMapper.selectById(item.getId());
        return toItemVO(latest, userNameMap(List.of(currentUserId)));
    }

    // ------------------------------------------------------------------
    // 结束
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long taskId, String remark) {
        InventoryTask task = requireTask(taskId);
        requireInProgress(task);
        LocalDateTime now = LocalDateTime.now();
        String text = trimToNull(remark);
        int updated = taskMapper.update(null, Wrappers.<InventoryTask>lambdaUpdate()
                .eq(InventoryTask::getId, taskId)
                .eq(InventoryTask::getStatus, InventoryStatus.IN_PROGRESS.name())
                .set(InventoryTask::getStatus, InventoryStatus.COMPLETED.name())
                .set(InventoryTask::getCompletedAt, now)
                .set(text != null, InventoryTask::getRemark, text));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INVENTORY_STATE_INVALID, "该盘点任务已被其他操作结束，请刷新后查看");
        }
        // 允许存在未核对项：真实盘点常常盘不完（有的设备借在外、有的在别的楼），
        // 强行要求 100% 只会让人随便勾一遍。未核对台数在报告里如实显示。
        log.info("盘点任务 {} 已完成（核对 {} / {}）", taskId, task.getCheckedCount(), task.getTotalCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long taskId, String remark) {
        InventoryTask task = requireTask(taskId);
        requireInProgress(task);
        String text = trimToNull(remark);
        int updated = taskMapper.update(null, Wrappers.<InventoryTask>lambdaUpdate()
                .eq(InventoryTask::getId, taskId)
                .eq(InventoryTask::getStatus, InventoryStatus.IN_PROGRESS.name())
                .set(InventoryTask::getStatus, InventoryStatus.CANCELLED.name())
                .set(InventoryTask::getCompletedAt, LocalDateTime.now())
                .set(text != null, InventoryTask::getRemark, text));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INVENTORY_STATE_INVALID, "该盘点任务已被其他操作结束，请刷新后查看");
        }
        log.info("盘点任务 {} 已取消", taskId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 重算任务计数。
     *
     * <p>用 SQL 重新数一遍，而不是在内存里给计数 +1：同一个任务会被多台手机同时核对，
     * 内存累加会丢更新；重数天然幂等，且不会出现「计数与明细对不上」的中间状态。
     */
    private void recount(InventoryTask task, LocalDateTime now) {
        taskMapper.update(null, Wrappers.<InventoryTask>lambdaUpdate()
                .eq(InventoryTask::getId, task.getId())
                .set(InventoryTask::getCheckedCount, (int) countByResult(task.getId(), null))
                .set(InventoryTask::getInPlaceCount,
                        (int) countByResult(task.getId(), InventoryCheckResult.IN_PLACE))
                .set(InventoryTask::getMissingCount,
                        (int) countByResult(task.getId(), InventoryCheckResult.MISSING))
                .set(InventoryTask::getWrongLocationCount,
                        (int) countByResult(task.getId(), InventoryCheckResult.WRONG_LOCATION))
                // 只有第一次核对才写 startedAt（盘点真实开始的时间）
                .set(task.getStartedAt() == null, InventoryTask::getStartedAt, now));
    }

    private long countByResult(Long taskId, InventoryCheckResult result) {
        return itemMapper.selectCount(Wrappers.<InventoryTaskItem>lambdaQuery()
                .eq(InventoryTaskItem::getTaskId, taskId)
                .isNotNull(result == null, InventoryTaskItem::getCheckResult)
                .eq(result != null, InventoryTaskItem::getCheckResult,
                        result == null ? null : result.name()));
    }

    /** 范围 → 设备查询条件（不含排序，便于先 selectCount 再 selectList） */
    private LambdaQueryWrapper<Device> scopeWrapper(InventoryScopeType scopeType, String scopeValue) {
        LambdaQueryWrapper<Device> wrapper = Wrappers.<Device>lambdaQuery();
        // 逻辑删除由 @TableLogic 自动排除，这里不必再写 deleted = 0
        switch (scopeType) {
            case ALL -> {
                // 不过滤
            }
            case CATEGORY -> {
                Long categoryId = parseCategoryId(scopeValue);
                // 一级或二级分类都算命中：设备台账里分类是可选的二级结构，
                // 只按一级匹会漏掉「挂在子分类下」的设备
                wrapper.and(w -> w.eq(Device::getPrimaryCategoryId, categoryId)
                        .or().eq(Device::getSecondaryCategoryId, categoryId));
            }
            case LOCATION -> wrapper.eq(Device::getStorageLocation, scopeValue);
        }
        return wrapper;
    }

    /** 范围的中文描述快照（分类/位置后来改名，报告仍要能复现「当时盘的是哪一片」） */
    private String scopeLabelOf(InventoryScopeType scopeType, String scopeValue) {
        if (scopeType == InventoryScopeType.ALL) {
            return InventoryScopeType.ALL.getLabel();
        }
        if (scopeType == InventoryScopeType.CATEGORY) {
            DeviceCategory category = categoryMapper.selectById(parseCategoryId(scopeValue));
            return scopeType.getLabel() + "：" + (category == null ? scopeValue : category.getCategoryName());
        }
        return scopeType.getLabel() + "：" + scopeValue;
    }

    private Long parseCategoryId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVENTORY_SCOPE_INVALID, "分类参数不正确：" + value);
        }
    }

    private String nextTaskNo(LocalDateTime now) {
        String datePart = TASK_NO_DATE.format(now);
        String prefix = "PC-" + datePart + "-";
        long todayCount = taskMapper.selectCount(Wrappers.<InventoryTask>lambdaQuery()
                .likeRight(InventoryTask::getTaskNo, prefix));
        return prefix + String.format("%03d", todayCount + 1);
    }

    private InventoryTask requireTask(Long taskId) {
        InventoryTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.INVENTORY_TASK_NOT_FOUND);
        }
        return task;
    }

    private void requireInProgress(InventoryTask task) {
        if (!InventoryStatus.IN_PROGRESS.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.INVENTORY_STATE_INVALID,
                    "盘点任务当前状态为「" + InventoryStatus.labelOf(task.getStatus()) + "」，不能再核对或修改");
        }
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private int progressOf(Integer checked, Integer total) {
        if (total == null || total <= 0) {
            return 0;
        }
        return (int) Math.round((checked == null ? 0 : checked) * 100.0 / total);
    }

    private InventoryTaskVO toTaskVO(InventoryTask task, Map<Long, String> names) {
        InventoryTaskVO vo = new InventoryTaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setTaskName(task.getTaskName());
        vo.setScopeType(task.getScopeType());
        vo.setScopeTypeLabel(InventoryScopeType.labelOf(task.getScopeType()));
        vo.setScopeValue(task.getScopeValue());
        vo.setScopeLabel(task.getScopeLabel());
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(InventoryStatus.labelOf(task.getStatus()));
        vo.setTotalCount(task.getTotalCount());
        vo.setCheckedCount(task.getCheckedCount());
        vo.setInPlaceCount(task.getInPlaceCount());
        vo.setMissingCount(task.getMissingCount());
        vo.setWrongLocationCount(task.getWrongLocationCount());
        vo.setProgressPercent(progressOf(task.getCheckedCount(), task.getTotalCount()));
        vo.setCreatedBy(task.getCreatedBy());
        vo.setCreatedByName(names.get(task.getCreatedBy()));
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setCompletedAt(task.getCompletedAt());
        vo.setRemark(task.getRemark());
        return vo;
    }

    private InventoryItemVO toItemVO(InventoryTaskItem item, Map<Long, String> names) {
        InventoryItemVO vo = new InventoryItemVO();
        vo.setId(item.getId());
        vo.setDeviceId(item.getDeviceId());
        vo.setAssetNo(item.getAssetNo());
        vo.setDeviceName(item.getDeviceName());
        vo.setStorageLocation(item.getStorageLocation());
        vo.setExpectedStatus(item.getExpectedStatus());
        vo.setExpectedStatusLabel(DeviceStatus.labelOf(item.getExpectedStatus()));
        vo.setCheckResult(item.getCheckResult());
        // 尚未核对时 checkResult 为 null，label 也**保持 null**：不编一个「待核对」的假值，
        // 否则前端无法区分「还没盘」与「盘出来的结果就叫这个」
        vo.setCheckResultLabel(item.getCheckResult() == null
                ? null : InventoryCheckResult.labelOf(item.getCheckResult()));
        vo.setCheckedBy(item.getCheckedBy());
        vo.setCheckedByName(item.getCheckedBy() == null ? null : names.get(item.getCheckedBy()));
        vo.setCheckedAt(item.getCheckedAt());
        vo.setRemark(item.getRemark());
        return vo;
    }

    private Map<Long, String> userNameMap(Collection<Long> ids) {
        Collection<Long> filtered = ids == null ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (filtered.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(filtered).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
