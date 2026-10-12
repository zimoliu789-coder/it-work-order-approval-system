package com.enterprise.ticket.module.device.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
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
 * <h2>为什么不按「主部门 department_id」查成员</h2>
 * <p>员工的主部门列（{@code employee.department_id}）只记录**归属**，
 * 而系统的成员关系以 {@code user_department} 为准（借用单的执行人池也是这么取的）。
 * 只查主部门列会漏掉兼职成员 —— 而 IT 运维组恰恰经常由兼职承担。
 * 漏掉的表现是「通知发出去了，但组里的老王没收到」，且不会报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultRepairAssigneeResolver {

    /** 最终处理部门的标记列取值（{@code department.handler_group = 1}） */
    private static final int HANDLER_GROUP = 1;

    private final SystemConfigService systemConfigService;
    private final DepartmentMapper departmentMapper;
    private final UserDepartmentMapper userDepartmentMapper;
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

    private List<Long> departmentMembers(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        List<UserDepartment> members = userDepartmentMapper.selectList(
                Wrappers.<UserDepartment>lambdaQuery().eq(UserDepartment::getDepartmentId, departmentId));
        Set<Long> ids = new LinkedHashSet<>();
        for (UserDepartment member : members) {
            if (member.getUserId() != null) {
                ids.add(member.getUserId());
            }
        }
        return filterActive(ids);
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
