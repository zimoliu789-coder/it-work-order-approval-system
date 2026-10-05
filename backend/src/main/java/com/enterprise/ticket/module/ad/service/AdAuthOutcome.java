package com.enterprise.ticket.module.ad.service;

/**
 * AD 认证结果分类
 *
 * <p>详见 {@link AdAuthResult} 的表格：每一个取值都对应登录流程里一个<b>不同</b>的分支。
 */
public enum AdAuthOutcome {

    /** 域口令正确 */
    SUCCESS,

    /** 账号在 AD 中存在，但口令错误 */
    BAD_CREDENTIALS,

    /** AD 中不存在该账号（说明这是本地账号，应回退本地认证） */
    USER_NOT_FOUND,

    /** AD 侧的账号已被禁用 */
    ACCOUNT_DISABLED,

    /** 域控不可达，或配置不可用（缺项 / 密码不可解） */
    UNAVAILABLE
}
