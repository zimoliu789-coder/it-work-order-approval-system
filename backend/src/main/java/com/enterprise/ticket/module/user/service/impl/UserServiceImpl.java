package com.enterprise.ticket.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.BatchLimits;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.ReturnTrigger;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.common.util.RandomPasswordGenerator;
import com.enterprise.ticket.common.permission.BuiltinAdmin;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.config.SuperAdminInitializer;
import com.enterprise.ticket.module.auth.service.PasswordPolicyService;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.dto.UserBatchDepartmentRequest;
import com.enterprise.ticket.module.user.dto.UserCreateRequest;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.dto.UserPageQuery;
import com.enterprise.ticket.module.user.dto.UserUpdateRequest;
import com.enterprise.ticket.module.user.dto.vo.DimissionResultVO;
import com.enterprise.ticket.module.user.dto.vo.UserAccountVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
import java.util.stream.Collectors;

/**
 * 员工服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 自动分配登录名的起始序号（ ）。
     *
     * <p>登录名统一为「5 位以上纯数字」并从 {@code 10001} 起编号 —— 与 V27 迁移改写
     * 存量登录名时所用起点一致，使「全新部署」与「升级后的环境」编出的号段落在同一区间，
     * 不会出现两套互不相知的编号。
     */
    private static final long USERNAME_SEQ_START = 10001L;

    /** 账号来源：本地账号（本地掌管口令） */
    private static final String AUTH_TYPE_LOCAL = "LOCAL";

    /** 账号来源：AD 域账号（口令由域控掌管） */
    private static final String AUTH_TYPE_LDAP = "LDAP";

    /** 生成唯一登录名时的最大重试次数，避免极端情况下死循环 */
    private static final int MAX_USERNAME_RETRY = 200;

    private final PasswordEncoder passwordEncoder;

    /**
     * 密码策略：新增员工、重置密码、批量导入三条入口共用同一套强度校验。
     *
     * <p>跨模块依赖说明：本类是 {@code user} 模块，{@code PasswordPolicyService} 属 {@code auth} 模块。
     * 项目既有约定是「跨模块只依赖 Mapper」，但此处依赖 Service 是安全的 ——
     * {@code PasswordPolicyService} 只依赖 {@code SystemConfigService}，不反向依赖 {@code UserService}，
     * 不构成循环依赖；而把密码策略复制一份到 user 模块，必然造成两条入口强度不一致。
     */
    private final PasswordPolicyService passwordPolicyService;

    private final DepartmentMapper departmentMapper;
    /**
     * 离职联动要读取/推进该员工的在办工单。
     *
     * <p>刻意注入其它模块的 {@code Mapper} 而不是 {@code OrderService}：
     * 与项目既有约定一致（跨模块只依赖 Mapper），也避免「用户 ↔ 工单」形成 Service 级循环依赖。
     */
    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper nodeMapper;
    private final DeviceMapper deviceMapper;
    private final MessageService messageService;

    /**
     * 工单转交出口
     *
     * <p>离职联动需要把离职员工名下的在办工单自动转交出去（需求方  ）。
     * 方向为「用户模块 → 工单模块」单向依赖：{@code OrderTransferServiceImpl} 只依赖
     * order/message/handlergroup 的 Mapper 与 MessageService，不反向依赖本服务，无循环依赖。
     */
    private final OrderTransferService orderTransferService;

    /**
     * 角色服务（需求方三波·第一波·）
     *
     * <p>依赖方向：{@code UserServiceImpl → RoleService → UserMapper}。
     * 刻意让 {@code RoleServiceImpl} 直接依赖 {@code UserMapper}（而不是 {@code UserService}）
     * 来统计「该角色有多少员工」—— 否则会形成 Service 级循环依赖，Spring 启动即失败。
     * 这也是项目既有约定「跨模块只依赖 Mapper」的自然延伸。
     */
    private final RoleService roleService;

    /**
     * 业务配置（2026-09-20 ）。
     *
     * <p>只为读取「内置超级管理员的登录名」（{@code app.super-admin.username}，默认
     * {@code administrator}）。超管降级护栏必须能认出「哪个账号是不可被动角色的根账号」，
     * 而这个登录名是配置驱动的（{@code SUPER_ADMIN_USERNAME} 可覆盖），
     * 因此不能把 {@code administrator} 硬编码进业务逻辑。
     */
    private final AppProperties appProperties;

    /** 员工账号列表单页上限 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Override
    public User getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return baseMapper.selectByUsername(username.trim());
    }

    @Override
    public User getByIdRequired(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    public LoginUser loadLoginUser(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = getById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
            return null;
        }
        return new LoginUser(user);
    }

    /**
     * 自动分配一个全局唯一的登录名（ ）。
     *
     * <h2>为什么不再是「姓名 + _2」</h2>
     * <p>规定登录名是「5 位以上纯数字、全局唯一、不可修改」，而姓名允许重复。
     * 旧实现按姓名派生（{@code 张伟} / {@code 张伟_2}），有两个致命问题：
     * <ol>
     *   <li><b>直接违反</b> —— 中文登录名在「登录名不能填中文」的校验下是非法值，
     *       存量数据正是因此需要一次 V27 迁移批量改写；生成器若不改，<b>全新部署</b>
     *       会立刻重新造出一批违规登录名；</li>
     *   <li><b>缺陷被登录逻辑掩盖</b> —— {@code resolveByUsernameThenName} 会按
     *       「先登录名、后姓名」兜底，中文登录名照样能登录，于是测试全绿、
     *       问题只在空库首次导入时才暴露。</li>
     * </ol>
     *
     * <h2>编号口径</h2>
     * <p>取「库中已用最大编号 + 1」，不足 5 位左侧补零。起点兜底为
     * {@link #USERNAME_SEQ_START}（空库时同样从 10001 开始）。若顺延到的编号已被占用
     * （极端并发下被别的线程抢先），继续向后找，最多 {@link #MAX_USERNAME_RETRY} 次，
     * 避免死循环。
     */
    @Override
    public String generateUniqueUsername() {
        long start = Math.max(baseMapper.selectMaxNumericUsername() + 1, USERNAME_SEQ_START);
        for (long seq = start; seq < start + MAX_USERNAME_RETRY; seq++) {
            String candidate = String.format("%05d", seq);
            if (getByUsername(candidate) == null) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS,
                "无法自动生成唯一登录名（已连续尝试 " + MAX_USERNAME_RETRY + " 个编号）");
    }

    @Override
    public void touchLastLoginAt(Long userId) {
        User update = new User();
        update.setId(userId);
        update.setLastLoginAt(LocalDateTime.now());
        updateById(update);
    }

    @Override
    public void updatePassword(Long userId, String rawPassword, boolean clearForceChangeFlag) {
        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(rawPassword));
        if (clearForceChangeFlag) {
            update.setForceChangePassword(false);
        }
        // updateById 默认忽略 null 字段，此处 forceChangePassword 显式赋值才生效
        updateById(update);
    }

    @Override
    public boolean matchesPassword(User user, String rawPassword) {
        if (user == null || !StringUtils.hasText(user.getPasswordHash()) || rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    @Override
    public List<User> listByDepartment(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        return list(Wrappers.<User>lambdaQuery()
                .eq(User::getDepartmentId, departmentId)
                // 在职在前、离职在后，便于管理员优先看到可用成员
                .orderByAsc(User::getDimission)
                .orderByAsc(User::getId));
    }

    @Override
    public List<UserOptionVO> listOptions(String keyword, Long departmentId) {
        // 只取必要列：password_hash / ldap_dn 等敏感字段不进入查询结果（ / ）
        LambdaQueryWrapper<User> query = Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getUsername, User::getDisplayName, User::getRole,
                        User::getDepartmentId, User::getEnabled, User::getDimission)
                .orderByAsc(User::getDepartmentId)
                .orderByAsc(User::getId)
                .last("LIMIT " + MAX_OPTION_SIZE);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(wrapper -> wrapper.like(User::getUsername, kw)
                    .or().like(User::getDisplayName, kw));
        }
        if (departmentId != null) {
            query.eq(User::getDepartmentId, departmentId);
        }
        List<User> users = list(query);
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, String> groupNames = groupNameMap();
        return users.stream()
                .map(user -> UserOptionVO.of(user, groupNames.get(user.getDepartmentId())))
                .toList();
    }

    @Override
    public List<UserOptionVO> listActiveOptions(Collection<Long> ids) {
        // ids == null（不限定）与 ids.isEmpty()（范围内无人）必须走不同路径：
        // MP 的 `.in(column, 空集合)` 会生成 `IN ()` 这种非法 SQL，而若为此把空集合
        // 当成"不限定"，就会把"IT执行人池为空"静默变成"全员可选"—— 那是一次越权。
        if (ids != null && ids.isEmpty()) {
            return List.of();
        }
        // 只取必要列：password_hash / ldap_dn 等敏感字段不进入查询结果（ / ）
        LambdaQueryWrapper<User> query = Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getUsername, User::getDisplayName, User::getRole,
                        User::getDepartmentId, User::getEnabled, User::getDimission)
                .eq(User::getEnabled, true)
                .eq(User::getDimission, false)
                .orderByAsc(User::getDepartmentId)
                .orderByAsc(User::getId)
                .last("LIMIT " + MAX_OPTION_SIZE);
        if (ids != null) {
            query.in(User::getId, ids);
        }
        List<User> users = list(query);
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, String> groupNames = groupNameMap();
        return users.stream()
                .map(user -> UserOptionVO.of(user, groupNames.get(user.getDepartmentId())))
                .toList();
    }

    @Override
    public void assignDepartment(Collection<Long> userIds, Long departmentId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        LambdaUpdateWrapper<User> update = Wrappers.<User>lambdaUpdate().in(User::getId, userIds);
        if (departmentId == null) {
            // LambdaUpdateWrapper#set 传 null 会被 MP 的属性策略忽略，故显式写 NULL
            update.setSql("department_id = NULL");
        } else {
            update.set(User::getDepartmentId, departmentId);
        }
        update(update);
    }

    @Override
    public long countActiveByDepartment(Long departmentId) {
        if (departmentId == null) {
            return 0L;
        }
        return count(Wrappers.<User>lambdaQuery()
                .eq(User::getDepartmentId, departmentId)
                .eq(User::getEnabled, true)
                .eq(User::getDimission, false));
    }

    @Override
    public Map<Long, Long> countGroupedByDepartment() {
        // 别名刻意不含下划线：mapUnderscoreToCamelCase 对 Map 结果集的键名影响因版本而异，
        // 用无下划线别名可彻底规避该不确定性
        List<Map<String, Object>> rows = baseMapper.selectMaps(Wrappers.<User>query()
                .select("department_id AS gid", "COUNT(*) AS cnt")
                .isNotNull("department_id")
                .groupBy("department_id"));
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object groupId = row.get("gid");
            Object count = row.get("cnt");
            if (groupId instanceof Number gid && count instanceof Number cnt) {
                result.put(gid.longValue(), cnt.longValue());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // ：轻量版员工管理 + 离职联动
    // ------------------------------------------------------------------

    @Override
    public PageResult<UserAccountVO> pageAccounts(UserPageQuery query) {
        long safePage = Math.max(query.getPage(), 1L);
        long safeSize = Math.min(Math.max(query.getSize(), 1L), MAX_PAGE_SIZE);

        String role = trimToNull(query.getRole());
        if (role != null && !roleService.exists(role)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "角色筛选值不存在：" + role);
        }
        // 账号来源筛选（ ）：取值只认 LOCAL / LDAP，大小写不敏感。
        // 非法值显式报错而不是当成「全部」——否则运维筛「AD 账号」却看到一屏本地账号，
        // 会误判「同步没生效」，把排查方向带到完全错误的地方。
        String authType = trimToNull(query.getAuthType());
        if (authType != null) {
            authType = authType.toUpperCase(java.util.Locale.ROOT);
            if (!AUTH_TYPE_LOCAL.equals(authType) && !AUTH_TYPE_LDAP.equals(authType)) {
                throw new BusinessException(ErrorCode.AUTH_TYPE_INVALID,
                        "账号来源筛选值只能是 LOCAL 或 LDAP");
            }
        }
        String keyword = trimToNull(query.getKeyword());

        // 只取必要列：password_hash 等敏感字段不进入查询结果（ / ）
        LambdaQueryWrapper<User> wrapper = Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getUsername, User::getRealName, User::getDisplayName, User::getRole,
                        User::getDepartmentId, User::getEnabled, User::getDimission, User::getDimissionAt,
                        User::getCreatedAt, User::getAuthType, User::getEmail, User::getDepartment,
                        User::getAdSyncedAt, User::getLeaderId)
                .eq(query.getDimission() != null, User::getDimission, query.getDimission())
                .eq(query.getDepartmentId() != null, User::getDepartmentId, query.getDepartmentId())
                .eq(role != null, User::getRole, role)
                .eq(authType != null, User::getAuthType, authType)
                // 在职在前、离职在后，与下拉选项的排序保持一致
                .orderByAsc(User::getDimission)
                .orderByAsc(User::getId);
        if (keyword != null) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getRealName, keyword)
                    .or().like(User::getDisplayName, keyword));
        }

        IPage<User> resultPage = page(new Page<>(safePage, safeSize), wrapper);
        List<User> users = resultPage.getRecords();
        List<Long> userIds = users.stream().map(User::getId).toList();
        Map<Long, String> groupNames = groupNameMap();
        Map<Long, Integer> heldCounts = heldDeviceCounts(userIds);
        Map<Long, Integer> approvalCounts = pendingApprovalCounts(userIds);
        // ：直属领导姓名一次性批量取（避免逐行回查 → N+1）
        Map<Long, String> leaderNames = leaderNameMap(users);
        return PageResult.of(resultPage,
                user -> toAccountVO(user, groupNames, heldCounts, approvalCounts, leaderNames));
    }

    /** 批量取这批员工的直属领导显示名。领导账号已被删除时该 id 缺席，VO 里 leaderName 为 null */
    private Map<Long, String> leaderNameMap(Collection<User> users) {
        Set<Long> leaderIds = users == null ? Set.of() : users.stream()
                .map(User::getLeaderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (leaderIds.isEmpty()) {
            return Map.of();
        }
        return listByIds(leaderIds).stream().collect(Collectors.toMap(
                User::getId,
                user -> user.getDisplayName() == null ? user.getRealName() : user.getDisplayName(),
                (a, b) -> a));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DimissionResultVO markDimission(Long userId) {
        User user = getByIdRequired(userId);
        // 两条自我保护规则：超管是审批兜底与系统管理的最后一道防线；
        // 管理员把自己的账号停用会立刻把自己踢出系统（且无法自行恢复）。
        if (RoleCode.isSuperAdmin(user.getRole())) {
            throw new BusinessException(ErrorCode.SUPER_ADMIN_CANNOT_DIMISSION);
        }
        if (Objects.equals(userId, SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.USER_SELF_DIMISSION_FORBIDDEN);
        }
        if (Boolean.TRUE.equals(user.getDimission())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_DIMISSION);
        }

        // 1) 名下「使用中」工单 → 待收回。
        //    需求方已确认：归还只是借用流程的一个状态阶段，**复用原借用单**推进，
        //    不新建 order_type=RETURN 的独立归还单 —— 否则同一台设备会同时存在两笔在办工单。
        List<Order> ongoing = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplicantId, userId)
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .orderByAsc(Order::getId));
        Map<Long, Device> devices = devicesOf(ongoing);
        List<Order> transitioned = new ArrayList<>();
        List<String> orderNos = new ArrayList<>();
        for (Order order : ongoing) {
            int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, OrderStatus.BORROWED.name())
                    .set(Order::getStatus, OrderStatus.PENDING_RETURN.name())
                    .set(Order::getReturnTrigger, ReturnTrigger.DIMISSION.name()));
            if (updated == 0) {
                // 并发下已被归还/其它流程推进，跳过即可（幂等）
                continue;
            }
            transitioned.add(order);
            orderNos.add(order.getOrderNo());
        }

        // 2) 账号禁用（：禁止登录，且不允许再提交 / 审批 / 交付 / 归还）
        LocalDateTime now = LocalDateTime.now();
        String name = displayName(user);
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getDimission, true)
                .set(User::getEnabled, false)
                .set(User::getDimissionAt, now));

        // 3) ：该员工作为「实际执行人」的在办工单自动转交给同组其他在职成员
        //    （需求方  ，解决  遗留的「执行人离职后待办无人处理」限制）。
        //    策略为负载最少优先、并列随机；小组内无其他在职成员时保留原样并告警，绝不阻断离职流程。
        OrderTransferService.AutoTransferResult transferResult =
                orderTransferService.transferOnDimission(userId, name);

        // 4) 通知「当前实际执行人」回收设备。
        //     起实际执行人可能刚在第 3 步被改派，因此必须重新读取当前执行人 ——
        //    否则会把通知发给已离职、已禁用的旧账号，等于没人收到。
        Map<Long, Long> handlerByOrderId = transitioned.isEmpty()
                ? Map.of()
                : orderMapper.selectBatchIds(transitioned.stream().map(Order::getId).toList()).stream()
                        .filter(row -> row.getActualFinalHandlerId() != null)
                        .collect(Collectors.toMap(Order::getId, Order::getActualFinalHandlerId, (a, b) -> a));
        for (Order order : transitioned) {
            Long handlerId = handlerByOrderId.get(order.getId());
            if (handlerId == null || Objects.equals(handlerId, userId)) {
                // 该单未能转交（组内无其他在职成员），仍挂在离职账号上，发通知无接收方
                continue;
            }
            Device device = devices.get(order.getDeviceId());
            String deviceLabel = device == null
                    ? "设备#" + order.getDeviceId()
                    : device.getDeviceName() + "（" + device.getAssetNo() + "）";
            messageService.send(handlerId, MessageType.DIMISSION_RETURN,
                    "员工离职回收设备",
                    "%s 已离职，其借用的设备「%s」已自动转入待收回，请尽快确认收回（工单 %s）。"
                            .formatted(name, deviceLabel, order.getOrderNo()),
                    order.getId());
        }

        // 5) 提示在途审批节点： 只要求「新建快照时替换离职审批人」，
        //    已生成的待办节点不会自动改派（转交的语义是「执行人变更」，不覆盖审批人），
        //    若该员工是当前审批人，工单会停在原地，需管理员手工处理。
        int pendingApprovalCount = pendingApprovalCounts(List.of(userId)).getOrDefault(userId, 0);
        if (pendingApprovalCount > 0) {
            log.warn("员工 {}（id={}）已标记离职，但仍待其处理 {} 个在途审批节点，需管理员手工改派（ 未要求自动改派）",
                    name, userId, pendingApprovalCount);
        }

        DimissionResultVO vo = new DimissionResultVO();
        vo.setUserId(userId);
        vo.setDisplayName(name);
        vo.setOrderCount(transitioned.size());
        vo.setDeviceCount(transitioned.size());
        vo.setOrderNos(orderNos);
        vo.setPendingApprovalCount(pendingApprovalCount);
        vo.setTransferredOrderCount(transferResult.transferred());
        vo.setTransferSkippedCount(transferResult.skipped());
        vo.setMessage(buildDimissionMessage(name, transitioned.size(), transferResult));
        log.info("员工 {}（id={}）已标记离职：账号已禁用，{} 笔在办工单转入待收回，自动转交 {} 笔（跳过 {} 笔）",
                name, userId, transitioned.size(), transferResult.transferred(), transferResult.skipped());
        return vo;
    }

    /** 离职结果的一句话文案（把「转入待收回」「自动转交」「未能转交」三类结果都说清楚） */
    private String buildDimissionMessage(String name, int transitionedCount,
                                         OrderTransferService.AutoTransferResult transfer) {
        if (transitionedCount == 0 && transfer.transferred() == 0 && transfer.skipped() == 0) {
            return "%s 已标记离职，名下没有在办工单，无需回收或转交。".formatted(name);
        }
        StringBuilder sb = new StringBuilder(name).append(" 已标记离职");
        if (transitionedCount > 0) {
            sb.append("，名下 ").append(transitionedCount).append(" 台使用中设备已自动转入待收回并通知执行人回收");
        }
        if (transfer.transferred() > 0) {
            sb.append("，其名下 ").append(transfer.transferred()).append(" 笔在办工单已自动转交给同组在职成员");
        }
        if (transfer.skipped() > 0) {
            sb.append("；另有 ").append(transfer.skipped())
                    .append(" 笔因小组内无其他在职成员未能转交，需管理员手工处理");
        }
        return sb.append("。").toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DimissionResultVO reinstate(Long userId) {
        User user = getByIdRequired(userId);
        if (!Boolean.TRUE.equals(user.getDimission())) {
            throw new BusinessException(ErrorCode.USER_NOT_DIMISSION);
        }
        // set 传 null 会被 MP 的属性策略忽略，故离职时间用 setSql 显式置空（与 assignDepartment 的处理一致）
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getDimission, false)
                .set(User::getEnabled, true)
                .setSql("dimission_at = NULL"));

        String name = displayName(user);
        DimissionResultVO vo = new DimissionResultVO();
        vo.setUserId(userId);
        vo.setDisplayName(name);
        vo.setOrderCount(0);
        vo.setDeviceCount(0);
        vo.setOrderNos(List.of());
        vo.setPendingApprovalCount(0);
        vo.setMessage("%s 已恢复在职，可正常登录。此前已回收的设备需重新提交借用申请，未完成的归还流程不会回退。"
                .formatted(name));
        log.info("员工 {}（id={}）已恢复在职", name, userId);
        return vo;
    }

    // ------------------------------------------------------------------
    // 员工管理增强（需求方 2026-09-18 小迭代 · ）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAccountVO createUser(UserCreateRequest request) {
        return getAccount(insertUser(request));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertUser(UserCreateRequest request) {
        String realName = trimToNull(request.getRealName());
        String username = trimToNull(request.getUsername());
        String role = trimToNull(request.getRole());
        if (realName == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入姓名");
        }
        if (username == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入登录名");
        }

        // 校验顺序即失败原因的优先级：**格式 → 唯一性 → 其它规则**。
        // 顺序里「格式先于唯一」是刻意的（）：用户把「abc」填进登录名时，
        // 他要听到的是「登录名必须是5位以上纯数字」，而不是「登录名已存在」——
        // 后者会把一个格式问题伪装成一个冲突问题，让他去改一个根本没填对的东西。
        assertRealNameFormat(realName);
        assertUsernameFormat(username);
        // ：姓名允许重复，因此这里**没有**姓名唯一性预检；
        // 唯一性只属于登录名（下一行）。
        assertUsernameAvailable(username);
        assertRoleValid(role);
        Long departmentId = requireDepartmentId(request.getDepartmentId());
        // ：直属领导（可选）—— 必须是在职启用用户，且不能是本人（自审回避）
        assertLeaderValid(request.getLeaderId(), null);
        passwordPolicyService.validate(request.getPassword(), username);

        // 联系方式（V26）：格式非法直接 400，已被他人绑定的号码 / 邮箱直接 400；
        // 允许留空（新员工可以先不绑，首次登录时再引导绑定）。
        String phone = normalizePhone(request.getPhone(), null);
        String email = normalizeEmail(request.getEmail(), null);
        assertPhoneAvailable(phone, null);
        assertEmailAvailable(email, null);

        User user = new User();
        user.setUsername(username);
        user.setRealName(realName);
        // 显示名称留空时直接落「姓名」而不是留 null：所有既有消费方（消息文案、下拉选项、
        // 待办列表）都读 display_name，落库时补齐可以让它们无需改动地继续工作。
        user.setDisplayName(defaultDisplayName(request.getDisplayName(), realName));
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setAuthType(AUTH_TYPE_LOCAL);
        user.setDepartmentId(departmentId);
        user.setLeaderId(request.getLeaderId());
        user.setPhone(phone);
        user.setEmail(email);
        // 管理员设置的只是初始密码，员工首登必须自行修改
        user.setForceChangePassword(true);
        user.setEnabled(true);
        user.setDimission(false);
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发下「预检通过、写入被抢先」会落在这里，
            // 转成规范错误码而不是 500（与设备新增同一策略）
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS, "登录名已存在：" + username);
        }
        log.info("新增员工：{}（登录名 {}，角色 {}，分组 {}）", realName, username, role, departmentId);
        // ：「密码被重置」需通知对应用户。新增员工拿到的同样是管理员设定的初始密码
        // （必须首登改密），与「重置」属同一类，故统一发 PASSWORD_RESET 提醒。
        // 本方法被单条新增与批量导入共同复用，通知因此自动覆盖两条路径。
        // best-effort：消息写失败只记日志，绝不回滚已创建成功的员工。
        messageService.send(user.getId(), MessageType.PASSWORD_RESET, "账号已创建，请修改初始密码",
                "你的账号已创建，初始密码由管理员设定，请尽快登录并修改为本人密码。", null);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAccountVO updateUser(Long userId, UserUpdateRequest request) {
        User user = getByIdRequired(userId);
        String realName = trimToNull(request.getRealName());
        String role = trimToNull(request.getRole());
        if (realName == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入姓名");
        }

        //  的格式校验只在「姓名**发生了变化**」时执行。
        //
        // 为什么不无条件校验：库里存在一批历史姓名（如「回归员工052207改」「压测员工1」，
        // 早期「姓名即账号」时期留下的带序号姓名）。无条件校验会让「给这个人改个角色」
        // 这种与姓名无关的编辑直接 400，管理员必须先连姓名一起改 —— 那是把
        // 「历史数据的格式债」转嫁成了「今天的管理操作做不了」。
        // 而「改了就必须合规」这条仍然成立：想落到一个值上，就得按新规则落。
        if (!Objects.equals(realName, trimToNull(user.getRealName()))) {
            assertRealNameFormat(realName);
        }
        assertRoleValid(role);

        // 超管降级护栏（2026-09-20  调整）。
        //
        // 旧规则把「任何超管 → 非超管」一刀切拒绝（USER_SUPER_ADMIN_PROTECTED），
        // 导致内置超管即便想给某个离岗超管收回权限也做不到。新规则放开「降级其它超管」，
        // 但用三条硬约束兜住安全底线，三条按优先级排列、命中即拒：
        //   ① 内置超管账号（登录名 = 配置值，默认 administrator）的角色恒不可改 ——
        //      任何人（含其本人）都不例外，它是系统永不失效的兜底入口；
        //   ② 操作人不得把自己降级 —— 一步之差就会把自己踢出系统，且再也无法自行恢复；
        //   ③ 每次降级都必须保证系统里至少还剩 1 个超管。
        // 三条判定整体排在 requireDepartmentId 之前：否则「把超管降权」会先撞上「缺少部门」
        // 而返回 USER_WITHOUT_DEPARTMENT，安全护栏被下层校验遮蔽，误导排查方向（沿用旧实现约定）。
        boolean demoting = RoleCode.isSuperAdmin(user.getRole()) && !RoleCode.isSuperAdmin(role);
        if (demoting) {
            if (isBuiltinSuperAdmin(user)) {
                throw new BusinessException(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                        "内置超级管理员账号（" + user.getUsername() + "）的角色不可更改");
            }
            if (Objects.equals(userId, SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ErrorCode.USER_SELF_DEMOTE_FORBIDDEN);
            }
            long remainingSuperAdmins = count(Wrappers.<User>lambdaQuery()
                    .eq(User::getRole, RoleCode.SUPER_ADMIN)
                    .ne(User::getId, userId));
            if (remainingSuperAdmins < 1) {
                throw new BusinessException(ErrorCode.USER_LAST_SUPER_ADMIN_PROTECTED);
            }
        }

        Long departmentId = requireDepartmentId(request.getDepartmentId());
        // ：直属领导同样校验（不能是本人 —— 否则「直属领导审批」规则会解析出申请自己，
        // 触发自审回避兜底，看起来像 bug）
        assertLeaderValid(request.getLeaderId(), userId);

        // 角色一旦发生变化，该员工会话里缓存的权限就已过期：把 token_version 原子 +1，
        // 让他的全部旧 Token 立即失效，强制重新登录换取带新角色权限的 Token。
        // （与「重置密码」「整用户下线」共用同一套 jti + ver 机制，见 / Flyway V12。）
        // 联系方式（ / 四.2）：**只有内置超级管理员**能改别人的手机号与邮箱。
        //
        // 其他角色即使前端传了值，服务端也**静默忽略**（而不是报 403）。理由：
        // 员工编辑弹窗是一张整体表单，对非内置超管这两个输入框是置灰只读的；
        // 而「置灰的输入框」在浏览器里仍可能被整体序列化带上（表单收集、浏览器自动填充）。
        // 为一个用户看不见可编辑入口、也无意修改的字段整单报错，属于误伤 ——
        // 他会得到一个自己无法理解、也无法自行解决的失败。
        // 安全上不受影响：忽略的语义就是「这次写入根本不包含这两个字段」，
        // 越权者拿不到任何写入口，只是不会收到一个多余的报错。
        boolean canEditContact = BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties);
        String phone = null;
        String email = null;
        if (canEditContact) {
            // 传空 = 保持原值（不提供「清空」语义，见 UserService#bindContact 的说明）
            phone = normalizePhone(request.getPhone(), user.getPhone());
            email = normalizeEmail(request.getEmail(), user.getEmail());
            assertPhoneAvailable(phone, userId);
            assertEmailAvailable(email, userId);
        }

        LambdaUpdateWrapper<User> updateWrapper = Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getRealName, realName)
                .set(User::getRole, role)
                .set(User::getDepartmentId, departmentId)
                .set(User::getLeaderId, request.getLeaderId())
                .set(User::getDisplayName, defaultDisplayName(request.getDisplayName(), realName));
        if (canEditContact) {
            updateWrapper.set(User::getPhone, phone).set(User::getEmail, email);
        }
        boolean roleChanged = !Objects.equals(user.getRole(), role);
        if (roleChanged) {
            updateWrapper.setSql("token_version = token_version + 1");
        }
        update(updateWrapper);
        log.info("编辑员工：id={} 姓名 {}，角色 {}，分组 {}{}", userId, realName, role, departmentId,
                roleChanged ? "（角色已变更，该员工全部会话已作废）" : "");
        return getAccount(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long userId) {
        User user = getByIdRequired(userId);
        if (RoleCode.isSuperAdmin(user.getRole())
                && !BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties)) {
            // ：超管账号的口令只归内置超管（administrator）处理。
            //
            // 改造前此处对「所有超管」一律拒绝，后果是其他超管一旦忘记口令即成死局：
            // 进不去系统 → 无法自助改密；也没有任何人能替他重置，唯一出路是手工改库。
            // 现在的口径：内置超管可重置其他超管的口令；其他超管与 admin 到此为止 ——
            // 否则任一超管都能顶掉最高管理员的口令，「最高」就不存在了。
            //
            // 判据必须是「当前登录者是不是内置超管」而不是「目标是不是内置超管」：
            // 内置超管重置别的超管是允许的，反过来才不允许。
            throw new BusinessException(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                    "仅内置超级管理员 " + BuiltinAdmin.username(appProperties) + " 可重置超级管理员账号的密码");
        }
        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            // ：AD 用户不能在本地改密码。域账号的凭据只存在于域控，
            // 本地重置出来的口令根本不会被用作登录凭据 —— 用户会以为「密码被重置了」，
            // 实际下次仍必须用域口令，是典型的静默无效操作，因此直接在入口拒绝。
            throw new BusinessException(ErrorCode.AD_PASSWORD_MANAGED_BY_AD,
                    "该员工是 AD 域账号，请在其 AD 域控中重置密码");
        }
        // 系统生成临时口令（2026-09-20 ）：改造前是前端写死一个默认口令，
        // 管理员既看不到、也不被提示，只能靠口口相传 —— 现在由服务端生成并回传。
        // 长度取 12：管理员要抄写或粘贴转交，生成器默认的 32 位过长不实用；
        // 而它保证大写 / 小写 / 数字 / 特殊字符各至少一个，对「一次性口令 +
        // 首登强制改密」这个场景强度已足够。
        final String tempPassword = RandomPasswordGenerator.generate(12);
        // 再走一次策略校验：生成器已保证字符种类，这一步是为了覆盖
        // 「禁用用户名包含」等与字符种类无关的规则（校验必须与新增/改密同源）
        passwordPolicyService.validate(tempPassword, user.getUsername());
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPasswordHash, passwordEncoder.encode(tempPassword))
                // 管理员给的是临时密码，首登必须改密（ / ）
                .set(User::getForceChangePassword, true)
                // 2026-09-20 ：作废该员工全部既有会话（token_version 是校验时比对的
                // 版本号，+1 后旧 Token 立即失效）。被重置口令的人往往正处于「账号可能已泄露」
                // 的场景，只改口令而不作废旧 Token，等于给入侵者留了一把仍在有效期的备用钥匙。
                .setSql("token_version = token_version + 1")
                // 上线前：**同时清空手机号与邮箱**。
                //
                // 重置的语义是「这个账号可能已经泄露，由管理员重新掌握」。若保留原手机号 /
                // 邮箱，那条「自助找回密码」的恢复通道仍然开在**可能已失控的旧联系方式**上 ——
                // 入侵者只要还握着那个手机 / 邮箱，就能绕过管理员刚做的重置把口令再改回去。
                // 清空后，员工下次登录会被引导重新绑定（），此时绑定的是
                // 「本人当前实际持有」的联系方式。
                .set(User::getPhone, null)
                .set(User::getEmail, null));
        //  + 2026-09-20 ：消息正文必须带上临时口令。
        // 管理员在页面上能直接复制，消息里再留一份，避免「弹窗一关口令就再也找不到」。
        messageService.send(userId, MessageType.PASSWORD_RESET, "密码已被重置",
                "您的密码已由管理员重置，临时密码为 " + tempPassword + "，请尽快登录修改。", null);
        log.info("重置员工密码：id={}（{}），已置首登强制改密并作废其全部会话", userId, displayName(user));
        return tempPassword;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAccountVO setEnabled(Long userId, boolean enabled) {
        User user = getByIdRequired(userId);
        if (RoleCode.isSuperAdmin(user.getRole())) {
            throw new BusinessException(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                    "超级管理员账号不可被禁用");
        }
        if (!enabled && Objects.equals(userId, SecurityUtils.getCurrentUserId())) {
            // 禁用自己会立刻把自己踢出系统，且无法自行恢复
            throw new BusinessException(ErrorCode.USER_SELF_DISABLE_FORBIDDEN);
        }
        if (Boolean.TRUE.equals(user.getEnabled()) == enabled) {
            throw new BusinessException(enabled ? ErrorCode.USER_ALREADY_ENABLED : ErrorCode.USER_ALREADY_DISABLED);
        }
        if (enabled && Boolean.TRUE.equals(user.getDimission())) {
            // 离职账号必须保持禁用。这里刻意不复用 reinstate()：
            // 「启用」是账号开关，「恢复在职」是人事状态变更，混在一起会让审计日志语义失真。
            throw new BusinessException(ErrorCode.USER_DIMISSION_USE_REINSTATE);
        }
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getEnabled, enabled));
        log.info("{}员工账号：id={}（{}）", enabled ? "启用" : "禁用", userId, displayName(user));
        return getAccount(userId);
    }

    @Override
    public void assertUsernameAvailable(String username) {
        String login = trimToNull(username);
        if (login == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入登录名");
        }
        if (getByUsername(login) != null) {
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS, "登录名已存在：" + login);
        }
    }

    @Override
    public void assertRoleValid(String role) {
        //  起角色由数据驱动：只要 sys_role 中存在且启用即可分配。
        // 不再硬编码三个编码 —— 否则新建的角色永远无法分配给员工，「角色管理」就成了摆设。
        if (!roleService.isAssignable(role)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE,
                    "角色「" + (role == null ? "" : role) + "」不存在或已停用，请先在「角色与权限」中创建并启用");
        }
    }

    /**
     * 直属领导合法性校验。
     *
     * <p>三条约束，命中即拒：
     * <ol>
     *   <li><b>不能是自己</b> —— 否则「直属领导审批」规则必然解析出申请人本人，
     *       触发自审回避后落成超管兜底，看起来像系统出错；</li>
     *   <li><b>账号必须存在</b>；</li>
     *   <li><b>必须在职且账号启用</b> —— 离职/停用的领导不能作为审批人。
     *       虽然运行期还有「轮到该步骤时失效即兜底替换」的兜底网，但那是异常路径；
     *       能在配置阶段拦住就不该留到提交时才暴露。</li>
     * </ol>
     *
     * @param selfId 被编辑员工自己的 id；新增员工时为 null（此时不存在自指的可能）
     */
    private void assertLeaderValid(Long leaderId, Long selfId) {
        if (leaderId == null) {
            return;
        }
        if (Objects.equals(leaderId, selfId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "直属领导不能是本人");
        }
        User leader = getById(leaderId);
        if (leader == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选直属领导账号不存在，请重新选择");
        }
        if (Boolean.TRUE.equals(leader.getDimission())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "直属领导「" + displayName(leader) + "」已离职，请选择在职员工");
        }
        if (!Boolean.TRUE.equals(leader.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "直属领导「" + displayName(leader) + "」账号已停用，请选择启用状态的员工");
        }
    }

    @Override
    public int bumpTokenVersion(Long userId) {
        if (userId == null) {
            return 0;
        }
        // 原子自增：并发下「读-改-写」会丢失更新，导致本该失效的会话悄悄复活
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .setSql("token_version = token_version + 1"));
        return currentTokenVersion(userId);
    }

    @Override
    public int currentTokenVersion(Long userId) {
        if (userId == null) {
            return 0;
        }
        // 只取版本号一列：登录路径上没必要把密码哈希等字段一起读回来
        User user = getOne(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getTokenVersion)
                .eq(User::getId, userId), false);
        return user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    @Override
    public Long requireDepartmentId(Long departmentId) {
        if (departmentId == null) {
            throw new BusinessException(ErrorCode.USER_WITHOUT_DEPARTMENT);
        }
        if (departmentMapper.selectById(departmentId) == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        return departmentId;
    }

    @Override
    public Long findDepartmentIdByName(String groupName) {
        String name = trimToNull(groupName);
        if (name == null) {
            return null;
        }
        Department group = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getDeptName, name)
                .last("LIMIT 1"));
        return group == null ? null : group.getId();
    }

    @Override
    public UserAccountVO getAccount(Long userId) {
        User user = getByIdRequired(userId);
        return toAccountVO(user, groupNameMap(),
                heldDeviceCounts(List.of(userId)), pendingApprovalCounts(List.of(userId)),
                leaderNameMap(List.of(user)));
    }

    /** 显示名称兜底：入参 → 姓名（保证 display_name 落库后永不为空） */
    private String defaultDisplayName(String requestDisplayName, String realName) {
        String value = trimToNull(requestDisplayName);
        return value == null ? realName : value;
    }

    /** 批量统计「名下使用中设备数」= 这些员工作为申请人的「使用中」工单数（工单与设备一一对应） */
    private Map<Long, Integer> heldDeviceCounts(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getApplicantId)
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .in(Order::getApplicantId, userIds));
        return orders.stream()
                .collect(Collectors.groupingBy(Order::getApplicantId,
                        Collectors.summingInt(order -> 1)));
    }

    /**
     * 批量统计「仍待这些员工处理的在途审批节点数」
     *
     * <p>必须限定「节点位于该工单当前待办步骤」：否则第 2 步的审批人会在第 1 步尚未通过时
     * 被算作有待办（违反 逐级流转），提示数字会虚高。
     */
    private Map<Long, Integer> pendingApprovalCounts(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<OrderApprovalNode> pendingNodes = nodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .in(OrderApprovalNode::getApproverId, userIds));
        if (pendingNodes.isEmpty()) {
            return Map.of();
        }
        Set<Long> orderIds = pendingNodes.stream().map(OrderApprovalNode::getOrderId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 只保留仍处于「审批中」的工单：已驳回/已撤回的工单其节点会被标记 CANCELLED，
        // 但历史上可能存在脏数据，这里再按工单状态收敛一次
        Set<Long> ongoingOrderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .select(Order::getId)
                        .eq(Order::getStatus, OrderStatus.PENDING_APPROVAL.name())
                        .in(Order::getId, orderIds))
                .stream().map(Order::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ongoingOrderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> minStepByOrder = new HashMap<>();
        for (OrderApprovalNode node : pendingNodes) {
            if (!ongoingOrderIds.contains(node.getOrderId())) {
                continue;
            }
            minStepByOrder.merge(node.getOrderId(), node.getStepOrder(), Math::min);
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (OrderApprovalNode node : pendingNodes) {
            if (!ongoingOrderIds.contains(node.getOrderId())
                    || !Objects.equals(minStepByOrder.get(node.getOrderId()), node.getStepOrder())) {
                continue;
            }
            counts.merge(node.getApproverId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 空值安全的 Map 取值。
     *
     * <p>为什么必须有：这批映射在「没有数据 / 没有命中」时是 {@code Map.of()}（不可变空 Map），
     * 而 {@code Map.of().get(null)} 会直接抛 NPE —— {@code ImmutableCollections} 拒绝 null key。
     * 偏偏「未配置直属领导（null）」是最常见的正常状态，所以必须显式判空，
     * 不能依赖 Map 的默认行为。（Order 侧 {@code mapGet} 是同一处坑的另一次教训。）
     */
    private static <K, V> V mapGet(Map<K, V> map, K key) {
        return key == null || map == null ? null : map.get(key);
    }

    private UserAccountVO toAccountVO(User user, Map<Long, String> groupNames,
                                      Map<Long, Integer> heldCounts, Map<Long, Integer> approvalCounts,
                                      Map<Long, String> leaderNames) {
        UserAccountVO vo = new UserAccountVO();
        vo.setId(user.getId());
        vo.setRealName(user.getRealName());
        vo.setDisplayName(user.getDisplayName());
        vo.setUsername(user.getUsername());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setDepartmentName(mapGet(groupNames, user.getDepartmentId()));
        // ：直属领导（未配置 → null；领导账号已删除 → leaderId 有值但 leaderName 为 null，
        // 前端可据此提示「领导账号已不存在」，而不是显示为「未配置」——两者处置不同）
        vo.setLeaderId(user.getLeaderId());
        vo.setLeaderName(mapGet(leaderNames, user.getLeaderId()));
        vo.setRole(user.getRole());
        vo.setRoleLabel(roleLabel(user.getRole()));
        // 内置超管账号角色锁定标记：前端据此把「编辑」弹窗的角色下拉置灰（与后端护栏同源）。
        vo.setRoleLocked(isBuiltinSuperAdmin(user));
        vo.setEnabled(Boolean.TRUE.equals(user.getEnabled()));
        vo.setDimission(Boolean.TRUE.equals(user.getDimission()));
        vo.setDimissionAt(user.getDimissionAt());
        vo.setHeldDeviceCount(heldCounts.getOrDefault(user.getId(), 0));
        vo.setPendingApprovalCount(approvalCounts.getOrDefault(user.getId(), 0));
        vo.setAuthType(authTypeLabelOf(user));
        vo.setAuthTypeLabel(authTypeText(vo.getAuthType()));
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setDepartment(user.getDepartment());
        vo.setAdSyncedAt(user.getAdSyncedAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /**
     * 账号来源归一化：历史数据（ 之前建号）该列可能为空，一律按「本地」处理。
     *
     * <p>不能直接回传 null：前端要按该值决定「重置密码」按钮是否置灰，
     * 空值会让判断落到「既不是 LDAP 也不是 LOCAL」的第三条分支上，行为不可预期。
     */
    private String authTypeLabelOf(User user) {
        return AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType()) ? AUTH_TYPE_LDAP : AUTH_TYPE_LOCAL;
    }

    private String authTypeText(String authType) {
        return AUTH_TYPE_LDAP.equals(authType) ? "AD" : "本地";
    }

    /**
     * 内置超级管理员的登录名。
     *
     * <p>实现已下沉到 {@link BuiltinAdmin#username(AppProperties)} —— 该判定在本轮新增了
     * 「系统名称 / logo 仅内置超管可改」「内置超管可重置其他超管口令」两处使用点，
     * 各写一份必然漂移，因此统一到一处。本方法仅作本类内的可读别名保留。
     */
    private String builtinSuperAdminUsername() {
        return BuiltinAdmin.username(appProperties);
    }

    /**
     * 是否为「内置超级管理员」账号 = 当前角色为 {@code super_admin} 且登录名等于配置的超管登录名。
     *
     * <p>该账号的角色受硬保护：不可被任何人（含其本人）降级，见 {@code updateUser} 护栏 ①；
     * 员工列表的 {@code roleLocked} 也由它派生，前后端判定同源。
     */
    private boolean isBuiltinSuperAdmin(User user) {
        return user != null
                && BuiltinAdmin.isBuiltinAdmin(appProperties, user.getRole(), user.getUsername());
    }

    private Map<Long, Device> devicesOf(Collection<Order> orders) {
        Set<Long> deviceIds = orders.stream().map(Order::getDeviceId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, device -> device, (a, b) -> a, LinkedHashMap::new));
    }

    private String roleLabel(String role) {
        if (RoleCode.SUPER_ADMIN.equals(role)) {
            return "超级管理员";
        }
        if (RoleCode.ADMIN.equals(role)) {
            return "管理员";
        }
        if (RoleCode.USER.equals(role)) {
            return "普通员工";
        }
        //  起支持自定义角色：名称取自角色表；角色已被删除时回退显示编码，
        // 而不是谎报「普通员工」—— 那会掩盖「员工的角色值指向了一个不存在的角色」这一数据问题
        com.enterprise.ticket.module.role.entity.SysRole custom = roleService.getByCode(role);
        return custom == null ? role : custom.getRoleName();
    }

    /** 姓名兜底显示：显示名 → 登录名 */
    private String displayName(User user) {
        return StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 分组ID → 分组名称（分组数量级很小，一次全量查询即可，避免 N+1） */
    private Map<Long, String> groupNameMap() {
        List<Department> groups = departmentMapper.selectList(
                Wrappers.<Department>lambdaQuery().select(Department::getId, Department::getDeptName));
        return groups.stream().collect(Collectors.toMap(Department::getId, Department::getDeptName, (a, b) -> a));
    }

    // ==================================================================
    // 上线前需求（九）：姓名 / 登录名的格式规则
    // ==================================================================

    @Override
    public void assertUsernameFormat(String username) {
        String login = trimToNull(username);
        if (login == null) {
            throw new BusinessException(ErrorCode.USERNAME_FORMAT_INVALID, "请输入登录名");
        }
        if (!AccountFormats.isUsername(login)) {
            throw new BusinessException(ErrorCode.USERNAME_FORMAT_INVALID);
        }
    }

    @Override
    public void assertRealNameFormat(String realName) {
        String name = trimToNull(realName);
        if (name == null) {
            throw new BusinessException(ErrorCode.REAL_NAME_FORMAT_INVALID, "请输入姓名");
        }
        if (!AccountFormats.isChineseName(name)) {
            throw new BusinessException(ErrorCode.REAL_NAME_FORMAT_INVALID);
        }
    }

    // ==================================================================
    // 上线前需求（九）：账号识别
    // ==================================================================

    @Override
    public User findByLoginAccount(String account) {
        String key = trimToNull(account);
        if (key == null) {
            return null;
        }
        return resolveByUsernameThenName(key);
    }

    @Override
    public User findByAccount(String account) {
        String key = trimToNull(account);
        if (key == null) {
            return null;
        }
        return switch (AccountFormats.shapeOf(key)) {
            case EMAIL -> requireContactMatch(baseMapper.selectByEmail(key.toLowerCase()));
            case PHONE -> requireContactMatch(baseMapper.selectByPhone(key));
            // 登录名与姓名合并处理，见 resolveByUsernameThenName 的说明
            case USERNAME, NAME -> resolveByUsernameThenName(key);
        };
    }

    /**
     * 先按登录名精确匹配，匹配不到再按姓名。
     *
     * <h2>为什么不能只靠「输入形态」二选一</h2>
     * <p> 规定<b>新建的</b>登录名必须 5 位以上纯数字，但系统里至少有两个
     * 不满足该形态、却必须能登录的登录名：
     * <ul>
     *   <li>{@code administrator} —— 内置超级管理员的固定登录名（配置驱动），
     *       「纯字母」是它的既定例外，V27 迁移也刻意跳过它；</li>
     *   <li>AD 域账号的登录名 —— 由域控命名（如 {@code zhangsan}），本地无权改。</li>
     * </ul>
     * 若严格按「纯数字 = 登录名、其余 = 姓名」分派，上面两类账号都会掉进姓名查询而查不到，
     * 表现为<b>「超管和所有 AD 用户突然登不进来」</b>——一个只在真实环境才暴露的灾难性回归。
     *
     * <p>因此这里改成「登录名优先」：登录名是<b>全局唯一</b>的精确键，
     * 先试它没有任何副作用（不会误命中别人）；而姓名允许重复，
     * 放在后面并继续保留「重名即报错」的保护（见 {@link #resolveByRealName}）。
     */
    private User resolveByUsernameThenName(String key) {
        User byUsername = baseMapper.selectByUsername(key);
        if (byUsername != null) {
            return byUsername;
        }
        return resolveByRealName(key);
    }

    @Override
    public List<User> listByRealName(String realName) {
        String name = trimToNull(realName);
        return name == null ? List.of() : baseMapper.selectByRealName(name);
    }

    /**
     * 按姓名定位唯一账号；重名时明确报错而不是随便挑一个。
     *
     * <p>「随便挑一个」是最危险的做法：用户输入「张伟」，系统把验证码发给了另一个张伟，
     * 然后那个人用自己的手机号重置了这个账号的密码 —— 两个张伟的账号就打通了。
     * 因此重名必须在这里被拦住。
     */
    private User resolveByRealName(String realName) {
        List<User> matched = baseMapper.selectByRealName(realName);
        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_NAME_AMBIGUOUS);
        }
        return matched.get(0);
    }

    /**
     * 按手机号 / 邮箱查不到账号时的统一处置（）。
     *
     * <p>抛的文案刻意是「未找到绑定该手机号/邮箱的账号」而<b>不是</b>「账号不存在」：
     * 后一种说法等于对外提供了一个「这个号码有没有注册」的查询接口，
     * 可以被批量枚举来反推员工的手机号（从而对这些人发定向钓鱼短信）。
     * 现在的措辞只陈述「没有账号绑定它」，不透露号码本身是否在系统里出现过。
     */
    private User requireContactMatch(User matched) {
        if (matched == null) {
            throw new BusinessException(ErrorCode.CONTACT_NOT_BOUND_TO_ACCOUNT);
        }
        return matched;
    }

    // ==================================================================
    // 上线前需求（二 / 三 / 四）：联系方式绑定与唯一性
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User bindContact(Long userId, String phone, String email) {
        User user = getByIdRequired(userId);

        // 传空 = 保持原值；传入值必须是合法格式
        String nextPhone = normalizePhone(phone, user.getPhone());
        String nextEmail = normalizeEmail(email, user.getEmail());
        if (!StringUtils.hasText(nextPhone) && !StringUtils.hasText(nextEmail)) {
            // ：至少绑定一个。两者都空意味着「绑完还是没法找回密码」，
            // 那这个引导页就白弹了一次。
            throw new BusinessException(ErrorCode.CONTACT_REQUIRED);
        }
        assertPhoneAvailable(nextPhone, userId);
        assertEmailAvailable(nextEmail, userId);

        try {
            update(Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, userId)
                    .set(User::getPhone, nextPhone)
                    .set(User::getEmail, nextEmail));
        } catch (DuplicateKeyException e) {
            throw translateContactDuplicate(e);
        }
        log.info("员工 id={} 更新联系方式（手机={}，邮箱={}）",
                userId, AccountFormats.maskContact(nextPhone), AccountFormats.maskContact(nextEmail));
        return getByIdRequired(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearContacts(Long userId) {
        update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPhone, null)
                .set(User::getEmail, null));
        log.info("已清空员工 id={} 的手机号与邮箱（下次登录将被引导重新绑定）", userId);
    }

    @Override
    public void assertPhoneAvailable(String phone, Long excludeUserId) {
        String value = trimToNull(phone);
        if (value == null) {
            return;
        }
        User owner = baseMapper.selectByPhone(value);
        if (owner != null && !Objects.equals(owner.getId(), excludeUserId)) {
            // 报「已被其他账号绑定」而不回显是谁绑的 —— 回显等于泄露了
            // 「某个同事的手机号」这一关联关系
            throw new BusinessException(ErrorCode.CONTACT_PHONE_EXISTS);
        }
    }

    @Override
    public void assertEmailAvailable(String email, Long excludeUserId) {
        String value = trimToNull(email);
        if (value == null) {
            return;
        }
        User owner = baseMapper.selectByEmail(value);
        if (owner != null && !Objects.equals(owner.getId(), excludeUserId)) {
            throw new BusinessException(ErrorCode.CONTACT_EMAIL_EXISTS);
        }
    }

    /**
     * 归一化手机号：空 → 沿用当前值（不提供清空语义）；非空 → 校验格式后返回。
     *
     * @param input   请求传入值
     * @param current 库中当前值（用于「未传则保持」）
     */
    private String normalizePhone(String input, String current) {
        String value = trimToNull(input);
        if (value == null) {
            return trimToNull(current);
        }
        if (!AccountFormats.isPhone(value)) {
            throw new BusinessException(ErrorCode.CONTACT_PHONE_INVALID);
        }
        return value;
    }

    /** 归一化邮箱：语义同上。邮箱统一转小写，避免「A@x.com」与「a@x.com」被当成两个账号 */
    private String normalizeEmail(String input, String current) {
        String value = trimToNull(input);
        if (value == null) {
            return trimToNull(current);
        }
        if (!AccountFormats.isEmail(value)) {
            throw new BusinessException(ErrorCode.CONTACT_EMAIL_INVALID);
        }
        return value.toLowerCase();
    }

    /**
     * 把唯一索引冲突翻译成「到底是手机号重了还是邮箱重了」。
     *
     * <p>预检（{@link #assertPhoneAvailable}）已经覆盖了绝大多数情况，这里兜的是
     * <b>并发</b>：两个请求同时通过了预检、同时写入，后写入的那个会撞唯一索引。
     * 不做这层翻译，用户看到的是 500「系统内部错误」—— 而他真正需要知道的
     * 只是「这个号码已经被绑走了」。
     */
    // ------------------------------------------------------------------
    // 批量操作（P3）
    // ------------------------------------------------------------------

    @Override
    public BatchResultVO batchChangeDepartment(UserBatchDepartmentRequest request) {
        List<Long> ids = distinctIds(request.getIds());
        Department department = departmentMapper.selectById(request.getDepartmentId());
        if (department == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND, "部门不存在");
        }

        List<BatchResultVO.Failure> failures = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            try {
                if (baseMapper.selectById(id) == null) {
                    throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
                }
                // 只改归属：不动角色，因此不触碰「超管不可降级」那道护栏；
                // 也不重算在途工单的审批人快照 —— 在途流程必须按提交时冻结的规则走完，
                // 否则审批人会中途换人，这类「看起来更实时」的改动是在途流程的污染源。
                baseMapper.update(null, Wrappers.<User>lambdaUpdate()
                        .eq(User::getId, id)
                        .set(User::getDepartmentId, department.getId()));
                succeeded++;
            } catch (BusinessException e) {
                failures.add(userFailureOf(id, e.getMessage()));
            } catch (RuntimeException e) {
                failures.add(userFailureOf(id, "处理失败：" + e.getMessage()));
                log.warn("批量调整部门时用户 id={} 处理失败", id, e);
            }
        }
        log.info("批量调整员工部门 → {}：共 {} 条，成功 {}，失败 {}",
                department.getDeptName(), ids.size(), succeeded, failures.size());
        return BatchResultVO.of(ids.size(), succeeded, failures);
    }

    /**
     * 去重 + 条目数校验（超限先拒绝、一条都不处理，见 {@link BatchLimits}）
     *
     * <p>去重的原因：重复 id 会被处理两次，「成功 3 条」里可能只有 2 个人。
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

    /** 失败明细：展示名用「姓名（登录名）」，无姓名时用登录名，用户已不存在时回落 id */
    private BatchResultVO.Failure userFailureOf(Long id, String reason) {
        BatchResultVO.Failure failure = new BatchResultVO.Failure();
        failure.setId(id);
        User user = baseMapper.selectById(id);
        if (user == null) {
            failure.setName("id=" + id);
        } else if (user.getRealName() == null || user.getRealName().isBlank()) {
            failure.setName(user.getUsername());
        } else {
            failure.setName(user.getRealName() + "（" + user.getUsername() + "）");
        }
        failure.setReason(reason);
        return failure;
    }

    private BusinessException translateContactDuplicate(DuplicateKeyException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("uk_users_email")) {
            return new BusinessException(ErrorCode.CONTACT_EMAIL_EXISTS);
        }
        return new BusinessException(ErrorCode.CONTACT_PHONE_EXISTS);
    }

    // 打码统一走 AccountFormats.maskContact（ ：全库唯一实现）
}
