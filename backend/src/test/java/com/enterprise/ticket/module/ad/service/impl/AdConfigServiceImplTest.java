package com.enterprise.ticket.module.ad.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewRequest;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewVO;
import com.enterprise.ticket.module.ad.entity.AdConfig;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.mapper.AdConfigMapper;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.security.SecretCipher;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AD 配置服务（Phase 19 批次 E）—— 单元测试
 *
 * <h2>为什么断言的是「SET 子句」而不是「实体字段」</h2>
 * <p>本类的保存路径在真机上跑冒烟时暴露过一个静默 bug：全局
 * {@code update-strategy: not_null} 会让实体里的 null 字段**不进 SET 子句**，
 * 于是「留空 = 清空」在服务端完全失效 ——
 * 而当时的单测断言的是「实体上被设置了 null」，**恰好把这个 bug 判成绿的**。
 *
 * <p>因此本类改为捕获 {@code LambdaUpdateWrapper} 并展开成「列名 → 落库值」，
 * 直接断言**真正会发出去的 SQL**。判据也随之变得准确：
 * <ul>
 *   <li>{@code sets.containsKey(列)} —— 该列是否真的进了 SET（清空的前提）；</li>
 *   <li>{@code sets.get(列)} 是否为「空」—— 文本列的空是<b>空串</b>
 *       （这些列在 DDL 里是 {@code NOT NULL DEFAULT ''}），
 *       只有 {@code bind_password_cipher} 的空才是 {@code NULL}。</li>
 * </ul>
 * 两条同时成立，才等于「这条记录被清空了」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AD 配置服务（DN 推导接线 / 同步字段 / 清空语义）")
class AdConfigServiceImplTest {

    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(AdConfig.class);
    }

    @Mock
    private AdConfigMapper adConfigMapper;
    @Mock
    private AdDirectoryClient directoryClient;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private AdConfigServiceImpl service;

    /** 库里已有的那唯一一行配置（含已加密的绑定密码） */
    private AdConfig storedConfig() {
        AdConfig config = new AdConfig();
        config.setId(1L);
        config.setSingletonKey(1);
        config.setEnabled(false);
        config.setServerPort(389);
        config.setUseSsl(false);
        config.setStrictCert(true);
        config.setUserFilter(AdConnection.DEFAULT_FILTER);
        config.setAttrLogin("sAMAccountName");
        config.setAttrName("displayName");
        config.setAttrEmail("mail");
        config.setAttrPhone("telephoneNumber");
        config.setAttrDept("department");
        config.setAttrStatus("userAccountControl");
        config.setDefaultRole("user");
        config.setConnectTimeoutSeconds(5);
        config.setSyncEnabled(false);
        config.setSyncHour(2);
        config.setBindPasswordCipher("ENC1:stored-cipher");
        return config;
    }

    private AdConfigRequest requestOf(String serverUrls, String baseDn, String bindDn) {
        AdConfigRequest request = new AdConfigRequest();
        request.setEnabled(true);
        request.setServerUrls(serverUrls);
        request.setBaseDn(baseDn);
        request.setBindDn(bindDn);
        request.setBindPassword("s3cret");
        request.setDefaultRole("user");
        return request;
    }

    /** 跑一次 save，并把将要执行的 SET 子句展开成「列名 → 落库值」 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> saveAndCaptureSets(AdConfigRequest request) {
        service.save(request);
        ArgumentCaptor<LambdaUpdateWrapper<AdConfig>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(adConfigMapper).update(nullable(AdConfig.class), captor.capture());
        return setsOf(captor.getValue());
    }

    /**
     * 展开 {@code sqlSet}。MyBatis-Plus 的 {@code set()} 按调用顺序给每个值分配
     * {@code MPGENVALn} 占位符，因此「sqlSet 里列的顺序」与「占位符编号」一一对应，
     * 可以直接把列名与值配对起来。
     */
    private static Map<String, Object> setsOf(LambdaUpdateWrapper<AdConfig> wrapper) {
        Map<String, Object> sets = new LinkedHashMap<>();
        Matcher m = Pattern
                .compile("([A-Za-z_]+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
                .matcher(wrapper.getSqlSet());
        while (m.find()) {
            sets.put(m.group(1), wrapper.getParamNameValuePairs().get(m.group(2)));
        }
        return sets;
    }

    // ------------------------------------------------------------------
    // 保存：DN 归一化必须真的进 SET 子句
    // ------------------------------------------------------------------

    @Test
    @DisplayName("保存时把 dc01.company.com + company\\query 归一成完整 DN 再落库")
    void saveNormalizesDns() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        Map<String, Object> sets = saveAndCaptureSets(requestOf("dc01.company.com", null, "company\\query"));

        assertEquals("DC=company,DC=com", sets.get("base_dn"),
                "基础 DN 应由域地址自动推导后落库，而不是留空");
        assertEquals("CN=query,CN=Users,DC=company,DC=com", sets.get("bind_dn"),
                "绑定账号应转成完整 DN 后再落库 —— 只在前端预览不算数");
    }

    @Test
    @DisplayName("高级选项里填过的 DN 优先，不被推导覆盖（自研目录 / 多域森林依赖这条）")
    void saveKeepsExplicitDns() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        Map<String, Object> sets =
                saveAndCaptureSets(requestOf("dc01.company.com", "DC=custom,DC=com", "ldapquery"));

        assertEquals("DC=custom,DC=com", sets.get("base_dn"));
        assertEquals("CN=ldapquery,CN=Users,DC=custom,DC=com", sets.get("bind_dn"),
                "裸账号应挂到**自定义**基础 DN 下，而不是域名推导出来的那个");
    }

    @Test
    @DisplayName("域地址是 IP 且未填基础 DN → **拒绝启用**（推不出域名就不该硬启用）")
    void enablingWithIpServerRequiresCustomBaseDn() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());

        // 完整性校验在「默认角色是否可分配」之前就失败了，因此不 stub roleService
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save(requestOf("192.168.1.10", null, "company\\query")));

        assertEquals(ErrorCode.AD_CONFIG_INCOMPLETE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("基础 DN"),
                "错误信息应明确指出缺基础 DN，引导维护人员去高级选项填：" + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 保存：清空语义（本轮真机冒烟抓到的回归点）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 清空基础 DN 时该列必须仍进 SET 并以空串落库（否则「改回自动推导」永远不生效）")
    void clearingBaseDnWritesEmpty() {
        AdConfig stored = storedConfig();
        // 现场曾手工填过自定义 OU；现在管理员把它清空、改回「交给系统推导」
        stored.setBaseDn("OU=Staff,DC=old,DC=local");
        when(adConfigMapper.selectOne(any())).thenReturn(stored);

        AdConfigRequest request = requestOf("192.168.1.10", null, "company\\query");
        request.setEnabled(false);   // 存草稿：不触发完整性校验，重点看「清空是否落库」
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertTrue(sets.containsKey("base_dn"),
                "清空基础 DN 时该列**必须**出现在 SET 子句里；缺席意味着旧的自定义值被留下来了"
                        + "（update-strategy=not_null 的静默跳过）");
        // 落库值是空串而不是 NULL：base_dn 在 DDL 里是 NOT NULL DEFAULT ''，
        // 「没有值/请系统推导」的既有表示就是空串
        assertEquals("", sets.get("base_dn"), "落库值应为空串（该列 NOT NULL，写 NULL 会被库拒绝）");
    }

    @Test
    @DisplayName("★ 清空手机号属性时该列必须仍进 SET 并以空串落库（否则「不同步手机号」做不到）")
    void clearingPhoneAttributeWritesEmpty() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setAttrPhone("");   // 前端「留空 = 不同步手机号」
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertTrue(sets.containsKey("attr_phone"), "清空手机号属性时该列必须出现在 SET 子句里");
        assertEquals("", sets.get("attr_phone"),
                "落库值应为空串 —— 否则域手机号会继续覆盖本地值，而那是找回密码的唯一渠道");
    }

    @Test
    @DisplayName("域地址是 IP 且只存草稿（未启用）→ 下行式绑定账号原样保留，不拼半截 DN")
    void draftWithIpServerKeepsRawBindIdentity() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        AdConfigRequest request = requestOf("192.168.1.10", null, "company\\query");
        request.setEnabled(false);   // 存草稿：不触发完整性校验
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertEquals("", sets.get("base_dn"));
        // 下行式写法在推不出 baseDn 时原样保留 —— AD/JNDI 原生接受，比拼半截 DN 安全
        assertEquals("company\\query", sets.get("bind_dn"));
    }

    @Test
    @DisplayName("绑定密码留空 → 该列**完全不进 SET**（只改端口不能把密码抹掉）")
    void blankPasswordLeavesCipherUntouched() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setBindPassword(null);   // 编辑时没重填密码
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertFalse(sets.containsKey("bind_password_cipher"),
                "留空必须等同于「保持原密文」，而不是写入 NULL（那会让 AD 认证直接坏掉）");
    }

    @Test
    @DisplayName("提交脱敏占位符 **** → 同样不动密码（防「密码变成四个星号」）")
    void maskedPlaceholderLeavesCipherUntouched() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setBindPassword(SecretCipher.MASK);
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertFalse(sets.containsKey("bind_password_cipher"),
                "把界面回显的掩码原样提交回来时，不能真的把它当成新密码");
    }

    @Test
    @DisplayName("显式清空密码 → 该列进 SET 且为 NULL")
    void clearingPasswordWritesNull() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setBindPassword(null);
        request.setClearBindPassword(true);
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertTrue(sets.containsKey("bind_password_cipher"));
        assertNull(sets.get("bind_password_cipher"));
    }

    // ------------------------------------------------------------------
    // 保存：同步字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同步开关 / 时刻随配置一起落库（原 system_config 的两个键）")
    void savePersistsSyncSettings() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setSyncEnabled(true);
        request.setSyncHour(6);
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertEquals(true, sets.get("sync_enabled"));
        assertEquals(6, sets.get("sync_hour"));
    }

    @Test
    @DisplayName("同步时刻缺失 → 落库为默认 2（不因缺省而无法保存）")
    void saveDefaultsMissingSyncHour() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setSyncHour(null);
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertEquals(2, sets.get("sync_hour"));
    }

    @Test
    @DisplayName("同步时刻越界 → 在校验处被拒（与端口 / 超时同一口径，不静默修正提交值）")
    void saveRejectsOutOfRangeSyncHour() {
        when(adConfigMapper.selectOne(any())).thenReturn(storedConfig());

        // 越界时刻在格式校验处即被拒，同样走不到角色检查
        AdConfigRequest request = requestOf("dc01.company.com", null, "company\\query");
        request.setSyncHour(99);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(request));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("未勾选自动同步 → syncEnabled 落库为 false（不保留上一次的 true）")
    void saveClearsSyncEnabledWhenUnchecked() {
        AdConfig stored = storedConfig();
        stored.setSyncEnabled(true);
        when(adConfigMapper.selectOne(any())).thenReturn(stored);
        when(roleService.isAssignable("user")).thenReturn(true);
        when(secretCipher.encrypt("s3cret")).thenReturn("ENC1:new-cipher");

        // syncEnabled 不设（null）→ 关闭
        Map<String, Object> sets = saveAndCaptureSets(requestOf("dc01.company.com", null, "company\\query"));

        assertEquals(false, sets.get("sync_enabled"),
                "null 必须被当作「关闭」，否则管理员无法关掉自动同步");
    }

    // ------------------------------------------------------------------
    // 读取：同步开关 / 时刻
    // ------------------------------------------------------------------

    @Test
    @DisplayName("syncEnabled / syncHour 从 ad_config 读取，并按区间钳制")
    void syncAccessors() {
        AdConfig stored = storedConfig();
        stored.setSyncEnabled(true);
        stored.setSyncHour(9);
        when(adConfigMapper.selectOne(any())).thenReturn(stored);

        assertTrue(service.syncEnabled());
        assertEquals(9, service.syncHour());

        // 读库路径必须钳制：库里若残留 99（人工 SQL / 旧版本写入），
        // 定时任务的小时判定将永远不成立，表现为「开了自动同步却从没同步过」
        stored.setSyncHour(99);
        assertEquals(23, service.syncHour());
    }

    @Test
    @DisplayName("字段为 null 时 syncEnabled=false、syncHour=默认 2（不回 500）")
    void syncAccessorsHandleNulls() {
        AdConfig stored = storedConfig();
        stored.setSyncEnabled(null);
        stored.setSyncHour(null);
        when(adConfigMapper.selectOne(any())).thenReturn(stored);

        assertFalse(service.syncEnabled());
        assertEquals(2, service.syncHour());
    }

    // ------------------------------------------------------------------
    // 预览：与保存同源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预览：自动推导时给出 baseDn 与改写后的 bindDn，并标记来源")
    void previewDerivesBoth() {
        AdDnPreviewRequest request = new AdDnPreviewRequest();
        request.setServerUrls("dc01.company.com");
        request.setBindDn("company\\query");

        AdDnPreviewVO vo = service.previewDns(request);

        assertEquals("DC=company,DC=com", vo.getBaseDn());
        assertEquals("CN=query,CN=Users,DC=company,DC=com", vo.getBindDn());
        assertTrue(vo.isBaseDnAuto(), "维护人员没填 → 应标记为自动推导");
        assertTrue(vo.isBindDnAuto(), "原始写法被改写 → 应标记为自动转换");
        assertTrue(vo.getHint().contains("自动推导"), "应给出一句人话说明：" + vo.getHint());
    }

    @Test
    @DisplayName("预览：自定义基础 DN 时不再声称「自动推导」")
    void previewWithExplicitBaseDn() {
        AdDnPreviewRequest request = new AdDnPreviewRequest();
        request.setServerUrls("dc01.company.com");
        request.setBaseDn("DC=custom,DC=com");
        request.setBindDn("company\\query");

        AdDnPreviewVO vo = service.previewDns(request);

        assertEquals("DC=custom,DC=com", vo.getBaseDn());
        assertFalse(vo.isBaseDnAuto());
        assertTrue(vo.getHint().contains("自定义"));
        // 两件事必须同时成立（本用例的 DisplayName 已经承诺了第一件，此前却没有断言）：
        //   ① 文案不得出现「自动推导」—— 否则维护人员会以为改域地址时这个值会跟着变；
        //   ② 必须指明在哪里改回去 —— 批次 E 之前基础 DN 是必填项，现场填过的值会被锁定，
        //      这行提示是唯一能发现「它不跟着域地址变」的地方。
        assertFalse(vo.getHint().contains("自动推导"), "自定义分支不得声称自动推导：" + vo.getHint());
        assertTrue(vo.getHint().contains("高级选项"), "应指明可去「高级选项」清空改回：" + vo.getHint());
    }

    @Test
    @DisplayName("预览：IP 域地址推不出基础 DN → 提示去高级选项手工填写")
    void previewHintsWhenIpAddress() {
        AdDnPreviewRequest request = new AdDnPreviewRequest();
        request.setServerUrls("192.168.1.10");
        request.setBindDn("company\\query");

        AdDnPreviewVO vo = service.previewDns(request);

        assertNull(vo.getBaseDn());
        assertTrue(vo.getHint().contains("自定义基础 DN"), "必须明确告诉维护人员去哪儿填：" + vo.getHint());
    }

    @Test
    @DisplayName("预览：request 为 null 不抛异常（接口健壮性）")
    void previewHandlesNullRequest() {
        AdDnPreviewVO vo = service.previewDns(null);
        assertEquals("缺少参数", vo.getHint());
    }
}
