package com.enterprise.ticket.module.ha.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ha.dto.HaHeartbeatRequest;
import com.enterprise.ticket.module.ha.entity.HaConfig;
import com.enterprise.ticket.module.ha.entity.HaNode;
import com.enterprise.ticket.module.ha.mapper.HaConfigMapper;
import com.enterprise.ticket.module.ha.mapper.HaNodeMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主备心跳与超时扫描（Phase 19 批次 G）—— 单元测试
 *
 * <h2>本类守的四条「不许静默」口径</h2>
 * <ol>
 *   <li><b>未登记的 IP 不自动建行。</b>心跳端点在 {@code permit-all} 白名单里、
 *       只靠共享密钥保护；若收到未知 IP 就插行，密钥一旦泄漏就能把节点列表刷成垃圾，
 *       而那是维护人员判断「我的两台机器状态如何」的唯一视图；</li>
 *   <li><b>点名要报的字段才写库。</b>心跳每秒一发、复制状态几十秒才查一次；
 *       若每次心跳都覆盖同步快照，页面上的「数据是否一致」会在一秒内反复跳变；</li>
 *   <li><b>告警幂等。</b>复制中断期间每轮上报都推一条消息，几分钟就能把消息中心刷红，
 *       真正需要被看到的告警会被自己制造的噪音埋掉；</li>
 *   <li><b>本机不算超时。</b>本机心跳靠自己刷新，纳入超时判定就会出现
 *       「页面说本机异常、但页面明明可用」的自相矛盾。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主备心跳服务（上报解析 / 同步快照 / 超时扫描）")
class HaHeartbeatServiceTest {

    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(HaConfig.class, HaNode.class);
    }

    @Mock
    private HaConfigMapper haConfigMapper;
    @Mock
    private HaNodeMapper haNodeMapper;
    @Mock
    private HaAlertNotifier alertNotifier;

    @InjectMocks
    private HaHeartbeatService service;

    private HaConfig config(boolean enabled) {
        HaConfig config = new HaConfig();
        config.setId(1L);
        config.setEnabled(enabled);
        config.setNodeIp("192.168.1.10");
        config.setHeartbeatTimeoutSeconds(10);
        config.setSyncState(HaSyncState.UNKNOWN.name());
        return config;
    }

    private HaNode node(long id, String ip, String role, String status, boolean isLocal) {
        HaNode node = new HaNode();
        node.setId(id);
        node.setNodeName(isLocal ? "ticket-master" : "ticket-slave");
        node.setNodeIp(ip);
        node.setNodeRole(role);
        node.setNodeStatus(status);
        node.setIsLocal(isLocal);
        return node;
    }

    private HaHeartbeatRequest request(String ip) {
        HaHeartbeatRequest request = new HaHeartbeatRequest();
        request.setNodeIp(ip);
        return request;
    }

    /** 桩：只有一行配置 + 一个已登记节点 */
    private void givenRegisteredNode(HaNode node, boolean enabled) {
        when(haConfigMapper.selectOne(any())).thenReturn(config(enabled));
        when(haNodeMapper.selectByIp(node.getNodeIp())).thenReturn(node);
    }

    // ==================================================================
    // 上报解析
    // ==================================================================

    @Test
    @DisplayName("缺少 nodeIp → PARAM_INVALID（脚本写错了要当场拒绝，而不是猜一个节点）")
    void reportRejectsMissingIp() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BusinessException.class, () -> service.report(new HaHeartbeatRequest()))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BusinessException.class, () -> service.report(request("   ")))
                        .getErrorCode());
    }

    @Test
    @DisplayName("★ 未登记的 IP → 忽略并留日志，绝不建行")
    void reportIgnoresUnregisteredIp() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(true));
        when(haNodeMapper.selectByIp("10.9.9.9")).thenReturn(null);
        when(haNodeMapper.selectLocal()).thenReturn(null);

        service.report(request("10.9.9.9"));

        verify(haNodeMapper, never()).insert(any(HaNode.class));
        verify(haNodeMapper, never()).touchHeartbeat(any(), any(), any());
        verify(alertNotifier, never()).notifyNodeDisconnected(any(), anyInt());
    }

    @Test
    @DisplayName("主节点报心跳 → 状态置「运行中」")
    void reportMapsMasterToRunning() {
        HaNode master = node(1L, "192.168.1.10", HaRole.MASTER.name(), HaNodeStatus.ABNORMAL.name(), true);
        givenRegisteredNode(master, true);

        service.report(request("192.168.1.10"));

        verify(haNodeMapper).touchHeartbeat(eq(1L), eq(HaNodeStatus.RUNNING.name()), any());
    }

    @Test
    @DisplayName("备节点报心跳 → 状态置「待命」（不是「运行中」——两者含义不同）")
    void reportMapsStandbyToStandby() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.UNKNOWN.name(), false);
        givenRegisteredNode(peer, true);

        service.report(request("192.168.1.101"));

        verify(haNodeMapper).touchHeartbeat(eq(2L), eq(HaNodeStatus.STANDBY.name()), any());
    }

    @Test
    @DisplayName("★ 上报未带角色时不动角色（心跳每秒在发，空值覆盖会让角色在页面上闪回）")
    void reportKeepsRoleWhenNotReported() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setRole(null);
        service.report(request);

        verify(haNodeMapper, never()).updateRole(any(), any());
        verify(haNodeMapper).touchHeartbeat(eq(2L), eq(HaNodeStatus.STANDBY.name()), any());
    }

    @Test
    @DisplayName("上报带了合法角色且与库中不同 → 更新角色，并按新角色映射状态")
    void reportUpdatesRoleWhenChanged() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setRole(HaRole.MASTER.name());
        service.report(request);

        verify(haNodeMapper).updateRole(eq(2L), eq(HaRole.MASTER.name()));
        verify(haNodeMapper).touchHeartbeat(eq(2L), eq(HaNodeStatus.RUNNING.name()), any());
    }

    @Test
    @DisplayName("上报角色是非法值 → 忽略（不让脏值写进角色列）")
    void reportIgnoresInvalidRole() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setRole("PRIMARY");
        service.report(request);

        verify(haNodeMapper, never()).updateRole(any(), any());
        verify(haNodeMapper).touchHeartbeat(eq(2L), eq(HaNodeStatus.STANDBY.name()), any());
    }

    @Test
    @DisplayName("角色与库中相同 → 不做无意义的 UPDATE")
    void reportSkipsRedundantRoleUpdate() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setRole(HaRole.STANDBY.name());
        service.report(request);

        verify(haNodeMapper, never()).updateRole(any(), any());
    }

    @Test
    @DisplayName("★ 本机行 IP 为空时，首次上报把 IP 认领为本机地址（不新建行）")
    void reportAdoptsIpForLocalRowWithoutIp() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(true));
        when(haNodeMapper.selectByIp("172.20.0.5")).thenReturn(null);
        HaNode local = node(1L, "", HaRole.MASTER.name(), HaNodeStatus.UNKNOWN.name(), true);
        when(haNodeMapper.selectLocal()).thenReturn(local);

        service.report(request("172.20.0.5"));

        verify(haNodeMapper).updateIp(eq(1L), eq("172.20.0.5"));
        verify(haConfigMapper).updateLocalIdentity(eq(1L), eq("ticket-master"), eq("172.20.0.5"));
        verify(haNodeMapper, never()).insert(any(HaNode.class));
        verify(haNodeMapper).touchHeartbeat(eq(1L), eq(HaNodeStatus.RUNNING.name()), any());
    }

    @Test
    @DisplayName("本机行已有 IP 且上报的是别的地址 → 不认领（陌生 IP 不能被塞进本机行）")
    void reportDoesNotAdoptForLocalRowWithIp() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(true));
        when(haNodeMapper.selectByIp("10.9.9.9")).thenReturn(null);
        when(haNodeMapper.selectLocal()).thenReturn(node(1L, "192.168.1.10", HaRole.MASTER.name(),
                HaNodeStatus.RUNNING.name(), true));

        service.report(request("10.9.9.9"));

        verify(haNodeMapper, never()).updateIp(any(), any());
        verify(haNodeMapper, never()).touchHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("节点从「异常」恢复心跳 → 状态转回健康（恢复刻意不发告警）")
    void reportRecoversAbnormalNode() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.ABNORMAL.name(), false);
        givenRegisteredNode(peer, true);

        service.report(request("192.168.1.101"));

        verify(haNodeMapper).touchHeartbeat(eq(2L), eq(HaNodeStatus.STANDBY.name()), any());
        verify(alertNotifier, never()).notifyNodeDisconnected(any(), anyInt());
    }

    // ==================================================================
    // 同步状态快照
    // ==================================================================

    @Test
    @DisplayName("★ 本次没带同步信息 → 完全不写同步快照（心跳不该覆盖几十秒才查一次的复制状态）")
    void syncSnapshotSkippedWhenNothingReported() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        service.report(request("192.168.1.101"));

        verify(haConfigMapper, never()).updateSyncSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("带了状态 → 落库；LAGGING 不告警（落后是常态）")
    void syncSnapshotWritesAndDoesNotAlertOnLagging() {
        HaConfig config = config(true);
        config.setSyncState(HaSyncState.IN_SYNC.name());
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peer);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setSyncState(HaSyncState.LAGGING.name());
        request.setDelaySeconds(12);
        service.report(request);

        verify(haConfigMapper).updateSyncSnapshot(eq(1L), eq(HaSyncState.LAGGING.name()), eq(12), any());
        verify(alertNotifier, never()).notifySyncFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 从非失败跃迁到 FAILED → 告警一次")
    void syncSnapshotAlertsOnTransitionToFailed() {
        HaConfig config = config(true);
        config.setSyncState(HaSyncState.IN_SYNC.name());
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peer);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setSyncState(HaSyncState.FAILED.name());
        service.report(request);

        verify(alertNotifier).notifySyncFailed(eq("192.168.1.101"), eq(HaSyncState.FAILED.name()),
                any(), anyString());
    }

    @Test
    @DisplayName("★ 上一次已经是 FAILED → 不重复告警（幂等闸门，防消息中心被刷红）")
    void syncSnapshotAlertsOnlyOnceWhileFailing() {
        HaConfig config = config(true);
        config.setSyncState(HaSyncState.FAILED.name());
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peer);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setSyncState(HaSyncState.FAILED.name());
        service.report(request);

        verify(haConfigMapper).updateSyncSnapshot(eq(1L), eq(HaSyncState.FAILED.name()), any(), any());
        verify(alertNotifier, never()).notifySyncFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("只报了延迟、没报状态 → 状态落 UNKNOWN（不沿用旧值撒谎）")
    void syncSnapshotWritesUnknownWhenOnlyDelayReported() {
        HaConfig config = config(true);
        config.setSyncState(HaSyncState.IN_SYNC.name());
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peer);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setDelaySeconds(3);
        service.report(request);

        verify(haConfigMapper).updateSyncSnapshot(eq(1L), eq(HaSyncState.UNKNOWN.name()), eq(3), any());
    }

    @Test
    @DisplayName("只报了最后同步时间 → 也触发一次快照写入（保留上一条状态码）")
    void syncSnapshotWritesWhenOnlyLastSyncAtReported() {
        HaConfig config = config(true);
        config.setSyncState(HaSyncState.IN_SYNC.name());
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peer);

        LocalDateTime at = LocalDateTime.now();
        HaHeartbeatRequest request = request("192.168.1.101");
        request.setLastSyncAt(at);
        service.report(request);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(haConfigMapper).updateSyncSnapshot(eq(1L), eq(HaSyncState.UNKNOWN.name()), any(), captor.capture());
        assertEquals(at, captor.getValue());
    }

    // ==================================================================
    // 切换事件
    // ==================================================================

    @Test
    @DisplayName("event=switchover（大小写不敏感）→ 落切换时间 + 发切换告警")
    void switchoverEventAccountsAndAlerts() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.MASTER.name(), HaNodeStatus.RUNNING.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setEvent("switchover");
        request.setTrigger("自动接管（对端心跳超时）");
        service.report(request);

        verify(haConfigMapper).markSwitched(eq(1L), any());
        verify(alertNotifier).notifySwitchover(eq("192.168.1.101"), eq(HaRole.MASTER.name()),
                eq("自动接管（对端心跳超时）"), any());
    }

    @Test
    @DisplayName("★ 切换未说明触发原因 → 如实记「未上报」，绝不擅自填「自动切换」（会把计划内维护误报成故障）")
    void switchoverEventWithoutTriggerIsHonest() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.MASTER.name(), HaNodeStatus.RUNNING.name(), false);
        givenRegisteredNode(peer, true);

        HaHeartbeatRequest request = request("192.168.1.101");
        request.setEvent("SWITCHOVER");
        service.report(request);

        ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
        verify(alertNotifier).notifySwitchover(any(), any(), trigger.capture(), any());
        assertTrue(trigger.getValue().contains("未上报"),
                "触发原因缺失时必须如实标注，不能猜：" + trigger.getValue());
    }

    @Test
    @DisplayName("普通心跳（event 为空 → 默认 HEARTBEAT）不落切换时间、不发切换告警")
    void plainHeartbeatDoesNotLookLikeSwitchover() {
        HaNode peer = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        givenRegisteredNode(peer, true);

        service.report(request("192.168.1.101"));

        verify(haConfigMapper, never()).markSwitched(any(), any());
        verify(alertNotifier, never()).notifySwitchover(any(), any(), any(), any());
    }

    // ==================================================================
    // 超时扫描
    // ==================================================================

    @Test
    @DisplayName("★ 未启用主备 → 直接返回 0，一行都不碰（单机环境不该被反复误判）")
    void scanSkippedWhenDisabled() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(false));

        assertEquals(0, service.scanAndMarkStale());

        verify(haNodeMapper, never()).selectNewlyStale(any());
        verify(haNodeMapper, never()).touchHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("★ 已启用 → 刷新本机心跳，并把超时对端逐个置异常 + 发断连告警")
    void scanMarksStalePeersAndAlerts() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(true));
        when(haNodeMapper.selectLocal())
                .thenReturn(node(1L, "192.168.1.10", HaRole.MASTER.name(), HaNodeStatus.RUNNING.name(), true));
        HaNode stale = node(2L, "192.168.1.101", HaRole.STANDBY.name(), HaNodeStatus.STANDBY.name(), false);
        when(haNodeMapper.selectNewlyStale(any())).thenReturn(List.of(stale));

        assertEquals(1, service.scanAndMarkStale());

        // 本机心跳按角色刷新（主节点=运行中）
        verify(haNodeMapper).touchHeartbeat(eq(1L), eq(HaNodeStatus.RUNNING.name()), any());
        verify(haNodeMapper).markAbnormal(2L);
        verify(alertNotifier).notifyNodeDisconnected(any(HaNode.class), eq(10));
    }

    @Test
    @DisplayName("对端心跳超时按「当前生效阈值」判定（读库值先经钳制）")
    void scanUsesClampedTimeoutThreshold() {
        HaConfig config = config(true);
        config.setHeartbeatTimeoutSeconds(0);   // 库里残留的非法值
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        when(haNodeMapper.selectLocal()).thenReturn(null);

        when(haNodeMapper.selectNewlyStale(any())).thenReturn(List.of());

        assertEquals(0, service.scanAndMarkStale());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(haNodeMapper).selectNewlyStale(cutoff.capture());

        // 阈值 0 会被钳到 3 秒 ⇒ cutoff 应约为「现在 - 3 秒」，而不是「现在」
        long seconds = java.time.Duration.between(cutoff.getValue(), LocalDateTime.now()).getSeconds();
        assertTrue(seconds >= 2 && seconds <= 4,
                "阈值 0 必须被钳到 3 秒，实际 cutoff 距今 " + seconds + " 秒");
    }

    @Test
    @DisplayName("无超时节点 → 返回 0 且不发任何告警")
    void scanWithoutStaleNodesDoesNotAlert() {
        when(haConfigMapper.selectOne(any())).thenReturn(config(true));
        when(haNodeMapper.selectLocal()).thenReturn(null);
        when(haNodeMapper.selectNewlyStale(any())).thenReturn(List.of());

        assertEquals(0, service.scanAndMarkStale());

        verify(haNodeMapper, never()).markAbnormal(any());
        verify(alertNotifier, never()).notifyNodeDisconnected(any(), anyInt());
    }
}
