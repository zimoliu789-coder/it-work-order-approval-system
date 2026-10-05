package com.enterprise.ticket.module.ops.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 内部通道共享密钥校验（Phase 19 批次 G 抽取）—— 单元测试
 *
 * <h2>为什么这个抽取本身就要被测试钉住</h2>
 * <p>「脚本 → 应用」的内部通道原本只有备份上报一个端点，校验逻辑内联在那个类里。
 * 批次 G 新增主备心跳上报时，如果照抄一份，系统里就出现了<b>两份安全判定</b> ——
 * 而安全校验最怕的就是两份实现：将来某次加固只改了一处，
 * 另一条通道就成了没被加固的缺口，而且从代码上看不出任何异常。
 *
 * <p>因此本类不仅验证「对不对」，还钉住两条容易在重构中丢失的口径：
 * <ol>
 *   <li><b>未配置即拒绝（fail-closed）</b>：宁可「主备断连时告警发不出来」，
 *       也不能留下一个匿名可调、能向全部超管灌消息的接口；</li>
 *   <li><b>逐字节精确比对</b>：前缀相同、长度不同、大小写不同都必须被拒。</li>
 * </ol>
 */
@DisplayName("内部通道令牌校验（fail-closed / 常量时间比对）")
class InternalTokenVerifierTest {

    private static final String SECRET = "internal-token-9f3c1d7a";

    private InternalTokenVerifier verifier(String configuredToken) {
        AppProperties properties = new AppProperties();
        properties.getOps().setInternalAlertToken(configuredToken);
        return new InternalTokenVerifier(properties);
    }

    private ErrorCode codeOf(InternalTokenVerifier verifier, String token) {
        return assertThrows(BusinessException.class, () -> verifier.verify(token)).getErrorCode();
    }

    @Test
    @DisplayName("请求头常量与脚本约定一致（改这个名字会让所有上报静默 403）")
    void headerNameIsContract() {
        assertEquals("X-Internal-Token", InternalTokenVerifier.HEADER_TOKEN);
    }

    @Test
    @DisplayName("令牌一致 → 放行")
    void matchingTokenPasses() {
        assertDoesNotThrow(() -> verifier(SECRET).verify(SECRET));
    }

    @Test
    @DisplayName("★ 服务端未配置密钥 → 一律拒绝（fail-closed，绝不是「不校验就放行」）")
    void unconfiguredTokenFailsClosed() {
        assertEquals(ErrorCode.INTERNAL_ERROR, codeOf(verifier(null), SECRET));
        assertEquals(ErrorCode.INTERNAL_ERROR, codeOf(verifier(""), SECRET));
        assertEquals(ErrorCode.INTERNAL_ERROR, codeOf(verifier("   "), SECRET));
        // 即使调用方"恰好"也没带令牌，未配置仍然是拒绝 —— 不能因为两边都空就算匹配
        assertEquals(ErrorCode.INTERNAL_ERROR, codeOf(verifier(null), null));
    }

    @Test
    @DisplayName("令牌不匹配 → FORBIDDEN（前缀相同 / 大小写不同 / 多一个字符都被拒）")
    void mismatchedTokenForbidden() {
        InternalTokenVerifier verifier = verifier(SECRET);

        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, "internal-token-9f3c1d7b"));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, "internal-token-9f3c1d7"));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, "internal-token-9f3c1d7a_x"));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, SECRET.toUpperCase()));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, "  " + SECRET));
    }

    @Test
    @DisplayName("调用方未带令牌（null / 空串）→ FORBIDDEN，而不是 500")
    void missingTokenForbidden() {
        InternalTokenVerifier verifier = verifier(SECRET);

        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, null));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier, ""));
    }

    @Test
    @DisplayName("非 ASCII 密钥按 UTF-8 字节比对（不会因为编码不一致而放过）")
    void nonAsciiTokenComparedAsUtf8() {
        String token = "内部令牌-密钥-2026";
        assertDoesNotThrow(() -> verifier(token).verify(token));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(verifier(token), "内部令牌-密钥-2025"));
    }
}
