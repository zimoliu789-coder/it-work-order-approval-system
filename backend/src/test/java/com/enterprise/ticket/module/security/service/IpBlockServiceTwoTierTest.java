package com.enterprise.ticket.module.security.service;

import com.enterprise.ticket.module.security.entity.IpBlock;
import com.enterprise.ticket.module.security.mapper.IpBlockMapper;
import com.enterprise.ticket.module.security.mapper.SecurityEventMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IP 两级自动封禁单测（P2 安全修复）。
 *
 * <p>口径（用户拍板）：累计失败 10 次 → 封 30 分钟；累计失败 20 次 → 封 24 小时（1440 分钟）。
 * 二级优先判定；已有一级封禁的来源若继续攻击，会被**升级**为二级长封。
 */
@ExtendWith(MockitoExtension.class)
class IpBlockServiceTwoTierTest {

    @Mock
    private IpBlockMapper ipBlockMapper;
    @Mock
    private SecurityEventMapper securityEventMapper;
    @Mock
    private SecurityEventRecorder securityEventRecorder;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private IpBlockService service;

    private static final String IP = "203.0.113.9";

    @BeforeEach
    void setUp() {
        when(systemConfigService.securityIpBlockEnabled()).thenReturn(true);
        when(systemConfigService.securityIpBlockMaxCount()).thenReturn(10);
        when(systemConfigService.securityIpBlockMinutes()).thenReturn(30);
        when(systemConfigService.securityIpBlockLongMaxCount()).thenReturn(20);
        when(systemConfigService.securityIpBlockLongMinutes()).thenReturn(1440);
        // 白名单为空 → 不在豁免内（inWhitelist 因配置为空返回 false）
        when(systemConfigService.securityIpWhitelist()).thenReturn("");
    }

    private IpBlock capturedRow() {
        ArgumentCaptor<IpBlock> captor = ArgumentCaptor.forClass(IpBlock.class);
        verify(ipBlockMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("累计 12 次（一级）→ 封 30 分钟")
    void tierOneBlocksThirtyMinutes() {
        when(securityEventMapper.countLoginFailByIpSince(eq(IP), any())).thenReturn(12L);
        when(ipBlockMapper.selectActiveRow(IP)).thenReturn(null);

        assertTrue(service.recordFailureAndMaybeBlock(IP, "user", "ua"));

        IpBlock row = capturedRow();
        assertEquals(30, Duration.between(row.getBlockedAt(), row.getExpireAt()).toMinutes());
    }

    @Test
    @DisplayName("累计 25 次（二级优先）→ 直接长封 1440 分钟")
    void tierTwoBlocksLonger() {
        when(securityEventMapper.countLoginFailByIpSince(eq(IP), any())).thenReturn(25L);
        when(ipBlockMapper.selectActiveRow(IP)).thenReturn(null);

        assertTrue(service.recordFailureAndMaybeBlock(IP, "user", "ua"));

        IpBlock row = capturedRow();
        assertEquals(1440, Duration.between(row.getBlockedAt(), row.getExpireAt()).toMinutes());
    }

    @Test
    @DisplayName("未达阈值（9 次）→ 不封禁")
    void belowThresholdNoBlock() {
        when(securityEventMapper.countLoginFailByIpSince(eq(IP), any())).thenReturn(9L);

        assertFalse(service.recordFailureAndMaybeBlock(IP, "user", "ua"));
        verify(ipBlockMapper, never()).insert(any());
    }

    @Test
    @DisplayName("已有一级封禁 + 累计达二级 → 升级为长封（update 而非 insert）")
    void escalatesToTierTwo() {
        LocalDateTime now = LocalDateTime.now();
        IpBlock active = new IpBlock();
        active.setId(1L);
        active.setIp(IP);
        active.setBlockedAt(now.minusMinutes(5));
        active.setExpireAt(now.plusMinutes(25)); // 一级封禁剩余 25 分钟
        when(ipBlockMapper.selectActiveRow(IP)).thenReturn(active);
        when(securityEventMapper.countLoginFailByIpSince(eq(IP), any())).thenReturn(22L);

        assertTrue(service.recordFailureAndMaybeBlock(IP, "user", "ua"));

        ArgumentCaptor<IpBlock> captor = ArgumentCaptor.forClass(IpBlock.class);
        verify(ipBlockMapper).updateById(captor.capture());
        verify(ipBlockMapper, never()).insert(any());
        // 升级后到期时间 = now + 1440 分钟（block() 以「当前时刻」为基准，不是原 blockedAt）
        long remainMinutes = Duration.between(LocalDateTime.now(), captor.getValue().getExpireAt()).toMinutes();
        assertTrue(remainMinutes >= 1435 && remainMinutes <= 1440,
                "升级后应剩余约 1440 分钟，实际 " + remainMinutes);
    }

    @Test
    @DisplayName("已有一级封禁 + 未达二级 → 不重复写（避免同一 IP 刷屏）")
    void noRewriteWhenAlreadyBlockedAtSameTier() {
        LocalDateTime now = LocalDateTime.now();
        IpBlock active = new IpBlock();
        active.setId(1L);
        active.setIp(IP);
        active.setBlockedAt(now.minusMinutes(1));
        active.setExpireAt(now.plusMinutes(29));
        when(ipBlockMapper.selectActiveRow(IP)).thenReturn(active);
        when(securityEventMapper.countLoginFailByIpSince(eq(IP), any())).thenReturn(11L);

        assertFalse(service.recordFailureAndMaybeBlock(IP, "user", "ua"));
        verify(ipBlockMapper, never()).updateById(any());
        verify(ipBlockMapper, never()).insert(any());
    }
}
