package com.enterprise.ticket.module.department.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（，需求文档 二·部门管理）。
 *
 * <h2>它同时承担了三件事</h2>
 * <ol>
 *   <li><b>组织树</b>：左侧部门树，支持无限层级（公司 → 部门 → 小组）；</li>
 *   <li><b>审批上级的事实源</b>：{@link #id} 被 {@code employee.department_id} 引用，
 *       而「直属主管默认 = 所在部门的部门主管」这条规则让**组织结构直接决定审批路径**；</li>
 *   <li><b>最终处理部门的载体</b>：{@link #handlerGroup} 为真的那一个部门
 *       （IT运维组）取代了原来的「最终处理小组」概念。</li>
 * </ol>
 *
 * <h2>path 与 depth 的维护约定</h2>
 * <p>{@link #path} 是物化路径（形如 {@code /1/10/}），由 {@code DepartmentServiceImpl}
 * 在**新增 / 移动**部门时统一维护；{@link #depth} 与之同步。
 * 之所以物化而不是每次递归查子节点：取子树（「研发部及其所有下级的人」）
 * 只需要一次 {@code path LIKE '/1/10/%'} 前缀匹配，不必按层递归打库。
 *
 * <p>⚠️ <b>不要手写 UPDATE 改 parent_id</b>：那样 path / depth 会与树结构不一致，
 * 而所有「取子树」的查询都会静默给出错误结果（不报错、只是少人或多人）。
 * 移动部门必须走 {@code DepartmentService#move}。
 */
@Data
@TableName("department")
public class Department {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门名称（同一上级下唯一，由服务层预检 + 捕获保证） */
    private String deptName;

    /** 上级部门ID；{@code null} = 根节点（公司） */
    private Long parentId;

    /** 物化路径，形如 {@code /1/10/} */
    private String path;

    /** 层级深度（根为 0） */
    private Integer depth;

    /** 同级显示顺序 */
    private Integer sortOrder;

    /**
     * 是否「最终处理部门」（IT运维组）。
     *
     * <p><b>V53 起语义放宽</b>：这个标记回答「本部门是不是一个处理小组」，<b>可以有多个</b>
     * （每个一级分部可以有自己的 IT 组）。哪个组负责哪个分部，由
     * {@code division_handler_group} 绑定表回答；全都匹配不上时用
     * {@link #handlerDefault} 那个兜底。
     *
     * <p>历史注释里「全局只应有 1 个」是 V53 之前的事实（V31 迁移里甚至有
     * 「有且仅有 1 个」的断言）—— 那条断言属于历史迁移，按项目约定不回改。
     */
    private Boolean handlerGroup;

    /**
     * 是否<b>全局默认</b>处理小组（V53）。
     *
     * <p>当「表单选中的分部 / 申请人主部门所属分部」沿组织树向上都找不到绑定关系时，
     * 用它兜底，并发一条运维告警（否则管理员永远不知道某个分部漏配了）。
     * 最多 1 个，由服务层保证。
     */
    private Boolean handlerDefault;

    /**
     * 本部门绑定的已发布审批流程版本。
     *
     * <p><b>承接自原 {@code biz_group.approval_flow_version_id}</b>：
     * 改造前「分组绑流程」，改造后「部门绑流程」—— 语义未变，
     * 因此既有演示环境里绑了流程的部门（演示-研发中心 / 演示-市场中心 / FT6演示业务组）
     * 在迁移后行为完全一致，既有回归不会因为这次重构而变脸。
     */
    private Long approvalFlowVersionId;

    /** 状态：{@code true} 正常 / {@code false} 停用 */
    private Boolean status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
