package com.enterprise.ticket.module.ad.ldap;

/**
 * AD / LDAP 目录访问异常
 *
 * <h2>为什么需要区分 {@link Kind}，而不是笼统地抛一个异常</h2>
 * <p>登录流程对「AD 出了什么事」的处置<b>完全不同</b>（ 的 2/4/5 三条）：
 * <table border="1">
 *   <caption>故障类型 → 登录处置</caption>
 *   <tr><th>Kind</th><th>含义</th><th>登录时怎么做</th></tr>
 *   <tr><td>{@link Kind#BAD_CREDENTIALS}</td><td>用户在 AD 里存在，但口令不对</td>
 *       <td>直接 401，<b>不回退本地</b>（防止用旧本地口令绕过域控策略）</td></tr>
 *   <tr><td>{@link Kind#USER_NOT_FOUND}</td><td>AD 里根本没有这个账号</td>
 *       <td>说明这是<b>本地账号</b>：回退本地口令校验。
 *           若把它也当成「认证失败」，AD 一开启就会把所有非 AD 账号锁在门外</td></tr>
 *   <tr><td>{@link Kind#UNAVAILABLE}</td><td>连不上 / 超时 / 目录服务报错</td>
 *       <td>回退本地认证，并在纯 AD 用户上给出「域服务器不可用」的明确提示</td></tr>
 *   <tr><td>{@link Kind#CONFIG}</td><td>配置本身有问题（缺基础 DN、绑定账号被拒等）</td>
 *       <td>按 {@link Kind#UNAVAILABLE} 处理（本地账号照常能进），但日志级别更高</td></tr>
 * </table>
 * 把这三类揉成一个异常，就会出现「AD 没这个人」被判成「密码错」的产品缺陷 ——
 * 而这正是最容易被漏掉的一种：测试时用的是真实 AD 账号，永远不会触发。
 */
public class AdDirectoryException extends RuntimeException {

    /** 故障分类（决定登录流程的处置分支，见类注释） */
    public enum Kind {
        /** 连接 / 读取失败、超时、服务不可达 */
        UNAVAILABLE,
        /** 凭据错误（绑定服务账号被拒，或目标用户口令错） */
        BAD_CREDENTIALS,
        /** 目录中不存在该账号 */
        USER_NOT_FOUND,
        /** 配置不完整或自相矛盾（缺 baseDn / 过滤器语法错等） */
        CONFIG
    }

    private final Kind kind;

    public AdDirectoryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AdDirectoryException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
