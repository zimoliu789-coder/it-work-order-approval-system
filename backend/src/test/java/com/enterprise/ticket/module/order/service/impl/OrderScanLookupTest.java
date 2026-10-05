package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.ScanAction;
import com.enterprise.ticket.common.constant.ScanMatchType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceFaultService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.vo.ScanLookupVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderExtendService;
import com.enterprise.ticket.module.order.support.OrderReferenceNames;
import com.enterprise.ticket.module.system.service.SystemConfigService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 扫码借还查询单元测试（P1 扫码借还）
 *
 * <h2>这个类真正在守什么</h2>
 * <ul>
 *   <li><b>判定顺序</b>：正在借用的设备状态是 {@code IN_USE}（不可申请），
 *       所以「先看是否我在借、再看是否可借」不可颠倒 —— 颠倒后用户站在自己借的设备前，
 *       得到的答复是「不可借用」而不是「可以归还」。这条有专门的用例钉住。</li>
 *   <li><b>可借口径与列表页一致</b>：{@code DeviceStatus.isApplicable()} + {@code LOCKED}
 *       锁超时特例（规范 §9 / §10），不另写状态名白名单。</li>
 *   <li><b>扫工单号不产生任何写操作</b>：只回答「能不能看」，避免扫单据顺手把设备还了。</li>
 *   <li><b>未识别时不查库</b>：空内容直接短路，不能拿空串去 like 出一堆东西。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("扫码借还查询（P1）")
class OrderScanLookupTest {

    private static final Long ME = 2L;
    private static final Long OTHER = 3L;
    private static final Long ADMIN_ID = 9L;
    private static final Long DEVICE_ID = 11L;
    private static final Long ORDER_ID = 100L;
    private static final String ASSET_NO = "IT-2026-0001";
    private static final String ORDER_NO = "BO20261003-100";

    @BeforeAll
    static void initMyBatisLambdaCache() {
        // DeviceCategory 也要注册：deviceOptionOf 里要拼分类名，走的是 DeviceCategory 的 lambda 查询
        MyBatisLambdaCache.init(Order.class, Device.class, User.class, DeviceCategory.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentManagerMapper departmentManagerMapper;
    @Mock
    private UserDepartmentMapper userDepartmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private MessageService messageService;
    @Mock
    private DeviceFaultService deviceFaultService;
    @Mock
    private OrderExtendService orderExtendService;
    @Mock
    private ApplyTypeMapper applyTypeMapper;

    @InjectMocks
    private OrderServiceImpl service;

    /** 与生产一致：名称解析用真实实例（内部仍走本类已声明的 mock mapper） */
    @BeforeEach
    void wireReferenceNames() {
        ReflectionTestUtils.setField(service, "referenceNames",
                new OrderReferenceNames(userMapper, deviceMapper, categoryMapper,
                        departmentMapper, applyTypeMapper));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 扫「资产编号」：借 / 还 / 不可借 三岔口
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("扫资产编号")
    class ByAssetNo {

        @Test
        @DisplayName("设备空闲且我没有在借 ⇒ 建议借用")
        void borrowable() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.AVAILABLE.name(), null));

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertTrue(vo.getMatched());
            assertEquals(ScanMatchType.ASSET_NO, vo.getMatchType());
            assertEquals(ScanAction.BORROW, vo.getAction());
            assertEquals(ASSET_NO, vo.getDevice().getAssetNo(), "回带设备信息让用户核对手里这台是不是扫到的那台");
            assertNull(vo.getOrder(), "没有在借工单时不应带回工单");
        }

        @Test
        @DisplayName("我有该设备的在借工单 ⇒ 建议归还（设备处于使用中，绝不能落成「不可借用」）")
        void mineShouldReturn() {
            loginAs(ME, RoleCode.USER);
            when(orderMapper.selectOne(any())).thenReturn((Order) null).thenReturn(borrowedOrder());
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.IN_USE.name(), null));
            when(userMapper.selectById(ME)).thenReturn(user(ME, "张三"));

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.RETURN, vo.getAction(),
                    "设备处于 IN_USE（isApplicable=false）—— 若先判可借性，这里会误报成「不可借用」");
            assertEquals(DeviceStatus.IN_USE.name(), vo.getDevice().getStatus());
            assertNotNull(vo.getOrder());
            assertEquals(ORDER_ID, vo.getOrder().getId());
            assertEquals(ORDER_NO, vo.getOrder().getOrderNo());
            assertEquals(ASSET_NO, vo.getOrder().getAssetNo(), "归还页要显示资产编号供核对");
            assertEquals("张三", vo.getOrder().getApplicantName());
            assertTrue(vo.getOrder().getCanRequestReturn());
        }

        @Test
        @DisplayName("他人正在使用 ⇒ 不可借用，并说清还要等归还")
        void inUseByOther() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.IN_USE.name(), null));

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.UNAVAILABLE, vo.getAction());
            assertTrue(vo.getReason().contains("使用中"), "原因文案要能直接展示，别让前端再拼一遍");
            assertNull(vo.getOrder());
        }

        @Test
        @DisplayName("维修中 ⇒ 不可借用")
        void maintenance() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.MAINTENANCE.name(), null));

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.UNAVAILABLE, vo.getAction());
            assertTrue(vo.getReason().contains("维修中"));
        }

        @Test
        @DisplayName("已丢失（P0 新增状态）⇒ 不可借用，并引导联系管理员")
        void lost() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.LOST.name(), null));

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.UNAVAILABLE, vo.getAction());
            assertTrue(vo.getReason().contains("丢失"));
        }

        @Test
        @DisplayName("被他人锁定且未超时 ⇒ 不可借用")
        void lockedFresh() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(device(DeviceStatus.LOCKED.name(), LocalDateTime.now()));
            when(systemConfigService.lockTimeoutMinutes()).thenReturn(5);

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.UNAVAILABLE, vo.getAction());
        }

        @Test
        @DisplayName("锁定已超时 ⇒ 视为已释放，可直接借用（规范 §10）")
        void lockedExpired() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(
                    device(DeviceStatus.LOCKED.name(), LocalDateTime.now().minusMinutes(30)));
            when(systemConfigService.lockTimeoutMinutes()).thenReturn(5);

            ScanLookupVO vo = service.scanLookup(ASSET_NO);

            assertEquals(ScanAction.BORROW, vo.getAction(),
                    "锁超时未释放会让设备看起来永远被别人占着，规范 §10 明确超时视为已释放");
        }

        @Test
        @DisplayName("库里没有这个资产编号 ⇒ 未识别，且提示里回显用户扫到的内容")
        void notFound() {
            loginAs(ME, RoleCode.USER);
            when(deviceMapper.selectOne(any())).thenReturn(null);

            ScanLookupVO vo = service.scanLookup("IT-9999");

            assertFalse(vo.getMatched());
            assertEquals(ScanMatchType.NONE, vo.getMatchType());
            assertEquals(ScanAction.NONE, vo.getAction());
            assertTrue(vo.getReason().contains("IT-9999"),
                    "回显用户扫到的内容，否则他无法自查是不是扫错了码");
        }
    }

    // ------------------------------------------------------------------
    // 扫「工单号」：只读，绝不产生写操作
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("扫工单号")
    class ByOrderNo {

        @Test
        @DisplayName("我是申请人 ⇒ 可查看详情")
        void asApplicant() {
            loginAs(ME, RoleCode.USER);
            Order order = order(OrderStatus.BORROWED.name(), ME);
            when(orderMapper.selectOne(any())).thenReturn(order);
            when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name(), null));

            ScanLookupVO vo = service.scanLookup("ORDER:" + ORDER_NO);

            assertEquals(ScanAction.VIEW, vo.getAction(), "扫单据只回答「能不能看」，不自动触发归还");
            assertEquals(ScanMatchType.ORDER_NO, vo.getMatchType());
            assertEquals(ORDER_NO, vo.getOrder().getOrderNo());
            assertEquals(ASSET_NO, vo.getDevice().getAssetNo());
        }

        @Test
        @DisplayName("我是本单审批人 ⇒ 可查看详情")
        void asApprover() {
            loginAs(ME, RoleCode.USER);
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), OTHER));
            when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_APPROVAL.name(), null));
            when(nodeMapper.selectCount(any())).thenReturn(1L);

            ScanLookupVO vo = service.scanLookup("ORDER:" + ORDER_NO);

            assertEquals(ScanAction.VIEW, vo.getAction());
        }

        @Test
        @DisplayName("admin 可查看任意工单")
        void asAdmin() {
            loginAs(ADMIN_ID, RoleCode.ADMIN);
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.BORROWED.name(), OTHER));
            when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name(), null));

            ScanLookupVO vo = service.scanLookup("ORDER:" + ORDER_NO);

            assertEquals(ScanAction.VIEW, vo.getAction());
        }

        @Test
        @DisplayName("与我无关的工单 ⇒ 不可查看，且**不返回任何工单摘要**")
        void notRelated() {
            loginAs(ME, RoleCode.USER);
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.BORROWED.name(), OTHER));
            when(nodeMapper.selectCount(any())).thenReturn(0L);
            // 刻意**不** stub deviceMapper.selectById：无权分支本就不该去查设备。
            // 「根本没查」比「查了但没返回」更强 —— 它排除了「数据被取出来又碰巧没发出去」的可能。

            ScanLookupVO vo = service.scanLookup("ORDER:" + ORDER_NO);

            assertEquals(ScanAction.UNAVAILABLE, vo.getAction());
            assertTrue(vo.getReason().contains("无权") || vo.getReason().contains("不是你发起"),
                    "要告诉用户「找到了但这单不归你看」，而不是含糊的「未识别」");
            // 反向断言：无权时连摘要都不能带回 —— 否则设备名 / 资产编号 / 申请人姓名
            // 会随响应一起发出去（前端不展示 ≠ 客户端拿不到）。
            assertNull(vo.getOrder(), "无权时不得返回工单摘要");
            assertNull(vo.getDevice(), "无权时不得返回设备信息");
        }
    }

    // ------------------------------------------------------------------
    // 空内容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空内容 ⇒ 未识别，且完全不查库")
    void blankCodeDoesNotQuery() {
        loginAs(ME, RoleCode.USER);

        ScanLookupVO vo = service.scanLookup("   ");

        assertEquals(ScanAction.NONE, vo.getAction());
        assertFalse(vo.getMatched());
        verifyNoInteractions(orderMapper, deviceMapper);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void loginAs(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(role);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private User user(Long id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName(displayName);
        return user;
    }

    private Device device(String status, LocalDateTime lockedAt) {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo(ASSET_NO);
        device.setStatus(status);
        device.setLockedAt(lockedAt);
        return device;
    }

    private Order order(String status, Long applicantId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(applicantId);
        order.setStatus(status);
        return order;
    }

    private Order borrowedOrder() {
        Order order = order(OrderStatus.BORROWED.name(), ME);
        order.setActualFinalHandlerId(OTHER);
        order.setPlannedEndTime(LocalDateTime.now().plusDays(3));
        return order;
    }
}
