package com.enterprise.ticket.module.system.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.module.auth.service.VerificationCodeSender;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.system.support.SmsSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * 短信通道配置测试（ · 的「发送测试短信」）。
 *
 * <h2>这个接口能回答什么、不能回答什么（必须诚实）</h2>
 * <p>短信网关在本期是<b>预留</b>（），{@code RoutingVerificationCodeSender}
 * 对 SMS 渠道必然落到日志实现。因此「测试短信」<b>不可能</b>证明
 * 「密钥对、签名已备案、模板存在」——那些只有服务商能答。
 *
 * <p>它真正提供的是三件事，缺一不可：
 * <ol>
 *   <li><b>把缺项一次说完</b>：手机号格式、服务商是否选了、五项参数缺哪几项
 *       （{@link SmsSettings.Settings#problems()}）。这是管理员点这个按钮时
 *       唯一能立刻拿到手的信息。</li>
 *   <li><b>校验通道开关</b>：通道没启用时直接拒绝，而不是假装发了一条 ——
 *       否则「测试通过」与「用户收不到码」会同时成立，那是这个功能最坏的结局。</li>
 *   <li><b>走一遍真实链路</b>：经 {@link VerificationCodeSender} 路由投递，
 *       接入网关后这条路径<b>一行不用改</b>就会真的发短信。</li>
 * </ol>
 *
 * <h2>返回结果为什么不报「成功」</h2>
 * <p>返回体里的 {@code delivered} 严格表示「是否真的送达用户手机」。
 * 日志通道下它是 {@code false}，前端据此展示「参数已通过校验，但通道未接入、
 * 未真实发送」的提示，而不是一个绿色的「发送成功」——
 * 后者会让管理员以为配置已可用，直到有用户反馈收不到验证码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigSmsService {

    /** 测试验证码的位数：与「找回密码」同源会引入参数依赖，这里固定 6 位即可 —— 它的用途只是产生一条可 grep 的日志 */
    private static final int TEST_CODE_LENGTH = 6;

    private final SystemConfigService systemConfigService;

    /**
     * 验证码投递路由（{@code RoutingVerificationCodeSender} 标了 {@code @Primary}）。
     *
     * <p>「system 模块依赖 auth 模块的接口」是有意的：投递通道的选路逻辑
     * （邮箱走 SMTP、其余走日志）已经存在于那一侧，这里<b>再实现一遍</b>
     * 就会出现第二个「短信怎么发」的判定点，接入网关时必然只改一处。
     */
    private final VerificationCodeSender verificationCodeSender;

    private final SecureRandom random = new SecureRandom();

    /** 当前生效的短信设置（AccessKey Secret 已解密） */
    public SmsSettings.Settings effectiveSettings() {
        return systemConfigService.smsSettings();
    }

    /**
     * 发送测试短信。
     *
     * @param to       目标手机号（原样传入，内部归一化与校验）
     * @param settings 本次要试的设置（允许来自<b>未保存</b>的表单值）
     * @return 测试结果（含「是否真实送达」与说明文案）
     * @throws BusinessException 手机号非法 / 通道未启用 / 参数缺项
     */
    public SmsTestResult sendTestSms(String to, SmsSettings.Settings settings) {
        String target = to == null ? "" : to.trim();
        // 手机号格式的唯一事实源：AccountFormats.isPhone（与找回密码、绑定联系方式同一份判定）
        if (!AccountFormats.isPhone(target)) {
            throw new BusinessException(ErrorCode.CONTACT_PHONE_INVALID);
        }
        // 通道关闭时不允许测：见类注释第二点
        if (!systemConfigService.smsVerifyEnabled()) {
            throw new BusinessException(ErrorCode.CONFIG_CHANNEL_DISABLED,
                    "短信通道当前未启用，无法发送测试短信。请先打开「启用短信通知」并保存。");
        }
        // 参数缺项：一次性把全部问题说出来，而不是修一项报一项
        var problems = settings.problems();
        if (!problems.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < problems.size(); i++) {
                detail.append(i + 1).append(". ").append(problems.get(i)).append("；");
            }
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED,
                    "短信通道配置不完整（" + problems.size() + " 项）：" + detail);
        }

        String code = randomCode();
        // 经统一路由投递：短信未接入网关时必然落到日志实现（grep FORGOT-CODE 可取）
        verificationCodeSender.send(ContactRecovery.CONTACT_SMS, target, code,
                systemConfigService.forgotCodeExpireMinutes());

        String providerLabel = settings.providerLabel();
        log.info("短信通道测试：目标={}，服务商={}（短信网关未接入，本次仅写日志）",
                AccountFormats.maskContact(target), providerLabel);

        return new SmsTestResult(
                settings.provider(),
                providerLabel,
                false,
                "LOG",
                "参数校验已通过（服务商 / 密钥 / 签名 / 模板均已填写），测试验证码已写入应用日志。"
                        + "短信网关尚未接入（为预留），因此本次没有真实发出短信 —— "
                        + "接入网关后本按钮即可真实发送；验证码条数不代表手机能收到。");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(TEST_CODE_LENGTH);
        for (int i = 0; i < TEST_CODE_LENGTH; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    /**
     * 测试结果。
     *
     * @param provider    本次使用的服务商编码
     * @param providerLabel 服务商显示名
     * @param delivered   是否<b>真的送达了用户手机</b>；日志通道下恒为 {@code false}
     * @param channel     实际投递通道：{@code LOG}（未接入） / {@code GATEWAY}（已接入）
     * @param message     给管理员看的说明（前端直接展示，不要用别的文案覆盖）
     */
    public record SmsTestResult(String provider, String providerLabel,
                                boolean delivered, String channel, String message) {
    }
}
