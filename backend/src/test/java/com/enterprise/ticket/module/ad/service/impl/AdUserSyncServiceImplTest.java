package com.enterprise.ticket.module.ad.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdSyncResultVO;
import com.enterprise.ticket.module.ad.ldap.AdAttributeMapping;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService.ProvisionResult;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AD 用户同步（Phase 13；需求一.2）—— 单元测试
 *
 * <p>本类只做「拉取 → 逐个本地化 → 收尾禁用」三段，其中收尾禁用阶段承载了三条
 * <b>每条都对应一次真实事故</b>的安全不变式，因此测试重点是这几条不变式，而非计数本身：
 * <ol>
 *   <li>目录返回 0 个用户时<b>绝不</b>执行禁用阶段（否则过滤器写错 = 一次同步禁用全公司）；</li>
 *   <li>只禁用、不删除（保留历史工单引用）；</li>
 *   <li>绝不触碰超级管理员（否则唯一管理入口消失）。</li>
 * </ol>
 *
 * <p>另外验证「逐条尽力同步」：单条失败计入 failures 但不中断整批。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AD 用户同步")
class AdUserSyncServiceImplTest {

    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(User.class);
    }

    @Mock
    private AdConfigService adConfigService;
    @Mock
    private AdDirectoryClient directoryClient;
    @Mock
    private AdUserProvisioningService provisioningService;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AdUserSyncServiceImpl service;

    private final AdConnection connection = new AdConnection(
            List.of("dc1.company.com"), 389, false, true,
            "DC=company,DC=com", "CN=query,DC=company,DC=com", "pwd", 5,
            AdConnection.DEFAULT_FILTER,
            new AdAttributeMapping("sAMAccountName", "displayName", "mail", null, "department", "userAccountControl"));

    private AdUser adUser(String account) {
        return new AdUser("CN=" + account, account, account, null, null, null, false, null);
    }

    private User localLdap(String username, String role, boolean enabled) {
        User user = new User();
        user.setId(Math.abs((long) username.hashCode()));
        user.setUsername(username);
        user.setRole(role);
        user.setAuthType("LDAP");
        user.setEnabled(enabled);
        return user;
    }

    // ------------------------------------------------------------------
    // 目录不可达 / 未启用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("目录不可访问 → AD_SYNC_FAILED（不产生本地副作用）")
    void directoryUnavailable() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt()))
                .thenThrow(new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE, "连接超时"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sync());
        assertEquals(ErrorCode.AD_SYNC_FAILED, ex.getErrorCode());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("未启用 AD 时由 AdConfigService 抛出带指向性的错误（不再继续）")
    void disabledPropagates() {
        when(adConfigService.activeConnection()).thenThrow(new BusinessException(ErrorCode.AD_DISABLED));

        assertEquals(ErrorCode.AD_DISABLED,
                assertThrows(BusinessException.class, () -> service.sync()).getErrorCode());
        verify(directoryClient, never()).fetchAll(any(), anyInt());
    }

    // ------------------------------------------------------------------
    // 安全不变式 1：目录空集不禁用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("目录返回 0 个用户 → 跳过禁用阶段，绝不批量误禁用")
    void emptyDirectorySkipsDisablePhase() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of());

        AdSyncResultVO result = service.sync();

        assertEquals(0, result.getTotal());
        assertEquals(0, result.getDisabled());
        verify(userMapper, never()).selectList(any());
        verify(userMapper, never()).disableForAdRemoval(any());
        assertFalse(result.getFailures().isEmpty(), "应给出「已跳过禁用阶段」的告警");
        assertTrue(result.getFailures().get(0).contains("0 个用户"));
    }

    // ------------------------------------------------------------------
    // 正常同步 + 收尾禁用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分类计数：新建 / 更新 / 无变化分别累加")
    void countsClassified() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt()))
                .thenReturn(List.of(adUser("a"), adUser("b"), adUser("c")));

        when(provisioningService.provision(any()))
                .thenReturn(new ProvisionResult(new User(), true, false))
                .thenReturn(new ProvisionResult(new User(), false, true))
                .thenReturn(new ProvisionResult(new User(), false, false));
        // 收尾禁用阶段会查本地域账号；这里本地为空，避免额外禁用
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdSyncResultVO result = service.sync();

        assertEquals(3, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getUnchanged());
        assertEquals(0, result.getDisabled());
        assertEquals(0, result.getFailed());
    }

    @Test
    @DisplayName("本地有、AD 已无 → 禁用（不删除），并计入 disabled")
    void disablesMissingLocalAccount() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of(adUser("still-there")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), false, false));

        User gone = localLdap("gone", RoleCode.USER, true);
        when(userMapper.selectList(any())).thenReturn(List.of(gone));
        when(userMapper.disableForAdRemoval(gone.getId())).thenReturn(1);

        AdSyncResultVO result = service.sync();

        assertEquals(1, result.getDisabled());
        verify(userMapper).disableForAdRemoval(gone.getId());
        verify(userMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("不变式 3：超管账号即使不在 AD 中也绝不被禁用")
    void neverDisablesSuperAdmin() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of(adUser("someone")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), false, false));

        User superAdmin = localLdap("administrator", RoleCode.SUPER_ADMIN, true);
        when(userMapper.selectList(any())).thenReturn(List.of(superAdmin));

        AdSyncResultVO result = service.sync();

        assertEquals(0, result.getDisabled());
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    @Test
    @DisplayName("本地已禁用的域账号不重复处理")
    void skipsAlreadyDisabledLocal() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of(adUser("someone")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), false, false));

        User already = localLdap("already-off", RoleCode.USER, false);
        when(userMapper.selectList(any())).thenReturn(List.of(already));

        AdSyncResultVO result = service.sync();

        assertEquals(0, result.getDisabled());
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    @Test
    @DisplayName("账号名大小写不敏感匹配（AD 侧大小写与本地不同也算「仍在 AD」）")
    void accountMatchIsCaseInsensitive() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of(adUser("ZhangWei")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), false, false));

        User local = localLdap("zhangwei", RoleCode.USER, true);
        when(userMapper.selectList(any())).thenReturn(List.of(local));

        AdSyncResultVO result = service.sync();

        assertEquals(0, result.getDisabled(), "大小写不同不应被判为「已从 AD 删除」");
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    // ------------------------------------------------------------------
    // 逐条尽力同步
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单条本地化失败 → 计入 failed 且不中断整批")
    void singleFailureDoesNotAbortBatch() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt()))
                .thenReturn(List.of(adUser("bad"), adUser("good")));
        when(provisioningService.provision(any()))
                .thenThrow(new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE, "默认角色失效"))
                .thenReturn(new ProvisionResult(new User(), true, false));
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdSyncResultVO result = service.sync();

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getCreated());
        assertTrue(result.getFailures().stream().anyMatch(f -> f.contains("bad")));
    }

    @Test
    @DisplayName("无登录名的目录条目（组 / 计算机）被跳过：不本地化、不计数、不报错")
    void skipsEntriesWithoutAccount() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt()))
                .thenReturn(List.of(new AdUser("CN=group", null, "组", null, null, null, false, null),
                        adUser("real")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), true, false));
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdSyncResultVO result = service.sync();

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(0, result.getFailed());
        // 只有「real」被本地化：无登录名条目根本不进来
        verify(provisioningService).provision(eq(adUser("real")));
    }

    @Test
    @DisplayName("同步完成后回写配置（markSyncResult）并填充时间范围")
    void marksResultAndFillsTimeRange() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.fetchAll(eq(connection), anyInt())).thenReturn(List.of(adUser("a")));
        when(provisioningService.provision(any())).thenReturn(new ProvisionResult(new User(), true, false));
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdSyncResultVO result = service.sync();

        verify(adConfigService).markSyncResult(eq(result.getMessage()));
        assertTrue(result.getMessage().contains("新建 1"));
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getFinishedAt());
    }
}
