package com.enterprise.ticket.module.department.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门主管（，需求文档 二·部门管理「每个部门设部门主管，可设多个」）。
 *
 * <h2>它为什么是一张关联表而不是 {@code department.leader_ids} JSON 列</h2>
 * <p>核心用例是<b>反查</b>：「张伟管哪些部门？」—— 用于
 * ① 审批人规则 {@code DEPARTMENT_MANAGER} 找他管的部门下的人；
 * ② 组织页上高亮「我管的部门」。
 * 关联表上 {@code idx_dm_user} 一次索引查找就能回答；JSON 列只能全表扫 JSON。
 *
 * <h2>多主管时「直属主管默认值」取谁</h2>
 * <p>多主管是合法配置（正副职），但 {@code employee.leader_id} 是**单值**。
 * 取舍：按 {@code user_id} 升序取第一个作为默认值（见
 * {@code DepartmentServiceImpl#syncMembersLeader}）—— 规则唯一、可预期、可测试。
 * 需要指定别人的场景由「成员手工覆盖」承担（{@code employee.leader_override = 1}）。
 */
@Data
@TableName("department_manager")
public class DepartmentManager {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long departmentId;

    /** 部门主管 user_id */
    private Long userId;

    private LocalDateTime createdAt;
}
