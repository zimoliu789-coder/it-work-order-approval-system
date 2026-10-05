package com.enterprise.ticket.module.ops.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部通道共享密钥校验（ 抽取）
 *
 * <h2>为什么把它抽出来</h2>
 * <p>「脚本 → 应用」的内部通道原本只有备份上报一个端点（{@code InternalAlertController}），
 * 校验逻辑内联在那个类里。 新增了主备心跳上报，如果照抄一份，
 * 系统里就出现了<b>两份安全判定</b> —— 而安全校验最怕的就是两份实现：
 * 将来某次加固（例如加上时间戳防重放、加上调用方白名单）只改了一处，
 * 另一条通道就成了没被加固的那个缺口，而且从代码上看不出任何异常。
 *
 * <p>抽取之后的规则很明确：<b>任何免登录的内部端点，都必须先过这一个校验器。</b>
 *
 * <h2>安全模型（沿用备份上报已确立的三条口径）</h2>
 * <ol>
 *   <li><b>共享密钥</b>：请求头 {@code X-Internal-Token} 必须等于 {@code INTERNAL_ALERT_TOKEN}。
 *       比对使用 {@link MessageDigest#isEqual} 做<b>常量时间</b>比较 ——
 *       普通的 {@code String.equals} 会在第一个不同字符处返回，
 *       攻击者能通过测量响应时间逐字节猜出密钥；</li>
 *   <li><b>未配置即拒绝（fail-closed）</b>：密钥为空时接口一律不可用。
 *       宁可「主备断连时告警发不出来」，也不能留下一个匿名可调、
 *       能向全部超管灌消息的接口 —— 后者是一个现成的消息轰炸通道；</li>
 *   <li><b>不发布到宿主机</b>：这些端点只在内网网络暴露，外部流量必须经 Nginx 进入。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTokenVerifier {

    /** 内部通道共享密钥的请求头名（脚本与服务端约定的常量） */
    public static final String HEADER_TOKEN = "X-Internal-Token";

    private final AppProperties appProperties;

    /**
     * 校验内部令牌，不通过即抛异常。
     *
     * @param token 请求头 {@code X-Internal-Token} 的值（可为空）
     * @throws BusinessException 未配置密钥（500）或密钥不匹配（403）
     */
    public void verify(String token) {
        String expected = appProperties.getOps().getInternalAlertToken();
        if (!StringUtils.hasText(expected)) {
            log.warn("收到内部通道请求，但 INTERNAL_ALERT_TOKEN 未配置，已拒绝");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "内部通道未启用（服务端未配置 INTERNAL_ALERT_TOKEN）");
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            log.warn("内部通道令牌校验失败");
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部通道令牌无效");
        }
    }
}
