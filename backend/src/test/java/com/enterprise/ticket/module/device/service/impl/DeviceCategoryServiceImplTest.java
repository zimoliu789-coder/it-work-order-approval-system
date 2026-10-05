package com.enterprise.ticket.module.device.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceCategorySaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceCategorySortRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceCategoryVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备分类规则单元测试（规范 §8 校验分支）
 *
 * <p>纯 Mockito：不启动 Spring、不连 MySQL/Redis。覆盖两级分类约束、同级重名、
 * 删除守卫（子分类 / 物理设备行）与排序一致性 —— 这些是 Phase 4 借用申请依赖的分类前置条件。
 */
@ExtendWith(MockitoExtension.class)
class DeviceCategoryServiceImplTest {

    /** MyBatis-Plus 的方法引用 Wrapper 依赖实体元数据缓存，无 Spring 上下文时需手动注册 */
    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Device.class, DeviceCategory.class);
    }

    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private DeviceMapper deviceMapper;

    @InjectMocks
    private DeviceCategoryServiceImpl service;

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private DeviceCategory category(Long id, String name, Long parentId, int level, int sortOrder) {
        DeviceCategory category = new DeviceCategory();
        category.setId(id);
        category.setCategoryName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setSortOrder(sortOrder);
        return category;
    }

    private Device device(Long id, Long primaryCategoryId, Long secondaryCategoryId) {
        Device device = new Device();
        device.setId(id);
        device.setPrimaryCategoryId(primaryCategoryId);
        device.setSecondaryCategoryId(secondaryCategoryId);
        return device;
    }

    private DeviceCategorySaveRequest saveRequest(String name, Long parentId) {
        DeviceCategorySaveRequest request = new DeviceCategorySaveRequest();
        request.setCategoryName(name);
        request.setParentId(parentId);
        return request;
    }

    private static ErrorCode errorCodeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    // ------------------------------------------------------------------
    // listTree
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分类树：一级挂载二级，并按一级/二级各自聚合设备数")
    void listTree_buildsTreeAndCountsDevices() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, "电脑", 0L, 1, 1),
                category(2L, "显示器", 0L, 1, 2),
                category(3L, "笔记本", 1L, 2, 1),
                category(4L, "台式机", 1L, 2, 2)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, 1L, 3L),
                device(2L, 1L, 3L),
                device(3L, 2L, null)));

        List<DeviceCategoryVO> tree = service.listTree();

        assertEquals(2, tree.size());
        DeviceCategoryVO computer = tree.get(0);
        assertEquals("电脑", computer.getCategoryName());
        assertEquals(2, computer.getDeviceCount(), "一级分类统计直属设备数");
        assertEquals(List.of("笔记本", "台式机"),
                computer.getChildren().stream().map(DeviceCategoryVO::getCategoryName).toList());
        assertEquals(2, computer.getChildren().get(0).getDeviceCount());
        assertEquals(0, computer.getChildren().get(1).getDeviceCount());

        DeviceCategoryVO monitor = tree.get(1);
        assertEquals(1, monitor.getDeviceCount());
        assertTrue(monitor.getChildren().isEmpty());
    }

    @Test
    @DisplayName("分类树：无分类时返回空列表，不发额外查询")
    void listTree_emptyReturnsEmptyList() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        assertEquals(List.of(), service.listTree());
        verify(deviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("分类树：父分类缺失的孤立二级分类被跳过，不影响整棵树返回")
    void listTree_skipsOrphanSecondaryCategory() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, "电脑", 0L, 1, 1),
                category(9L, "孤立分类", 999L, 2, 1)));
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        List<DeviceCategoryVO> tree = service.listTree();

        assertEquals(1, tree.size());
        assertTrue(tree.get(0).getChildren().isEmpty());
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增分类：parentId 为空按一级分类创建，sortOrder 追加到同级末尾")
    void create_rootCategoryAppendsSortOrder() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(category(1L, "电脑", 0L, 1, 1)));
        when(categoryMapper.insert(any(DeviceCategory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DeviceCategory.class).setId(20L);
            return 1;
        });

        assertEquals(20L, service.create(saveRequest("外设", null)).longValue());

        ArgumentCaptor<DeviceCategory> captor = ArgumentCaptor.forClass(DeviceCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertEquals(DeviceCategory.LEVEL_PRIMARY, captor.getValue().getLevel());
        assertEquals(DeviceCategory.ROOT_PARENT_ID, captor.getValue().getParentId());
        assertEquals(2, captor.getValue().getSortOrder().intValue());
    }

    @Test
    @DisplayName("新增分类：parentId 指向一级分类时按二级分类创建")
    void create_secondaryCategoryUnderRoot() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1, 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of());
        when(categoryMapper.insert(any(DeviceCategory.class))).thenReturn(1);

        service.create(saveRequest("笔记本", 1L));

        ArgumentCaptor<DeviceCategory> captor = ArgumentCaptor.forClass(DeviceCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertEquals(DeviceCategory.LEVEL_SECONDARY, captor.getValue().getLevel());
        assertEquals(1L, captor.getValue().getParentId());
        assertEquals(1, captor.getValue().getSortOrder().intValue(), "同级无分类时从 1 开始");
    }

    @Test
    @DisplayName("新增分类：parentId 指向二级分类 → DEVICE_CATEGORY_LEVEL_INVALID（仅支持两级）")
    void create_rejectsThirdLevel() {
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "笔记本", 1L, 2, 1));

        assertEquals(ErrorCode.DEVICE_CATEGORY_LEVEL_INVALID,
                errorCodeOf(() -> service.create(saveRequest("超极本", 3L))));
        verify(categoryMapper, never()).insert(any(DeviceCategory.class));
    }

    @Test
    @DisplayName("新增分类：父分类不存在 → DEVICE_CATEGORY_NOT_FOUND")
    void create_rejectsMissingParent() {
        when(categoryMapper.selectById(88L)).thenReturn(null);

        assertEquals(ErrorCode.DEVICE_CATEGORY_NOT_FOUND,
                errorCodeOf(() -> service.create(saveRequest("笔记本", 88L))));
    }

    @Test
    @DisplayName("新增分类：同级重名 → DEVICE_CATEGORY_NAME_EXISTS，不写库")
    void create_rejectsDuplicateNameInSameLevel() {
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.DEVICE_CATEGORY_NAME_EXISTS,
                errorCodeOf(() -> service.create(saveRequest("电脑", null))));
        verify(categoryMapper, never()).insert(any(DeviceCategory.class));
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("修改分类：不存在 → DEVICE_CATEGORY_NOT_FOUND")
    void update_rejectsMissingCategory() {
        when(categoryMapper.selectById(7L)).thenReturn(null);

        assertEquals(ErrorCode.DEVICE_CATEGORY_NOT_FOUND,
                errorCodeOf(() -> service.update(7L, saveRequest("电脑", null))));
    }

    @Test
    @DisplayName("修改分类：同级重名（排除自身）→ DEVICE_CATEGORY_NAME_EXISTS")
    void update_rejectsDuplicateName() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1, 1));
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.DEVICE_CATEGORY_NAME_EXISTS,
                errorCodeOf(() -> service.update(1L, saveRequest("显示器", null))));
        verify(categoryMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改分类：名称可用时更新名称与备注")
    void update_appliesNameAndRemark() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1, 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);

        service.update(1L, saveRequest("计算设备", null));

        verify(categoryMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // delete（规范 §8 删除守卫）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除分类：仍有子分类 → DEVICE_CATEGORY_HAS_CHILDREN")
    void delete_rejectsCategoryWithChildren() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1, 1));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertEquals(ErrorCode.DEVICE_CATEGORY_HAS_CHILDREN,
                errorCodeOf(() -> service.delete(1L)));
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除分类：仍被设备引用（含已软删除设备）→ DEVICE_CATEGORY_HAS_DEVICE")
    void delete_rejectsCategoryInUseByDevices() {
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "笔记本", 1L, 2, 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.countByCategoryIncludeDeleted(3L)).thenReturn(4L);

        assertEquals(ErrorCode.DEVICE_CATEGORY_HAS_DEVICE,
                errorCodeOf(() -> service.delete(3L)));
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除分类：无子分类且无设备引用时物理删除")
    void delete_removesUnusedCategory() {
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "笔记本", 1L, 2, 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.countByCategoryIncludeDeleted(3L)).thenReturn(0L);

        service.delete(3L);

        verify(categoryMapper).deleteById(3L);
    }

    // ------------------------------------------------------------------
    // sort
    // ------------------------------------------------------------------

    @Test
    @DisplayName("排序：提交集合与现存同级分类不一致 → PARAM_INVALID，且不更新")
    void sort_rejectsMismatchedIdSet() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, "电脑", 0L, 1, 1),
                category(2L, "显示器", 0L, 1, 2)));

        DeviceCategorySortRequest request = new DeviceCategorySortRequest();
        request.setParentId(0L);
        request.setOrderedIds(List.of(1L));

        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.sort(request)));
        verify(categoryMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("排序：集合一致时按下标逐条重写 sortOrder")
    void sort_rewritesSortOrderByIndex() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, "电脑", 0L, 1, 1),
                category(2L, "显示器", 0L, 1, 2)));

        DeviceCategorySortRequest request = new DeviceCategorySortRequest();
        request.setParentId(0L);
        request.setOrderedIds(List.of(2L, 1L));

        service.sort(request);

        verify(categoryMapper, times(2)).update(any(), any());
    }
}
