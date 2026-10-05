package com.enterprise.ticket.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.InventoryCheckResult;
import com.enterprise.ticket.common.constant.InventoryStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.inventory.dto.InventoryCheckRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskCreateRequest;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryItemVO;
import com.enterprise.ticket.module.inventory.entity.InventoryTask;
import com.enterprise.ticket.module.inventory.entity.InventoryTaskItem;
import com.enterprise.ticket.module.inventory.mapper.InventoryTaskItemMapper;
import com.enterprise.ticket.module.inventory.mapper.InventoryTaskMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备盘点服务单元测试（P2）
 *
 * <h2>为什么这几条值得单测（而不只是靠冒烟）</h2>
 * <p>冒烟能覆盖「正常路径」与少量边界，但下面这些**都在写库之前**就该拦住，
 * 而且它们的失败形态是「静默写脏数据」，不是报错：
 * <ul>
 *   <li><b>范围与取值不匹配</b>（按分类却没给分类）—— 若放过，会建出一个「范围内 0 台」
 *       或范围含义不明的任务，报告里的盘亏从此不可信；</li>
 *   <li><b>范围内设备过多</b> —— 明细是逐条插入的，不加闸门会先读进内存再爆；</li>
 *   <li><b>明细字段必须来自设备快照</b> —— 若写成「不赋值」，明细里资产编号全是空串，
 *       而页面上看起来只是「待核对清单没编号」；</li>
 *   <li><b>状态闸门</b> —— 已结束的任务还能核对，等于让报告在完成后继续变化。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("设备盘点服务（P2）")
class InventoryServiceImplTest {

    private static final Long ME = 4L;
    private static final Long DEVICE_ID = 11L;
    private static final Long TASK_ID = 100L;
    private static final String ASSET_NO = "IT-2026-0001";

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(InventoryTask.class, InventoryTaskItem.class, Device.class, User.class);
    }

    @Mock
    private InventoryTaskMapper taskMapper;
    @Mock
    private InventoryTaskItemMapper itemMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private InventoryServiceImpl service;

    @BeforeEach
    void login() {
        User user = new User();
        user.setId(ME);
        user.setUsername("u" + ME);
        user.setDisplayName("管理员");
        user.setRole("admin");
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 创建：范围校验
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("创建任务的范围校验")
    class CreateScope {

        @Test
        @DisplayName("范围类型非法 ⇒ 拒绝（不猜默认值）")
        void invalidScopeType() {
            InventoryTaskCreateRequest request = request("LOCATIONX", null);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request));

            assertEquals(ErrorCode.INVENTORY_SCOPE_INVALID, ex.getErrorCode());
            verify(taskMapper, never()).insert(any());
        }

        @Test
        @DisplayName("按分类但没给分类 ⇒ 拒绝")
        void categoryWithoutValue() {
            InventoryTaskCreateRequest request = request("CATEGORY", null);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request));

            assertEquals(ErrorCode.INVENTORY_SCOPE_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("范围内没有设备 ⇒ 拒绝（否则建出一个恒为空的盘点）")
        void emptyScope() {
            when(deviceMapper.selectCount(any())).thenReturn(0L);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request("ALL", null)));

            assertEquals(ErrorCode.INVENTORY_SCOPE_EMPTY, ex.getErrorCode());
            verify(deviceMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("范围过大 ⇒ 拒绝，且**不把数据捞出来**就返回")
        void tooLargeScope() {
            when(deviceMapper.selectCount(any())).thenReturn(6000L);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request("ALL", null)));

            assertEquals(ErrorCode.INVENTORY_SCOPE_TOO_LARGE, ex.getErrorCode());
            // 关键：超限时不能先 selectList 再判断 —— 那等于先 OOM 再报错
            verify(deviceMapper, never()).selectList(any());
        }
    }

    // ------------------------------------------------------------------
    // 创建：明细快照
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 明细是设备的快照（资产编号/名称/位置/台账状态逐项落库）")
    void createsItemsAsSnapshot() {
        when(deviceMapper.selectCount(any())).thenReturn(1L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));

        service.create(request("ALL", null));

        ArgumentCaptor<InventoryTask> taskCaptor = ArgumentCaptor.forClass(InventoryTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        InventoryTask task = taskCaptor.getValue();
        assertEquals(1, task.getTotalCount());
        assertEquals(InventoryStatus.IN_PROGRESS.name(), task.getStatus());
        assertEquals(0, task.getCheckedCount());
        assertNotNull(task.getTaskNo());
        assertTrue(task.getScopeLabel().contains("全部设备"), task.getScopeLabel());

        ArgumentCaptor<InventoryTaskItem> itemCaptor = ArgumentCaptor.forClass(InventoryTaskItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        InventoryTaskItem item = itemCaptor.getValue();
        assertEquals(ASSET_NO, item.getAssetNo());
        assertEquals("ThinkPad X1", item.getDeviceName());
        assertEquals("A座3F研发区", item.getStorageLocation());
        assertEquals(DeviceStatus.AVAILABLE.name(), item.getExpectedStatus());
        // 尚未核对：checkResult 必须是 null（而不是 PENDING 之类的占位值）
        assertNull(item.getCheckResult(), "新建明细的核对结果必须是 null（= 尚未核对）");
    }

    // ------------------------------------------------------------------
    // 核对
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("扫码核对")
    class Check {

        @Test
        @DisplayName("已结束的任务不可再核对")
        void cannotCheckFinishedTask() {
            when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.COMPLETED.name()));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.check(TASK_ID, checkRequest(ASSET_NO, "IN_PLACE")));

            assertEquals(ErrorCode.INVENTORY_STATE_INVALID, ex.getErrorCode());
            verify(itemMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("核对结果取值非法 ⇒ 拒绝")
        void invalidCheckResult() {
            when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.IN_PROGRESS.name()));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.check(TASK_ID, checkRequest(ASSET_NO, "WHATEVER")));

            assertEquals(ErrorCode.INVENTORY_CHECK_RESULT_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("★ 资产编号不在本次范围内 ⇒ 明确拒绝（不是静默写到别处）")
        void assetNotInTask() {
            when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.IN_PROGRESS.name()));
            when(itemMapper.selectOne(any())).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.check(TASK_ID, checkRequest("IT-9999", "IN_PLACE")));

            assertEquals(ErrorCode.INVENTORY_ITEM_NOT_FOUND, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("IT-9999"), "拒绝文案要回显扫到的编号，便于自查是否扫错标签");
            verify(itemMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("★ 核对成功：写核对结果 + 重算任务计数（用 SQL 重数，不做内存累加）")
        void checkRecountsFromSql() {
            when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.IN_PROGRESS.name()));
            InventoryTaskItem item = item();
            when(itemMapper.selectOne(any())).thenReturn(item);
            when(itemMapper.selectById(item.getId())).thenReturn(item);
            // recount 会按「已核对 / 在库 / 缺失 / 位置不符」各查一次
            when(itemMapper.selectCount(any())).thenReturn(1L);
            when(userMapper.selectBatchIds(any())).thenReturn(List.of());

            InventoryItemVO vo = service.check(TASK_ID, checkRequest(ASSET_NO, "MISSING"));

            verify(itemMapper).update(any(), any());
            // 4 次计数（countByResult 调 4 遍）
            verify(itemMapper, times(4)).selectCount(any());
            verify(taskMapper).update(any(), any());
            assertNotNull(vo);
        }
    }

    // ------------------------------------------------------------------
    // 结束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完成盘点：状态闸门（已结束的任务不能再完成）")
    void cannotCompleteTwice() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.CANCELLED.name()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(TASK_ID, "x"));

        assertEquals(ErrorCode.INVENTORY_STATE_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("完成时带前置条件更新：并发下被别人抢先结束时命中 0 行 ⇒ 报错而不是静默成功")
    void completeDetectsConcurrentFinish() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(InventoryStatus.IN_PROGRESS.name()));
        when(taskMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(TASK_ID, "x"));

        assertEquals(ErrorCode.INVENTORY_STATE_INVALID, ex.getErrorCode());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private InventoryTaskCreateRequest request(String scopeType, String scopeValue) {
        InventoryTaskCreateRequest request = new InventoryTaskCreateRequest();
        request.setTaskName("测试盘点");
        request.setScopeType(scopeType);
        request.setScopeValue(scopeValue);
        return request;
    }

    private InventoryCheckRequest checkRequest(String assetNo, String result) {
        InventoryCheckRequest request = new InventoryCheckRequest();
        request.setAssetNo(assetNo);
        request.setCheckResult(result);
        return request;
    }

    private InventoryTask task(String status) {
        InventoryTask task = new InventoryTask();
        task.setId(TASK_ID);
        task.setTaskNo("PC-20261003-001");
        task.setTaskName("测试盘点");
        task.setScopeLabel("全部设备");
        task.setStatus(status);
        task.setTotalCount(5);
        task.setCheckedCount(0);
        task.setInPlaceCount(0);
        task.setMissingCount(0);
        task.setWrongLocationCount(0);
        return task;
    }

    private InventoryTaskItem item() {
        InventoryTaskItem item = new InventoryTaskItem();
        item.setId(200L);
        item.setTaskId(TASK_ID);
        item.setDeviceId(DEVICE_ID);
        item.setAssetNo(ASSET_NO);
        item.setDeviceName("ThinkPad X1");
        return item;
    }

    private Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo(ASSET_NO);
        device.setStorageLocation("A座3F研发区");
        device.setStatus(DeviceStatus.AVAILABLE.name());
        return device;
    }
}
