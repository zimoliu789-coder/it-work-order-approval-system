package com.enterprise.ticket.module.ad.dto;

import lombok.Data;

/**
 * 账号来源互转结果（；）
 *
 * <p>「AD 用户 ↔ 本地用户」互转是一次性的、影响登录方式的管理动作，
 * 因此返回值必须把「转完之后是什么」「用户下一步该做什么」讲清楚，
 * 而不是只回一个 {@code success}。
 */
@Data
public class AdAccountConvertVO {

    private Long userId;

    private String username;

    private String displayName;

    /** 转换后的账号来源：{@code LOCAL} / {@code LDAP} */
    private String authType;

    /** 转换后的账号来源中文标签 */
    private String authTypeLabel;

    /**
     * 一次性展示的临时密码（<b>仅 AD → 本地</b>时非空）。
     *
     * <p>三个约束：
     * <ol>
     *   <li>只在本响应里返回一次，服务端不落库明文、不写审计（审计里只记「已生成临时密码」）；</li>
     *   <li>对应的账号被置 {@code force_change_password = true}，用户首登必须自行改密，
     *       因此这个临时口令的有效期只到「第一次登录」为止；</li>
     *   <li>本地 → AD 方向为 {@code null}：域账号的通行凭据由域控掌管，本地不生成也不展示任何口令。</li>
     * </ol>
     */
    private String temporaryPassword;

    /** 给管理员看的一句话说明（含必要的后续提醒） */
    private String message;
}
