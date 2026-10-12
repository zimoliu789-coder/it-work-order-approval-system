package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.device.dto.vo.FaultRepairHandlerConfigVO;
import com.enterprise.ticket.module.device.support.FaultRepairAssigneeResolver;
import com.enterprise.ticket.module.device.support.FaultRepairSettings;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 故障报修「处理流程」配置的读写入口（V50）
 *
 * <h2>它把「通知给谁」收成一个可以被页面读写的整体</h2>
 * 读（{@link #describe()}）返回：当前配置 + 实际生效目标 + **当前会通知到几个人** + 三个下拉的候选。
 * 写（{@link #save(String, String)}）做完整的取值校验。
 *
 * <h2>为什么把这个服务放在 device 模块</h2>
 * 键常量（{@code FaultRepairSettings}）、解析口径（{@code FaultRepairAssigneeResolver}）
 * 都在故障域里。页面入口虽然在「申请类型与审批流程」，但那只是**入口**——
 * 若把读写逻辑搬到 applytype 模块，那边就要知道 {@code fault_repair_assignee_type} 这种键名，
 * 而「通知谁」这件事从此有了两个可能出现分歧的口径。
 * 这里只暴露 {@code describe / save} 两个方法，入口在哪都不影响事实源只有一份。
 *
 * <h2>校验为什么必须严于「解析器的容错」</h2>
 * {@code FaultRepairAssigneeResolver} 对非法值是**容错**的（解析失败记 WARN、本次不通知），
 * 因为报修提交不能因为运维侧配置写坏而失败。但**保存**是管理员主动动作，
 * 此时静默纠正恰恰是最坏的选择：他把类型选成「指定人员」、值填了「张三」这种名字，
 * 存进去变成「按部门且未指定」，页面刷新后看着像是保存成功了 ——
 * 而实际上按部门通知的完全不是他想要的人。因此这里对每一种类型都做取值校验，
 * 并在无法命中任何人时给出**明确提示**而不是报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultRepairHandlerConfigService {

    private final SystemConfigService systemConfigService;
    private final FaultRepairAssigneeResolver assigneeResolver;
    private final DepartmentService departmentService;
    private final RoleService roleService;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 配置面板数据：当前配置 + 生效目标 + 命中人数 + 可选项 */
    public FaultRepairHandlerConfigVO describe() {
        FaultRepairSettings.AssigneeType type = assigneeResolver.currentType();
        String configuredValue = assigneeResolver.currentValue();

        FaultRepairHandlerConfigVO vo = new FaultRepairHandlerConfigVO();
        vo.setAssigneeType(type.name());
        vo.setAssigneeValue(configuredValue);
        vo.setTypeOptions(typeOptions());
        vo.setDepartmentOptions(departmentOptions());
        vo.setRoleOptions(roleOptions());

        boolean usingDefault = type == FaultRepairSettings.AssigneeType.DEPARTMENT
                && !StringUtils.hasText(configuredValue);
        vo.setUsingDefault(usingDefault);

        String effectiveValue = assigneeResolver.effectiveValue();
        vo.setEffectiveValue(effectiveValue);
        vo.setEffectiveLabel(labelOf(type, effectiveValue, usingDefault));
        // 命中人数：与「提交时通知谁」「接单时谁有权限」用的是同一个解析器 ——
        // 页面上看到的数字，就是系统实际会做的事，不是另算一遍的估计值。
        vo.setResolvedCount(assigneeResolver.resolve().size());
        if (type == FaultRepairSettings.AssigneeType.USER && StringUtils.hasText(configuredValue)) {
            vo.setUserDisplayName(displayNameOf(parseIdOrNull(configuredValue)));
        }
        return vo;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 保存「通知给谁」。
     *
     * @param assigneeType 通知类型枚举名（DEPARTMENT / ROLE / USER），大小写不敏感
     * @param assigneeValue 目标值（部门 id / 角色码 / 用户 id）；{@code DEPARTMENT} 允许为空 =
     *                      沿用系统里那个最终处理部门
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(String assigneeType, String assigneeValue) {
        FaultRepairSettings.AssigneeType type = parseType(assigneeType);
        String value = assigneeValue == null ? "" : assigneeValue.trim();

        switch (type) {
            case DEPARTMENT -> {
                // 空值合法：表示「跟着最终处理部门走」。这是默认值，也是唯一会自动跟随组织架构变动的选项。
                if (StringUtils.hasText(value)) {
                    Long departmentId = parseId(value, "部门");
                    if (departmentOptions().stream().noneMatch(option ->
                            option.value().equals(String.valueOf(departmentId)))) {
                        throw new BusinessException(ErrorCode.PARAM_INVALID,
                                "所选部门不存在或已被删除，请重新选择");
                    }
                }
            }
            case ROLE -> {
                if (!StringUtils.hasText(value)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "选择「按角色」时必须指定角色；留空会导致一条通知也发不出去");
                }
                if (roleService.getByCode(value) == null) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "所选角色不存在：" + value);
                }
            }
            case USER -> {
                if (!StringUtils.hasText(value)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "选择「指定人员」时必须指定一个人；留空会导致一条通知也发不出去");
                }
                Long userId = parseId(value, "人员");
                User user = userMapper.selectById(userId);
                if (user == null) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "所选人员不存在");
                }
                // 离职 / 停用的人收不到通知（解析器会静默过滤），
                // 若无条件放过，管理员会得到一个「保存成功但永远不会有人处理」的配置。
                if (!Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "「" + displayName(user) + "」已停用或已离职，收不到报修通知，请换人");
                }
            }
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的通知类型");
        }

        // 两次写入落在同一次事务里：类型与值必须同时生效。
        // 若只写成功一半，会得到「类型=指定人员、值=部门的数字 id」这种组合 ——
        // 解析时按 user id 去找一个部门 id，结果是没人收到通知且不报错。
        systemConfigService.updateValue(FaultRepairSettings.KEY_ASSIGNEE_TYPE, type.name());
        systemConfigService.updateValue(FaultRepairSettings.KEY_ASSIGNEE_VALUE, value);

        // 保存后立刻算一次命中人数。**不因为「0 人」而拒绝保存** ——
        // 「角色已建好、但还没给人挂上」是配置过程中的正常中间态，
        // 拦住它只会逼管理员去走一条更绕的路。这里的价值是把事实说出来。
        int resolved = assigneeResolver.resolve().size();
        if (resolved == 0) {
            log.warn("[报修] 「处理流程」已保存，但当前配置命中 0 名在职人员（类型={} 值={}）——"
                    + "报修仍可提交，但不会有人收到通知", type, value);
        } else {
            log.info("[报修] 「处理流程」已更新：类型={} 值={}，当前命中 {} 名在职人员", type, value, resolved);
        }
    }

    // ------------------------------------------------------------------
    // 内部：可选项与显示名
    // ------------------------------------------------------------------

    private List<FaultRepairHandlerConfigVO.Option> typeOptions() {
        List<FaultRepairHandlerConfigVO.Option> options = new ArrayList<>();
        for (FaultRepairSettings.AssigneeType type : FaultRepairSettings.AssigneeType.values()) {
            options.add(new FaultRepairHandlerConfigVO.Option(
                    type.name(), type.getLabel(), type.getDescription()));
        }
        return options;
    }

    /**
     * 部门候选：复用 {@code DepartmentService.options()}，拿它的 {@code displayPath}。
     *
     * <p>刻意复用而不是在本服务里自己遍历部门树：下拉里「部门名怎么拼」的口径
     * （是否带上级路径、怎么处理同名部门）必须全站只有一份，
     * 否则这里选出来的显示名会和员工管理、流程配置里的对不上。
     */
    private List<FaultRepairHandlerConfigVO.Option> departmentOptions() {
        List<FaultRepairHandlerConfigVO.Option> options = new ArrayList<>();
        for (DepartmentOptionVO dept : departmentService.options()) {
            String label = StringUtils.hasText(dept.getDisplayPath())
                    ? dept.getDisplayPath() : dept.getDeptName();
            options.add(new FaultRepairHandlerConfigVO.Option(
                    String.valueOf(dept.getId()), label, null));
        }
        return options;
    }

    /** 角色候选：启用中的角色（{@code assignableRoles} 已是「启用 + 按排序号」的口径） */
    private List<FaultRepairHandlerConfigVO.Option> roleOptions() {
        List<FaultRepairHandlerConfigVO.Option> options = new ArrayList<>();
        for (RoleVO role : roleService.assignableRoles()) {
            options.add(new FaultRepairHandlerConfigVO.Option(
                    role.getRoleCode(), role.getRoleName(), null));
        }
        return options;
    }

    /**
     * 生效目标的显示名。
     *
     * <p>取不到时退化成 {@code 「部门 #12」} 这类可辨认的占位，而不是空串 ——
     * 空串会让页面上那一行「将通知：」看起来像渲染坏了，
     * 而带 id 的占位至少能让管理员去库里确认到底指向什么。
     */
    private String labelOf(FaultRepairSettings.AssigneeType type, String effectiveValue, boolean usingDefault) {
        if (!StringUtils.hasText(effectiveValue)) {
            return "（未配置，当前不会通知任何人）";
        }
        return switch (type) {
            case DEPARTMENT -> {
                String label = departmentOptions().stream()
                        .filter(option -> option.value().equals(effectiveValue))
                        .map(FaultRepairHandlerConfigVO.Option::label)
                        .findFirst()
                        .orElse("部门 #" + effectiveValue);
                yield usingDefault ? label + "（未指定，默认使用最终处理部门）" : label;
            }
            case ROLE -> roleOptions().stream()
                    .filter(option -> option.value().equals(effectiveValue))
                    .map(FaultRepairHandlerConfigVO.Option::label)
                    .findFirst()
                    .orElse("角色 " + effectiveValue);
            case USER -> {
                String name = displayNameOf(parseIdOrNull(effectiveValue));
                yield name == null ? "用户 #" + effectiveValue : name;
            }
        };
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return displayName(userMapper.selectById(userId));
    }

    private String displayName(User user) {
        if (user == null) {
            return null;
        }
        String name = user.getDisplayName();
        return StringUtils.hasText(name) ? name : user.getUsername();
    }

    /**
     * 解析通知类型。
     *
     * <p>刻意**不用** {@code AssigneeType.of()}：那个方法的语义是「容错读取配置」
     * （非法值一律回落 DEPARTMENT），用在读取侧是对的 —— 库里被手工改坏了也不该让报修挂掉。
     * 但用在写入侧会把「管理员选错了」静默改写成「按部门」，属于最不该发生的失败形态。
     */
    private FaultRepairSettings.AssigneeType parseType(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择通知方式");
        }
        for (FaultRepairSettings.AssigneeType type : FaultRepairSettings.AssigneeType.values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的通知方式：" + raw);
    }

    private Long parseId(String value, String what) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选" + what + "的取值不合法：" + value);
        }
    }

    private Long parseIdOrNull(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
