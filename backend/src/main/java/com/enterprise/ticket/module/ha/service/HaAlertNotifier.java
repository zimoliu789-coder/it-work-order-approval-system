package com.enterprise.ticket.module.ha.service;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.ha.entity.HaNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 主备告警通知（，  [184]–[186]）
 *
 * <h2> 起：本类只负责「写什么」，不再负责「怎么发」</h2>
 * 原先本类自己实现了「站内消息 + 邮件 + 短信 + 审计」的完整分发。 做异常告警时
 * 需要同一套能力 —— 如果各写一份，两份实现必然漂移（最常见的漂移是「有一处忘了发站内消息」，
 * 而那种缺失只有真出事时才发现）。因此分发被抽成 {@link AlertService}，
 * <b>本类改为只组装三类告警的正文并交给它</b>。
 *
 * <p>这也是为什么本类的测试从「验证通道行为」收缩为「验证正文语义」——
 * 通道行为（必达通道、逐人隔离、短信不假成功、审计失败不抛出）现在由
 * {@code AlertServiceTest} 统一守着，不必在两处重复。
 *
 * <h2>说明与三通道的落地对应</h2>
 * <ul>
 *   <li>[184]「主备节点断连、同步失败、切换发生时，通过系统消息中心通知管理员」
 *       → 站内消息，接收人 = 全部启用中的超级管理员（由 {@code AlertService} 决定）；</li>
 *   <li>[185]「如果配置了短信 / 邮箱通知，同时发短信和邮件告警」
 *       → 邮件真实发送；短信见 {@code AlertService} 的「诚实性说明」（网关未接入）；</li>
 *   <li>[186]「页面上节点状态变红」→ 由状态字段 {@code ABNORMAL} 承载，
 *       前端按状态码着色，<b>不</b>由本类负责。</li>
 * </ul>
 *
 * <h2>分级口径</h2>
 * <ul>
 *   <li><b>同步失败 → P0</b>：两台机器的数据正在分叉，这是**数据完整性**风险，
 *       与「一台机器联系不上」不是一个量级；</li>
 *   <li><b>节点断连 / 发生切换 → P1</b>：服务由虚拟 IP 接管，员工无感知，
 *       但管理员必须在当天知情（尤其是「昨晚三点换过主」这种事后追查）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HaAlertNotifier {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertService alertService;

    // ------------------------------------------------------------------
    // 三类告警入口
    // ------------------------------------------------------------------

    /**
     * 节点断连（心跳超时）。
     *
     * @param node           超时的节点
     * @param timeoutSeconds 当前生效的心跳超时阈值（写进正文，便于维护人员核对是不是阈值太紧）
     */
    public void notifyNodeDisconnected(HaNode node, int timeoutSeconds) {
        String title = "主备节点断连：" + displayName(node);
        String body = "以下主备节点心跳超时，已判定为断连。\n"
                + "节点名称：" + displayName(node) + "\n"
                + "节点 IP：" + safe(node.getNodeIp()) + "\n"
                + "节点角色：" + HaRole.labelOf(node.getNodeRole()) + "\n"
                + "最后心跳：" + format(node.getLastHeartbeatAt()) + "\n"
                + "判定阈值：" + timeoutSeconds + " 秒\n"
                + "发现时间：" + LocalDateTime.now().format(FORMATTER) + "\n\n"
                + "处理建议：先确认该机器的进程与网络是否正常（"
                + "若为整机宕机，keepalived 应已把虚拟 IP 漂到对端，员工访问不受影响）；"
                + "机器恢复后无需手工操作，keepalived 配置了 nopreempt，"
                + "它会作为备节点重新加入，不会抢回虚拟 IP。";
        alertService.alert(AlertLevel.P1, MessageType.HA_NODE_DISCONNECTED, title, body,
                "HA_NODE_DISCONNECTED",
                "节点断连告警 | node=" + safe(node.getNodeIp()) + " | timeoutSeconds=" + timeoutSeconds);
    }

    /**
     * 同步失败（复制中断 / 报错）。
     *
     * @param nodeIp       上报该状态的节点 IP（可为空）
     * @param syncState    上报的状态码
     * @param delaySeconds 复制延迟（秒，可为空）
     * @param detail       补充说明（可为空）
     */
    public void notifySyncFailed(String nodeIp, String syncState, Integer delaySeconds, String detail) {
        String title = "主备数据同步失败";
        String body = "主备之间的数据同步未能正常进行，两台机器的数据可能已经开始不一致。\n"
                + "上报节点：" + safe(nodeIp) + "\n"
                + "同步状态：" + HaSyncState.labelOf(syncState) + "\n"
                + "复制延迟：" + (delaySeconds == null ? "未知" : delaySeconds + " 秒") + "\n"
                + (StringUtils.hasText(detail) ? "详细说明：" + detail.trim() + "\n" : "")
                + "发现时间：" + LocalDateTime.now().format(FORMATTER) + "\n\n"
                + "处理建议：到上报节点上查看 MySQL 复制状态（SHOW REPLICA STATUS），"
                + "确认 IO / SQL 线程是否停止、错误码是什么；"
                + "必要时执行 deploy/ha/scripts/setup-replication.sh 重建复制并触发全量同步。";
        alertService.alert(AlertLevel.P0, MessageType.HA_SYNC_FAILED, title, body,
                "HA_SYNC_FAILED",
                "同步失败告警 | node=" + safe(nodeIp) + " | state=" + safe(syncState)
                        + " | delay=" + (delaySeconds == null ? "-" : delaySeconds));
    }

    /**
     * 发生主备切换。
     *
     * <p><b>本告警不分成功失败</b>：切换是一个必须让管理员知情的<b>事实</b>。
     * 「昨晚三点换过主」这件事如果不留下痕迹，第二天管理员看到一切正常，
     * 根本不知道曾经有一台机器出过问题 —— 而那次故障很可能还会再来。
     *
     * @param nodeIp    发生切换的节点 IP（可为空）
     * @param localRole 切换后该节点的角色
     * @param trigger   触发方式：手动 / 自动（心跳超时等）
     * @param detail    补充说明
     */
    public void notifySwitchover(String nodeIp, String localRole, String trigger, String detail) {
        String title = "主备发生切换";
        String body = "主备虚拟 IP 的归属已经发生变化。\n"
                + "涉及节点：" + safe(nodeIp) + "\n"
                + "切换后角色：" + HaRole.labelOf(localRole) + "\n"
                + "触发方式：" + safe(trigger) + "\n"
                + (StringUtils.hasText(detail) ? "详细说明：" + detail.trim() + "\n" : "")
                + "发生时间：" + LocalDateTime.now().format(FORMATTER) + "\n\n"
                + "说明：员工通过统一域名访问，虚拟 IP 漂移对员工无感知，"
                + "在途工单不会丢失（数据实时同步，延迟在秒级）。"
                + "本通知的目的是让管理员知道「发生过一次切换」，以便回溯原因。";
        alertService.alert(AlertLevel.P1, MessageType.HA_SWITCHOVER, title, body,
                "HA_SWITCHOVER",
                "主备切换告警 | node=" + safe(nodeIp) + " | role=" + safe(localRole)
                        + " | trigger=" + safe(trigger));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String displayName(HaNode node) {
        return StringUtils.hasText(node.getNodeName()) ? node.getNodeName().trim() : safe(node.getNodeIp());
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private String format(LocalDateTime time) {
        return time == null ? "从未上报" : time.format(FORMATTER);
    }
}
