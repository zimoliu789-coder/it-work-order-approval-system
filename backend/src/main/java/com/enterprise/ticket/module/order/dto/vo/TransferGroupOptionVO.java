package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

/**
 * 跨组转单的目标小组选项（V55 / D5.7）。
 *
 * <h2>为什么把管理员一并下发</h2>
 * <p>跨组转单的目标**只能选对方小组的管理员**，不能直接选对方的普通 IT ——
 * 但发起人（本组管理员）通常不认识对方组里的其他人，他认识的是「这件事归谁管」。
 * 因此下拉直接展示「小组名 · 管理员」：选中它等于选中了那笔单的接收人，
 * 避免出现"选了一个没有接收权限的人、提交后才发现"的往返。
 *
 * <p>{@code adminId} 为空表示该小组**没有配置管理员**：前端应把它置灰并说明原因，
 * 而不是让发起人选中后收到一句"目标小组未配置管理员"。
 */
@Data
public class TransferGroupOptionVO {

    /** 处理小组（部门）id */
    private Long departmentId;

    private String departmentName;

    /** 接收人（该小组管理员）user_id；为空 = 该小组未配置管理员 */
    private Long adminId;

    private String adminName;

    /** 该小组管理员人数（>1 时取 user_id 最小者为接收人，下拉里注明） */
    private Integer adminCount;

    /** 该小组在职成员数（发起人据此判断"转过去有没有人干"） */
    private Integer memberCount;

    /** 是否可选（未配置管理员 / 无在职成员时为 false） */
    private Boolean selectable;
}
