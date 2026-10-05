package com.enterprise.ticket.module.ad.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AD 配置校验（Phase 13；需求一.1）—— 纯函数单测
 *
 * <p>本类只做一件事：把「填错了不会报错、只会表现为登录一直失败」的配置组合挡在保存入口。
 * 因此测试聚焦三条：
 * <ul>
 *   <li><b>格式校验</b>（端口 / 超时 / 过滤器括号与占位符 / DN 形状 / 属性名）无论是否启用都执行；</li>
 *   <li><b>完整性校验</b>只在启用 / 测试连接时执行 —— 未启用必须允许「存草稿」；</li>
 *   <li><b>密码留空</b>的语义是「保持原值」，不能因为「本次没填」就判成缺失。</li>
 * </ul>
 */
@DisplayName("AD 配置校验（纯函数）")
class AdConfigValidatorTest {

    /** 一份完整且合法的配置，便于各用例按需破坏单个字段 */
    private AdConfigRequest complete() {
        AdConfigRequest request = new AdConfigRequest();
        request.setEnabled(true);
        request.setServerUrls("dc1.company.com, dc2.company.com");
        request.setServerPort(636);
        request.setUseSsl(true);
        request.setStrictCert(true);
        request.setBaseDn("DC=company,DC=com");
        request.setBindDn("CN=ldapquery,CN=Users,DC=company,DC=com");
        request.setBindPassword("s3cret");
        request.setUserFilter(AdConnection.DEFAULT_FILTER);
        request.setAttrLogin("sAMAccountName");
        request.setAttrName("displayName");
        request.setAttrEmail("mail");
        request.setAttrPhone("telephoneNumber");
        request.setAttrDept("department");
        request.setAttrStatus("userAccountControl");
        request.setDefaultRole("user");
        request.setConnectTimeoutSeconds(5);
        request.setSyncEnabled(true);
        request.setSyncHour(2);
        return request;
    }

    private ErrorCode codeOf(AdConfigRequest request) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> AdConfigValidator.validate(request, true, false, false));
        return ex.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 格式校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整合法配置（启用态）校验通过")
    void completeConfigPasses() {
        assertDoesNotThrow(() -> AdConfigValidator.validate(complete(), true, false, false));
    }

    @Test
    @DisplayName("端口越界 → PARAM_INVALID")
    void portOutOfRange() {
        AdConfigRequest request = complete();
        request.setServerPort(70000);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("连接超时越界 → PARAM_INVALID")
    void timeoutOutOfRange() {
        AdConfigRequest request = complete();
        request.setConnectTimeoutSeconds(0);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));

        request.setConnectTimeoutSeconds(999);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("过滤器括号不配平 → PARAM_INVALID")
    void unbalancedFilter() {
        AdConfigRequest request = complete();
        // 少一个右括号
        request.setUserFilter("(&(objectClass=user)(sAMAccountName={0})");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("过滤器缺少账号占位符 {0} → PARAM_INVALID")
    void filterWithoutPlaceholder() {
        AdConfigRequest request = complete();
        request.setUserFilter("(objectClass=user)");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("基础 DN 形状不对 → PARAM_INVALID")
    void badBaseDn() {
        AdConfigRequest request = complete();
        request.setBaseDn("company.com");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("登录名属性名含非法字符 → PARAM_INVALID")
    void badAttributeName() {
        AdConfigRequest request = complete();
        request.setAttrLogin("sAM AccountName");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("手机号属性名含非法字符 → PARAM_INVALID（Phase 19 批次 E 新增字段同样受校验）")
    void badPhoneAttributeName() {
        AdConfigRequest request = complete();
        request.setAttrPhone("telephone Number");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("同步时刻越界 → PARAM_INVALID（写成 25 会让定时同步永远不触发）")
    void syncHourOutOfRange() {
        AdConfigRequest request = complete();
        request.setSyncHour(25);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));

        request.setSyncHour(-1);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request));
    }

    @Test
    @DisplayName("同步时刻为 null → 放行（由 normalizeSyncHour 兜默认值 2）")
    void syncHourNullable() {
        AdConfigRequest request = complete();
        request.setSyncHour(null);
        assertDoesNotThrow(() -> AdConfigValidator.validate(request, true, false, false));
    }

    // ------------------------------------------------------------------
    // 完整性校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("启用但缺必填项 → AD_CONFIG_INCOMPLETE，且消息点出缺失项")
    void incompleteWhenEnabled() {
        AdConfigRequest request = complete();
        request.setServerUrls(null);
        request.setBaseDn(null);
        request.setBindDn(null);
        request.setBindPassword(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> AdConfigValidator.validate(request, true, false, false));
        assertEquals(ErrorCode.AD_CONFIG_INCOMPLETE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("服务器地址"), "消息应指出缺失项：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("绑定密码"));
    }

    @Test
    @DisplayName("未启用（requireComplete=false）允许不完整地暂存草稿")
    void draftAllowedWhenDisabled() {
        AdConfigRequest request = new AdConfigRequest();
        request.setEnabled(false);
        // 只填了一半，甚至完全为空
        request.setServerUrls("dc1.company.com");
        assertDoesNotThrow(() -> AdConfigValidator.validate(request, false, false, false));
    }

    @Test
    @DisplayName("密码本次留空但库中已有密文 → 视为已配置，校验通过")
    void blankPasswordWithStoredPassword() {
        AdConfigRequest request = complete();
        request.setBindPassword(null);
        assertDoesNotThrow(() -> AdConfigValidator.validate(request, true, true, false));
    }

    @Test
    @DisplayName("密码本次留空且库中也没有 → AD_CONFIG_INCOMPLETE")
    void blankPasswordWithoutStored() {
        AdConfigRequest request = complete();
        request.setBindPassword("");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> AdConfigValidator.validate(request, true, false, false));
        assertEquals(ErrorCode.AD_CONFIG_INCOMPLETE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("绑定密码"));
    }

    @Test
    @DisplayName("要求清空密码但本来就没有可清 → 放行（不算错误）")
    void clearingNonExistentPassword() {
        AdConfigRequest request = complete();
        request.setBindPassword(null);
        assertDoesNotThrow(() -> AdConfigValidator.validate(request, true, false, true));
    }

    @Test
    @DisplayName("request 为 null → PARAM_INVALID")
    void nullRequest() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(null));
    }

    // ------------------------------------------------------------------
    // 静态工具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hasBalancedParens：配平 / 先闭后开 / 空串")
    void balancedParens() {
        assertTrue(AdConfigValidator.hasBalancedParens("(a(b))"));
        assertTrue(AdConfigValidator.hasBalancedParens("()"));
        assertFalse(AdConfigValidator.hasBalancedParens(")("));
        assertFalse(AdConfigValidator.hasBalancedParens("((a)"));
        assertFalse(AdConfigValidator.hasBalancedParens(""));
        assertFalse(AdConfigValidator.hasBalancedParens(null));
    }

    @Test
    @DisplayName("looksLikeDn：接受 DC/CN/OU 起头，拒绝无等号")
    void looksLikeDn() {
        assertTrue(AdConfigValidator.looksLikeDn("DC=company,DC=com"));
        assertTrue(AdConfigValidator.looksLikeDn("CN=ldapquery,CN=Users,DC=company,DC=com"));
        assertFalse(AdConfigValidator.looksLikeDn("company.com"));
        assertFalse(AdConfigValidator.looksLikeDn("=oops"));
        assertFalse(AdConfigValidator.looksLikeDn(""));
    }

    @Test
    @DisplayName("isValidAttributeName：覆盖 AD 常见属性名（含连字符 / 数字）")
    void validAttributeName() {
        assertTrue(AdConfigValidator.isValidAttributeName("sAMAccountName"));
        assertTrue(AdConfigValidator.isValidAttributeName("userAccountControl"));
        assertTrue(AdConfigValidator.isValidAttributeName("msDS-UserAccountDisabled"));
        assertFalse(AdConfigValidator.isValidAttributeName("1abc"));
        assertFalse(AdConfigValidator.isValidAttributeName("a b"));
        assertFalse(AdConfigValidator.isValidAttributeName(""));
    }

    @Test
    @DisplayName("normalizePort：未填时按 SSL 取默认端口，填了则原样返回")
    void normalizePort() {
        assertEquals(AdConfigValidator.DEFAULT_PORT, AdConfigValidator.normalizePort(null, false));
        assertEquals(AdConfigValidator.DEFAULT_LDAPS_PORT, AdConfigValidator.normalizePort(null, true));
        assertEquals(10389, AdConfigValidator.normalizePort(10389, true));
    }

    @Test
    @DisplayName("normalizeTimeout：null 取默认值，越界则钳制到合法区间")
    void normalizeTimeout() {
        assertEquals(AdConfigValidator.DEFAULT_TIMEOUT_SECONDS, AdConfigValidator.normalizeTimeout(null));
        assertEquals(AdConfigValidator.MIN_TIMEOUT_SECONDS, AdConfigValidator.normalizeTimeout(0));
        assertEquals(AdConfigValidator.MAX_TIMEOUT_SECONDS, AdConfigValidator.normalizeTimeout(600));
        assertEquals(8, AdConfigValidator.normalizeTimeout(8));
    }

    @Test
    @DisplayName("looksLikeBindIdentity：DN / 下行式 / UPN 三种写法都算合法（AD 原生支持）")
    void looksLikeBindIdentity() {
        // 完整 DN
        assertTrue(AdConfigValidator.looksLikeBindIdentity("CN=ldapquery,CN=Users,DC=company,DC=com"));
        // 下行式（Windows 登录名）
        assertTrue(AdConfigValidator.looksLikeBindIdentity("company\\query"));
        // UPN
        assertTrue(AdConfigValidator.looksLikeBindIdentity("query@company.com"));
        // 两侧必须有内容，不能只给一个分隔符
        assertFalse(AdConfigValidator.looksLikeBindIdentity("company\\"));
        assertFalse(AdConfigValidator.looksLikeBindIdentity("\\query"));
        assertFalse(AdConfigValidator.looksLikeBindIdentity("@company.com"));
        assertFalse(AdConfigValidator.looksLikeBindIdentity("query@"));
        // 纯主机名既不是 DN 也不是绑定身份 —— 基础 DN 与绑定 DN 的严格度差异就在这里
        assertFalse(AdConfigValidator.looksLikeBindIdentity("company.com"));
        assertFalse(AdConfigValidator.looksLikeBindIdentity(""));
        assertFalse(AdConfigValidator.looksLikeBindIdentity(null));
    }

    @Test
    @DisplayName("绑定身份判据比 DN 判据宽松：company\\query 过绑定校验，但过不了基础 DN 校验")
    void bindIdentityIsLooserThanDn() {
        assertTrue(AdConfigValidator.looksLikeBindIdentity("company\\query"));
        assertFalse(AdConfigValidator.looksLikeDn("company\\query"));
    }

    @Test
    @DisplayName("用下行式账号填写绑定 DN 也能通过完整校验（Phase 19 批次 E 的关键放宽）")
    void downLevelBindDnPassesValidation() {
        AdConfigRequest request = complete();
        request.setBindDn("company\\query");
        assertDoesNotThrow(() -> AdConfigValidator.validate(request, true, true, false));
    }

    @Test
    @DisplayName("normalizeSyncHour：null 取默认 2，越界钳制到 0-23")
    void normalizeSyncHour() {
        assertEquals(AdConfigValidator.DEFAULT_SYNC_HOUR, AdConfigValidator.normalizeSyncHour(null));
        assertEquals(AdConfigValidator.MIN_SYNC_HOUR, AdConfigValidator.normalizeSyncHour(-5));
        assertEquals(AdConfigValidator.MAX_SYNC_HOUR, AdConfigValidator.normalizeSyncHour(99));
        assertEquals(6, AdConfigValidator.normalizeSyncHour(6));
    }
}
