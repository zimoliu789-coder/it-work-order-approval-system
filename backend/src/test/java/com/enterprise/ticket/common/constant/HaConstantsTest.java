package com.enterprise.ticket.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主备模块三个枚举 + 三个新消息类型（Phase 19 批次 G）
 *
 * <h2>为什么枚举也要单测</h2>
 * <p>这三个枚举是主备页面<b>颜色与文案的唯一事实源</b>：
 * {@code ABNORMAL} 变红、{@code RUNNING} 变绿、{@code FAILED} 才告警。
 * 前端拿原始码做映射、拿中文标签做展示 —— 因此「标签被顺手改掉」
 * 或「of() 的大小写宽容度变了」都会让页面静默错色，而页面错色不会报任何错。
 *
 * <p>另外 {@link HaNodeStatus#isHealthy()} 与 {@link HaSyncState#needsAlert()}
 * 是两处「聚合判据」，它们被下游（总体可用性、告警闸门）直接取用 ——
 * 判据本身写错，下游怎么调都是错的，因此在这里单独钉住。
 */
@DisplayName("主备枚举与消息类型（批次 G）")
class HaConstantsTest {

    // ------------------------------------------------------------------
    // HaRole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HaRole：of 严格区分大小写，非法值与 null 都返回 null")
    void haRoleOf() {
        assertSame(HaRole.MASTER, HaRole.of("MASTER"));
        assertSame(HaRole.STANDBY, HaRole.of("STANDBY"));
        assertNull(HaRole.of("master"), "小写不是合法码 —— 宽容匹配会让脏数据静默生效");
        assertNull(HaRole.of("PRIMARY"));
        assertNull(HaRole.of(""));
        assertNull(HaRole.of(null));
    }

    @Test
    @DisplayName("HaRole：labelOf 把未知值原样返回，绝不渲染出 null")
    void haRoleLabel() {
        assertEquals("主节点", HaRole.labelOf("MASTER"));
        assertEquals("备节点", HaRole.labelOf("STANDBY"));
        assertEquals("PRIMARY", HaRole.labelOf("PRIMARY"));
        // null 时返回 null 是刻意的：调用方据此显示「未知」而不是一个空字符串
        assertNull(HaRole.labelOf(null));
    }

    @Test
    @DisplayName("HaRole：isMaster 只有 MASTER 为真")
    void haRoleIsMaster() {
        assertTrue(HaRole.MASTER.isMaster());
        assertFalse(HaRole.STANDBY.isMaster());
    }

    // ------------------------------------------------------------------
    // HaNodeStatus
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HaNodeStatus：四档齐全，of 严格匹配")
    void haNodeStatusOf() {
        assertSame(HaNodeStatus.RUNNING, HaNodeStatus.of("RUNNING"));
        assertSame(HaNodeStatus.STANDBY, HaNodeStatus.of("STANDBY"));
        assertSame(HaNodeStatus.ABNORMAL, HaNodeStatus.of("ABNORMAL"));
        assertSame(HaNodeStatus.UNKNOWN, HaNodeStatus.of("UNKNOWN"));
        assertNull(HaNodeStatus.of("running"));
        assertNull(HaNodeStatus.of(null));
    }

    @Test
    @DisplayName("★ UNKNOWN 不算健康 —— 否则一台从未连上的备机会让整体看起来是好的")
    void unknownIsNotHealthy() {
        assertTrue(HaNodeStatus.RUNNING.isHealthy());
        assertTrue(HaNodeStatus.STANDBY.isHealthy());
        assertFalse(HaNodeStatus.ABNORMAL.isHealthy());
        assertFalse(HaNodeStatus.UNKNOWN.isHealthy(),
                "未知是「还没拿到证据」，不是「一切正常」");
    }

    @Test
    @DisplayName("HaNodeStatus：isAbnormal 只认 ABNORMAL（界面标红的唯一判据）")
    void isAbnormal() {
        assertTrue(HaNodeStatus.ABNORMAL.isAbnormal());
        assertFalse(HaNodeStatus.UNKNOWN.isAbnormal());
        assertFalse(HaNodeStatus.RUNNING.isAbnormal());
        assertFalse(HaNodeStatus.STANDBY.isAbnormal());
    }

    @Test
    @DisplayName("HaNodeStatus：中文标签与需求 [164] 的三档展示口径一致")
    void haNodeStatusLabels() {
        assertEquals("运行中", HaNodeStatus.labelOf("RUNNING"));
        assertEquals("待命", HaNodeStatus.labelOf("STANDBY"));
        assertEquals("异常", HaNodeStatus.labelOf("ABNORMAL"));
        assertEquals("未知", HaNodeStatus.labelOf("UNKNOWN"));
    }

    // ------------------------------------------------------------------
    // HaSyncState
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 只有 FAILED 需要告警 —— LAGGING 是常态，不该刷消息中心")
    void onlyFailedNeedsAlert() {
        assertTrue(HaSyncState.FAILED.needsAlert());
        assertFalse(HaSyncState.LAGGING.needsAlert(),
                "主从复制短暂落后是常态；把它当告警会让页面每分钟红几百次");
        assertFalse(HaSyncState.IN_SYNC.needsAlert());
        assertFalse(HaSyncState.UNKNOWN.needsAlert());
    }

    @Test
    @DisplayName("HaSyncState：四档与标签")
    void haSyncStateLabels() {
        assertSame(HaSyncState.IN_SYNC, HaSyncState.of("IN_SYNC"));
        assertNull(HaSyncState.of("in_sync"));
        assertNull(HaSyncState.of(null));
        assertEquals("数据一致", HaSyncState.labelOf("IN_SYNC"));
        assertEquals("同步落后", HaSyncState.labelOf("LAGGING"));
        assertEquals("同步失败", HaSyncState.labelOf("FAILED"));
        assertEquals("未知", HaSyncState.labelOf("UNKNOWN"));
        assertEquals("WEIRD", HaSyncState.labelOf("WEIRD"));
    }

    // ------------------------------------------------------------------
    // MessageType（批次 G 新增三种）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个新消息类型已登记且标签正确（接收人=全部超管）")
    void haMessageTypesPresent() {
        assertEquals("主备节点断连", MessageType.HA_NODE_DISCONNECTED.getLabel());
        assertEquals("主备同步失败", MessageType.HA_SYNC_FAILED.getLabel());
        assertEquals("主备切换", MessageType.HA_SWITCHOVER.getLabel());
    }

    @Test
    @DisplayName("★ 断连与同步失败刻意分开：一个要去看机器，一个只需看复制日志")
    void disconnectAndSyncFailureAreDistinct() {
        assertFalse(MessageType.HA_NODE_DISCONNECTED == MessageType.HA_SYNC_FAILED);
        assertFalse(MessageType.HA_NODE_DISCONNECTED.getLabel()
                .equals(MessageType.HA_SYNC_FAILED.getLabel()),
                "两者文案必须可区分，否则维护人员看不出该往哪个方向排查");
    }
}
