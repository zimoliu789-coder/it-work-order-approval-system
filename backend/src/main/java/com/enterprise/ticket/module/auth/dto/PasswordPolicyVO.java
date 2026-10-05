package com.enterprise.ticket.module.auth.dto;

import lombok.Data;

import java.util.List;

/**
 * 密码策略说明（ 收尾优化·）
 *
 * <h2>为什么要做成接口而不是前端写死</h2>
 * <p>策略的两个关键阈值（最小长度、最少字符类数）来自 {@code system_config}，
 * 超管可以在「系统参数」页随时调整。若前端写死一段「至少 8 位、含两类字符」的说明，
 * 调整参数后页面就会<b>教用户一条已经过时的规则</b> —— 用户按提示输入仍被拒绝，
 * 只会得出「系统坏了」的结论。因此把「策略说明」和「策略校验」放在同一个来源上。
 */
@Data
public class PasswordPolicyVO {

    /** 最小长度（{@code password_min_length}） */
    private Integer minLength;

    /** 最大长度（固定 64，策略硬上限） */
    private Integer maxLength;

    /** 最少字符类数（{@code password_min_char_types}） */
    private Integer minCharTypes;

    /** 是否启用「常见弱密码」校验 */
    private Boolean weakPasswordChecked;

    /** 是否禁止密码包含登录名 */
    private Boolean usernameRule;

    /**
     * 当前登录用户的账号来源（{@code LOCAL} / {@code LDAP}）。
     *
     * <p>AD 域账号在本页不应看到修改表单 —— 域口令只存在于域控，
     * 页面据此直接切换为提示文案，而不是让用户填完表单再收到 400。
     */
    private String authType;

    /** 逐条中文说明，前端直接渲染成列表，避免前端再拼一遍文案 */
    private List<String> rules;
}
