package com.enterprise.ticket.module.ad.ldap;

/**
 * 从 AD 目录读回来的一个用户（；手机号 ）
 *
 * <p>这是<b>与 JNDI 解耦后的中间表示</b>：{@code AdDirectoryClient} 负责把
 * {@code SearchResult} 拆成它，之后的增量同步、属性映射、状态判定全部只依赖本记录。
 * 这样做的好处是「同步逻辑」可以脱离真实目录做单测 —— 否则每个用例都要起一个 LDAP 服务器。
 *
 * @param dn         唯一标识 DN（AD 中对象的完整路径，作为最后手段的匹配锚点）
 * @param account    登录名（来自 {@code attrLogin} 映射，通常是 sAMAccountName）
 * @param name       姓名（来自 {@code attrName}，通常是 displayName，为空回退 cn）
 * @param email      邮箱（可能为空）
 * @param phone      手机号（来自 {@code attrPhone}，通常是 telephoneNumber；可能为空）
 * @param department 部门（可能为空）
 * @param disabled   账号是否已被 AD 禁用（按 {@code userAccountControl} 的 ACCOUNTDISABLE 位判定）
 * @param objectGuid AD 对象的 objectGUID（十六进制字符串，可能为空）
 */
public record AdUser(String dn,
                     String account,
                     String name,
                     String email,
                     String phone,
                     String department,
                     boolean disabled,
                     String objectGuid) {

    /** 是否具备「可以建成本地账号」的最小信息（登录名非空） */
    public boolean usable() {
        return account != null && !account.isBlank();
    }
}
