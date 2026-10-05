package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordChannelsVO;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordMetaVO;
import com.enterprise.ticket.module.auth.dto.vo.ForgotPasswordSendCodeVO;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.security.RateLimitGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 找回密码（ / 五 / 六 / 七）。
 *
 * <h2>三步流程与它们各自的职责</h2>
 * <ol>
 *   <li>{@link #channels(String)} —— <b>「有哪些渠道可用」</b>。<b>免鉴权</b>；
 *       只回传打码后的联系方式，绝不回传完整号码或验证码。</li>
 *   <li>{@link #sendCode(String, String)} —— <b>「发码」</b>。真正的第一道限流闸门在这里。</li>
 *   <li>{@link #reset(String, String, String)} —— <b>「兑码改密」</b>。校验成功后
 *       改口令 + 作废该账号全部会话。</li>
 * </ol>
 *
 * <h2>为什么验证码存 Redis 而不是「签一个带验证码的 JWT」</h2>
 * <p>验证码天然是 <b>TTL 语义</b>的数据：到期就该消失，无需清理任务。
 * 更重要的是<b>「5 分钟有效 + 错 5 次作废」需要服务端能主动删掉它</b> ——
 * 无状态的签名令牌删不掉，只能等它自己过期，而「作废」恰恰是防爆破的关键动作。
 * 与 {@code TokenBlacklistService} 用 Redis 是同一个理由（纯 TTL + 需要主动失效）。
 *
 * <h2>限流与锁定为什么都用「账号 id」做键，而不是用户输入的字符串</h2>
 * <p>用户可以用四种形态指代同一个账号（登录名 / 姓名 / 手机 / 邮箱）。
 * 若按输入串计数，攻击者只要换个写法就能重置计数窗口，
 * 而「同一账号 10 分钟最多 3 次」（）就成了摆设。
 * 统一解析到 {@code user_id} 之后，无论怎么换写法都落在同一个计数器上。
 *
 * <h2>账号枚举（）</h2>
 * <p>按手机号 / 邮箱查不到账号时，错误文案是「未找到绑定该手机号/邮箱的账号」，
 * <b>不是</b>「账号不存在」。差别在于：后者会让本接口变成一个可以批量调用的
 * 「这个手机号有没有注册」查询器 —— 用一份手机号名单跑一遍，就能筛出
 * 哪些号段属于本公司员工，进而对这些人生成可信度极高的钓鱼短信。
 * 现在的措辞只陈述「没有账号绑定它」，不透露号码本身的信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {

    /** 验证码：`forget:code:<userId>` → 验证码明文（TTL = 有效期） */
    private static final String KEY_CODE = "forget:code:";

    /** 发码计数：`forget:send:<userId>` → 次数（固定窗口 10 分钟） */
    private static final String KEY_SEND = "forget:send:";

    /** 校验失败计数：`forget:fail:<userId>` → 次数（错满即作废） */
    private static final String KEY_FAIL = "forget:fail:";

    /** 发码窗口与上限（：同一账号 10 分钟内最多发 3 次） */
    private static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    private static final int SEND_MAX = 3;

    /** 校验失败上限（：验证码输错 5 次作废） */
    private static final int FAIL_MAX = 5;

    /**
     * 同 IP 限流键与阈值（ ：找回密码接口每分钟最多 10 次）。
     *
     * <p>为什么「按 IP」这一层必须补上：{@code /forgot-password/channels} 是<b>免鉴权</b>
     * 且会回答「这个账号存在吗、绑了什么渠道」的接口 —— 没有 IP 桶时它就是一个
     * 免费的账号枚举预言机，把登录接口刻意统一的「账号或密码错误」反枚举设计整个抵消掉。
     * 账号级限流挡不住枚举：攻击者换账号名就行了，而这正是他要枚举的东西。
     *
     * <p>阈值暂时以常量固定（与需求文本一致）； 引入系统参数字典后会并入可配置项。
     */
    private static final String KEY_RATE_FORGOT_IP = "forget:rate:ip:";
    private static final Duration FORGOT_IP_WINDOW = Duration.ofMinutes(1);
    private static final int FORGOT_IP_MAX = 10;

    /** 同账号限流键与阈值（ ：10 分钟最多 3 次） */
    private static final String KEY_RATE_FORGOT_ACCOUNT = "forget:rate:acct:";
    private static final Duration FORGOT_ACCOUNT_WINDOW = Duration.ofMinutes(10);
    private static final int FORGOT_ACCOUNT_MAX = 3;

    private static final String AUTH_TYPE_LDAP = "LDAP";

    /** 验证码生成器：SecureRandom 而不是 Random —— 后者可被从输出序列倒推种子 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final SystemConfigService systemConfigService;
    private final PasswordPolicyService passwordPolicyService;
    private final StringRedisTemplate redisTemplate;
    private final OperationLogService operationLogService;
    private final VerificationCodeSender codeSender;
    private final Environment environment;
    private final RateLimitGuard rateLimitGuard;

    // ==================================================================
    // 元信息（登录页决定是否显示「无法登录？」入口）
    // ==================================================================

    /**
     * 找回密码功能是否可用、有哪些渠道 —— <b>免鉴权</b>（登录页就要用）。
     *
     * <p>只回传「功能级」信息，不含任何账号数据（见 {@link ForgotPasswordMetaVO}）。
     */
    public ForgotPasswordMetaVO meta() {
        boolean smsEnabled = systemConfigService.smsVerifyEnabled();
        boolean emailEnabled = systemConfigService.emailVerifyEnabled();

        ForgotPasswordMetaVO vo = new ForgotPasswordMetaVO();
        vo.setEnabled(!ContactRecovery.allChannelsDisabled(smsEnabled, emailEnabled));
        List<String> channels = new ArrayList<>();
        if (smsEnabled) {
            channels.add(ContactRecovery.CONTACT_SMS);
        }
        if (emailEnabled) {
            channels.add(ContactRecovery.CONTACT_EMAIL);
        }
        vo.setChannels(channels);
        vo.setCodeLength(systemConfigService.forgotCodeLength());
        return vo;
    }

    // ==================================================================
    // 第一步：查询可用渠道
    // ==================================================================

    /**
     * 查询该账号能用来接收验证码的渠道（前端第 2 步、）。
     *
     * <p>返回的 {@code channels} 已经同时过滤掉「渠道没启用」与「账号没绑定」两种不可用，
     * 前端直接渲染即可 —— 判断逻辑只存在于服务端一处，不会出现
     * 「页面显示了手机选项、后端却拒绝发送」的自相矛盾。
     */
    public ForgotPasswordChannelsVO channels(String account) {
        ensureEnabled();
        // 先限流再查库：本接口免鉴权且会回答账号是否存在，是枚举的入口
        checkForgotIpRateLimit();
        User user = requireAccount(account);
        ensureLocalAccount(user);

        boolean smsEnabled = systemConfigService.smsVerifyEnabled();
        boolean emailEnabled = systemConfigService.emailVerifyEnabled();
        boolean hasPhone = StringUtils.hasText(user.getPhone());
        boolean hasEmail = StringUtils.hasText(user.getEmail());

        // 顺序很关键：先判「一个都没绑」（这是用户自己的状态问题，提示他去联系管理员），
        // 再判「绑了但渠道被关」（这是管理员配置问题，提示他等其他方式）。
        // 反过来的话，「什么联系方式都没绑」的用户会收到一句
        // 「该验证方式已被管理员关闭」—— 而他其实什么都没绑，会白等管理员去开开关。
        if (!hasPhone && !hasEmail) {
            throw new BusinessException(ErrorCode.ACCOUNT_WITHOUT_CONTACT);
        }

        ForgotPasswordChannelsVO vo = new ForgotPasswordChannelsVO();
        List<String> channels = new ArrayList<>();
        if (smsEnabled && hasPhone) {
            channels.add(ContactRecovery.CONTACT_SMS);
            vo.setMaskedPhone(AccountFormats.maskContact(user.getPhone()));
        }
        if (emailEnabled && hasEmail) {
            channels.add(ContactRecovery.CONTACT_EMAIL);
            vo.setMaskedEmail(AccountFormats.maskContact(user.getEmail()));
        }
        if (channels.isEmpty()) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_DISABLED,
                    "该账号绑定的联系方式均已被管理员关闭，请联系管理员重置密码");
        }
        vo.setChannels(channels);
        vo.setDisplayName(StringUtils.hasText(user.getDisplayName())
                ? user.getDisplayName() : user.getRealName());
        vo.setCodeLength(systemConfigService.forgotCodeLength());
        vo.setCodeExpireMinutes(systemConfigService.forgotCodeExpireMinutes());
        return vo;
    }

    // ==================================================================
    // 第二步：发送验证码
    // ==================================================================

    /**
     * 生成并发送验证码（ / 二.3 / 二.4）。
     *
     * <p>顺序：可用性 → 账号 → 渠道合法 → 渠道启用 → 已绑定 → <b>发码限流</b> → 生成 → 落 Redis → 投递 → 审计。
     * 把限流放在「生成」之前是刻意的：先写 Redis 再判断限流，会在被限流时留下一枚
     * 「已生成但没发出去」的验证码（用户没收到，万一猜中却能改密）。
     */
    public ForgotPasswordSendCodeVO sendCode(String account, String contactType) {
        ensureEnabled();
        checkForgotIpRateLimit();
        User user = requireAccount(account);
        ensureLocalAccount(user);
        checkForgotAccountRateLimit(user.getId());

        String channel = ContactRecovery.normalizeContactType(contactType);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证方式取值不合法（仅支持 SMS / EMAIL）");
        }
        ensureChannelEnabled(channel);
        String target = requireBoundTarget(user, channel);

        checkSendRateLimit(user.getId());

        int expireMinutes = systemConfigService.forgotCodeExpireMinutes();
        String code = generateCode(systemConfigService.forgotCodeLength());

        redisTemplate.opsForValue().set(KEY_CODE + user.getId(), code, Duration.ofMinutes(expireMinutes));
        // 新码一发，旧的输错计数就该清零 —— 否则用户被上一枚码的错误拖累，
        // 刚拿到新码却发现"输错 5 次已作废"
        redisTemplate.delete(KEY_FAIL + user.getId());

        // 投递失败会抛异常并让整个流程中止（见 VerificationCodeSender 的约定）。
        // 注意：Redis 里的码此时已经写入了 —— 但我们**不**回滚它，
        // 因为「投递失败」对调用方是不可见的（网关异常），而用户看到的是错误提示；
        // 此时若保留旧码，用户重试时会生成新码覆盖它，不会产生不一致。
        codeSender.send(channel, target, code, expireMinutes);

        record(user, "FORGOT_PASSWORD_SEND_CODE",
                "发送找回密码验证码，渠道=" + ContactRecovery.label(channel)
                        + "，目标=" + AccountFormats.maskContact(target));

        return buildSendResult(channel, target, expireMinutes, code);
    }

    // ==================================================================
    // 第三步：校验验证码并重置密码
    // ==================================================================

    /**
     * 校验验证码并设置新密码（ / 二.5）。
     *
     * <p>成功后做三件事：
     * <ol>
     *   <li>写入新口令并清除「首登强制改密」标记 —— 这是用户<b>自己设定</b>的口令，
     *       没有理由再要求他改一遍；</li>
     *   <li>{@code token_version + 1}，<b>作废该账号全部已有会话</b>（踢下线）。
     *       找回密码的典型场景是「账号可能已经泄露」，此刻旧会话正是需要立刻切断的东西；</li>
     *   <li>清除 Redis 中的验证码与两个计数器 —— 同一枚验证码不允许兑两次。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void reset(String account, String code, String newPassword) {
        ensureEnabled();
        checkForgotIpRateLimit();
        User user = requireAccount(account);
        ensureLocalAccount(user);
        checkForgotAccountRateLimit(user.getId());

        String key = KEY_CODE + user.getId();
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            // 没发过码 / 已过期 / 已被兑掉 / 已被错满作废，四种情况一律同一提示 ——
            // 分别提示会告诉攻击者「这个账号到底有没有在走找回流程」
            throw new BusinessException(ErrorCode.VERIFY_CODE_INVALID);
        }
        if (!cached.equals(code == null ? "" : code.trim())) {
            registerWrongAttempt(user, key);
            // registerWrongAttempt 必然抛出，这里不会继续执行
            return;
        }

        // 强度要求与改密完全一致（）：直接复用同一个策略服务，
        // 而不是另写一套 —— 两套阈值迟早会漂移成「改密要 8 位、找回密码 6 位就行」
        passwordPolicyService.validate(newPassword, user.getUsername());

        userService.updatePassword(user.getId(), newPassword, true);
        userService.bumpTokenVersion(user.getId());

        redisTemplate.delete(key);
        redisTemplate.delete(KEY_FAIL + user.getId());
        redisTemplate.delete(KEY_SEND + user.getId());

        record(user, "FORGOT_PASSWORD_RESET",
                "通过找回密码自助重置口令成功（已作废该账号全部会话）");
        log.warn("账号 [{}] 通过找回密码流程自助重置了密码，其既有会话已全部失效",
                user.getUsername());
    }

    // ==================================================================
    // 内部：可用性与账号
    // ==================================================================

    /** 两个验证渠道都关时，找回密码整体不可用（） */
    private void ensureEnabled() {
        if (ContactRecovery.allChannelsDisabled(
                systemConfigService.smsVerifyEnabled(), systemConfigService.emailVerifyEnabled())) {
            throw new BusinessException(ErrorCode.FORGOT_PASSWORD_DISABLED);
        }
    }

    /**
     * 解析账号；不存在时给出与「输入形态」匹配的报错。
     *
     * <p>{@code findByAccount} 对手机号 / 邮箱查不到的情况已经抛
     * {@code CONTACT_NOT_BOUND_TO_ACCOUNT}（防枚举）；这里只需兜住
     * 「登录名 / 姓名查不到」这一路，给出直白的「账号不存在」。
     */
    private User requireAccount(String account) {
        User user = userService.findByAccount(account);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "账号不存在，请检查登录名或姓名");
        }
        return user;
    }

    /**
     * AD 域账号不能本地重置口令（）。
     *
     * <p>域口令只存在于域控里，本地改出来的口令根本不会被用作登录凭据 ——
     * 用户会以为「密码重置成功了」，实际下次仍必须用域口令，属于典型的静默无效操作。
     * 这里在入口处直接拒绝并给出明确指引，而不是让他走完三步再失败。
     */
    private void ensureLocalAccount(User user) {
        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new BusinessException(ErrorCode.AD_PASSWORD_MANAGED_BY_AD,
                    "该账号由域控管理，请联系管理员");
        }
    }

    private void ensureChannelEnabled(String channel) {
        boolean enabled = ContactRecovery.CONTACT_SMS.equals(channel)
                ? systemConfigService.smsVerifyEnabled()
                : systemConfigService.emailVerifyEnabled();
        if (!enabled) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_DISABLED);
        }
    }

    /** 取该渠道对应的已绑定目标；没绑定时报「该账号未绑定所选的验证方式」 */
    private String requireBoundTarget(User user, String channel) {
        String target = ContactRecovery.CONTACT_SMS.equals(channel) ? user.getPhone() : user.getEmail();
        if (!StringUtils.hasText(target)) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_UNBOUND);
        }
        return target;
    }

    // ==================================================================
    // 内部：限流与验证码
    // ==================================================================

    /**
     * 找回密码「同 IP」限流（ ：每分钟 10 次，三个接口共用）。
     *
     * <p>使用固定窗口而非令牌桶：与登录限流保持同一算法，使「被限流」时的
     * {@code Retry-After} 语义在全站一致；且固定窗口的计数键可读，
     * 排查时能直接看到「这个 IP 在这分钟内打了多少次」。
     *
     * <p>Redis 不可用时由 {@link RateLimitGuard} 放行（fail-open）—— 限流是防滥用，
     * 不该在缓存抖动时把「找回密码」这条自救通道一并关掉。
     */
    private void checkForgotIpRateLimit() {
        String key = KEY_RATE_FORGOT_IP + safeKey(SecurityUtils.getClientIp());
        RateLimitGuard.Decision decision = rateLimitGuard.tryAcquireFixedWindow(
                key, FORGOT_IP_MAX, FORGOT_IP_WINDOW);
        if (!decision.allowed()) {
            throw BusinessException.rateLimited(
                    "操作过于频繁，请 " + decision.retryAfterSeconds() + " 秒后重试",
                    decision.retryAfterSeconds());
        }
    }

    /**
     * 找回密码「同账号」限流（ ：10 分钟 3 次）。
     *
     * <p>与 {@code checkSendRateLimit} 分开计数是刻意的：后者限制的是
     * <b>「同一个账号能收几条短信/邮件」</b>（防短信轰炸），只作用于发码；
     * 本方法限制的是<b>「同一个账号能被试几次找回流程」</b>，发码与重置都算 ——
     * 合并成一个计数器会让「试了 3 次重置但一次码都没发出去」的账号被误判为发码超限。
     */
    private void checkForgotAccountRateLimit(Long userId) {
        String key = KEY_RATE_FORGOT_ACCOUNT + (userId == null ? "unknown" : userId);
        RateLimitGuard.Decision decision = rateLimitGuard.tryAcquireFixedWindow(
                key, FORGOT_ACCOUNT_MAX, FORGOT_ACCOUNT_WINDOW);
        if (!decision.allowed()) {
            throw BusinessException.rateLimited(
                    "该账号操作过于频繁，请 " + decision.retryAfterSeconds() + " 秒后重试",
                    decision.retryAfterSeconds());
        }
    }

    /** 限流键里的空值兜底（IP 取不到时不能拼出「前缀 + null」，否则所有人共用一个桶） */
    private String safeKey(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }

    /**
     * 发码限流：同一账号 10 分钟内最多 3 次（）。
     *
     * <p>固定窗口计数（与 {@code LoginProtectionService} 的口径一致），
     * 用 Redis 的 {@code INCR} + 首次 {@code EXPIRE} 实现：
     * 计数与过期必须原子地一起做，否则「刚 INCR 完进程挂了」会留下一个永不过期的
     * 计数器，把该账号的发码能力永久锁死。
     */
    private void checkSendRateLimit(Long userId) {
        String key = KEY_SEND + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(key, SEND_WINDOW);
        }
        if (count > SEND_MAX) {
            Long ttl = redisTemplate.getExpire(key);
            long retryAfter = ttl == null || ttl < 0 ? SEND_WINDOW.toSeconds() : ttl;
            throw BusinessException.rateLimited(
                    "验证码发送过于频繁，请 " + Math.max(1, retryAfter / 60) + " 分钟后再试",
                    retryAfter);
        }
    }

    /**
     * 记录一次验证码校验失败；达到上限则作废本次验证码（：错 5 次作废）。
     *
     * <p>「作废」是必须的动作：只锁 5 分钟不够 —— 攻击者可以等锁定结束继续用**同一枚**
     * 验证码试，等于把「5 分钟有效期」变成了「无限次尝试」。删掉码之后，
     * 他必须重新触发发送，而那一步有 10 分钟 3 次的限流兜着。
     */
    private void registerWrongAttempt(User user, String codeKey) {
        String failKey = KEY_FAIL + user.getId();
        Long failCount = redisTemplate.opsForValue().increment(failKey);
        // 失败计数与验证码同寿命（略长），避免计数比码活得久导致的"隔天还算账"
        redisTemplate.expire(failKey, Duration.ofMinutes(systemConfigService.forgotCodeExpireMinutes()
                + SEND_WINDOW.toMinutes()));

        if (failCount != null && failCount >= FAIL_MAX) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(failKey);
            record(user, "FORGOT_PASSWORD_CODE_LOCKED",
                    "验证码连续输错 " + failCount + " 次，本次验证码已作废");
            throw new BusinessException(ErrorCode.VERIFY_CODE_LOCKED);
        }
        throw new BusinessException(ErrorCode.VERIFY_CODE_INVALID);
    }

    /**
     * 生成指定位数的纯数字验证码。
     *
     * <p>逐位取 {@code 0-9} 而不是「取一个 0~999999 的随机数再补零」：
     * 后者会让「首位是 0」的码长度不足（如 000123 变成 123），
     * 用户在界面上按 6 位输入就会永远对不上。逐位生成天然保证位数固定。
     */
    private String generateCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return builder.toString();
    }

    /**
     * 组装发送结果；<b>验证码只在开发环境回传</b>（）。
     */
    private ForgotPasswordSendCodeVO buildSendResult(String channel, String target,
                                                     int expireMinutes, String code) {
        ForgotPasswordSendCodeVO vo = new ForgotPasswordSendCodeVO();
        vo.setContactType(channel);
        vo.setMaskedTarget(AccountFormats.maskContact(target));
        vo.setExpireMinutes(expireMinutes);
        if (devMode()) {
            vo.setDevCode(code);
            log.warn("[DEV] 找回密码验证码已随响应返回（生产环境不会出现这一行）：账号={}，渠道={}",
                    target, ContactRecovery.label(channel));
        }
        return vo;
    }

    /**
     * 是否开发环境。
     *
     * <p>判据取 <b>profile</b> 而不是某个可热改的系统参数：这条开关决定
     * 「验证码是否随响应回传」，等价于「是否跳过验证」。用参数页能改的开关来承载它，
     * 意味着任何人只要拿到 {@code config:manage} 就能把全站变成「无需验证码即可改密」。
     * profile 是部署时确定的、改它要动环境变量，风险等级完全不同。
     */
    private boolean devMode() {
        return environment.acceptsProfiles(Profiles.of("dev"));
    }

    // ==================================================================
    // 内部：审计与打码
    // ==================================================================

    /**
     * 高危审计（）：发送验证码与成功重置口令都要留痕。
     *
     * <p>写失败不中断主流程（与 {@code AuthService#recordHighRiskQuietly} 同一取舍）：
     * 审计系统故障不应让用户连密码都改不了。
     */
    private void record(User user, String action, String details) {
        try {
            operationLogService.record(user.getId(), user.getUsername(), "AUTH", action,
                    details, true, RiskLevel.HIGH);
        } catch (Exception e) {
            log.error("找回密码高危审计写入失败（已忽略，不影响主流程）：action={}", action, e);
        }
    }

    // 打码统一走 AccountFormats.maskContact（ ：全库唯一实现）
}
