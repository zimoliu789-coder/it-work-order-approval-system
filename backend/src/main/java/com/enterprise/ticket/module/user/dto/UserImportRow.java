package com.enterprise.ticket.module.user.dto;

import lombok.Data;

/**
 * 员工批量导入的「一行」（需求方 2026-09-18 小迭代 · ）
 *
 * <p>列顺序与模板一致：姓名、登录名、初始密码、部门名称、角色、显示名称。
 * 与设备导入同样采用<b>按列位置解析</b>（见 {@code UserImportExcelSupport}）。
 *
 * <p>注意：本对象会被前端原样回传用于「确认导入」，因此<b>不</b>携带 {@code valid}/{@code reason}
 * 这类校验态字段 —— 那些在 {@link com.enterprise.ticket.module.user.dto.vo.UserImportRowVO} 上。
 * 确认导入时后端会重新校验一遍，不信任客户端提交的行。
 */
@Data
public class UserImportRow {

    /** 文件中的行号（含表头，从 1 开始；用于把失败原因定位回用户看到的行） */
    private Integer rowNo;

    /** 姓名（必填，全局唯一 + 文件内唯一） */
    private String realName;

    /** 登录名（必填，全局唯一 + 文件内唯一） */
    private String username;

    /** 初始密码（必填） */
    private String password;

    /** 部门名称（必填，按名称匹配既有分组） */
    private String departmentName;

    /** 角色：super_admin / admin / user */
    private String role;

    /** 显示名称（选填；留空回退为姓名） */
    private String displayName;

    /**
     * 直属领导姓名（选填；）。
     *
     * <p>按<b>姓名</b>匹配既有员工（{@code real_name}）——与「部门名称」同样的
     * 「填名称按名匹配」约定，避免要求用户去查 user_id。
     * 匹配不到（不存在 / 已离职 / 已停用）时该行校验失败并给出明确原因，不静默丢弃。
     */
    private String leaderName;
}
