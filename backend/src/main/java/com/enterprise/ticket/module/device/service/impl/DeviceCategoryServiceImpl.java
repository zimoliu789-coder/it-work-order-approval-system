package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceCategorySaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceCategorySortRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceCategoryVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 设备分类实现
 *
 * <p>分类固定两级：一级（parentId=0）与二级（parentId=一级ID）。层级在 {@link #create} 时确定，
 * 之后不可通过修改接口调整 —— 层级变更会让已挂接的设备归属错乱。
 *
 * <p>同级重名由「预检 + 唯一索引 uk_device_category_parent_name」双重保证：预检负责给出友好提示，
 * 唯一索引负责兜住并发写入，写操作捕获 {@link DuplicateKeyException} 后统一转成
 * {@code DEVICE_CATEGORY_NAME_EXISTS}，避免数据库报错外泄成 500。
 *
 * <p>性能：设备数量按「一次取回全部分类的挂接关系再内存聚合」统计，
 * 不采用「逐分类 count」的写法，避免分类数增长后出现 N+1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCategoryServiceImpl implements DeviceCategoryService {

    private final DeviceCategoryMapper categoryMapper;
    private final DeviceMapper deviceMapper;

    @Override
    public List<DeviceCategoryVO> listTree() {
        List<DeviceCategory> all = categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery()
                .orderByAsc(DeviceCategory::getLevel)
                .orderByAsc(DeviceCategory::getSortOrder)
                .orderByAsc(DeviceCategory::getId));
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> primaryCounts = countByPrimaryCategory();
        Map<Long, Long> secondaryCounts = countBySecondaryCategory();

        List<DeviceCategoryVO> roots = new ArrayList<>();
        Map<Long, DeviceCategoryVO> rootById = new LinkedHashMap<>();
        for (DeviceCategory category : all) {
            if (isRoot(category)) {
                DeviceCategoryVO vo = toVO(category, primaryCounts.getOrDefault(category.getId(), 0L));
                vo.setChildren(new ArrayList<>());
                roots.add(vo);
                rootById.put(category.getId(), vo);
            }
        }
        for (DeviceCategory category : all) {
            if (isRoot(category)) {
                continue;
            }
            DeviceCategoryVO parent = rootById.get(category.getParentId());
            if (parent == null) {
                // 父分类被并发删除时会出现孤立二级分类：跳过而不是抛错，
                // 否则一条脏数据会导致整个分类管理页打不开
                log.warn("设备分类 id={} 的父分类 id={} 不存在，已跳过", category.getId(), category.getParentId());
                continue;
            }
            parent.getChildren().add(toVO(category, secondaryCounts.getOrDefault(category.getId(), 0L)));
        }
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DeviceCategorySaveRequest request) {
        String name = request.getCategoryName().trim();
        long parentId = request.getParentId() == null ? DeviceCategory.ROOT_PARENT_ID : request.getParentId();
        int level;
        if (parentId == DeviceCategory.ROOT_PARENT_ID) {
            level = DeviceCategory.LEVEL_PRIMARY;
        } else {
            DeviceCategory parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ErrorCode.DEVICE_CATEGORY_NOT_FOUND, "父分类不存在，请刷新后重试");
            }
            if (parent.getLevel() == null || parent.getLevel() != DeviceCategory.LEVEL_PRIMARY) {
                throw new BusinessException(ErrorCode.DEVICE_CATEGORY_LEVEL_INVALID,
                        "仅支持两级分类，二级分类下不能再建子分类");
            }
            level = DeviceCategory.LEVEL_SECONDARY;
        }
        assertNameUnique(parentId, name, null);

        DeviceCategory category = new DeviceCategory();
        category.setCategoryName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setSortOrder(nextSortOrder(parentId));
        category.setRemark(request.getRemark());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            // 预检通过后被并发请求抢先写入：由唯一索引 uk_device_category_parent_name 兜底
            throw duplicateName(parentId, name);
        }
        log.info("设备分类已创建 id={} name={} level={}", category.getId(), name, level);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DeviceCategorySaveRequest request) {
        DeviceCategory category = requireCategory(id);
        String name = request.getCategoryName().trim();
        assertNameUnique(category.getParentId(), name, id);
        LambdaUpdateWrapper<DeviceCategory> update = Wrappers.<DeviceCategory>lambdaUpdate()
                .eq(DeviceCategory::getId, id)
                .set(DeviceCategory::getCategoryName, name)
                .set(DeviceCategory::getRemark, request.getRemark());
        try {
            categoryMapper.update(null, update);
        } catch (DuplicateKeyException e) {
            throw duplicateName(category.getParentId(), name);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireCategory(id);
        Long childCount = categoryMapper.selectCount(Wrappers.<DeviceCategory>lambdaQuery()
                .eq(DeviceCategory::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_HAS_CHILDREN);
        }
        // 必须按物理行统计（含已软删除设备）：device 表有外键 ON DELETE RESTRICT，
        // 软删除设备同样会阻止分类删除；若此处漏掉会出现「守卫放行 → 数据库报错 → 500」
        long deviceCount = deviceMapper.countByCategoryIncludeDeleted(id);
        if (deviceCount > 0) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_HAS_DEVICE,
                    "该分类下仍有 " + deviceCount + " 台设备（含已删除设备），请先调整设备分类");
        }
        categoryMapper.deleteById(id);
        log.info("设备分类 id={} 已删除", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sort(DeviceCategorySortRequest request) {
        long parentId = request.getParentId() == null ? DeviceCategory.ROOT_PARENT_ID : request.getParentId();
        List<Long> orderedIds = request.getOrderedIds() == null ? List.of()
                : request.getOrderedIds().stream().filter(Objects::nonNull).distinct().toList();
        Set<Long> currentIds = categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery()
                        .eq(DeviceCategory::getParentId, parentId)
                        .select(DeviceCategory::getId))
                .stream()
                .map(DeviceCategory::getId)
                .collect(Collectors.toSet());
        if (!currentIds.equals(new HashSet<>(orderedIds))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "排序列表与当前分类不一致，请刷新后重试");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            categoryMapper.update(null, Wrappers.<DeviceCategory>lambdaUpdate()
                    .eq(DeviceCategory::getId, orderedIds.get(i))
                    .set(DeviceCategory::getSortOrder, i + 1));
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private DeviceCategory requireCategory(Long id) {
        DeviceCategory category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.DEVICE_CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private boolean isRoot(DeviceCategory category) {
        return category.getParentId() == null || category.getParentId() == DeviceCategory.ROOT_PARENT_ID;
    }

    private void assertNameUnique(long parentId, String name, Long excludeId) {
        Long count = categoryMapper.selectCount(Wrappers.<DeviceCategory>lambdaQuery()
                .eq(DeviceCategory::getParentId, parentId)
                .eq(DeviceCategory::getCategoryName, name)
                .ne(excludeId != null, DeviceCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw duplicateName(parentId, name);
        }
    }

    private BusinessException duplicateName(long parentId, String name) {
        String scope = parentId == DeviceCategory.ROOT_PARENT_ID ? "一级分类" : "该一级分类下";
        return new BusinessException(ErrorCode.DEVICE_CATEGORY_NAME_EXISTS, scope + "已存在「" + name + "」");
    }

    /** 追加到同级末尾：取同级最大 sort_order + 1 */
    private int nextSortOrder(long parentId) {
        return categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery()
                        .eq(DeviceCategory::getParentId, parentId)
                        .select(DeviceCategory::getSortOrder))
                .stream()
                .map(DeviceCategory::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    /** 一级分类ID → 有效设备数（设备一级分类 = 本分类） */
    private Map<Long, Long> countByPrimaryCategory() {
        Map<Long, Long> result = new HashMap<>();
        List<Device> devices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .select(Device::getPrimaryCategoryId));
        for (Device device : devices) {
            result.merge(device.getPrimaryCategoryId(), 1L, Long::sum);
        }
        return result;
    }

    /** 二级分类ID → 有效设备数（设备二级分类 = 本分类） */
    private Map<Long, Long> countBySecondaryCategory() {
        Map<Long, Long> result = new HashMap<>();
        List<Device> devices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .isNotNull(Device::getSecondaryCategoryId)
                .select(Device::getSecondaryCategoryId));
        for (Device device : devices) {
            result.merge(device.getSecondaryCategoryId(), 1L, Long::sum);
        }
        return result;
    }

    private DeviceCategoryVO toVO(DeviceCategory category, long deviceCount) {
        DeviceCategoryVO vo = new DeviceCategoryVO();
        vo.setId(category.getId());
        vo.setCategoryName(category.getCategoryName());
        vo.setParentId(category.getParentId());
        vo.setLevel(category.getLevel());
        vo.setSortOrder(category.getSortOrder());
        vo.setRemark(category.getRemark());
        vo.setDeviceCount(deviceCount);
        return vo;
    }
}
