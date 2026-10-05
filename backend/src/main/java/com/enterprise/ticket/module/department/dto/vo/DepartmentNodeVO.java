package com.enterprise.ticket.module.department.dto.vo;

import com.enterprise.ticket.module.user.dto.UserOptionVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（，供左侧部门树渲染）。
 *
 * <p>树由服务端一次组装好（一次查全部部门 + 一次按部门分组统计人数），
 * 前端不再逐个部门请求 —— 组织树是「一屏可见全貌」的控件，
 * 懒加载会让「展开到第 4 层」变成 4 次往返，而本系统的部门量级（几十个）完全撑得住一次全量。
 */
@Data
public class DepartmentNodeVO {

    private Long id;

    private String deptName;

    private Long parentId;

    private String path;

    private Integer depth;

    private Integer sortOrder;

    /** 是否最终处理部门（IT运维组）—— 前端据此打标记，避免管理员误删 */
    private Boolean handlerGroup;

    /** 绑定的审批流程版本；{@code null} 表示走系统内置流程 */
    private Long approvalFlowVersionId;

    private Boolean status;

    private String remark;

    /**
     * 本部门**直接**成员数（不含子部门）。
     *
     * <p>树节点上显示的就是这个数 —— 与钉钉一致：点开研发部看到的是「研发部本级」的人，
     * 子部门的人在自己的节点上。
     */
    private Long memberCount;

    /**
     * 本部门及**全部下级部门**的成员数合计。
     *
     * <p>供「删除部门」的二次确认使用（需求文档：提示「该部门下有 X 人，确认删除后人员移到上一级部门」）。
     * 与 {@link #memberCount} 分开而不是只留一个：树上看本级、确认框看总数，
     * 合成一个必然有一处是错的。
     */
    private Long totalMemberCount;

    /** 部门主管（可多个，已展开为员工选项） */
    private List<UserOptionVO> managers = new ArrayList<>();

    private List<DepartmentNodeVO> children = new ArrayList<>();
}
