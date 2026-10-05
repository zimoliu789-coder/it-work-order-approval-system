package com.enterprise.ticket.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.BatchLimits;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.dto.UserBatchDepartmentRequest;
import com.enterprise.ticket.module.user.dto.vo.DimissionResultVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工管理 + 离职联动单元测试（规范 §28，需求方 Phase 5 需求 5；Phase 7 增补自动转交联动）
 *
 * <p>离职联动是本阶段<b>唯一会连锁改多张表</b>的功能（工单状态 + 用户状态 + 执行人转交 + 通知），
 * 因此测试聚焦：
 * <ul>
 *   <li><b>自我保护</b>：超管不可被标记离职；管理员不可停用自己（否则会把自己踢出系统且无法自恢复）；</li>
 *   <li><b>联动完整性</b>：名下「使用中」工单必须全部转「待收回」，并把通知发给<b>转交后的</b>执行人；</li>
 *   <li><b>并发幂等</b>：条件 UPDATE 0 行（工单已被归还/推进）时只跳过、不误报；</li>
 *   <li><b>恢复在职</b>：非离职状态调用恢复应被拒绝。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final Long ADMIN_ID = 3L;
    private static final Long EMP_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(User.class, Order.class, Device.class,
                com.enterprise.ticket.module.department.entity.Department.class);
    }

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private OrderTransferService orderTransferService;
    /**
     * 需求方三波·第一波·需求 4：角色改为数据驱动后，增改员工前会调用
     * {@code roleService.isAssignable(role)} 校验角色可分配性。本类用例聚焦「超管保护」等
     * 护栏，需要该依赖存在（详见各用例的 stub），否则会先抛 NPE 而遮蔽真正的断言目标。
     */
    @Mock
    private RoleService roleService;
    @Mock
    private com.enterprise.ticket.module.auth.service.PasswordPolicyService passwordPolicyService;

    /**
     * 业务配置：{@code updateUser} 的「内置超管」判定要读 {@code app.super-admin.username}。
     *
     * <p>本类刻意不 stub 它（Mock 的 {@code getSuperAdmin()} 返回 null），服务内已对 null
     * 兜底为默认登录名 {@code administrator} —— 因此各用例只要把目标账号的 username 设为
     * {@code administrator} 即可模拟「内置超管」，无需任何额外桩。
     */
    @Mock
    private com.enterprise.ticket.config.AppProperties appProperties;

    @InjectMocks
    private UserServiceImpl service;

    /**
     * 显式注入基类字段 {@code ServiceImpl#baseMapper}。
     *
     * <p>{@code getByIdRequired} 走的是 {@code ServiceImpl.getById → baseMapper.selectById}，
     * 而 {@code baseMapper} 是父类受保护字段、由 Spring 装配，纯 Mockito 的 {@code @InjectMocks}
     * 不会为它赋值（本项目其它服务都直接用显式 Mapper 字段，本类是目前唯一依赖父类 Mapper 的用例）。
     */
    @BeforeEach
    void injectBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(RoleCode.ADMIN);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private User user(Long id, String role, boolean enabled, boolean dimission) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setDimission(dimission);
        return user;
    }

    private Order borrowedOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(EMP_ID);
        order.setActualFinalHandlerId(HANDLER_ID);
        order.setStatus(OrderStatus.BORROWED.name());
        return order;
    }

    private Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo("IT-2026-0001");
        return device;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 标记离职：自我保护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("标记离职：目标为超级管理员 → 拒绝（审批兜底不可被停用）")
    void markDimission_superAdminTarget_rejected() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));

        assertEquals(ErrorCode.SUPER_ADMIN_CANNOT_DIMISSION,
                errorCodeOf(() -> service.markDimission(EMP_ID)));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("标记离职：管理员标记自己 → 拒绝（避免把自己踢出且无法自恢复）")
    void markDimission_self_rejected() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, RoleCode.ADMIN, true, false));

        assertEquals(ErrorCode.USER_SELF_DIMISSION_FORBIDDEN,
                errorCodeOf(() -> service.markDimission(ADMIN_ID)));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("标记离职：员工已是离职状态 → 拒绝重复标记")
    void markDimission_alreadyDimission_rejected() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, false, true));

        assertEquals(ErrorCode.USER_ALREADY_DIMISSION,
                errorCodeOf(() -> service.markDimission(EMP_ID)));
    }

    @Test
    @DisplayName("标记离职：员工不存在 → 报用户不存在")
    void markDimission_userNotFound() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(null);

        assertEquals(ErrorCode.USER_NOT_FOUND, errorCodeOf(() -> service.markDimission(EMP_ID)));
    }

    // ------------------------------------------------------------------
    // 标记离职：联动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("标记离职：名下使用中工单全部转待收回，并逐一通知实际执行人")
    void markDimission_promotesOngoingOrdersAndNotifiesHandlers() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        when(orderMapper.selectList(any())).thenReturn(List.of(borrowedOrder()));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(userMapper.update(any(), any())).thenReturn(1);
        // Phase 7：该员工不是这些工单的执行人（执行人是 HANDLER_ID），故离职自动转交 0 笔 ——
        // 执行人未变，通知仍发给 HANDLER_ID。
        when(orderTransferService.transferOnDimission(any(), any()))
                .thenReturn(new OrderTransferService.AutoTransferResult(0, 0));
        // Phase 7：通知前会重新读取工单当前执行人，避免发给已禁用的离职账号。
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(borrowedOrder()));

        DimissionResultVO vo = service.markDimission(EMP_ID);

        assertEquals(1, vo.getOrderCount());
        assertEquals(1, vo.getDeviceCount());
        assertEquals(List.of("BO-100"), vo.getOrderNos());
        assertEquals(0, vo.getTransferredOrderCount());
        assertEquals(0, vo.getTransferSkippedCount());
        verify(messageService).send(eq(HANDLER_ID), eq(MessageType.DIMISSION_RETURN), any(), any(), eq(ORDER_ID));
        // 账号被禁用（离职联动规范 §28）
        verify(userMapper).update(any(), any());
    }

    @Test
    @DisplayName("标记离职：名下无使用中设备 → 仍禁用账号，且不发送回收通知")
    void markDimission_whenNoOngoingOrders_stillDisablesAccount() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.update(any(), any())).thenReturn(1);
        when(orderTransferService.transferOnDimission(any(), any()))
                .thenReturn(new OrderTransferService.AutoTransferResult(0, 0));

        DimissionResultVO vo = service.markDimission(EMP_ID);

        assertEquals(0, vo.getOrderCount());
        assertTrue(vo.getMessage().contains("没有在办工单"));
        verify(messageService, never()).send(ArgumentMatchers.<Long>any(), any(), any(), any(), any());
        verify(userMapper).update(any(), any());
    }

    @Test
    @DisplayName("标记离职：工单条件更新 0 行（并发已被归还）→ 跳过该单、不误发通知")
    void markDimission_whenOrderConcurrentlyChanged_skipsThatOrder() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        when(orderMapper.selectList(any())).thenReturn(List.of(borrowedOrder()));
        when(orderMapper.update(any(), any())).thenReturn(0);
        when(userMapper.update(any(), any())).thenReturn(1);
        when(orderTransferService.transferOnDimission(any(), any()))
                .thenReturn(new OrderTransferService.AutoTransferResult(0, 0));

        DimissionResultVO vo = service.markDimission(EMP_ID);

        assertEquals(0, vo.getOrderCount());
        verify(messageService, never()).send(ArgumentMatchers.<Long>any(), any(), any(), any(), any());
        verify(userMapper).update(any(), any());
    }

    @Test
    @DisplayName("标记离职：该员工是执行人 → 名下在办工单被自动转交，通知发给新执行人")
    void markDimission_whenIsHandler_autoTransfersAndNotifiesNewHandler() {
        loginAs(ADMIN_ID);
        Long newHandlerId = 9L;
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        // 该单申请人是 EMP_ID 自己且执行人也是 EMP_ID（离职者既借又管）
        Order own = borrowedOrder();
        own.setActualFinalHandlerId(EMP_ID);
        when(orderMapper.selectList(any())).thenReturn(List.of(own));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(userMapper.update(any(), any())).thenReturn(1);
        when(orderTransferService.transferOnDimission(eq(EMP_ID), any()))
                .thenReturn(new OrderTransferService.AutoTransferResult(1, 0));
        // 转交后重新读取到的执行人是 newHandlerId
        Order afterTransfer = borrowedOrder();
        afterTransfer.setActualFinalHandlerId(newHandlerId);
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(afterTransfer));

        DimissionResultVO vo = service.markDimission(EMP_ID);

        assertEquals(1, vo.getTransferredOrderCount());
        assertTrue(vo.getMessage().contains("自动转交"));
        verify(messageService).send(eq(newHandlerId), eq(MessageType.DIMISSION_RETURN), any(), any(), eq(ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 恢复在职
    // ------------------------------------------------------------------

    @Test
    @DisplayName("恢复在职：目标并非离职状态 → 拒绝")
    void reinstate_whenNotDimission_rejected() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));

        assertEquals(ErrorCode.USER_NOT_DIMISSION, errorCodeOf(() -> service.reinstate(EMP_ID)));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("恢复在职：清空离职标记并重新启用账号")
    void reinstate_clearsFlags() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, false, true));
        when(userMapper.update(any(), any())).thenReturn(1);

        DimissionResultVO vo = service.reinstate(EMP_ID);

        assertEquals(0, vo.getOrderCount());
        assertTrue(vo.getMessage().contains("恢复在职"));
        verify(userMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 员工管理增强（需求一）：新增 / 编辑 / 重置密码 / 启用禁用
    // ------------------------------------------------------------------

    private com.enterprise.ticket.module.department.entity.Department department() {
        com.enterprise.ticket.module.department.entity.Department group =
                new com.enterprise.ticket.module.department.entity.Department();
        group.setId(7L);
        group.setDeptName("研发部");
        return group;
    }

    private com.enterprise.ticket.module.user.dto.UserCreateRequest createRequest(
            String realName, String username, String role, String displayName) {
        com.enterprise.ticket.module.user.dto.UserCreateRequest request =
                new com.enterprise.ticket.module.user.dto.UserCreateRequest();
        request.setRealName(realName);
        request.setUsername(username);
        request.setPassword("TestPass@2026");
        request.setDepartmentId(7L);
        request.setRole(role);
        request.setDisplayName(displayName);
        return request;
    }

    @Test
    @DisplayName("新增员工：姓名可重复（需求九.1）→ 同名员工可再次创建，不再抛 USER_NAME_EXISTS")
    void insertUser_duplicateRealName_allowed() {
        // 这条用例锁的是「姓名唯一性预检已被移除」这个事实：
        // 库里早就有一个「张三」，现在仍然要能再建一个「张三」。
        // 若哪天有人把 assertRealNameAvailable 加回来，本用例会立刻变红。
        when(userMapper.selectByUsername("10002")).thenReturn(null);
        when(departmentMapper.selectById(7L)).thenReturn(department());
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);
        when(passwordEncoder.encode("TestPass@2026")).thenReturn("ENC");
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(101L);
            return 1;
        });

        Long id = service.insertUser(createRequest("张三", "10002", RoleCode.USER, null));

        assertEquals(101L, id, "同名员工应被正常创建");
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("新增员工：登录名不是纯数字 → USERNAME_FORMAT_INVALID（需求九.2）")
    void insertUser_usernameNotNumeric_rejected() {
        // 格式检查在唯一性之前：这里不提供任何 Mapper 桩，
        // 一旦校验顺序被改回「先查库」，用例会因为 null 桩空指针而失败 —— 顺序本身也被锁住。
        assertEquals(ErrorCode.USERNAME_FORMAT_INVALID,
                errorCodeOf(() -> service.insertUser(createRequest("张三", "zhangsan", RoleCode.USER, null))));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增员工：姓名含非中文 → REAL_NAME_FORMAT_INVALID（需求九.1）")
    void insertUser_realNameNotChinese_rejected() {
        assertEquals(ErrorCode.REAL_NAME_FORMAT_INVALID,
                errorCodeOf(() -> service.insertUser(createRequest("Zhang San", "10003", RoleCode.USER, null))));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增员工：库内登录名已存在 → USER_USERNAME_EXISTS，不落库")
    void insertUser_usernameExists_rejected() {
        when(userMapper.selectByUsername("10002")).thenReturn(user(EMP_ID, RoleCode.USER, true, false));

        assertEquals(ErrorCode.USER_USERNAME_EXISTS,
                errorCodeOf(() -> service.insertUser(createRequest("张三", "10002", RoleCode.USER, null))));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增员工：成功 → 显示名兜底为姓名、首登强制改密、账号默认启用")
    void insertUser_success_defaults() {
        when(userMapper.selectByUsername(any())).thenReturn(null);
        when(departmentMapper.selectById(7L)).thenReturn(department());
        // 需求 4 起角色可分配性由 sys_role 数据决定，单测中以 stub 模拟「角色存在且启用」
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);
        when(passwordEncoder.encode("TestPass@2026")).thenReturn("ENC");
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(100L);
            return 1;
        });

        Long id = service.insertUser(createRequest("张三", "10002", RoleCode.USER, null));

        assertEquals(100L, id);
        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("张三", saved.getRealName());
        assertEquals("张三", saved.getDisplayName(), "显示名留空时兜底为姓名");
        assertEquals(Boolean.TRUE, saved.getForceChangePassword(), "初始密码必须首登强制改密");
        assertEquals(Boolean.TRUE, saved.getEnabled());
        assertEquals(Boolean.FALSE, saved.getDimission());
        assertEquals("ENC", saved.getPasswordHash());
        verify(passwordPolicyService).validate("TestPass@2026", "10002");
    }

    /** 构造「编辑员工」请求：只填护栏用例关心的三个字段。 */
    private com.enterprise.ticket.module.user.dto.UserUpdateRequest updateRequest(
            String realName, String role, Long departmentId) {
        com.enterprise.ticket.module.user.dto.UserUpdateRequest request =
                new com.enterprise.ticket.module.user.dto.UserUpdateRequest();
        request.setRealName(realName);
        request.setRole(role);
        request.setDepartmentId(departmentId);
        return request;
    }

    @Test
    @DisplayName("编辑员工：降级内置超管账号 → USER_SUPER_ADMIN_PROTECTED（根账号角色恒不可改）")
    void updateUser_builtinSuperAdminDemote_rejected() {
        User builtin = user(EMP_ID, RoleCode.SUPER_ADMIN, true, false);
        builtin.setUsername("administrator");
        when(userMapper.selectById(EMP_ID)).thenReturn(builtin);
        // 需求九.1 起姓名不参与唯一性判定，护栏之前已无 selectCount 调用；
        // 而护栏 ①（内置超管角色恒不可改）命中后同样不会去查「剩余超管数」——
        // 因此本用例全程不需要为 selectCount 给桩。
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);

        assertEquals(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                errorCodeOf(() -> service.updateUser(EMP_ID, updateRequest("管理员", RoleCode.USER, 7L))));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("编辑员工：把自己降级 → USER_SELF_DEMOTE_FORBIDDEN（避免把自己踢出系统）")
    void updateUser_demoteSelf_rejected() {
        loginAs(EMP_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);

        // 护栏 ②（自我保护）先于 ③（保底超管）命中，因此无需为「剩余超管数」再给桩
        assertEquals(ErrorCode.USER_SELF_DEMOTE_FORBIDDEN,
                errorCodeOf(() -> service.updateUser(EMP_ID, updateRequest("管理员", RoleCode.USER, 7L))));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("编辑员工：降级系统最后一个超管 → USER_LAST_SUPER_ADMIN_PROTECTED")
    void updateUser_demoteLastSuperAdmin_rejected() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));
        // selectCount 现在只有一处含义：剩余超管数 = 0（除目标外系统里再无超管）
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);

        assertEquals(ErrorCode.USER_LAST_SUPER_ADMIN_PROTECTED,
                errorCodeOf(() -> service.updateUser(EMP_ID, updateRequest("管理员", RoleCode.USER, 7L))));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("编辑员工：未提供部门也不得降级超管（护栏先于分组校验）")
    void updateUser_demoteLastSuperAdmin_withoutDepartment_rejected() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);

        // 刻意不传 departmentId：护栏必须先于「缺少部门」命中，
        // 否则「把超管降权」会返回误导性的 USER_WITHOUT_DEPARTMENT，掩盖真正的拒绝原因。
        assertEquals(ErrorCode.USER_LAST_SUPER_ADMIN_PROTECTED,
                errorCodeOf(() -> service.updateUser(EMP_ID, updateRequest("管理员", RoleCode.USER, null))));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("编辑员工：降级其它超管 → 放行，且同一条 UPDATE 内作废其全部会话")
    void updateUser_demoteOtherSuperAdmin_allowed() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));
        // 剩余超管数=1（除目标外仍有 1 个超管）→ 三条护栏全部放行。
        // 姓名已不参与唯一性判定，selectCount 不再有「姓名可用性」那一次调用。
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(roleService.isAssignable(RoleCode.USER)).thenReturn(true);
        when(departmentMapper.selectById(7L)).thenReturn(new com.enterprise.ticket.module.department.entity.Department());
        when(userMapper.update(any(), any())).thenReturn(1);
        // getAccount 组装视图所需的空桩：部门名 / 名下设备数 / 在途审批数
        when(departmentMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        com.enterprise.ticket.module.user.dto.vo.UserAccountVO updated =
                service.updateUser(EMP_ID, updateRequest("前超管", RoleCode.USER, 7L));

        // 需求 1 前端契约：非内置超管（其它超管）不应被标记为「角色锁定」，
        // 即前端必须保持其角色下拉可用 —— 否则无从把某个超管降级。
        assertNotNull(updated);
        assertEquals(Boolean.FALSE, updated.getRoleLocked(),
                "非内置超管不应被标记为角色锁定");

        // 角色变更必须与「作废全部会话」在同一条 UPDATE 内完成，避免出现「权限已降但仍持有旧 Token」的窗口
        ArgumentCaptor<Wrapper<User>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).update(any(), wrapperCaptor.capture());
        String sqlSet = ((LambdaUpdateWrapper<User>) wrapperCaptor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("token_version = token_version + 1"),
                "降级后应作废该员工全部会话，实际：" + sqlSet);
    }

    @Test
    @DisplayName("重置密码：超管账号不可被重置 → USER_SUPER_ADMIN_PROTECTED")
    void resetPassword_superAdmin_rejected() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.SUPER_ADMIN, true, false));

        assertEquals(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                errorCodeOf(() -> service.resetPassword(EMP_ID)));
        verify(userMapper, never()).update(any(), any());
        verify(passwordPolicyService, never()).validate(any(), any());
    }

    @Test
    @DisplayName("重置密码：AD 域账号本地重置被拒 → AD_PASSWORD_MANAGED_BY_AD")
    void resetPassword_ldapAccount_rejected() {
        User ldapUser = user(EMP_ID, RoleCode.USER, true, false);
        ldapUser.setAuthType("LDAP");
        when(userMapper.selectById(EMP_ID)).thenReturn(ldapUser);

        assertEquals(ErrorCode.AD_PASSWORD_MANAGED_BY_AD,
                errorCodeOf(() -> service.resetPassword(EMP_ID)));
        verify(userMapper, never()).update(any(), any());
        // 第一个参数用 eq(...) 而非 any()：MessageService#send 有
        // (Long,...) 与 (Collection<Long>,...) 两个重载，全用 any() 编译器无法定夺重载
        verify(messageService, never()).send(eq(EMP_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重置密码：回传临时口令 + 首登强制改密 + 作废全部会话 + 消息带口令（2026-09-20 需求 2）")
    void resetPassword_generatesTempPassword_andBumpsTokenVersion() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        when(passwordEncoder.encode(any())).thenReturn("HASH");

        String tempPassword = service.resetPassword(EMP_ID);

        // 1) 必须回传系统生成的临时口令（约定 12 位）—— 管理员靠它转交给员工。
        //    改造前用的是写死的默认口令 且不回传，管理员根本不知道新口令是什么。
        assertNotNull(tempPassword, "必须回传临时口令，否则管理员无从知晓新密码");
        assertEquals(12, tempPassword.length());

        // 2) 同一条 UPDATE 内完成三件事：写入口令哈希 / 置首登强制改密 / token_version +1。
        //    token_version +1 就是「旧会话立即失效」的实现，需求明确要求，漏掉即账号泄露后门。
        ArgumentCaptor<Wrapper<User>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).update(any(), wrapperCaptor.capture());
        String sqlSet = ((LambdaUpdateWrapper<User>) wrapperCaptor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("force_change_password"), "应置首登强制改密，实际：" + sqlSet);
        assertTrue(sqlSet.contains("token_version = token_version + 1"), "应作废全部会话，实际：" + sqlSet);

        // 3) 站内消息正文必须带上临时口令：管理员误关结果弹窗后，员工仍能从消息里看到
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).send(eq(EMP_ID), eq(MessageType.PASSWORD_RESET), any(),
                contentCaptor.capture(), any());
        assertTrue(contentCaptor.getValue().contains(tempPassword),
                "消息正文应包含临时口令，实际：" + contentCaptor.getValue());

        // 4) 生成的口令同样要过策略校验（与新增 / 自助改密同源，规范 §19）
        verify(passwordPolicyService).validate(eq(tempPassword), eq("u" + EMP_ID));
    }

    @Test
    @DisplayName("禁用账号：管理员禁用自己 → USER_SELF_DISABLE_FORBIDDEN")
    void setEnabled_selfDisable_rejected() {
        loginAs(ADMIN_ID);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, RoleCode.ADMIN, true, false));

        assertEquals(ErrorCode.USER_SELF_DISABLE_FORBIDDEN,
                errorCodeOf(() -> service.setEnabled(ADMIN_ID, false)));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("启用账号：离职账号必须走「恢复在职」→ USER_DIMISSION_USE_REINSTATE")
    void setEnabled_dimissionAccount_rejected() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, false, true));

        assertEquals(ErrorCode.USER_DIMISSION_USE_REINSTATE,
                errorCodeOf(() -> service.setEnabled(EMP_ID, true)));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("启用账号：已启用 → USER_ALREADY_ENABLED（避免无意义的重复操作与误导性成功提示）")
    void setEnabled_alreadyEnabled_rejected() {
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));

        assertEquals(ErrorCode.USER_ALREADY_ENABLED,
                errorCodeOf(() -> service.setEnabled(EMP_ID, true)));
        verify(userMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 批量操作（P3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量改部门：全部合法 ⇒ 全部成功，逐人各写一次")
    void batchDepartment_allValid() {
        when(departmentMapper.selectById(9L)).thenReturn(department());
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        // ⚠️ 第二个人用 999L，不要写 2L —— EMP_ID 本身就是 2，写 2L 会被去重掉只剩 1 条，
        // 用例会「看起来通过」但实际只验证了 1 个元素（本批就是这么撞上的）
        when(userMapper.selectById(999L)).thenReturn(user(999L, RoleCode.USER, true, false));

        BatchResultVO result = service.batchChangeDepartment(
                departmentRequest(List.of(EMP_ID, 999L), 9L));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSucceeded());
        verify(userMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("批量改部门：目标部门不存在 ⇒ 拒绝且一个人都没改")
    void batchDepartment_departmentMissing() {
        when(departmentMapper.selectById(999L)).thenReturn(null);

        assertEquals(ErrorCode.DEPARTMENT_NOT_FOUND,
                errorCodeOf(() -> service.batchChangeDepartment(
                        departmentRequest(List.of(EMP_ID), 999L))));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量改部门：某员工已不存在 ⇒ 该条失败、其余成功（明细回落 id）")
    void batchDepartment_userMissing() {
        when(departmentMapper.selectById(9L)).thenReturn(department());
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));
        when(userMapper.selectById(999L)).thenReturn(null);

        BatchResultVO result = service.batchChangeDepartment(
                departmentRequest(List.of(EMP_ID, 999L), 9L));

        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals("id=999", result.getFailures().get(0).getName());
        assertEquals("用户不存在", result.getFailures().get(0).getReason());
    }

    @Test
    @DisplayName("批量改部门：超过 200 条 ⇒ 整批拒绝且一条都没处理")
    void batchDepartment_exceedLimit() {
        List<Long> tooMany = LongStream.rangeClosed(1, BatchLimits.MAX_SIZE + 1).boxed().toList();

        assertEquals(ErrorCode.BATCH_SIZE_EXCEEDED,
                errorCodeOf(() -> service.batchChangeDepartment(
                        departmentRequest(tooMany, 9L))));
        // 超限在查部门之前就该拦住 ⇒ 连部门查询都不该发生
        verify(departmentMapper, never()).selectById(any());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量改部门：重复 id 只处理一次")
    void batchDepartment_deduplicate() {
        when(departmentMapper.selectById(9L)).thenReturn(department());
        when(userMapper.selectById(EMP_ID)).thenReturn(user(EMP_ID, RoleCode.USER, true, false));

        BatchResultVO result = service.batchChangeDepartment(
                departmentRequest(List.of(EMP_ID, EMP_ID), 9L));

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSucceeded());
        verify(userMapper, times(1)).update(isNull(), any());
    }

    private UserBatchDepartmentRequest departmentRequest(List<Long> ids, Long departmentId) {
        UserBatchDepartmentRequest request = new UserBatchDepartmentRequest();
        request.setIds(ids);
        request.setDepartmentId(departmentId);
        return request;
    }
}
