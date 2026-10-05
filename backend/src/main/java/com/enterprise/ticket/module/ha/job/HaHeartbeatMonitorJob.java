package com.enterprise.ticket.module.ha.job;

import com.enterprise.ticket.module.ha.service.HaHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 主备心跳刷新与超时判定（，  [174]）
 *
 * <h2>为什么需要一个独立的定时任务，而不是「有人看页面时才算」</h2>
 * <p>需求 [174] 行要求「主节点故障（心跳超时，默认 10 秒）→ 备节点自动升为主节点」，
 * 而虚拟 IP 的实际漂移由 keepalived 完成 —— 本任务<b>不</b>参与接管。
 * 它负责的是另外两件应用层必须自己做的事：
 * <ol>
 *   <li><b>刷新本机心跳</b>：让节点列表里的「本机」行有真实的最后心跳时间。
 *       本进程就是本机心跳的来源 —— 页面能打开就说明它活着；</li>
 *   <li><b>判定对端超时</b>：这是全系统唯一能发现「对端不再上报」的地方。
 *       原因很直白：<b>「没有消息」本身不会产生任何事件</b>。
 *       如果只在收到上报时做处理，那么一台机器静默死掉之后，
 *       应用永远不会知道 —— 页面会一直显示它上一次上报时的健康状态，
 *       那是一个越来越久远的谎言。</li>
 * </ol>
 *
 * <h2>为什么是「跳过任务」而不是「短周期 cron」</h2>
 * <p>{@code fixedDelay} 用 {@code app.ha.heartbeat-scan-interval-ms}（默认 10 秒），
 * 而不是 {@code cron = "*&#47;1 * * * * ?"}：默认的心跳超时阈值就是 10 秒
 * （需求 [174]），而 {@code fixedDelay} 的语义是「上一轮结束后再等 N 毫秒」——
 * 它天然避免了「上一轮还没跑完、下一轮又进来」的重入问题。
 * 每秒扫描一次既没有必要（白白写库），也会在数据库慢时造成任务堆积。
 *
 * <h2>为什么未启用主备时整体跳过</h2>
 * <p>见 {@code HaHeartbeatService#scanAndMarkStale}：单机环境下这张表可能是空的，
 * 继续扫描只会反复执行无意义的更新。判据放在服务层而不是这里，
 * 是为了让「未启用」这条规则只有一处实现 —— 本类保持成纯粹的调度触发器。
 *
 * <h2>幂等性</h2>
 * <p>超时判定用 {@code node_status <> 'ABNORMAL'} 作为闸门（见
 * {@code HaNodeMapper#selectNewlyStale}），因此已经异常的节点不会被重复判定、
 * 重复告警。重复执行只会在「本机心跳」上产生无害的同一值写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HaHeartbeatMonitorJob {

    private final HaHeartbeatService haHeartbeatService;

    /**
     * 扫描间隔由 {@code app.ha.heartbeat-scan-interval-ms} 控制（默认 10 秒）。
     *
     * <p>该值<b>必须明显小于</b>心跳超时阈值：超时判定的实际精度 = 阈值 + 扫描周期，
     * 若把 10 秒的阈值配上 60 秒的扫描周期，真实的断连感知会慢到 70 秒 ——
     * 而页面上写的是「10 秒」，那是一个会让人误判的承诺。
     */
    @Scheduled(fixedDelayString = "${app.ha.heartbeat-scan-interval-ms:10000}")
    public void scan() {
        try {
            int stale = haHeartbeatService.scanAndMarkStale();
            if (stale > 0) {
                log.warn("[主备] 心跳扫描完成：本轮新增 {} 个超时节点", stale);
            }
        } catch (Exception e) {
            // 定时任务异常不得影响调度线程（与其它 job 同一约定）
            log.error("[主备] 心跳扫描失败", e);
        }
    }
}
