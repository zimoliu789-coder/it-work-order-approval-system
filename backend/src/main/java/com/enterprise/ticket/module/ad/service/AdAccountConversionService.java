package com.enterprise.ticket.module.ad.service;

import com.enterprise.ticket.module.ad.dto.AdAccountConvertVO;

/**
 * 账号来源互转（；）
 *
 * <h2>为什么放在 ad 模块而不是 user 模块</h2>
 * <p>「转 AD」必须在写库之前<b>真的去域控确认这个账号存在</b>，否则转完就登不进来了
 * （本地口令被随机化，域里又没人）。这次确认要用到 {@code AdDirectoryClient}，
 * 属 ad 模块的协议能力。若把它拆一半放 user 模块，就会出现
 * 「user 模块调用 ad 的 Service」这种跨模块 Service 依赖 ——
 * 与项目既有约定（跨模块只依赖 Mapper）冲突。放在 ad 模块后，
 * 方向变成「ad 模块 → user 模块（UserMapper）」，仍然只有 Mapper 跨界。
 *
 * <h2>方向与后果</h2>
 * <table border="1">
 *   <caption>两个方向</caption>
 *   <tr><th>方向</th><th>password_hash</th><th>认证方式</th><th>会话</th></tr>
 *   <tr><td>AD → 本地</td><td>随机临时口令（一次性返回，首登强改）</td>
 *       <td>本地口令</td><td>既有会话全部作废，须用临时口令重新登录</td></tr>
 *   <tr><td>本地 → AD</td><td>替换为随机占位串（不可猜、不可用）</td>
 *       <td>域口令</td><td>既有会话全部作废，须用域口令重新登录</td></tr>
 * </table>
 */
public interface AdAccountConversionService {

    /**
     * AD 域账号 → 本地账号（）
     *
     * <p>转换后该账号由本地掌管口令：登录时不再走域认证，
     * AD 同步也不会再刷新或禁用它（见 {@code AdUserProvisioningServiceImpl#provision}）。
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         {@code USER_NOT_FOUND} 账号不存在 / {@code USER_SUPER_ADMIN_PROTECTED} 超管不可转换 /
     *         {@code AD_ACCOUNT_ALREADY_LOCAL} 已是本地账号
     */
    AdAccountConvertVO convertToLocal(Long userId);

    /**
     * 本地账号 → AD 域账号（「反之亦然」）
     *
     * <p>前置条件：AD 已启用，且<b>域控中确实存在同登录名账号</b> ——
     * 不满足则拒绝转换并说明原因，避免把用户转成「谁也登不进」的状态。
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         {@code USER_NOT_FOUND} 账号不存在 / {@code USER_SUPER_ADMIN_PROTECTED} 超管不可转换 /
     *         {@code AD_ACCOUNT_ALREADY_LDAP} 已是域账号 / {@code AD_DISABLED} 未启用 /
     *         {@code AD_ACCOUNT_NOT_IN_DIRECTORY} 域控中不存在该登录名 /
     *         {@code AD_TEST_FAILED} 域控不可达（此时不做任何修改）
     */
    AdAccountConvertVO convertToLdap(Long userId);
}
