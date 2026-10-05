package com.enterprise.ticket.module.system.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.entity.SystemConfig;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.system.support.MailSettings;
import com.enterprise.ticket.module.system.support.SiteBranding;
import com.enterprise.ticket.module.system.support.SiteLogoStorage;
import com.enterprise.ticket.module.system.support.SmsSettings;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.security.SecretCipher;
import com.enterprise.ticket.module.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统参数写入 + 通道联动校验（P19-F 需求五 / 需求六）。
 *
 * <h2>为什么必须用「真实现 + spy」而不是纯 Mock</h2>
 * <p>本次要验证的是{@code updateValues} 内部<b>多遍遍历之间的时序</b>——
 * 联动校验必须看到「本次提交里所有开关的最终值」。若把方法本身也 Mock 掉，
 * 测的就只是 Mock 的返回值，时序这条正是最容易写错的地方会完全测不到。
 * 因此只把 {@code list()} / {@code updateBatchById()} 这两个被 MyBatis 支撑的方法
 * 换成返回夹具，其余逻辑全走真实代码。
 *
 * <h2>本类要钉住的判据（「前后取并集」）</h2>
 * <ul>
 *   <li>提交前后<b>都是关</b> → 拒绝（这正是「灰掉不可编辑」对 curl 也要成立的那一条）；</li>
 *   <li>提交前关、提交后开（同一次提交里打开开关并填参数）→ <b>允许</b>；</li>
 *   <li>提交前开、提交后关（配好参数但暂时不启用）→ <b>允许</b>；</li>
 *   <li>开关自身不受自己控制 → 关掉之后仍然改得回来（否则参数页会把自己锁死）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("系统参数写入：通道联动校验")
class SystemConfigServiceImplTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private SiteLogoStorage logoStorage;

    @Mock
    private SecretCipher secretCipher;

    private SystemConfigServiceImpl service;

    /** 库内行（configKey → 行）；每个用例自行装配 */
    private final Map<String, SystemConfig> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = spy(new SystemConfigServiceImpl(appProperties, logoStorage, secretCipher));
        doReturn(new ArrayList<>(rows.values())).when(service).list();
        doReturn(true).when(service).updateBatchById(any());
        when(secretCipher.encrypt(anyString(), anyString())).thenReturn("ENC1:encrypted");
        loginAsBuiltinAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void loginAsBuiltinAdmin() {
        // 内置超管的登录名 = app.super-admin.username，Mock 返回 null 时回落默认值 administrator
        User user = new User();
        user.setId(1L);
        user.setUsername("administrator");
        user.setDisplayName("超级管理员");
        user.setRole(RoleCode.SUPER_ADMIN);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private void put(String key, String value) {
        SystemConfig row = new SystemConfig();
        row.setId((long) (rows.size() + 1));
        row.setConfigKey(key);
        row.setConfigValue(value);
        row.setConfigGroup("common");
        row.setEditable(true);
        rows.put(key, row);
    }

    private void installRows() {
        doReturn(new ArrayList<>(rows.values())).when(service).list();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("通道一直关着时改它的参数：拒绝（服务端是边界，不只是前端置灰）")
    void rejectsWhenChannelStaysOff() {
        put(ContactRecovery.KEY_SMS_ENABLED, "0");
        put(SmsSettings.KEY_SIGN_NAME, "旧签名");
        installRows();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateValues(Map.of(SmsSettings.KEY_SIGN_NAME, "新签名")));
        assertEquals(ErrorCode.CONFIG_CHANNEL_DISABLED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("短信通知"), "文案要点出是哪条通道：" + ex.getMessage());
        verify(service, never()).updateBatchById(any());
    }

    @Test
    @DisplayName("同一次提交里「打开开关 + 改参数」必须放行（否则会被自己拦死）")
    void allowsTurningOnAndWritingTogether() {
        put(ContactRecovery.KEY_SMS_ENABLED, "0");
        put(SmsSettings.KEY_SIGN_NAME, "旧签名");
        installRows();

        Map<String, String> values = new LinkedHashMap<>();
        values.put(ContactRecovery.KEY_SMS_ENABLED, "1");
        values.put(SmsSettings.KEY_SIGN_NAME, "新签名");

        assertEquals(2, service.updateValues(values), "开关与参数都应写入");
        assertEquals("1", rows.get(ContactRecovery.KEY_SMS_ENABLED).getConfigValue());
        assertEquals("新签名", rows.get(SmsSettings.KEY_SIGN_NAME).getConfigValue());
    }

    @Test
    @DisplayName("同一次提交里「配好参数 + 顺手关掉通道」也要放行（先配后启用是常见动作）")
    void allowsWritingAndTurningOffTogether() {
        put(ContactRecovery.KEY_SMS_ENABLED, "1");
        put(SmsSettings.KEY_SIGN_NAME, "旧签名");
        installRows();

        Map<String, String> values = new LinkedHashMap<>();
        values.put(ContactRecovery.KEY_SMS_ENABLED, "0");
        values.put(SmsSettings.KEY_SIGN_NAME, "新签名");

        assertEquals(2, service.updateValues(values));
        assertEquals("0", rows.get(ContactRecovery.KEY_SMS_ENABLED).getConfigValue());
        assertEquals("新签名", rows.get(SmsSettings.KEY_SIGN_NAME).getConfigValue());
    }

    @Test
    @DisplayName("通道开着时改参数：放行")
    void allowsWhenChannelOn() {
        put(ContactRecovery.KEY_SMS_ENABLED, "1");
        put(SmsSettings.KEY_SIGN_NAME, "旧签名");
        installRows();

        assertEquals(1, service.updateValues(Map.of(SmsSettings.KEY_SIGN_NAME, "新签名")));
    }

    @Test
    @DisplayName("开关自身不受自己控制：关掉之后仍改得回来（否则参数页会把自己锁死）")
    void switchItselfIsAlwaysWritable() {
        put(ContactRecovery.KEY_SMS_ENABLED, "0");
        installRows();

        assertEquals(1, service.updateValues(Map.of(ContactRecovery.KEY_SMS_ENABLED, "1")));
        assertEquals("1", rows.get(ContactRecovery.KEY_SMS_ENABLED).getConfigValue());
    }

    @Test
    @DisplayName("两条通道互不影响：短信关着也能改邮箱参数")
    void channelsAreIndependent() {
        put(ContactRecovery.KEY_SMS_ENABLED, "0");
        put(ContactRecovery.KEY_EMAIL_ENABLED, "1");
        put(MailSettings.KEY_HOST, "smtp.qq.com");
        installRows();

        assertEquals(1, service.updateValues(Map.of(MailSettings.KEY_HOST, "smtp.exmail.qq.com")));
    }

    @Test
    @DisplayName("不受开关控制的参数（如借用时长）不参与联动校验")
    void unrelatedKeysUnaffected() {
        put(ContactRecovery.KEY_SMS_ENABLED, "0");
        put("lock_timeout_minutes", "5");
        installRows();

        assertEquals(1, service.updateValues(Map.of("lock_timeout_minutes", "10")));
    }

    @Test
    @DisplayName("值未变的项不写库：affected 只统计真正变化的项")
    void unchangedValuesAreNotWritten() {
        put(ContactRecovery.KEY_SMS_ENABLED, "1");
        put("lock_timeout_minutes", "5");
        installRows();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("lock_timeout_minutes", "5");
        values.put("password_min_length", "8");
        put("password_min_length", "8");
        installRows();

        assertEquals(0, service.updateValues(values), "两项都没变，不应写入任何行");
        verify(service, never()).updateBatchById(any());
    }

    @Test
    @DisplayName("配置项不存在时拒绝（含通道参数与普通参数）")
    void unknownKeyRejected() {
        put("lock_timeout_minutes", "5");
        installRows();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateValues(Map.of("not_exists_key", "1")));
        assertEquals(ErrorCode.CONFIG_KEY_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("通道参数缺项时按「配置不完整」之外的正常路径保存（缺项由测试按钮负责报，不阻塞保存）")
    void incompleteChannelParamsCanStillBeSaved() {
        put(ContactRecovery.KEY_SMS_ENABLED, "1");
        put(SmsSettings.KEY_SIGN_NAME, "");
        installRows();

        // 保存路径不做「完整性」判定（见 SmsSettings 类注释）：允许先存一半，回头补。
        assertEquals(1, service.updateValues(Map.of(SmsSettings.KEY_SIGN_NAME, "设备借用")));
    }

    @Test
    @DisplayName("版权文字属于站点品牌：内置超管可写；其他超管被拒")
    void copyrightIsAdminOnly() {
        put(SiteBranding.KEY_COPYRIGHT, "");
        installRows();

        assertEquals(1, service.updateValues(Map.of(SiteBranding.KEY_COPYRIGHT, "© 2026 某某公司 版权所有")));
        assertEquals("© 2026 某某公司 版权所有", rows.get(SiteBranding.KEY_COPYRIGHT).getConfigValue());

        // 换成「其他超管」：角色仍是 super_admin，但登录名不是 administrator
        User other = new User();
        other.setId(9L);
        other.setUsername("10001");
        other.setDisplayName("张伟");
        other.setRole(RoleCode.SUPER_ADMIN);
        other.setEnabled(true);
        other.setDimission(false);
        LoginUser otherUser = new LoginUser(other);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(otherUser, null, otherUser.getAuthorities()));

        put(SiteBranding.KEY_COPYRIGHT, "");
        installRows();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateValues(Map.of(SiteBranding.KEY_COPYRIGHT, "改一下")));
        assertEquals(ErrorCode.CONFIG_NOT_EDITABLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("提交前后状态取自不同来源：开关未出现在本次提交里时，用库内现值判定")
    void switchValueFallsBackToStoredWhenAbsent() {
        put(ContactRecovery.KEY_EMAIL_ENABLED, "0");
        put(MailSettings.KEY_HOST, "smtp.qq.com");
        installRows();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateValues(Map.of(MailSettings.KEY_HOST, "smtp.163.com")));
        assertEquals(ErrorCode.CONFIG_CHANNEL_DISABLED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("邮件通知"), "文案要点出是哪条通道：" + ex.getMessage());
    }
}
