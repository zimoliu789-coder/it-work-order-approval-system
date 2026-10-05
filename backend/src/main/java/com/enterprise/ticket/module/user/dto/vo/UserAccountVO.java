package com.enterprise.ticket.module.user.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工账号列表视图（ 轻量版员工管理， / ）
 *
 * <p>需求方确定的展示列：姓名、登录名、部门、角色、状态（在职 / 离职）、创建时间；
 * 另补两项与本阶段功能直接相关的信息：
 * <ul>
 *   <li>{@code heldDeviceCount} —— 名下「使用中」设备数。前端在「标记离职」的二次确认弹窗里
 *       直接引用它提示「该员工名下有 N 台使用中设备」，因此必须随列表返回，
 *       而不是点按钮时再单独请求一次（少一次往返，也避免弹窗打开后数字与列表不一致）；</li>
 *   <li>{@code pendingApprovalCount} —— 该员工当前仍是「待处理审批节点审批人」的在途工单数。
 *       这些工单不会因离职而自动改派（ 只要求<b>新建</b>快照时替换离职审批人），
 *       因此需要提示管理员手工处理，避免工单静默卡住。</li>
 * </ul>
 */
@Data
public class UserAccountVO {

    private Long id;

    /**
     * 员工姓名（{@code users.real_name}）。
     *
     * <p>「姓名唯一」这条业务规则的锚点：新增 / 编辑 / 批量导入都以它判重。
     * 列表首列展示姓名的兜底顺序为 姓名 → 显示名称 → 登录名。
     */
    private String realName;

    /** 显示名称（员工姓名） */
    private String displayName;

    /** 登录名 */
    private String username;

    private Long departmentId;

    private String departmentName;

    /**
     * 直属领导 user_id（；null = 未配置）。
     *
     * <p>供员工管理页展示与编辑，也是「申请人直属领导」审批规则的数据来源。
     */
    private Long leaderId;

    /** 直属领导显示名（列表展示；领导已删除时为 null） */
    private String leaderName;

    private String role;

    private String roleLabel;

    /**
     * 该账号的「角色」是否被锁定（2026-09-20 ）。
     *
     * <p>为 {@code true} 的账号即<b>内置超级管理员</b>（登录名 = 配置的
     * {@code app.super-admin.username}，默认 {@code administrator}）。它是系统兜底审批与
     * 系统管理的最后入口，角色恒为 {@code super_admin}，不可被任何人（含其本人）降级。
     *
     * <p>前端据此把「编辑员工」弹窗里的角色下拉置灰并给出说明 —— 与后端写侧的
     * {@code USER_SUPER_ADMIN_PROTECTED} 护栏同源：后端是「拒绝」的事实源，
     * 前端只是提前「不给入口」，避免用户操作后才发现被拒。
     */
    private Boolean roleLocked;

    /** 账号是否启用 */
    private Boolean enabled;

    /** 是否离职 */
    private Boolean dimission;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dimissionAt;

    /** 名下处于「使用中」的设备数量（= 待回收数量） */
    private Integer heldDeviceCount;

    /** 名下仍待其审批的在途节点数量（离职后不会自动改派，需人工处理） */
    private Integer pendingApprovalCount;

    /**
     * 账号来源：{@code LOCAL}（本地账号）/ {@code LDAP}（AD 域账号）。
     *
     * <p>  要求员工列表显示「账号来源」列。这个字段同时是前端的一处
     * <b>行为开关</b>：{@code LDAP} 时「重置密码」「修改密码」入口必须置灰并提示
     * 「域账号请在 AD 域控中修改密码」—— 本地密码对域账号根本无效（其 {@code password_hash}
     * 是随机占位串），不放行是对用户的保护，而不是限制。
     */
    private String authType;

    /** 账号来源中文标签（本地 / AD），前端直接展示，无需再维护一份映射 */
    private String authTypeLabel;

    /** 邮箱（来自 AD 时由域控同步；本地账号可空） */
    private String email;

    /**
     * 手机号（V26 新增；NULL = 未绑定）。
     *
     * <p>与 {@link #email} 一起构成「找回密码」的投递通道，也是员工列表里
     * 「联系方式」这一列的展示来源。二者全局唯一。
     */
    private String phone;

    /** 部门（来自 AD 时由域控同步；本地账号可空） */
    private String department;

    /** 最近一次从 AD 同步确认该账号存在的时间（仅 AD 账号有值） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime adSyncedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
