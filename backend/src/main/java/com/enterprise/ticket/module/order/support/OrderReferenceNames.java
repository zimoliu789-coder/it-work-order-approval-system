package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单引用数据的名称 / 字典解析（ ·  · W4-A2）。
 *
 * <h2>它从 OrderServiceImpl 里搬出来的理由</h2>
 * <p>这些方法有三个共同特征：<b>只读</b>（没有一条写语句）、<b>无业务分支</b>
 * （只做「批量取回 → 建 id→名称 映射」）、<b>被读路径与写路径共用</b>
 * （列表/详情装配要用，提交与审批拼装消息正文也要用）。它们原先混在
 * 2581 行的 {@code OrderServiceImpl} 里，与状态机、流程装配、审批流转交织在一起，
 * 使得「想看看工单列表的字段是怎么填的」必须先在几千行里定位。
 *
 * <h2>三条约束（搬运时逐字保留）</h2>
 * <ol>
 *   <li><b>批量取，不逐行查</b>：所有 {@code xxxNames} 方法都是「一条 SQL 取一页」，
 *       这是避免列表 N+1 的关键。新增字段时不要图省事在装配处逐行 selectById；</li>
 *   <li><b>空输入返回 {@code Map.of()}</b>：调用方随后一律走 {@link #mapGet}
 *       （对 null key 返回 null），不得直接 {@code .get()} ——
 *       自定义工单的外键可为 NULL，而 {@code Map.of().get(null)} 会抛 NPE；</li>
 *   <li><b>可空键一律去重</b>：保持稳定顺序，让同样的输入产出同样的 SQL 参数顺序，
 *       便于排查慢查询。</li>
 * </ol>
 */
@Component
public class OrderReferenceNames {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceCategoryMapper categoryMapper;
    private final DepartmentMapper departmentMapper;
    private final ApplyTypeMapper applyTypeMapper;

    public OrderReferenceNames(UserMapper userMapper,
                               DeviceMapper deviceMapper,
                               DeviceCategoryMapper categoryMapper,
                               DepartmentMapper departmentMapper,
                               ApplyTypeMapper applyTypeMapper) {
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.categoryMapper = categoryMapper;
        this.departmentMapper = departmentMapper;
        this.applyTypeMapper = applyTypeMapper;
    }

    /**
     * 空值安全取字典映射。
     *
     * <p>存在的理由很具体：自定义工单的外键可为 {@code NULL}（设备 / 部门 /
     * 处理小组 / 申请类型），而字典映射在「无对应数据」时由 {@code Map.of()} 兜底，
     * 不可变空 Map 的 {@code get(null)} 会抛 {@link NullPointerException} ——
     * 于是「查一页没有自定义工单的列表」会变成 500。统一走这里，把「key 可为空」
     * 这件事实在取用处显式表达出来，而不是分散在每个 {@code .get()} 上赌映射非空。
     *
     * <p>公开且为静态：原先是 {@code OrderServiceImpl} 的包级静态方法，既有单测
     * （{@code OrderReferenceNamesMapGetTest}）直接断言该 NPE 陷阱；W4-A2 搬迁时把
     * 「实现」与「测试」一起搬到这里，原先留在服务类的转发入口（无生产调用方）
     * 已删除，避免留一层只为了让老测试变绿的间接。
     */
    public static <K, V> V mapGet(Map<K, V> map, K key) {
        if (key == null || map == null) {
            return null;
        }
        return map.get(key);
    }

    // ------------------------------------------------------------------
    // 申请类型
    // ------------------------------------------------------------------

    /** 单个工单的申请类型名称（详情 / 完成通知用） */
    public String applyTypeNameOf(Long applyTypeId) {
        if (applyTypeId == null) {
            return null;
        }
        ApplyType type = applyTypeMapper.selectById(applyTypeId);
        return type == null ? null : type.getTypeName();
    }

    /** 批量取申请类型名称（列表「类型」列），一条 SQL 取完，避免逐行查库 */
    public Map<Long, String> applyTypeNames(Collection<Order> orders) {
        Set<Long> ids = orders.stream()
                .map(Order::getApplyTypeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return applyTypeMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ApplyType::getId, ApplyType::getTypeName, (a, b) -> a));
    }

    // ------------------------------------------------------------------
    // 用户
    // ------------------------------------------------------------------

    /** 批量取展示名（显示名 → 登录名），一条 SQL 取完 */
    public Map<Long, String> userNameMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    /**
     * 单个用户的展示名（显示名 → 登录名 → 「用户#id」）。
     *
     * <p>供归还流程拼装站内消息正文使用：批量列表走 {@link #userNameMap} 避免 N+1，
     * 这里只用于单笔操作，一次查询开销可忽略。
     */
    public String userNameOf(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            return "用户#" + userId;
        }
        return user.getDisplayName() == null ? user.getUsername() : user.getDisplayName();
    }

    // ------------------------------------------------------------------
    // 设备 / 分类
    // ------------------------------------------------------------------

    /** 单个设备的展示名「设备名（资产编号）」；设备缺失时退化为 id，避免消息正文出现 null */
    public String deviceLabelOf(Long deviceId) {
        Device device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null) {
            return "设备#" + deviceId;
        }
        return device.getDeviceName() + "（" + device.getAssetNo() + "）";
    }

    /** 一次查出这一页工单涉及的设备，按 deviceId 索引（供装配分别取名称与资产编号） */
    public Map<Long, Device> devicesOf(Collection<Order> orders) {
        Set<Long> deviceIds = orders.stream().map(Order::getDeviceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectBatchIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, device -> device, (a, b) -> a));
    }

    /** 设备分类 id → 名称 */
    public Map<Long, String> categoryNameMap() {
        return categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery()
                        .select(DeviceCategory::getId, DeviceCategory::getCategoryName))
                .stream()
                .collect(Collectors.toMap(DeviceCategory::getId, DeviceCategory::getCategoryName, (a, b) -> a));
    }

    // ------------------------------------------------------------------
    // 部门
    // ------------------------------------------------------------------

    /**
     * 部门 id → 名称。
     *
     * <p> 起，「业务分组」与「最终处理小组」合并为同一棵部门树，
     * 工单上的 {@code department_id}（申请部门）与 {@code handler_department_id}（最终处理部门）
     * 都指向 {@code department} 表，因此**只要一张映射**。
     *
     * <p>改造前这里有两个方法（{@code bizGroupNames} / {@code handlerGroupNames}），
     * 各查一张表 —— 它们合并并非"顺手优化"，而是因为两张表已经不存在了。
     */
    public Map<Long, String> departmentNames() {
        return departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                        .select(Department::getId, Department::getDeptName))
                .stream()
                .collect(Collectors.toMap(Department::getId, Department::getDeptName, (a, b) -> a));
    }
}
