package com.enterprise.ticket.module.department.service;

import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.dto.vo.DepartmentDeleteResultVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentNodeVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.user.dto.UserOptionVO;

import java.util.List;

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
     * <p><b>这是「直属主管默认值」的唯一出处</b>（见 {@code users.leader_override}）：
     * 未被手工覆盖的成员，其直属主管就是它。
     */
    Long primaryManagerOf(Long departmentId);

    /** 部门的全部主管 user_id（含副职），顺序稳定 */
    List<Long> managerIdsOf(Long departmentId);

    /** 最终处理部门（IT运维组）的 id；未配置返回 {@code null} */
    Long handlerDepartmentId();

    /**
     * 某部门的**上层部门** id；已是根部门（{@code parent_id} 为空）时返回 {@code null}。
     *
     * <p>供 的「上级部门主管」审批人规则使用。放在本接口而不是让调用方自己查
     * {@code departments.parent_id}，是为了让「部门树怎么走」只有一处实现 ——
     * 将来若 tree 结构改成闭包表 / 物化路径为主，只需要改这一个方法。
     */
    Long parentIdOf(Long departmentId);

    /** 最终处理部门的**在职**成员 user_id（IT执行人候选之一） */
    List<Long> handlerDepartmentMemberIds();

    /**
     * 把某部门**未手工覆盖**的成员的直属主管同步为当前部门主管。
     *
     * <p>需求文档：「部门主管变更时，该部门所有没有手动覆盖过的成员，直属主管自动更新成新主管」。
     * 手工覆盖过的成员（{@code users.leader_override = 1}）**绝不改写** ——
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
