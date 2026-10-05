package com.enterprise.ticket.module.ad.service;

import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.user.entity.User;

/**
 * AD 账号本地化服务
 *
 * <h2>为什么要把「AD 用户 → 本地账号」这段逻辑单独抽出来</h2>
 * <p>它有<b>两个</b>入口，且必须产出完全一致的账号：
 * <ul>
 *   <li> 手动 / 定时同步：批量把域用户同步到本地；</li>
 *   <li> 首次登录自动创建：域用户第一次登录时按需建号。</li>
 * </ul>
 * 如果各写一份，最容易出现的分歧是「同步建的账号是启用的、首次登录建的是禁用的」
 * 或「字段映射不一致」—— 前者会让同一批人里出现两类可登录性，
 * 后者会让列表里同一属性的显示因创建路径而不同。这类缺陷极难定位，
 * 因为差异只体现在「先登录」还是「先同步」的先后顺序上。
 *
 * <p>因此本服务是<b>唯一</b>的账号本地化实现，两个入口都调用它。
 */
public interface AdUserProvisioningService {

    /**
     * 本地化一个 AD 用户：本地已有则刷新属性，没有则创建
     *
     * @param adUser AD 侧用户（含登录名、姓名、邮箱、部门、禁用状态）
     * @return 本地账号 + 本次是否新建 / 是否有变更
     */
    ProvisionResult provision(AdUser adUser);

    /**
     * 本地化结果
     *
     * @param user    当前本地账号（新建或既有）
     * @param created 是否本次新建
     * @param changed 既有账号上属性是否确有变化（{@code created=false} 时才有意义）
     */
    record ProvisionResult(User user, boolean created, boolean changed) {
    }
}
