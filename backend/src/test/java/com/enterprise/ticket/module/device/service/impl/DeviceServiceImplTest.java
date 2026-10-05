package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.BatchLimits;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceBatchCategoryRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备台账规则单元测试（规范 §8 / §9）
 *
 * <p>覆盖三类最容易出错、且后续阶段强依赖的规则：
 * <ul>
 *   <li>资产编号「全局唯一且不可复用」——必须按物理行（含已软删除设备）判定；</li>
 *   <li>分类挂接合法性 —— 一级必须 level=1，二级必须归属所选一级；</li>
 *   <li>状态机 —— 只放行「报废」「维修完成」，且 BORROWED 禁止直接报废（规范 §9）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceImplTest {

    /** MyBatis-Plus 的方法引用 Wrapper 依赖实体元数据缓存，无 Spring 上下文时需手动注册 */
    @BeforeAll
    static void initMyBatisLambdaCache() {
        // Order 也需注册：usageMap 会以 Order 构建 lambda wrapper 查询占用工单（Phase 4）
        MyBatisLambdaCache.init(Device.class, DeviceCategory.class, Order.class);
    }

    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private DeviceServiceImpl service;

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private DeviceCategory category(Long id, String name, Long parentId, int level) {
        DeviceCategory category = new DeviceCategory();
        category.setId(id);
        category.setCategoryName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setSortOrder(1);
        return category;
    }

    private Device device(Long id, String name, String assetNo, String status) {
        Device device = new Device();
        device.setId(id);
        device.setDeviceName(name);
        device.setAssetNo(assetNo);
        device.setStatus(status);
        device.setPrimaryCategoryId(1L);
        device.setSecondaryCategoryId(3L);
        return device;
    }

    private DeviceSaveRequest saveRequest(String assetNo, Long primaryCategoryId, Long secondaryCategoryId) {
        DeviceSaveRequest request = new DeviceSaveRequest();
        request.setDeviceName("ThinkPad X1");
        request.setAssetNo(assetNo);
        request.setPrimaryCategoryId(primaryCategoryId);
        request.setSecondaryCategoryId(secondaryCategoryId);
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
    // page
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分页查询：状态筛选值非法时直接 PARAM_INVALID 之外的 DEVICE_STATUS_INVALID，不查库")
    void page_rejectsUnknownStatusFilter() {
        assertEquals(ErrorCode.DEVICE_STATUS_INVALID,
                errorCodeOf(() -> service.page(1, 10, null, null, null, "NOT_A_STATUS")));
        verify(deviceMapper, never()).selectPage(ArgumentMatchers.<IPage<Device>>any(), any());
    }

    @Test
    @DisplayName("分页查询：返回分类名与状态中文名，并标记可否被申请")
    void page_mapsCategoryNamesAndStatus() {
        Page<Device> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.AVAILABLE.name())));
        mockPage.setTotal(1);
        when(deviceMapper.selectPage(ArgumentMatchers.<IPage<Device>>any(), any())).thenReturn(mockPage);
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, "电脑", 0L, 1),
                category(3L, "笔记本", 1L, 2)));

        PageResult<DeviceVO> result = service.page(1, 10, "IT-0001", 1L, 3L, "AVAILABLE");

        assertEquals(1, result.getRecords().size());
        DeviceVO vo = result.getRecords().get(0);
        assertEquals("电脑", vo.getPrimaryCategoryName());
        assertEquals("笔记本", vo.getSecondaryCategoryName());
        assertEquals(DeviceStatus.AVAILABLE.getLabel(), vo.getStatusLabel());
        assertTrue(vo.isApplicable());
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增设备：资产编号已被历史（含已软删除）设备占用 → DEVICE_ASSET_NO_EXISTS")
    void create_rejectsReusedAssetNo() {
        when(deviceMapper.selectAnyIdByAssetNo("IT-0001")).thenReturn(9L);

        assertEquals(ErrorCode.DEVICE_ASSET_NO_EXISTS,
                errorCodeOf(() -> service.create(saveRequest("IT-0001", 1L, null))));
        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    @DisplayName("新增设备：一级分类不存在 → DEVICE_CATEGORY_NOT_FOUND")
    void create_rejectsMissingPrimaryCategory() {
        when(deviceMapper.selectAnyIdByAssetNo(anyString())).thenReturn(null);
        when(categoryMapper.selectById(1L)).thenReturn(null);

        assertEquals(ErrorCode.DEVICE_CATEGORY_NOT_FOUND,
                errorCodeOf(() -> service.create(saveRequest("IT-0002", 1L, null))));
    }

    @Test
    @DisplayName("新增设备：一级分类位置传入了二级分类 → DEVICE_CATEGORY_LEVEL_INVALID")
    void create_rejectsSecondaryAsPrimary() {
        when(deviceMapper.selectAnyIdByAssetNo(anyString())).thenReturn(null);
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "笔记本", 1L, 2));

        assertEquals(ErrorCode.DEVICE_CATEGORY_LEVEL_INVALID,
                errorCodeOf(() -> service.create(saveRequest("IT-0003", 3L, null))));
    }

    @Test
    @DisplayName("新增设备：二级分类不属于所选一级分类 → DEVICE_CATEGORY_MISMATCH")
    void create_rejectsMismatchedSecondaryCategory() {
        when(deviceMapper.selectAnyIdByAssetNo(anyString())).thenReturn(null);
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1));
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "办公桌", 2L, 2));

        assertEquals(ErrorCode.DEVICE_CATEGORY_MISMATCH,
                errorCodeOf(() -> service.create(saveRequest("IT-0004", 1L, 3L))));
        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    @DisplayName("新增设备：正常创建时状态固定为 AVAILABLE（规范 §9 状态机入口）")
    void create_startsFromAvailable() {
        when(deviceMapper.selectAnyIdByAssetNo(anyString())).thenReturn(null);
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1));
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, "笔记本", 1L, 2));
        when(deviceMapper.insert(any(Device.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Device.class).setId(30L);
            return 1;
        });

        assertEquals(30L, service.create(saveRequest("IT-0005", 1L, 3L)).longValue());

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).insert(captor.capture());
        assertEquals(DeviceStatus.AVAILABLE.name(), captor.getValue().getStatus());
        assertEquals(1L, captor.getValue().getPrimaryCategoryId());
        assertEquals(3L, captor.getValue().getSecondaryCategoryId());
        assertFalse(captor.getValue().getDeleted());
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("修改设备：资产编号被其它设备占用 → DEVICE_ASSET_NO_EXISTS")
    void update_rejectsAssetNoTakenByOthers() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "ThinkPad X1", "IT-0001", "AVAILABLE"));
        when(deviceMapper.selectAnyIdByAssetNo("IT-9999")).thenReturn(2L);

        assertEquals(ErrorCode.DEVICE_ASSET_NO_EXISTS,
                errorCodeOf(() -> service.update(1L, saveRequest("IT-9999", 1L, null))));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改设备：保持自身资产编号不变时允许保存")
    void update_allowsKeepingOwnAssetNo() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "ThinkPad X1", "IT-0001", "AVAILABLE"));
        when(deviceMapper.selectAnyIdByAssetNo("IT-0001")).thenReturn(1L);
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, "电脑", 0L, 1));

        service.update(1L, saveRequest("IT-0001", 1L, null));

        verify(deviceMapper).update(any(), any());
    }

    @Test
    @DisplayName("修改设备：设备不存在 → DEVICE_NOT_FOUND")
    void update_rejectsMissingDevice() {
        when(deviceMapper.selectById(77L)).thenReturn(null);

        assertEquals(ErrorCode.DEVICE_NOT_FOUND,
                errorCodeOf(() -> service.update(77L, saveRequest("IT-0001", 1L, null))));
    }

    // ------------------------------------------------------------------
    // softDelete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("软删除设备：调用逻辑删除（不物理删除）")
    void softDelete_usesLogicDelete() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "ThinkPad X1", "IT-0001", "AVAILABLE"));

        service.softDelete(1L);

        verify(deviceMapper).deleteById(1L);
    }

    @Test
    @DisplayName("软删除设备：设备不存在 → DEVICE_NOT_FOUND，不执行删除")
    void softDelete_rejectsMissingDevice() {
        when(deviceMapper.selectById(88L)).thenReturn(null);

        assertEquals(ErrorCode.DEVICE_NOT_FOUND, errorCodeOf(() -> service.softDelete(88L)));
        verify(deviceMapper, never()).deleteById(any(Long.class));
    }

    // ------------------------------------------------------------------
    // changeStatus（规范 §9 状态机）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("状态操作：目标状态取值非法 → DEVICE_STATUS_INVALID")
    void changeStatus_rejectsUnknownTarget() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "ThinkPad X1", "IT-0001", "AVAILABLE"));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus("NOT_A_STATUS");

        assertEquals(ErrorCode.DEVICE_STATUS_INVALID,
                errorCodeOf(() -> service.changeStatus(1L, request)));
    }

    @Test
    @DisplayName("状态操作：使用中设备禁止直接报废 → DEVICE_IN_BORROWED（规范 §9 / §21）")
    void changeStatus_rejectsScrapWhileBorrowed() {
        when(deviceMapper.selectById(1L)).thenReturn(
                device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.IN_USE.name()));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus(DeviceStatus.SCRAPPED.name());

        assertEquals(ErrorCode.DEVICE_IN_BORROWED,
                errorCodeOf(() -> service.changeStatus(1L, request)));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("状态操作：不允许手工把可用设备置为使用中（必须走工单流程）")
    void changeStatus_rejectsManualBorrowed() {
        when(deviceMapper.selectById(1L)).thenReturn(
                device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.AVAILABLE.name()));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus(DeviceStatus.IN_USE.name());

        assertEquals(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.changeStatus(1L, request)));
    }

    @Test
    @DisplayName("状态操作：同状态重复提交视为非法变更")
    void changeStatus_rejectsSameStatus() {
        when(deviceMapper.selectById(1L)).thenReturn(
                device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.AVAILABLE.name()));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus(DeviceStatus.AVAILABLE.name());

        assertEquals(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.changeStatus(1L, request)));
    }

    @Test
    @DisplayName("状态操作：维修中 → 可用（维修完成）允许")
    void changeStatus_allowsMaintenanceCompleted() {
        when(deviceMapper.selectById(1L)).thenReturn(
                device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.MAINTENANCE.name()));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus(DeviceStatus.AVAILABLE.name());
        request.setReason("已更换主板");

        service.changeStatus(1L, request);

        verify(deviceMapper).update(any(), any());
    }

    @Test
    @DisplayName("状态操作：可用 → 报废（管理员报废）允许")
    void changeStatus_allowsScrapFromAvailable() {
        when(deviceMapper.selectById(1L)).thenReturn(
                device(1L, "ThinkPad X1", "IT-0001", DeviceStatus.AVAILABLE.name()));

        DeviceStatusRequest request = new DeviceStatusRequest();
        request.setTargetStatus(DeviceStatus.SCRAPPED.name());
        request.setReason("使用年限到期");

        service.changeStatus(1L, request);

        verify(deviceMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 批量操作（P3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量改状态：全部合法 ⇒ 全部成功，逐台各写一次")
    void batchStatus_allValid() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "A", "IT-0001", DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.selectById(2L)).thenReturn(device(2L, "B", "IT-0002", DeviceStatus.AVAILABLE.name()));

        BatchResultVO result = service.batchChangeStatus(statusRequest(List.of(1L, 2L), "MAINTENANCE"));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSucceeded());
        assertEquals(0, result.getFailed());
        verify(deviceMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("批量改状态：混入一台「使用中」设备 ⇒ 其余成功、该台逐条报错（而不是整批回滚）")
    void batchStatus_partialFailure() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "A", "IT-0001", DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.selectById(2L)).thenReturn(device(2L, "B", "IT-0002", DeviceStatus.IN_USE.name()));

        BatchResultVO result = service.batchChangeStatus(statusRequest(List.of(1L, 2L), "MAINTENANCE"));

        // 成对断言：既确认失败的被逐条报出，也确认合法的那些**确实已经生效**
        // （只断言"有失败"的话，整批都没生效也能通过）
        assertEquals(1, result.getSucceeded(), "合法的第 1 台必须已生效");
        assertEquals(1, result.getFailed(), "非法的第 2 台应逐条报错，而不是中断整批");
        assertEquals(2L, result.getFailures().get(0).getId(), "失败的正是第 2 台（IN_USE）");
        assertEquals("IT-0002", result.getFailures().get(0).getName(),
                "失败明细要用资产编号，用户才能对上台账上的那一台");
        assertTrue(result.getFailures().get(0).getReason().contains("不允许"),
                "失败原因要说清「当前状态不允许」，不能只给一个「失败」");
        verify(deviceMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("批量改状态：超过 200 条 ⇒ 整批拒绝且一条都没处理")
    void batchStatus_exceedLimit() {
        List<Long> tooMany = LongStream.rangeClosed(1, BatchLimits.MAX_SIZE + 1).boxed().toList();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchChangeStatus(statusRequest(tooMany, "MAINTENANCE")));

        assertEquals(ErrorCode.BATCH_SIZE_EXCEEDED, ex.getErrorCode());
        // 「先拒绝」的关键：一条都不能动。若改成"只处理前 200 条"，用户会以为全部完成
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量改状态：重复 id 只处理一次（否则「成功 3 条」里可能只有 2 台设备）")
    void batchStatus_deduplicate() {
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "A", "IT-0001", DeviceStatus.AVAILABLE.name()));

        BatchResultVO result = service.batchChangeStatus(statusRequest(List.of(1L, 1L, 1L), "MAINTENANCE"));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSucceeded());
        verify(deviceMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("批量改状态：目标状态取值非法 ⇒ 整批拒绝（属参数错误，不该变成逐条失败）")
    void batchStatus_invalidTarget() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchChangeStatus(statusRequest(List.of(1L), "NOT_A_STATUS")));

        assertEquals(ErrorCode.DEVICE_STATUS_INVALID, ex.getErrorCode());
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量改分类：目标分类不存在 ⇒ 拒绝且一台都没改（防批量写入悬空分类）")
    void batchCategory_categoryMissing() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchChangeCategory(categoryRequest(List.of(1L, 2L), 99L)));

        assertEquals(ErrorCode.DEVICE_CATEGORY_NOT_FOUND, ex.getErrorCode());
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量改分类：某台设备已不存在 ⇒ 该条失败、其余成功（明细回落 id）")
    void batchCategory_deviceMissing() {
        when(categoryMapper.selectById(7L)).thenReturn(category(7L, "电脑", null, 1));
        when(deviceMapper.selectById(1L)).thenReturn(device(1L, "A", "IT-0001", DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.selectById(2L)).thenReturn(null);

        BatchResultVO result = service.batchChangeCategory(categoryRequest(List.of(1L, 2L), 7L));

        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals("id=2", result.getFailures().get(0).getName(),
                "设备已被删除时明细要回落 id，否则这行显示空白、用户无从定位");
    }

    private DeviceBatchStatusRequest statusRequest(List<Long> ids, String target) {
        DeviceBatchStatusRequest request = new DeviceBatchStatusRequest();
        request.setIds(ids);
        request.setTargetStatus(target);
        request.setReason("批量操作");
        return request;
    }

    private DeviceBatchCategoryRequest categoryRequest(List<Long> ids, Long categoryId) {
        DeviceBatchCategoryRequest request = new DeviceBatchCategoryRequest();
        request.setIds(ids);
        request.setCategoryId(categoryId);
        return request;
    }
}
