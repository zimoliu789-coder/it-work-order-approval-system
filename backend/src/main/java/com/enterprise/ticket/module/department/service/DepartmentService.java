package com.enterprise.ticket.module.department.service;

import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.dto.vo.DepartmentDeleteResultVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentNodeVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.user.dto.UserOptionVO;

import java.util.List;
import java.util.Map;

/**
 * 组织与人员 —— 部门侧服务（，需求文档 二）。
 *
 * <h2>它是「审批上级」的事实源</h2>
 * <p>{@link #primaryManagerOf(Long)} 回答「这个部门的部门主管是谁」，
 * 而 {@code ApproverRuleResolver} 用它来解析审批人规则
 * {@code DEPARTMENT_MANAGER}，{@code UserServiceImpl} 用它来算「直属主管默认值」。
 * 因此本接口里任何一处「主管解析口径」的改动，都会直接改变审批走到谁那里 ——
 * 这也是为什么主管解析只允许有这一个实现（不要在下游各写一份）。
 */
public interface DepartmentService {

    /** 完整部门树（含每部门人数与主管） */
    List<DepartmentNodeVO> tree();

    /** 部门扁平选项（带层级缩进的展示名），供各处下拉使用 */
    List<DepartmentOptionVO> options();

    /** 新增部门，返回新部门 id */
    Long create(DepartmentSaveRequest request);

    /** 编辑部门（名称 / 排序 / 绑定流程 / 备注）；<b>不含移动</b>，移动走 {@link #move} */
    void update(Long id, DepartmentSaveRequest request);

    /** 移动部门到新的上级（会整体重写子树的 path / depth） */
    void move(Long id, Long newParentId);

    /** 删除部门：其成员上移到父部门、其主管关系一并清除；有子部门时拒绝 */
    DepartmentDeleteResultVO delete(Long id);

    /** 整体替换部门主管，并把该部门**未手工覆盖过**的成员的直属主管同步为新主管 */
    void setManagers(Long departmentId, List<Long> userIds);

    /**
     * 该部门的主主管（多个主管时取 {@code user_id} 最小者）；无主管返回 {@code null}。
     *
     * <p><b>这是「直属主管默认值」的唯一出处</b>（见 {@code employee.leader_override}）：
     * 未被手工覆盖的成员，其直属主管就是它。
     */
    Long primaryManagerOf(Long departmentId);

    /** 部门的全部主管 user_id（含副职），顺序稳定 */
    List<Long> managerIdsOf(Long departmentId);

    /** 最终处理部门（IT运维组）的 id；未配置返回 {@code null} */
    Long handlerDepartmentId();

    /**
     * 全局默认处理小组 id（V53）；未配置返回 {@code null}。
     *
     * <p>与 {@link #handlerDepartmentId()} 的区别：后者是 V53 之前「全局唯一组」的旧口径
     * （只是「取不到默认时回落 id 最小的那个」）；本方法是<b>显式标记</b>的默认组，
     * 在「分部逐级匹配失败」时兜底用。
     */
    Long defaultHandlerDepartmentId();

    /**
     * 从某个部门出发，<b>沿组织树逐级向上</b>找绑定的处理小组（V53 的核心解析）。
     *
     * <h2>为什么要从「部门」出发，而不是直接传分部</h2>
     * <p>调用方手里的起点形态不统一：申请人只有 {@code employee.department_id}（可能是三级部门），
     * 表单里选的是「一级分部」。把「往上走」的逻辑收在这里，调用方只需给一个部门 id ——
     * 与 {@link #parentIdOf(Long)} 的收口理由相同（部门树怎么走只有一处实现）。
     *
     * <p><b>不返回兜底值</b>：找不到就返回 {@code null}，由调用方决定「用默认组 + 告警」
     * 还是直接报错 —— 若在这里静默兜底，调用方永远不知道某个分部漏配了。
     *
     * @param startDepartmentId 起点部门（可为任意层级）
     * @return 命中绑定的处理小组 id；一路到根都没绑定返回 {@code null}
     */
    Long handlerDepartmentOf(Long startDepartmentId);

    /**
     * 某部门所属的<b>一级分部</b> id（部门树的 depth = 1 节点）。
     *
     * <p>「分部」不是独立实体（用户裁决 D6=A），就是部门树的一级节点。
     * 根部门本身 / 部门不存在时返回 {@code null}。
     */
    Long rootDivisionIdOf(Long departmentId);

    /** 某分部当前绑定的处理小组 id；未绑定返回 {@code null} */
    Long boundHandlerDepartmentOf(Long divisionId);

    /**
     * 绑定 / 解除绑定：{@code handlerDepartmentId} 为 {@code null} 表示解除。
     *
     * <p>服务层校验三条：① 绑定对象必须是一级分部；② 目标部门必须是处理小组
     * （{@code handler_group = 1}）且启用；③ 不能把分部绑到它自己身上。
     */
    void bindHandlerGroup(Long divisionId, Long handlerDepartmentId);

    /**
     * 设置**全局默认处理小组**（{@code handlerDepartmentId} 为 {@code null} 表示清除标记）。
     *
     * <h2>为什么需要一个显式入口</h2>
     * <p>默认组是「分部没绑处理小组」时的唯一兜底。若只能靠改库设置，管理员在页面上
     * 看到「未设置默认组」时无处可改 —— 而这个配置一旦缺失，会直接决定
     * 「全公司漏配分部的单子落到哪里」。因此它必须是一个可点、可审计的操作。
     *
     * <p>清除标记后 {@link #defaultHandlerDepartmentId()} 会回退为「id 最小的处理小组」
     * （与 V53 之前行为一致），因此清除不是「取消兜底」，而是「交还给默认推导」。
     * 服务层校验：目标必须存在、必须是处理小组（{@code handler_group = 1}）、必须启用。
     */
    void setDefaultHandlerDepartment(Long handlerDepartmentId);

    /** 全部绑定关系：分部 id → 处理小组 id（供配置页一次渲染） */
    Map<Long, Long> handlerGroupBindings();

    /** 全部处理小组（{@code handler_group = 1}）的可选列表，供配置页下拉 */
    List<DepartmentOptionVO> handlerGroupOptions();

    /** 「分部 → 处理小组」配置总览（一级分部 + 已绑定对象 + 全局默认组），供配置页一次渲染 */
    com.enterprise.ticket.module.department.dto.vo.DivisionHandlerConfigVO handlerGroupConfig();

    /**
     * 提交工单时用的「最终处理小组」解析入口（V53 默认实现）。
     *
     * <p>= {@link #handlerDepartmentOf(Long)} 逐级向上匹配；一路都没绑定则回落
     * {@link #defaultHandlerDepartmentId()}，<b>并发一条 P1 运维告警</b>
     * （否则「某个分部漏配了」这件事永远不会有人知道，只会表现为「单子全落到总部 IT」）。
     *
     * <h2>为什么兜底时不再抛错</h2>
     * <p>V53 之前这里直接抛 {@code HANDLER_DEPARTMENT_NOT_CONFIGURED}，一配漏就**全公司提交不了单**；
     * 现在有默认组可兜，兜底 + 告警比「整站不可用」更符合业务连续性要求。
     * 只有「连默认组都没有」才返回 {@code null}，由调用方抛错。
     *
     * @param startDepartmentId 起点部门（申请人主部门）
     * @return 处理小组 id；连默认组都没配置时返回 {@code null}
     */
    Long resolveHandlerDepartment(Long startDepartmentId);

    /**
     * 某部门的**上层部门** id；已是根部门（{@code parent_id} 为空）时返回 {@code null}。
     *
     * <p>供 的「上级部门主管」审批人规则使用。放在本接口而不是让调用方自己查
     * {@code department.parent_id}，是为了让「部门树怎么走」只有一处实现 ——
     * 将来若 tree 结构改成闭包表 / 物化路径为主，只需要改这一个方法。
     */
    Long parentIdOf(Long departmentId);

    /** 最终处理部门的**在职**成员 user_id（IT执行人候选之一） */
    List<Long> handlerDepartmentMemberIds();

    /**
     * 某部门的**在职**成员 user_id —— 「谁能当执行人 / 谁能接单」的**唯一出处**。
     *
     * <h2>口径（三者缺一不可）</h2>
     * <ol>
     *   <li>{@code employee.department_id}（主部门）<b>∪</b> {@code user_department}（兼职）的**并集**；</li>
     *   <li>账号 {@code enabled = 1}；</li>
     *   <li>未离职（{@code is_dimission = 0}）。</li>
     * </ol>
     *
     * <h2>为什么必须是并集（这不是可选优化，是正确性）</h2>
     * <p>运行期「某人的主部门」只写在 {@code employee.department_id}，而
     * {@code user_department} 只存**额外兼职**——生产代码里除了演示数据初始化器，
     * <b>没有任何写入点</b>（V31 只回填了历史 {@code handler_group_member} 那批人）。
     * 因此只查 {@code user_department} 的实现在一次真实部署上会**恒返回空列表**：
     * 管理员把员工主部门设成 IT运维组后，他既接不到单、也进不了候选人池，
     * 而且**不报错**（空列表被上层当作「暂无可用成员」或「无权限」）。
     *
     * <p>反过来只查主部门也不行：IT运维组的成员常由兼职承担，
     * 会在「组织与人员」页显示 0 人。
     */
    List<Long> activeMemberIdsOf(Long departmentId);

    /**
     * 把某部门**未手工覆盖**的成员的直属主管同步为当前部门主管。
     *
     * <p>需求文档：「部门主管变更时，该部门所有没有手动覆盖过的成员，直属主管自动更新成新主管」。
     * 手工覆盖过的成员（{@code employee.leader_override = 1}）**绝不改写** ——
     * 那正是「副组长管具体人」这类真实场景的落点，覆盖掉等于把管理员的手工配置吃掉。
     */
    void syncMembersLeader(Long departmentId);

    /** 某部门及其全部下级部门的 id（含自身）；用于取子树成员 */
    List<Long> subtreeIds(Long departmentId);

    /**
     * 某部门的成员列表（**主部门 ∪ 兼职部门**的并集）。
     *
     * <p>为什么不是「主部门」：IT运维组的成员主职都在别的部门，只按主部门取会得到空列表。
     * 而「组织与人员」页最需要看清楚的恰恰是「这个部门里都有谁」。
     */
    List<UserOptionVO> membersOf(Long departmentId);
}
