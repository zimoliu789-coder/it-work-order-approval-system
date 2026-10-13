package com.enterprise.ticket.module.device.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 故障报修「通知给谁」的解析器（V50）
 *
 * <h2>为什么单独成一个组件</h2>
 * <p>「通知谁」这件事有两个使用点，而它们**必须用同一套判定**：
 * <ol>
 *   <li><b>提交时</b>：解析出接收人并发通知（决定「谁会看到这张单」）；</li>
 *   <li><b>接单时</b>：判断「当前用户是否在这个范围内」（决定「谁能接这张单」）。</li>
 * </ol>
 * 若两处各写一遍，迟早出现「通知到了 A，但 A 接不了单」这种自相矛盾的状态 ——
 * 而它表现为「点了接单说无权限」，最容易被当成权限配置问题去排查，
 * 真正的根因却在两个方法的口径不一致。
 *
 * <h2>成员口径：唯一出处是 {@link DepartmentService#activeMemberIdsOf(Long)}</h2>
 * <p>本类**不自己查成员表**，一律委托 {@code DepartmentService#activeMemberIdsOf}：
 * 主部门 {@code employee.department_id} <b>∪</b> 兼职 {@code user_department} 的并集，
 * 再过滤「账号启用 + 未离职」。
 *
 * <p><b>为什么不能只查 {@code user_department}（本类曾经就这么写）</b>：
 * 那张表在运行期**没有任何写入点** —— 生产代码里只有 {@code DemoDataInitializer}
 * 会往里面塞演示数据，真实部署下它恒为空。于是「按部门取成员」会**恒返回空列表且不报错**，
 * 表现为报修通知发不出去、非管理员接不了单（管理员因为走 {@code isAdminOrAbove()}
 * 短路所以测不出来）。
 *
 * <p><b>为什么也不能只查 {@code employee.department_id}</b>：那会漏掉兼职成员 ——
 * IT 运维组恰恰经常由兼职承担。所以必须是**并集**，两个来源缺一不可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultRepairAssigneeResolver {

    /** 最终处理部门的标记列取值（{@code department.handler_group = 1}） */
    private static final int HANDLER_GROUP = 1;

    private final SystemConfigService systemConfigService;
    /** 成员口径的唯一出处（主部门 ∪ 兼职 + 在职）；不要在本类再写一份查询 */
    private final DepartmentService departmentService;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 当前配置
    // ------------------------------------------------------------------

    public FaultRepairSettings.AssigneeType currentType() {
        return FaultRepairSettings.AssigneeType.of(systemConfigService.getString(
                FaultRepairSettings.KEY_ASSIGNEE_TYPE, FaultRepairSettings.DEFAULT_ASSIGNEE_TYPE));
    }

    public String currentValue() {
        String value = systemConfigService.getString(
                FaultRepairSettings.KEY_ASSIGNEE_VALUE, FaultRepairSettings.DEFAULT_ASSIGNEE_VALUE);
        return value == null ? "" : value.trim();
    }

    /**
     * 默认（按部门且未指定）时命中的部门 —— 系统里那个全局唯一的最终处理部门。
     *
     * <p>找不到时返回 {@code null}：那是「还没配置最终处理部门」的部署，
     * 此时不通知任何人，并在调用方记一条 WARN（而不是抛错把报修入口堵死 ——
     * 报修是员工描述故障的唯一途径，不该因为运维侧没配好而不可用）。
     */
    public Long defaultDepartmentId() {
        Department handler = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerGroup, HANDLER_GROUP)
                .last("LIMIT 1"));
        return handler == null ? null : handler.getId();
    }

    /** 部门 / 角色 / 指定人 三种类型下，实际生效的目标值（用于页面回显） */
    public String effectiveValue() {
        String value = currentValue();
        if (currentType() == FaultRepairSettings.AssigneeType.DEPARTMENT && !StringUtils.hasText(value)) {
            Long departmentId = defaultDepartmentId();
            return departmentId == null ? "" : String.valueOf(departmentId);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 当前配置命中的在职用户 id（去重、保持稳定顺序）；无人命中时返回空列表 */
    public List<Long> resolve() {
        FaultRepairSettings.AssigneeType type = currentType();
        String value = currentValue();
        try {
            return switch (type) {
                case DEPARTMENT -> departmentMembers(
                        StringUtils.hasText(value) ? parseId(value) : defaultDepartmentId());
                case ROLE -> activeUsersByRole(value);
                case USER -> singleUser(parseId(value));
            };
        } catch (RuntimeException e) {
            // 配置写坏了（比如手填了一个非数字的部门 id）不该让报修提交失败 ——
            // 记下来、这次不通知，管理员在页面上改回来即可
            log.warn("[报修] 解析通知目标失败（本次不通知，请检查「处理流程」配置）type={} value={} err={}",
                    type, value, e.getMessage());
            return List.of();
        }
    }

    /** 当前用户是否落在通知范围内 —— 接单权限判定用 */
    public boolean covers(Long userId) {
        return userId != null && resolve().contains(userId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("目标值不是合法的数字 id：" + value);
        }
    }

    /**
     * 某部门的在职成员 —— 走 {@link DepartmentService#activeMemberIdsOf(Long)}。
     *
     * <p>本类原先自己查 {@code user_department} 单表：那是「额外兼职」表，
     * 运行期没有任何代码往里写，真实部署下恒为空 ⇒ 报修「通知谁」永远通知不到人、
     * 「谁能接单」永远没人有资格接。而成员口径的正确实现在部门服务里，
     * 与「组织与人员」页看到的成员完全同源。
     */
    private List<Long> departmentMembers(Long departmentId) {
        return departmentService.activeMemberIdsOf(departmentId);
    }

    private List<Long> activeUsersByRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return List.of();
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, roleCode.trim())
                .eq(User::getEnabled, true)
                .eq(User::getDimission, false));
        return users.stream().map(User::getId).filter(Objects::nonNull).distinct().toList();
    }

    private List<Long> singleUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return filterActive(new LinkedHashSet<>(List.of(userId)));
    }

    /**
     * 只保留在职可用账号。
     *
     * <p>离职账号收到通知毫无意义（还可能在离职交接期间引起误会），
     * 而「指定人员已离职」这种情况会让通知**落空且不报错** ——
     * 页面上对 USER 类型的说明里已明确提示这一点。
     */
    private List<Long> filterActive(Set<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(candidateIds).stream()
                .filter(user -> Boolean.TRUE.equals(user.getEnabled()))
                .filter(user -> !Boolean.TRUE.equals(user.getDimission()))
                .map(User::getId)
                .toList();
    }
}
