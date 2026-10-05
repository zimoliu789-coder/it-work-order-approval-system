package com.enterprise.ticket.module.ad.service;

import com.enterprise.ticket.module.ad.ldap.AdUser;

/**
 * AD 认证结果（； 登录流程）
 *
 * <p>五个分支各自对应登录流程里<b>不同</b>的处置，不能合并（合并就会丢信息）：
 * <table border="1">
 *   <caption>结果 → 登录分支</caption>
 *   <tr><th>结果</th><th>含义</th><th>登录流程</th></tr>
 *   <tr><td>{@link #SUCCESS}</td><td>域口令正确</td>
 *       <td>本地有账号则更新属性，没有则自动建号（分配默认角色），签发 Token</td></tr>
 *   <tr><td>{@link #BAD_CREDENTIALS}</td><td>账号在 AD 中存在，口令错误</td>
 *       <td>401，<b>不回退本地</b>（防止用本地旧口令绕过域控的锁定 / 过期策略）</td></tr>
 *   <tr><td>{@link #USER_NOT_FOUND}</td><td>AD 中不存在该账号</td>
 *       <td>说明这是<b>本地账号</b> → 回退本地口令校验</td></tr>
 *   <tr><td>{@link #ACCOUNT_DISABLED}</td><td>AD 侧账号已禁用</td>
 *       <td>按 ACCOUNT_DISABLED 拒绝，并给出「请联系管理员」而不是「密码错误」</td></tr>
 *   <tr><td>{@link #UNAVAILABLE}</td><td>域控不可达 / 配置不可用</td>
 *       <td>回退本地认证；纯 AD 用户提示「域服务器不可用，请稍后重试或联系管理员」</td></tr>
 * </table>
 *
 * @param outcome 结果分类
 * @param user    成功时带回的 AD 用户属性（用于属性同步 / 自动建号）
 * @param message 失败原因（仅用于日志与管理员排查，不直接回显给登录用户）
 */
public record AdAuthResult(AdAuthOutcome outcome, AdUser user, String message) {

    public static AdAuthResult success(AdUser user) {
        return new AdAuthResult(AdAuthOutcome.SUCCESS, user, null);
    }

    public static AdAuthResult failure(AdAuthOutcome outcome, String message) {
        return new AdAuthResult(outcome, null, message);
    }

    public boolean isSuccess() {
        return outcome == AdAuthOutcome.SUCCESS;
    }
}
