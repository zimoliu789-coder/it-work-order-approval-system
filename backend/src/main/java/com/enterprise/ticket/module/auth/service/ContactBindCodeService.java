package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.module.auth.dto.vo.BindContactSendCodeVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;

/**
 * 绑定 / 换绑联系方式的验证码（，）。
 *
 * <h2>为什么不能直接复用 {@code ForgotPasswordService} 的验证码</h2>
 * <p>两者虽然都用「六位数字 + 5 分钟有效 + 错 5 次作废」，但<b>验证的东西根本不同</b>：
 * <ul>
 *   <li>找回密码验证的是「<b>你已绑定的</b>联系方式」—— 目标值取自数据库，
 *       用户无法指定；码发到他早就绑好的号码上。</li>
 *   <li>绑定验证的是「<b>你想绑上去的</b>联系方式」—— 目标值来自用户输入。
 *       如果共用同一枚码、又不校验目标，就出现了最危险的一条路径：
 *       用户拿「发给自己的手机号」的验证码，去绑定<b>别人的</b>手机号。</li>
 * </ul>
 * 因此本服务除了独立的 Redis 键空间外，还多存一份 {@link #KEY_TARGET}：
 * <b>校验时必须同时比对「码」与「发码时的目标值」</b>，两者任一不符都按验证码错误处理。
 *
 * <h2>键空间隔离</h2>
 * <p>全部键加 {@code bind:} 前缀（找回密码是 {@code forget:}）。共用键会导致
 * 「先发找回密码的码，再走绑定流程，用同一枚码通关」这类跨流程串用。
 *
 * <h2>「用后即焚」不在这里做</h2>
 * <p>{@link #verify} <b>只校验、不消费</b>；消费由 {@link #consume} 在<b>写库成功之后</b>调用。
 * 若在 verify 里就删码，「同时改手机与邮箱、其中一个码填错」的重试需要重新获取那个已通过的码，
 * 而写库失败（号码被并发占用）时也一样要重新发码 —— 都是无谓的重复操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactBindCodeService {

    /** 验证码：`bind:code:<userId>:<channel>` → 验证码明文（TTL = 有效期） */
    private static final String KEY_CODE = "bind:code:";

    /** 发码目标：`bind:target:<userId>:<channel>` → 发码时的归一化目标值（TTL = 有效期） */
    private static final String KEY_TARGET = "bind:target:";

    /** 校验失败计数：`bind:fail:<userId>:<channel>` → 次数（错满即作废） */
    private static final String KEY_FAIL = "bind:fail:";

    /** 发码计数：`bind:send:<userId>:<channel>` → 次数（固定窗口 10 分钟） */
    private static final String KEY_SEND = "bind:send:";

    /** 发码窗口与上限（与找回密码同口径：同一账号同一渠道 10 分钟最多 3 条） */
    private static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    private static final int SEND_MAX = 3;

    /** 校验失败上限（错 5 次作废，与找回密码一致） */
    private static final int FAIL_MAX = 5;

    /** 验证码生成器：SecureRandom 而不是 Random —— 后者可被从输出序列倒推种子 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SystemConfigService systemConfigService;
    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeSender codeSender;
    private final UserService userService;
    private final Environment environment;

    // ==================================================================
    // 第一步：按目标值发码
    // ==================================================================

    /**
     * 向「用户想绑定的那个号码」发送验证码（）。
     *
     * <p>顺序：渠道合法 → 渠道启用 → 目标格式 → <b>唯一性预检</b> → 发码限流 → 生成 → 落 Redis → 投递。
     *
     * <p>把唯一性预检放在发码之前是刻意的：花一条短信告诉用户「这个号码可用」、
     * 等他在绑定页填完码才被告知「已被占用」，是最令人恼火的失败顺序。
     */
    public BindContactSendCodeVO sendCode(Long userId, String contactType, String target) {
        String channel = requireChannel(contactType);
        ensureChannelEnabled(channel);
        String normalized = normalizeTarget(channel, target);
        assertTargetAvailable(channel, normalized, userId);

        checkSendRateLimit(userId, channel);

        int expireMinutes = systemConfigService.forgotCodeExpireMinutes();
        String code = generateCode(systemConfigService.forgotCodeLength());

        String suffix = suffix(userId, channel);
        Duration ttl = Duration.ofMinutes(expireMinutes);
        redisTemplate.opsForValue().set(KEY_CODE + suffix, code, ttl);
        redisTemplate.opsForValue().set(KEY_TARGET + suffix, normalized, ttl);
        // 新码一发，旧的输错计数就该清零 —— 否则用户被上一枚码的错误拖累，
        // 刚拿到新码却发现「输错 5 次已作废」
        redisTemplate.delete(KEY_FAIL + suffix);

        // 投递失败会抛异常并让整个流程中止（见 VerificationCodeSender 的约定）——
        // 「界面提示已发送、实际没发出去」比直接报错更糟：用户会一直等一条永远不来的短信。
        codeSender.send(channel, normalized, code, expireMinutes);

        return buildResult(channel, normalized, expireMinutes, code);
    }

    // ==================================================================
    // 第二步：校验（不消费）
    // ==================================================================

    /**
     * 校验验证码，<b>不消费</b>（消费见 {@link #consume}）。
     *
     * <p>三道判定缺一不可：
     * <ol>
     *   <li>码不存在（没发过 / 已过期 / 已消费 / 已被错满作废）→ 一律「验证码错误或已过期」。
     *       分情况提示会告诉攻击者「这个账号到底有没有在走绑定流程」；</li>
     *   <li><b>发码目标 ≠ 当前提交目标</b> → 同样按验证码错误处理。
     *       抽查这一条就等于允许「用发给自己的码去绑定任意号码」；</li>
     *   <li>码本身不符 → 记一次失败，达到上限即作废。</li>
     * </ol>
     *
     * @param target 用户本次<b>提交</b>的目标值（原始输入，内部自行归一化比对）
     */
    public void verify(Long userId, String contactType, String target, String code) {
        String channel = requireChannel(contactType);
        String normalized = normalizeTarget(channel, target);

        String suffix = suffix(userId, channel);
        String codeKey = KEY_CODE + suffix;
        String cachedCode = redisTemplate.opsForValue().get(codeKey);
        if (cachedCode == null) {
            throw new BusinessException(ErrorCode.VERIFY_CODE_INVALID);
        }
        String cachedTarget = redisTemplate.opsForValue().get(KEY_TARGET + suffix);
        if (!Objects.equals(cachedTarget, normalized)) {
            // 与码错误同一提示：把「目标不匹配」单独说出来，等于告诉攻击者
            // 「这枚码确实有效、只是号码不对」，反而给了他换号重试的方向
            throw new BusinessException(ErrorCode.VERIFY_CODE_INVALID);
        }
        if (!cachedCode.equals(code == null ? "" : code.trim())) {
            registerWrongAttempt(userId, channel, codeKey);
            // registerWrongAttempt 必然抛出，这里不会继续执行
            return;
        }
    }

    /**
     * 消费验证码（写库成功后调用）：码、目标、失败计数一并清除。
     *
     * <p>不消费就允许同一枚码反复通关 —— 而「绑定」恰恰是改了库中身份标识的动作。
     */
    public void consume(Long userId, String contactType) {
        String channel = requireChannel(contactType);
        String suffix = suffix(userId, channel);
        redisTemplate.delete(KEY_CODE + suffix);
        redisTemplate.delete(KEY_TARGET + suffix);
        redisTemplate.delete(KEY_FAIL + suffix);
    }

    // ==================================================================
    // 内部：渠道与目标
    // ==================================================================

    /** 渠道编码归一化；非法取值直接报参数错误 */
    private String requireChannel(String contactType) {
        String channel = ContactRecovery.normalizeContactType(contactType);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证方式取值不合法（仅支持 SMS / EMAIL）");
        }
        return channel;
    }

    /** ：渠道被管理员关闭后，对应方式不能再用于绑定 / 改绑 */
    private void ensureChannelEnabled(String channel) {
        boolean enabled = ContactRecovery.CONTACT_SMS.equals(channel)
                ? systemConfigService.smsVerifyEnabled()
                : systemConfigService.emailVerifyEnabled();
        if (!enabled) {
            throw new BusinessException(ErrorCode.CONTACT_CHANNEL_DISABLED);
        }
    }

    /**
     * 归一化接收目标：手机号去空白；邮箱去空白 + 转小写。
     *
     * <p>邮箱转小写必须与 {@code UserServiceImpl#normalizeEmail} <b>同口径</b> ——
     * 否则「A@x.com」发码、「a@x.com」提交会被判成目标不一致，用户永远绑不上。
     */
    private String normalizeTarget(String channel, String target) {
        String value = target == null ? "" : target.trim();
        if (ContactRecovery.CONTACT_SMS.equals(channel)) {
            if (!AccountFormats.isPhone(value)) {
                throw new BusinessException(ErrorCode.CONTACT_PHONE_INVALID);
            }
            return value;
        }
        if (!AccountFormats.isEmail(value)) {
            throw new BusinessException(ErrorCode.CONTACT_EMAIL_INVALID);
        }
        return value.toLowerCase();
    }

    /** 唯一性预检：该号码 / 邮箱是否已被**其他**账号占用 */
    private void assertTargetAvailable(String channel, String normalized, Long userId) {
        if (ContactRecovery.CONTACT_SMS.equals(channel)) {
            userService.assertPhoneAvailable(normalized, userId);
        } else {
            userService.assertEmailAvailable(normalized, userId);
        }
    }

    // ==================================================================
    // 内部：限流与验证码
    // ==================================================================

    private String suffix(Long userId, String channel) {
        return (userId == null ? "unknown" : userId) + ":" + channel;
    }

    /**
     * 发码限流：同一账号同一渠道 10 分钟最多 3 条（与找回密码同口径）。
     *
     * <p>计数按<b>渠道</b>分开：用户「发了 3 条短信又想要邮件码」不应被手机桶挡住 ——
     * 两者是不同通道的消耗，混在一个桶里会让用户在自己没超限的渠道上被拒。
     */
    private void checkSendRateLimit(Long userId, String channel) {
        String key = KEY_SEND + suffix(userId, channel);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            // Redis 不可用：限流是防轰炸，不该在缓存抖动时把「绑定联系方式」这条自救通道关掉
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
     * 记录一次校验失败；达到上限则作废本次验证码（错 5 次作废）。
     *
     * <p>「作废」是必须的动作：只锁一段时间不够 —— 攻击者可以等锁定结束继续用
     * <b>同一枚</b>验证码试，等于把「5 分钟有效期」变成了「无限次尝试」。
     * 删掉码之后，他必须重新触发发送，而那一步有 10 分钟 3 次的限流兜着。
     */
    private void registerWrongAttempt(Long userId, String channel, String codeKey) {
        String suffix = suffix(userId, channel);
        String failKey = KEY_FAIL + suffix;
        Long failCount = redisTemplate.opsForValue().increment(failKey);
        // 失败计数与验证码同寿命（略长），避免计数比码活得久导致的「隔天还算账」
        redisTemplate.expire(failKey, Duration.ofMinutes(
                systemConfigService.forgotCodeExpireMinutes() + SEND_WINDOW.toMinutes()));

        if (failCount != null && failCount >= FAIL_MAX) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(KEY_TARGET + suffix);
            redisTemplate.delete(failKey);
            log.warn("员工 id={} 绑定验证码连续输错 {} 次，本次验证码已作废", userId, failCount);
            throw new BusinessException(ErrorCode.VERIFY_CODE_LOCKED);
        }
        throw new BusinessException(ErrorCode.VERIFY_CODE_INVALID);
    }

    /**
     * 生成指定位数的纯数字验证码（逐位生成，保证「首位是 0」时位数仍然正确）。
     */
    private String generateCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return builder.toString();
    }

    /** 组装发送结果；验证码只在开发环境回传（与找回密码同一取舍，见该 VO 的说明） */
    private BindContactSendCodeVO buildResult(String channel, String target,
                                              int expireMinutes, String code) {
        BindContactSendCodeVO vo = new BindContactSendCodeVO();
        vo.setContactType(channel);
        vo.setMaskedTarget(AccountFormats.maskContact(target));
        vo.setExpireMinutes(expireMinutes);
        if (devMode()) {
            vo.setDevCode(code);
            log.warn("[DEV] 绑定验证码已随响应返回（生产环境不会出现这一行）：渠道={}",
                    ContactRecovery.label(channel));
        }
        return vo;
    }

    /**
     * 是否开发环境（与找回密码同一判据）。
     *
     * <p>取 profile 而不是可热改的系统参数：这条开关决定「验证码是否随响应回传」，
     * 等价于「是否跳过验证」。用参数页能改的开关承载它，意味着任何拿到
     * {@code config:manage} 的人都能把全站变成「无需验证码即可改绑联系方式」。
     */
    private boolean devMode() {
        return environment.acceptsProfiles(Profiles.of("dev"));
    }
}
