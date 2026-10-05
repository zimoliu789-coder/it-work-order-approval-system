package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.permission.BuiltinAdmin;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdAuthOutcome;
import com.enterprise.ticket.module.ad.service.AdAuthResult;
import com.enterprise.ticket.module.ad.service.AdAuthenticationService;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService;
import com.enterprise.ticket.module.auth.dto.ChangePasswordRequest;
import com.enterprise.ticket.module.auth.dto.LoginRequest;
import com.enterprise.ticket.module.auth.dto.LoginUserInfo;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.security.service.IpBlockService;
import com.enterprise.ticket.module.security.service.SecurityEventRecorder;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.security.JwtCookieService;
import com.enterprise.ticket.security.JwtTokenProvider;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.security.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证服务（ 姓名登录、 密码与登录安全、 限流、 AD 域控对接）
 *
 * <h2>（三波遗漏补做·第一波）在本类落地三件事</h2>
 * <ol>
 *   <li>签发的每个 Token 带 {@code jti} 与 {@code ver}（令牌版本号）；</li>
 *   <li>改密成功后<b>版本号 +1 并补发新 Token</b> ——
 *       既让别处的旧 Token 立刻失效，又不把正在改密的这个人踢出系统；</li>
 *   <li>登出把当前 Token 的 jti 写进 Redis 黑名单，只作废这一次会话。</li>
 * </ol>
 *
 * <h2>（AD 域控对接）在本类落地「登录分支」</h2>
 * <p> 把登录判断写成了一张确定的流程表，本类严格照此实现：
 * <pre>
 *   ① 账号是超级管理员            → 永远走本地认证（保证系统永远有人能进）
 *   ② AD 未启用                   → 全部走本地认证
 *   ③ AD 已启用且非超管：
 *        域口令正确                → 本地有账号则刷新属性，没有则自动建号，签发 Token
 *        域口令错误                → 401，<b>不回退本地</b>
 *        域中无此账号              → 这是<b>本地账号</b>，回退本地口令校验
 *        账号在域中被禁用          → 按「账号已禁用」拒绝（而不是含糊的「密码错误」）
 *        域服务器不可用            → 回退本地；纯 AD 用户给出「域服务器不可用」明确提示
 * </pre>
 *
 * <h3>「不回退本地」与「本地账号能登录」如何同时成立</h3>
 * <p>这两条看似矛盾，实际分别约束<b>两种不同的账号</b>，本实现靠「域中是否存在该账号」区分：
 * <ul>
 *   <li>域里<b>有</b>这个人 → 他是域账号。域口令错就必须 401，不能让本地旧口令成为后门；
 *       而且结构上也不可行 —— 域账号的本地 {@code password_hash} 是随机串
 *       （见 {@code AdUserProvisioningServiceImpl}），本地校验永远不可能通过；</li>
 *   <li>域里<b>没有</b>这个人 → 他是本地账号（内网系统里管理员手工建的账号、
 *       或尚未接入域控的岗位）。此时若也判 401，AD 一开启就会把所有非域账号锁在门外，
 *       与「本地账号不被锁在外面」直接冲突。</li>
 * </ul>
 * 因此分支判据是「AD 认证结果的具体类型」，而不是「AD 认证是否成功」这个粗粒度布尔值。
 *
 * <h3>为什么不需要额外区分「回退」的日志来源</h3>
 * <p>每次登录都会在审计里写明 {@code 认证方式=LOCAL/LDAP}，降级路径另有一条
 * {@code LOGIN_LDAP_UNAVAILABLE} 记录。排查「某天为什么大家都走本地认证了」时，
 * 直接按这两条动作筛选即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 账号来源：AD 域账号 */
    private static final String AUTH_TYPE_LDAP = "LDAP";

    /** 账号来源：本地账号 */
    private static final String AUTH_TYPE_LOCAL = "LOCAL";

    private final UserService userService;
    private final PasswordPolicyService passwordPolicyService;
    private final LoginProtectionService loginProtectionService;
    /** IP 封禁：账号锁定管「一个人」，它管「一个来源出口」 */
    private final com.enterprise.ticket.module.security.service.IpBlockService ipBlockService;
    /** 安全事件落库与告警：登录失败只落库不告警，锁定 / 封禁才告警 */
    private final com.enterprise.ticket.module.security.service.SecurityEventRecorder securityEventRecorder;
    /** 异常登录检测（P2）：凌晨 / 新设备 / 非常用 IP —— 记录事件并通知本人 */
    private final com.enterprise.ticket.module.security.service.LoginAnomalyService loginAnomalyService;
    private final JwtTokenProvider tokenProvider;
    private final JwtCookieService cookieService;
    private final TokenBlacklistService tokenBlacklistService;
    private final SystemConfigService systemConfigService;
    private final OperationLogService operationLogService;
    private final DepartmentMapper departmentMapper;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    // ---------------- ：AD 域控 ----------------
    private final AdConfigService adConfigService;
    private final AdAuthenticationService adAuthenticationService;
    private final AdUserProvisioningService adUserProvisioningService;

    /**
     * 用于对齐「账号不存在」分支的哈希耗时，防止通过响应时间枚举有效账号。
     * 启动时用同一编码器生成，保证与真实密码校验的计算成本一致。
     */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 登录（姓名 + 密码），成功后签发 JWT 并写入 HttpOnly Cookie
     */
    public LoginUserInfo login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String username = StringUtils.hasText(request.getUsername()) ? request.getUsername().trim() : "";
        String ip = SecurityUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 0. IP 封禁检查：刻意放在限流**之前** ——
        //    已被封禁的来源连限流配额都不该消耗，而且它需要一条明确的原因
        //    （「你这个来源被挡了」），否则被误封的人会去反复核对密码。
        if (ipBlockService.isBlocked(ip)) {
            securityEventRecorder.record(
                    com.enterprise.ticket.module.security.support.SecurityEventType.LOGIN_FAIL,
                    username, null, ip, userAgent, "来源 IP 处于封禁期，拒绝登录");
            // 封禁期内被拒也计入计数：持续攻击若累计到二级阈值，会被升级为 24 小时长封
            // （recordFailureAndMaybeBlock 内部按「现有封禁时长 < 目标时长」判定是否升级）。
            ipBlockService.recordFailureAndMaybeBlock(ip, username, userAgent);
            throw new BusinessException(ErrorCode.IP_BLOCKED);
        }

        // 1. 限流：同 IP + 同账号双维度，1 分钟窗口
        try {
            loginProtectionService.checkLoginRateLimit(username, ip);
        } catch (BusinessException e) {
            operationLogService.record(null, username, "AUTH", "LOGIN_RATE_LIMITED",
                    "登录限流触发，ip=" + ip, false, RiskLevel.NORMAL);
            throw e;
        }

        // 2. 账号锁定检查（：连续失败 5 次锁定 30 分钟）
        if (loginProtectionService.isLocked(username)) {
            long remainSeconds = loginProtectionService.getLockRemainSeconds(username);
            long remainMinutes = Math.max(1, remainSeconds / 60);
            operationLogService.record(null, username, "AUTH", "LOGIN_LOCKED",
                    "账号处于锁定期，剩余约 " + remainMinutes + " 分钟，ip=" + ip, false, RiskLevel.NORMAL);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "连续登录失败次数过多，账号已锁定，请 " + remainMinutes + " 分钟后重试");
        }

        User localUser = userService.findByLoginAccount(username);

        // 3. 分支：AD 已启用且该账号「由域控掌管」→ 先走域认证
        //
        //    「由域控掌管」= 本地无此账号（待自动建号）或 本地账号 auth_type = LDAP。
        //    刻意把 auth_type = LOCAL 的账号排除在域认证之外，有两个硬理由：
        //    ① 「超管可以把 AD 用户转为本地用户」——若转换后仍先走域认证，
        //       本地随机临时口令永远用不上，「转为本地」就成了一个没有任何效果的操作；
        //    ② 关闭一条同名顶替路径：本地管理员若建了一个叫 zhangsan 的本地账号，
        //       而域里恰好也有 zhangsan，按「域认证优先」放行会让域口令直接登录成本地账号，
        //       继承其本地角色（可能是管理员）。auth_type=LOCAL 即「本地掌管」的显式标记。
        if (adConfigService.isEnabled() && !isSuperAdminAccount(username, localUser) && !isLocalAccount(localUser)) {
            // 域认证必须拿「域里的账号名」去 bind。登录框现在同时接受纯数字登录名与中文姓名
            // （），用户完全可能输入中文姓名 —— 拿姓名去 LDAP bind 必然失败，
            // 表现为「AD 用户用自己的姓名登不进来」。因此本地已有该账号时，
            // 一律用本地 username（LDAP 账号的 username 就是域账号名）去认证。
            String adAccount = localUser == null ? username : localUser.getUsername();
            return loginWithAd(adAccount, request.getPassword(), localUser, ip, httpRequest, httpResponse);
        }

        // 4. 本地认证路径
        if (localUser == null) {
            return failUnknownAccount(username, ip, userAgent);
        }
        if (isLdapAccount(localUser)) {
            // 纯 AD 账号不走本地：AD 未启用时它无路可走，必须给出明确提示而不是「密码错误」——
            // 否则用户会反复重试自己的域口令，而管理员完全无从得知「AD 被关掉了」
            operationLogService.record(localUser.getId(), localUser.getUsername(), "AUTH", "LOGIN_LDAP_UNAVAILABLE",
                    "AD 账号尝试登录，但 AD 认证当前未启用，ip=" + ip, false, RiskLevel.NORMAL);
            throw new BusinessException(ErrorCode.LDAP_NOT_AVAILABLE);
        }
        return localLogin(localUser, request.getPassword(), ip, httpRequest, httpResponse);
    }

    /**
     * 走 AD 认证的登录分支（ 的 2/3/4/5 条）
     */
    private LoginUserInfo loginWithAd(String username, String password, User localUser, String ip,
                                     HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AdAuthResult authResult = adAuthenticationService.authenticate(username, password);
        // 安全事件需要 UA 作为取证上下文；本方法有 httpRequest，就地取一次
        String userAgent = httpRequest.getHeader("User-Agent");

        switch (authResult.outcome()) {
            case SUCCESS -> {
                return completeAdLogin(authResult.user(), username, ip, httpRequest, httpResponse);
            }
            case BAD_CREDENTIALS -> {
                //  第 4 条：域口令错 → 401，不回退本地。
                // 这里的「不回退」是硬约束：域口令错说明这是域账号，
                // 若此时回退到本地口令，等于给「域账号 + 旧本地口令」留了一条
                // 绕过域控（密码过期、锁定策略、强制下线）的通路。
                int failCount = loginProtectionService.recordLoginFailure(username);
                recordLoginFailure(username, ip,
                        "域认证失败（口令错误），累计失败 " + failCount + "/"
                                + systemConfigService.loginFailMaxCount() + " 次，ip=" + ip,
                        failCount, userAgent);
                throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
            }
            case ACCOUNT_DISABLED -> {
                operationLogService.record(localUser == null ? null : localUser.getId(), username, "AUTH",
                        "LOGIN_DISABLED", "域账号在 AD 中已被禁用，拒绝登录，ip=" + ip,
                        false, RiskLevel.NORMAL);
                throw new BusinessException(ErrorCode.ACCOUNT_DISABLED,
                        "域账号已被禁用，请联系管理员或 AD 域控管理员");
            }
            case USER_NOT_FOUND -> {
                // 域里没有这个人 → 它是本地账号。回退本地口令校验（「本地账号不被锁在外面」）
                if (localUser == null) {
                    return failUnknownAccount(username, ip, userAgent);
                }
                log.debug("账号 [{}] 不在域控中，按本地账号处理", username);
                return localLogin(localUser, password, ip, httpRequest, httpResponse);
            }
            case UNAVAILABLE -> {
                //  第 5 条：域服务器不可用 → 本地账号可登录，纯 AD 用户给明确提示
                log.warn("AD 域控不可用，登录降级为本地认证：account={}（{}）", username, authResult.message());
                if (localUser == null || isLdapAccount(localUser)) {
                    operationLogService.record(localUser == null ? null : localUser.getId(), username, "AUTH",
                            "LOGIN_LDAP_UNAVAILABLE",
                            "域服务器不可用，纯 AD 账号无法登录；原因=" + authResult.message() + "，ip=" + ip,
                            false, RiskLevel.NORMAL);
                    throw new BusinessException(ErrorCode.LDAP_NOT_AVAILABLE,
                            "域服务器不可用，请稍后重试或联系管理员");
                }
                return localLogin(localUser, password, ip, httpRequest, httpResponse);
            }
            default -> throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 域认证成功后的收尾：本地化账号 → 状态复核 → 签发令牌（ 第 3 条）
     */
    private LoginUserInfo completeAdLogin(AdUser adUser, String username, String ip,
                                         HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AdUserProvisioningService.ProvisionResult provisioned;
        try {
            provisioned = adUserProvisioningService.provision(adUser);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.USER_USERNAME_EXISTS) {
                // 「登录自动建号」与「定时同步」并发时的良性冲突：账号已经被对方建好了，
                // 重新读一次继续登录即可，不必把用户拒之门外
                User existing = userService.getByUsername(username);
                if (existing == null) {
                    throw e;
                }
                provisioned = new AdUserProvisioningService.ProvisionResult(existing, false, false);
            } else {
                throw e;
            }
        }

        User user = provisioned.user();
        if (!Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
            // 本地禁用 / 离职优先于域认证结果：本地管理员禁用某个账号，就是不想让他登录，
            // 不能因为「域口令是对的」而放行（本地管控必须高于域控）
            operationLogService.record(user.getId(), user.getUsername(), "AUTH", "LOGIN_DISABLED",
                    "域口令校验通过，但本地账号已禁用或已离职，拒绝登录，ip=" + ip, false, RiskLevel.NORMAL);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        if (provisioned.created()) {
            operationLogService.record(user.getId(), user.getUsername(), "AUTH", "AD_AUTO_PROVISION",
                    "AD 用户首次登录自动创建本地账号，角色=" + user.getRole() + "，ip=" + ip,
                    true, RiskLevel.HIGH);
            log.info("AD 用户 [{}] 首次登录，已自动创建本地账号（id={}，角色={}）",
                    user.getUsername(), user.getId(), user.getRole());
        }
        return completeLogin(user, ip, AUTH_TYPE_LDAP, httpRequest, httpResponse);
    }

    /**
     * 本地口令校验后的登录（本地账号，或降级路径）
     */
    private LoginUserInfo localLogin(User user, String password, String ip,
                                    HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 安全事件需要 UA 作为取证上下文；本方法有 httpRequest，就地取一次
        String userAgent = httpRequest.getHeader("User-Agent");
        if (!Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
            operationLogService.record(user.getId(), user.getUsername(), "AUTH", "LOGIN_DISABLED",
                    "账号已禁用或离职，拒绝登录，ip=" + ip, false, RiskLevel.NORMAL);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!userService.matchesPassword(user, password)) {
            int failCount = loginProtectionService.recordLoginFailure(user.getUsername());
            // 失败次数只写审计，不回显给客户端 —— 「剩余 N 次」会让攻击者据此区分账号是否存在
            recordLoginFailure(user.getUsername(), ip,
                    "密码校验失败，累计失败 " + failCount + "/"
                            + systemConfigService.loginFailMaxCount() + " 次，ip=" + ip,
                    failCount, userAgent);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        return completeLogin(user, ip, AUTH_TYPE_LOCAL, httpRequest, httpResponse);
    }

    /**
     * 账号不存在的统一处置：时序对齐 + 计入失败次数 + 返回含糊的错误码
     *
     * <p>三点缺一不可：
     * <ul>
     *   <li><b>时序对齐</b>：不执行等价哈希则响应明显更快，可被用来枚举有效账号；</li>
     *   <li><b>计入失败</b>：否则攻击者可以无限次用不存在的账号试探而不触发任何限制；</li>
     *   <li><b>含糊错误码</b>：统一返回 BAD_CREDENTIALS，不区分「账号不存在」与「密码错误」。</li>
     * </ul>
     */
    private LoginUserInfo failUnknownAccount(String username, String ip, String userAgent) {
        passwordEncoder.matches("", dummyHash);
        int failCount = loginProtectionService.recordLoginFailure(username);
        recordLoginFailure(username, ip, "用户不存在", failCount, userAgent);
        throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
    }

    /**
     * 登录成功的公共收尾：清失败计数 → 更新登录时间 → 签发 Cookie → 组装信息 → 审计
     *
     * @param authType 认证方式（LOCAL / LDAP），写入审计日志（「记录认证方式」）
     */
    private LoginUserInfo completeLogin(User user, String ip, String authType,
                                       HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        loginProtectionService.clearLoginFailure(user.getUsername());
        userService.touchLastLoginAt(user.getId());

        // 异常登录检测（P2）：凌晨 / 新设备 / 非常用 IP —— 记录事件并通知本人；
        // 管理员账号额外告警超管。放在这里（身份已确认、令牌尚未签发）：
        // 检测内部吞掉所有异常，绝不把一次成功登录变成失败。
        loginAnomalyService.inspect(user, ip, httpRequest.getHeader("User-Agent"));

        int expireMinutes = systemConfigService.jwtExpireMinutes();
        String token = issueToken(user.getId(), user.getUsername(), user.getRole(),
                userService.currentTokenVersion(user.getId()), expireMinutes);
        cookieService.writeToken(httpResponse, token, expireMinutes);

        LoginUserInfo info = LoginUserInfo.from(user);
        info.setDepartmentName(resolveDepartmentName(user.getDepartmentId()));
        fillPermissionInfo(info, user);

        operationLogService.record(user.getId(), user.getUsername(), "AUTH", "LOGIN",
                "登录成功，认证方式=" + authType + "，role=" + user.getRole() + "，ip=" + ip,
                true, RiskLevel.NORMAL);
        return info;
    }

    /**
     * 该账号是否必须走本地认证
     *
     * <p>判据取「登录名等于配置的超管登录名」<b>或</b>「本地账号角色是 super_admin」，
     * 而不是只认其中一个：
     * <ul>
     *   <li>只认登录名：有人把超管角色给了别的账号时，那个账号会被域认证接管 ——
     *       万一域里没有它，就会落到「USER_NOT_FOUND → 回退本地」，其实也能用，
     *       但一旦域里有同名账号，超管就变成了「口令由域控掌管」；</li>
     *   <li>只认角色：若超管账号尚未建好（首次部署）或角色被误改，就失去了「永远能进」的保障。</li>
     * </ul>
     * 两者取或，得到的是 第 1 条真正想要的性质：<b>总有办法进系统</b>。
     */
    private boolean isSuperAdminAccount(String username, User localUser) {
        if (localUser != null && RoleCode.isSuperAdmin(localUser.getRole())) {
            return true;
        }
        String configured = appProperties.getSuperAdmin() == null
                ? null : appProperties.getSuperAdmin().getUsername();
        return StringUtils.hasText(configured) && configured.equalsIgnoreCase(username);
    }

    private boolean isLdapAccount(User user) {
        return user != null && AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType());
    }

    /** 该账号是否由本地掌管口令（{@code auth_type = LOCAL}）；null 账号返回 false */
    private boolean isLocalAccount(User user) {
        return user != null && AUTH_TYPE_LOCAL.equalsIgnoreCase(user.getAuthType());
    }

    // ------------------------------------------------------------------
    // 修改 / 重置密码
    // ------------------------------------------------------------------

    /**
     * 修改当前登录用户的密码（；：改密后旧 Token 立即失效）
     *
     * <p><b>为什么要重写 Cookie 而不是让用户重新登录</b>：改密是用户主动发起的正常操作，
     * 把他本人踢出去再让他登一遍，体验上像是「系统出错了」；而旧 Token 必须失效又是硬要求。
     * 因此版本号 +1（作废别处会话）后立即用新版本号补发一枚 Token 写回 Cookie，
     * 当前会话无缝继续，其它设备上的会话全部失效。
     *
     * <p>AD 账号在此<b>直接拒绝</b>（「AD 用户不能在本地改密码」）：
     * 域口令只存在于域控，本地改密改了也没用 —— 用户会以为改了密码，
     * 实际下次仍要用域口令登录，属于典型的「静默无效操作」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordRequest request, HttpServletResponse httpResponse) {
        User user = userService.getByIdRequired(userId);

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "两次输入的新密码不一致");
        }
        if (isLdapAccount(user)) {
            throw new BusinessException(ErrorCode.AD_PASSWORD_MANAGED_BY_AD);
        }
        if (!userService.matchesPassword(user, request.getOldPassword())) {
            recordHighRiskQuietly(user.getId(), user.getUsername(), "CHANGE_PASSWORD", "当前密码校验失败", false);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "当前密码不正确");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_OLD);
        }

        passwordPolicyService.validate(request.getNewPassword(), user.getUsername());
        userService.updatePassword(userId, request.getNewPassword(), true);

        // 版本号 +1：让该用户此前在其它设备 / 标签页上签发的 Token 全部作废
        int newVersion = userService.bumpTokenVersion(userId);
        int expireMinutes = systemConfigService.jwtExpireMinutes();
        String token = issueToken(userId, user.getUsername(), user.getRole(), newVersion, expireMinutes);
        cookieService.writeToken(httpResponse, token, expireMinutes);

        recordHighRiskQuietly(user.getId(), user.getUsername(), "CHANGE_PASSWORD",
                "修改密码成功，已清除强制改密标记并作废其它会话", true);
    }

    /**
     * 退出登录：拉黑当前 Token 的 jti + 清理 Cookie（；）
     *
     * <p>只作废<b>当前这一枚</b> Token：用户在手机上点退出，不应该把他在办公室电脑上的
     * 登录一起踢掉（那需要走「强制下线」语义，由版本号承担）。
     */
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        LoginUser current = SecurityUtils.getCurrentUser();
        String token = cookieService.readToken(httpRequest);
        if (StringUtils.hasText(token)) {
            Claims claims = tokenProvider.parse(token);
            String jti = tokenProvider.getTokenId(claims);
            if (jti != null) {
                tokenBlacklistService.revoke(jti, tokenProvider.remainingLifetime(claims));
            }
        }
        cookieService.clearToken(httpResponse);
        if (current != null) {
            operationLogService.record(current.getId(), current.getUsername(), "AUTH", "LOGOUT",
                    "退出登录（已拉黑当前令牌）", true, RiskLevel.NORMAL);
        }
    }

    /**
     * 管理员重置他人密码（ 密码管理、 高风险同步审计；；2026-09-20  改造）
     *
     * <p>重置后把目标用户的版本号 +1：被重置密码的人往往正处于「账号可能已泄露」的
     * 场景，此刻他的旧会话正是需要立刻切断的东西 —— 只改密码而不作废旧 Token，
     * 等于给入侵者留了一把 12 小时有效的备用钥匙。
     *
     * @return 服务端生成的临时口令（仅本次返回，调用方负责展示与转交）
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long operatorId, Long targetUserId) {
        User target = userService.getByIdRequired(targetUserId);
        // 2026-09-20 ：统一委托给 UserService#resetPassword，避免两套实现漂移。
        // 「生成临时口令 / 置首登强制改密 / token_version +1 作废全部会话 / 发送含口令的
        // 站内消息」全部收敛在那一处；顺带也获得了它的超管保护（原实现漏了这一层）。
        // 本方法只额外补一条认证模块自己的高危审计（AuthController 未挂 @AuditLog）。
        String tempPassword = userService.resetPassword(targetUserId);

        LoginUser operator = SecurityUtils.getCurrentUser();
        recordHighRiskQuietly(operatorId, operator == null ? null : operator.getUsername(),
                "RESET_PASSWORD",
                "重置员工 [" + target.getUsername() + "](id=" + targetUserId + ") 的密码并作废其全部会话", true);
        log.warn("管理员 {} 重置了员工 [{}] 的密码，其既有会话已全部失效", operatorId, target.getUsername());
        return tempPassword;
    }

    /**
     * 查询当前登录用户信息
     */
    public LoginUserInfo currentUserInfo(Long userId) {
        User user = userService.getByIdRequired(userId);
        LoginUserInfo info = LoginUserInfo.from(user);
        info.setDepartmentName(resolveDepartmentName(user.getDepartmentId()));
        fillPermissionInfo(info, user);
        return info;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private String issueToken(Long userId, String username, String role, int tokenVersion, int expireMinutes) {
        return tokenProvider.createToken(userId, username, role, tokenVersion, expireMinutes);
    }

    /**
     * 把权限码、数据范围、内置超管标识、强制绑定联系方式标识塞进登录用户信息。
     *
     * <p>前端据此渲染菜单与按钮（动态菜单）。放在 /auth/me 而不是单开一个接口的原因：
     * 前端每次刷新页面都要调 /auth/me 恢复会话，权限与身份是同一次「我是谁」的答案，
     * 拆成两个接口只会让首屏多一次往返，并引入「身份拿到了、权限还没到」的中间态。
     *
     * <p><b>所有「由服务端派生的一次性标志」都必须收敛在本方法里</b>：
     * 登录响应与 {@code /auth/me} 共用它，漏掉任一处就会出现
     * 「刚登录时按新逻辑、刷新页面后又变回去」这类只在刷新后暴露的缺陷
     * （{@code builtInAdmin} 曾经就踩过这个坑，见下方注释）。
     */
    private void fillPermissionInfo(LoginUserInfo info, User user) {
        info.setPermissions(roleService.permissionCodesOf(user.getRole()));
        info.setDataScope(roleService.dataScopeOf(user.getRole()));
        // 内置超管标识必须在这里补齐：/auth/me 与登录响应共用本方法，
        // 漏掉任一处会让「刷新页面后按钮变样」—— 前端登录时算出的可见性与刷新后不一致。
        info.setBuiltInAdmin(BuiltinAdmin.isBuiltinAdmin(appProperties, user.getRole(), user.getUsername()));
        // 上线前 / ：是否需要弹「首次绑定联系方式」引导。
        info.setRequireContactBinding(requiresContactBinding(user));
    }

    /**
     * 是否需要强制绑定手机号或邮箱（、）。
     *
     * <p>两条判据，缺一不可：
     * <ol>
     *   <li><b>手机号与邮箱都为空</b> —— 只要绑了一个就算已绑定，不再打扰（）；</li>
     *   <li><b>至少还有一个验证渠道开着</b> —— 两个开关都关时用户<b>不可能</b>完成绑定
     *       （没有渠道能验证号码归属），此时仍然强制引导会把他永远堵在引导页上，
     *       既进不了系统也无人可求助。明确要求这种情况「直接用临时密码进系统」，
     *       由管理员在后台补录。</li>
     * </ol>
     */
    private boolean requiresContactBinding(User user) {
        boolean smsEnabled = systemConfigService.smsVerifyEnabled();
        boolean emailEnabled = systemConfigService.emailVerifyEnabled();
        if (ContactRecovery.allChannelsDisabled(smsEnabled, emailEnabled)) {
            return false;
        }
        return !StringUtils.hasText(user.getPhone()) && !StringUtils.hasText(user.getEmail());
    }

    /**
     * 一次登录失败的统一处置（ 起扩为四件事）。
     *
     * <p>顺序是刻意的：
     * <ol>
     *   <li><b>审计</b>（既有，保持不变）；</li>
     *   <li><b>安全事件落库</b> —— 失败也要落，否则 IP 封禁没有计数依据；</li>
     *   <li><b>达阈值则记「账号锁定」事件并告警</b> —— 这一步才是「防护已介入」的信号；</li>
     *   <li><b>最后</b>才判 IP 封禁：这样本轮插入的 LOGIN_FAIL 已被计入，
     *       「第 N 次失败即封禁」与页面上写的阈值一致（差一步就会变成「N+1 次才封」）。</li>
     * </ol>
     *
     * <p>四件事都各自吞异常（见各自的实现），任何一步失败都不影响登录接口
     * 返回它本该返回的错误码 —— 安全记录不该改变业务结果。
     */
    private void recordLoginFailure(String username, String ip, String detail,
                                    int failCount, String userAgent) {
        operationLogService.record(null, username, "AUTH", "LOGIN_FAILED", detail, false, RiskLevel.NORMAL);

        securityEventRecorder.record(SecurityEventType.LOGIN_FAIL, username, null, ip, userAgent, detail);

        int maxCount = systemConfigService.loginFailMaxCount();
        if (failCount >= maxCount) {
            securityEventRecorder.record(SecurityEventType.ACCOUNT_LOCKED, username, null, ip, userAgent,
                    "连续失败 " + failCount + " 次，账号已锁定 "
                            + systemConfigService.loginLockMinutes() + " 分钟");
        }

        ipBlockService.recordFailureAndMaybeBlock(ip, username, userAgent);
    }

    private String resolveDepartmentName(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department group = departmentMapper.selectById(departmentId);
        return group == null ? null : group.getDeptName();
    }

    /**
     * 高危审计落库失败时不得中断主流程：审计系统故障不应让改密、重置密码等业务不可用。
     * 与 {@code @AuditLog} 切面的失败语义保持一致（切面同样吞掉异常并记 error）。
     */
    private void recordHighRiskQuietly(Long operatorId, String operatorName, String action,
                                       String details, boolean success) {
        try {
            operationLogService.record(operatorId, operatorName, "AUTH", action, details, success, RiskLevel.HIGH);
        } catch (Exception e) {
            log.error("高危审计写入失败（已忽略，不影响主流程）：action={}, operator={}", action, operatorName, e);
        }
    }
}
