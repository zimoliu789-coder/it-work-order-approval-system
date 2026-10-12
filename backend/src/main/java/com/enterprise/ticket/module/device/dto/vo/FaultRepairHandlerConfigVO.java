package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 故障报修「处理流程」配置面板数据（V50）
 *
 * <h2>它为什么挂在「申请类型」的配置接口里</h2>
 * 需求方要求这个配置项出现在「申请类型与审批流程 → 故障报修 → 配置」页面上。
 * 但它的**归属**是故障模块（键常量在 {@code FaultRepairSettings}、解析逻辑在
 * {@code FaultRepairAssigneeResolver}），因此这里只做数据搬运：
 * 申请类型服务把它塞进 {@code BuiltInConfigVO.handler}，页面照着渲染。
 * 这样「通知谁」这件事仍然只有一个事实源，没有因为换了个入口而复制出第二份口径。
 *
 * <h2>为什么要带 options 而不是让前端去拉三个接口</h2>
 * 弹窗一打开就要用「部门下拉 / 角色下拉」的候选。让前端分别去调
 * {@code /departments/options} 与 {@code /roles/options}，会带来两个问题：
 * 一是打开弹窗要打三个请求、且任一失败就渲染出一个半残的表单；
 * 二是「部门下拉里到底该出现哪些部门」这个口径会散到前端去。
 * 这里一次算好下发，与本项目 {@code /apply-types/{id}/flow-preview} 同一取向。
 *
 * <h2>为什么没有「人员下拉」</h2>
 * 「指定人员」这一档**刻意不发候选名单**：本系统按规格书要支撑 500 名员工，
 * 把整份名册塞进一个下拉里既慢又不可用。前端改用项目已有的
 * {@code UserSelectDialog}（带关键字搜索与分页），这里只回传当前选中人的姓名供回显
 * （见 {@link #userDisplayName}）。
 */
@Data
public class FaultRepairHandlerConfigVO {

    /** 当前通知类型：DEPARTMENT / ROLE / USER */
    private String assigneeType;

    /**
     * 当前**配置**的目标值原样（{@code DEPARTMENT} 且未指定时为空串）。
     *
     * <p>回显用。注意它与 {@link #effectiveValue} 的区别：前者是管理员填的，
     * 后者是实际生效的 —— 默认部门是在解析时补上的，不写回配置。
     * 若页面拿后者回显，管理员一保存就会把「默认」变成「写死」，此后再改最终处理部门也不会跟着变。
     */
    private String assigneeValue;

    /** 实际生效的目标值（{@code DEPARTMENT} 未指定时 = 系统里那个最终处理部门的 id） */
    private String effectiveValue;

    /** 实际生效目标的显示名（部门显示路径 / 角色名 / 姓名） */
    private String effectiveLabel;

    /**
     * {@link #effectiveValue} 是否来自「默认」而非管理员指定。
     *
     * <p>为 true 时页面应显示「未指定，默认使用最终处理部门」——
     * 让管理员知道这个空值不是「忘了填」，而是一条能在组织架构变动后**自动跟随**的规则。
     */
    private boolean usingDefault;

    /**
     * 按当前配置**实际会通知到的人数**。
     *
     * <p>这个数字是整块配置区最有价值的一项：配错的两种形态都不会报错 ——
     * 「配了但一个人都没有」（部门没有在职成员 / 角色无人持有）与
     * 「指定的人已离职」。后者由 {@code filterActive} 静默过滤掉，
     * 表现是「提交成功但没人收到通知」。把人数摆在页面上，管理员一眼就能发现。
     */
    private int resolvedCount;

    /** 「指定人员」档位下当前选中人的姓名（其余档位为 null） */
    private String userDisplayName;

    /** 三种通知类型（供「按什么通知」的下拉） */
    private List<Option> typeOptions;

    /** 部门候选（按组织架构顺序，含层级路径） */
    private List<Option> departmentOptions;

    /** 角色候选（启用中的角色，按排序号） */
    private List<Option> roleOptions;

    /**
     * 下拉项。
     *
     * @param value       取值（部门 id / 角色码；类型档位则是枚举名）
     * @param label       显示名
     * @param description 一句解释（类型档位用；部门 / 角色为 null）
     */
    public record Option(String value, String label, String description) {
    }
}
