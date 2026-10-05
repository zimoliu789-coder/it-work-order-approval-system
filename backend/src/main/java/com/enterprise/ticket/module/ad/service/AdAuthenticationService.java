package com.enterprise.ticket.module.ad.service;

/**
 * AD 域认证服务（； 登录流程的「AD 认证」一步）
 *
 * <p>只做一件事：拿账号 + 口令去域控验一次，并把结果分类成 {@link AdAuthResult}。
 * <b>不做</b>登录分支判断（是否回退本地、超管是否走本地），那是 {@code AuthService} 的职责 ——
 * 把分支逻辑留在那里，是因为它必须与本地认证在同一段代码里前后对照才能看懂。
 */
public interface AdAuthenticationService {

    /**
     * 域认证
     *
     * @param account     登录名（对应 AD 的 sAMAccountName 等映射属性）
     * @param rawPassword 用户提交的明文口令（只在内存中出现，不落库、不落日志）
     * @return 结果分类 + 成功时的 AD 用户属性；<b>不抛异常</b> ——
     *         所有故障都收敛成 {@link AdAuthOutcome#UNAVAILABLE}，
     *         因为登录流程需要的恰恰是「能判断、能降级」，而不是被异常打断
     */
    AdAuthResult authenticate(String account, String rawPassword);
}
