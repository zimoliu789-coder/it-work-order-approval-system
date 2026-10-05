package com.enterprise.ticket.module.ha.service;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.ha.entity.HaNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 主备告警通知（Phase 19 批次 G）—— 单元测试
 *
 * <h2>P4-C2 后本类的职责收缩为「正文语义」</h2>
 * 分发（站内消息必达、邮件逐人隔离、短信不假成功、审计失败不抛出）已抽到
 * {@link AlertService}，由 {@code AlertServiceTest} 统一守着。
 * 因此这里只钉两件事：
 * <ol>
 *   <li><b>正文里有没有「可操作的下一步」</b> —— 告警的价值不在「出事了」，
 *       而在「看完知道该做什么」。断连要说清 keepalived 已接管、恢复后不会抢回；
 *       同步失败要给出具体命令（{@code SHOW REPLICA STATUS} 与重建脚本）；</li>
 *   <li><b>不确定的事实不许伪装成确定</b> —— 延迟为 null 要写「未知」而不是「0 秒」
 *       （0 秒看起来是健康的），从未上报要写「从未上报」而不是一个假时间。</li>
 * </ol>
 * 这两条都是「写错了不会报错、但会让人做出错误判断」的类型，只能靠断言守。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主备告警通知（正文语义 / 分级 / 诚实性）")
class HaAlertNotifierTest {

    @Mock
    private AlertService alertService;

    @InjectMocks
    private HaAlertNotifier notifier;

    // ------------------------------------------------------------------
    // 夹具与捕获
    // ------------------------------------------------------------------

    private HaNode node() {
        HaNode node = new HaNode();
        node.setId(2L);
        node.setNodeName("ticket-slave");
        node.setNodeIp("192.168.1.101");
        node.setNodeRole(HaRole.STANDBY.name());
        node.setNodeStatus(HaNodeStatus.ABNORMAL.name());
        node.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(5));
        return node;
    }

    /** 捕获交给 AlertService 的正文 */
    private String capturedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(alertService).alert(any(), any(), anyString(), body.capture(), anyString(), anyString());
        return body.getValue();
    }

    /** 捕获交给 AlertService 的消息类型（确认三处告警没有被复制粘贴成同一个类型） */
    private List<MessageType> capturedTypes() {
        ArgumentCaptor<MessageType> type = ArgumentCaptor.forClass(MessageType.class);
        verify(alertService, atLeastOnce())
                .alert(any(), type.capture(), anyString(), anyString(), anyString(), anyString());
        return type.getAllValues();
    }

    private List<AlertLevel> capturedLevels() {
        ArgumentCaptor<AlertLevel> level = ArgumentCaptor.forClass(AlertLevel.class);
        verify(alertService, atLeastOnce())
                .alert(level.capture(), any(), anyString(), anyString(), anyString(), anyString());
        return level.getAllValues();
    }

    // ==================================================================
    // 正文语义
    // ==================================================================

    @Test
    @DisplayName("断连正文含排查建议（先看进程与网络，并说明 keepalived 已接管、恢复后不会抢回）")
    void disconnectBodyHasActionableAdvice() {
        notifier.notifyNodeDisconnected(node(), 10);

        String body = capturedBody();
        assertTrue(body.contains("192.168.1.101"), "正文应含节点 IP，便于直接定位机器：" + body);
        assertTrue(body.contains("10 秒"), "正文应带当前生效的判定阈值");
        assertTrue(body.contains("keepalived"),
                "应说明「整机宕机时 VIP 已漂到对端、员工访问不受影响」：" + body);
        assertTrue(body.contains("nopreempt"), "应说明恢复后不会自动抢回：" + body);
    }

    @Test
    @DisplayName("同步失败正文指向复制状态与重建脚本")
    void syncFailedBodyPointsToReplication() {
        notifier.notifySyncFailed("192.168.1.101", HaSyncState.FAILED.name(), 42, "IO 线程停止");

        String body = capturedBody();
        assertTrue(body.contains("SHOW REPLICA STATUS"), body);
        assertTrue(body.contains("setup-replication.sh"), body);
        assertTrue(body.contains("42 秒"), body);
        assertTrue(body.contains("IO 线程停止"), "补充说明应原样带出：" + body);
    }

    @Test
    @DisplayName("切换告警正文说明「员工无感知、在途工单不丢」——这是最需要解释清楚的一件事")
    void switchoverBodyExplainsUserExperience() {
        notifier.notifySwitchover("192.168.1.101", HaRole.MASTER.name(), "自动接管", "对端心跳超时");

        String body = capturedBody();
        assertTrue(body.contains("主节点"), body);
        assertTrue(body.contains("无感知"), body);
        assertTrue(body.contains("不会丢失"), body);
        assertTrue(body.contains("自动接管"), body);
    }

    @Test
    @DisplayName("同步延迟为 null → 正文显示「未知」，绝不显示 0 秒（会把故障伪装成健康）")
    void nullDelayShowsUnknown() {
        notifier.notifySyncFailed("192.168.1.101", HaSyncState.FAILED.name(), null, null);

        assertTrue(capturedBody().contains("未知"), capturedBody());
    }

    @Test
    @DisplayName("节点从未上报过心跳 → 正文写「从未上报」，而不是一个假时间")
    void neverReportedHeartbeatIsHonest() {
        HaNode fresh = node();
        fresh.setLastHeartbeatAt(null);

        notifier.notifyNodeDisconnected(fresh, 10);

        assertTrue(capturedBody().contains("从未上报"), capturedBody());
    }

    @Test
    @DisplayName("节点没起名字 → 标题回落到 IP（不出现「主备节点断连：null」）")
    void unnamedNodeFallsBackToIp() {
        HaNode unnamed = node();
        unnamed.setNodeName("   ");

        notifier.notifyNodeDisconnected(unnamed, 10);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(alertService).alert(any(), any(), title.capture(), anyString(), anyString(), anyString());
        assertTrue(title.getValue().contains("192.168.1.101"), title.getValue());
        assertTrue(!title.getValue().contains("null"), title.getValue());
    }

    // ==================================================================
    // 分级与消息类型（复制粘贴最容易错的地方）
    // ==================================================================

    @Test
    @DisplayName("同步失败 = P0（数据分叉是完整性风险），断连 / 切换 = P1")
    void severityByKind() {
        notifier.notifyNodeDisconnected(node(), 10);
        notifier.notifySyncFailed("192.168.1.101", HaSyncState.FAILED.name(), 1, null);
        notifier.notifySwitchover("192.168.1.101", HaRole.MASTER.name(), "自动接管", null);

        assertEquals(List.of(AlertLevel.P1, AlertLevel.P0, AlertLevel.P1), capturedLevels(),
                "同步失败必须升级为 P0；断连与切换是 P1（VIP 已接管，服务未停）");
    }

    @Test
    @DisplayName("三类告警各用各的消息类型（混用会让运维在错误的分类里翻找）")
    void eachKindUsesItsOwnMessageType() {
        notifier.notifyNodeDisconnected(node(), 10);
        notifier.notifySyncFailed("192.168.1.101", HaSyncState.FAILED.name(), 1, null);
        notifier.notifySwitchover("192.168.1.101", HaRole.MASTER.name(), "自动接管", null);

        assertEquals(List.of(MessageType.HA_NODE_DISCONNECTED, MessageType.HA_SYNC_FAILED,
                        MessageType.HA_SWITCHOVER),
                capturedTypes());
    }

    @Test
    @DisplayName("审计动作码与告警种类一一对应（便于按动作检索历史告警）")
    void auditActionMatchesKind() {
        notifier.notifyNodeDisconnected(node(), 10);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(alertService).alert(any(), any(), anyString(), anyString(), action.capture(), anyString());
        assertTrue("HA_NODE_DISCONNECTED".equals(action.getValue()), action.getValue());

        notifier.notifySyncFailed("192.168.1.101", HaSyncState.FAILED.name(), 1, null);
        verify(alertService).alert(any(), any(), anyString(), anyString(), eq("HA_SYNC_FAILED"), anyString());
    }
}
