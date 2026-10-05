package com.enterprise.ticket.module.ad.service.impl;

import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdAuthOutcome;
import com.enterprise.ticket.module.ad.service.AdAuthResult;
import com.enterprise.ticket.module.ad.service.AdAuthenticationService;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AD 域认证实现
 *
 * <h2>认证为什么是「先搜后绑」两步，而不是直接绑定</h2>
 * <p>AD 只认 DN 或 {@code 域\账号} / {@code 账号@域} 形式去做绑定。
 * 我们手里只有管理员配置的登录名属性（通常是 sAMAccountName），
 * 因此必须先用<b>服务账号</b>把这个人搜出来拿到 DN，再用「这个 DN + 用户口令」绑一次。
 *
 * <p>副产品：搜索这一步天然回答了一个关键问题 —— <b>「AD 里到底有没有这个人」</b>。
 * 这正是登录流程必须区分 {@link AdAuthOutcome#USER_NOT_FOUND} 与
 * {@link AdAuthOutcome#BAD_CREDENTIALS} 的依据：前者说明这是本地账号（应回退本地），
 * 后者才是真正的域口令错误（不能回退，否则等于允许用旧本地口令绕过域控策略）。
 *
 * <h2>为什么本方法不抛异常</h2>
 * <p>调用方（{@code AuthService}）需要的是「判断 + 降级」，而不是「处理异常」：
 * 抛出异常会让「域控不可用时本地账号要能登录」这条需求退化成一大片 try/catch 分支。
 * 把分类收敛在返回值里，登录流程就是一段可读的 switch。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdAuthenticationServiceImpl implements AdAuthenticationService {

    private final AdConfigService adConfigService;
    private final AdDirectoryClient directoryClient;

    @Override
    public AdAuthResult authenticate(String account, String rawPassword) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(rawPassword)) {
            // 空口令必须判失败：JNDI 对空凭据会执行「匿名绑定」且会成功，
            // 放过去等于给所有人开了一道后门
            return AdAuthResult.failure(AdAuthOutcome.BAD_CREDENTIALS, "账号或域口令为空");
        }

        AdConnection connection;
        try {
            connection = adConfigService.activeConnection();
        } catch (BusinessException e) {
            // 配置不可用（未启用 / 缺项 / 密码不可解）：对登录而言等价于「域控不可用」，
            // 让本地账号照常能进，而不是把所有人一起拒之门外
            return AdAuthResult.failure(AdAuthOutcome.UNAVAILABLE, e.getMessage());
        }

        try {
            AdUser user = directoryClient.findByAccount(connection, account);
            if (user.disabled()) {
                // 先判禁用再验口令：AD 对已禁用账号的绑定同样会失败，
                // 若不提前判断，管理员看到的会是「密码错误」——一个误导排查方向的信息
                return AdAuthResult.failure(AdAuthOutcome.ACCOUNT_DISABLED,
                        "域账号「" + account + "」在 AD 中已被禁用");
            }
            directoryClient.verifyUserCredentials(connection, user.dn(), rawPassword);
            log.debug("域认证成功：account={}，dn={}", account, user.dn());
            return AdAuthResult.success(user);
        } catch (AdDirectoryException e) {
            return switch (e.getKind()) {
                case BAD_CREDENTIALS -> AdAuthResult.failure(AdAuthOutcome.BAD_CREDENTIALS, "域口令校验失败");
                case USER_NOT_FOUND -> AdAuthResult.failure(AdAuthOutcome.USER_NOT_FOUND, e.getMessage());
                case CONFIG -> {
                    // 配置错也走 UNAVAILABLE：本地账号仍需能登录（「不被锁在外面」）
                    log.error("AD 配置存在错误，域认证不可用：{}", e.getMessage());
                    yield AdAuthResult.failure(AdAuthOutcome.UNAVAILABLE, e.getMessage());
                }
                case UNAVAILABLE -> AdAuthResult.failure(AdAuthOutcome.UNAVAILABLE, e.getMessage());
            };
        } catch (Exception e) {
            // 兜底：任何未预期异常都不能让登录接口 500，必须能降级
            log.error("域认证发生未预期异常，按「域控不可用」处理", e);
            return AdAuthResult.failure(AdAuthOutcome.UNAVAILABLE, e.getMessage());
        }
    }
}
