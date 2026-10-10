package com.enterprise.ticket.module.department.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成员兼职部门（，需求文档 二·成员管理「成员可以属于多个部门，
 * 主部门+兼职部门，主部门决定审批上级」）。
 *
 * <h2>为什么兼职部门**不参与**审批人推导</h2>
 * <p>审批人规则 {@code DEPARTMENT_MANAGER} 只读 {@code employee.department_id}（主部门）。
 * 若兼职也算，同一个人会因为多挂了一个部门而同时出现在两条审批路径上 ——
 * 「谁是直属主管」变成不确定的，而审批链最忌讳的就是不确定。
 *
 * <h2>本次的实际用途</h2>
 * <p>原「最终处理小组」的成员整体挂到「IT运维组」的**兼职**关系上：
 * 这样「谁能当执行人」可以由「IT运维组部门成员 ∪ IT执行人角色」两路共同回答，
 * 而不会因为改了主部门而破坏演示环境里既有的组织归属与审批锚点。
 */
@Data
@TableName("user_department")
public class UserDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long departmentId;

    private LocalDateTime createdAt;
}
