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
import com.enterprise.ticket.module.ha.support.HaConfigValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主备心跳与同步状态处理（，  [174] / [182] / [184]）
 *
 * <h2>数据流：脚本 → 应用（内部通道）</h2>
 * <pre>
 *   deploy/ha/scripts/*.sh （跑在目标机器上，无用户会话）
 *        │  POST /api/internal/ha/report   （permit-all 白名单 + X-Internal-Token）
 *        ▼
 *   本类 report()  →  ha_node（节点心跳 / 角色 / 状态）
 *                  →  ha_config（同步状态快照：state / delay / lastSyncAt）
 *                  →  HaAlertNotifier（断连 / 同步失败 / 切换告警）
 *
 *   HaHeartbeatMonitorJob（每 10 秒）
 *        →  刷新本机心跳 + 扫描对端超时 → 置 ABNORMAL + 断连告警
 * </pre>
 *
 * <h2>为什么「心跳」与「超时判定」是两条独立的路径</h2>
 * <p>心跳是<b>事件</b>（对端主动告诉我们它还活着），超时是<b>状态推断</b>
 * （在没有任何消息的情况下，推断对端可能已经死了）。两者必须在不同的地方发生：
 * <ul>
 *   <li>心跳由外部触发（脚本上报），频率由脚本决定；</li>
 *   <li>超时只能由应用自己周期性推断 —— 因为「对端没发消息」这件事本身
 *       <b>不会产生任何事件</b>，没有定时扫描就永远不会被发现。</li>
 * </ul>
 * 把它们合成一个「对端定期来报，不来就报警」的模型是可行的，但那样
 * 「应用自己还活着」这件事就无法被记录 —— 而页面上的「本机运行状态」
 * 正需要它。因此本机心跳由 {@link com.enterprise.ticket.module.ha.job.HaHeartbeatMonitorJob}
 * 自行刷新。
 *
 * <h2>⚠️ 为什么「未登记的 IP 上报心跳」不自动建行</h2>
 * <p>心跳接口在 {@code permit-all} 白名单里（脚本没有会话），只靠共享密钥保护。
 * 若收到未知 IP 就自动插入节点行，那么任何拿到密钥的人（或者密钥泄漏后）都能
 * 通过反复上报把节点列表刷成一堆垃圾行 —— 而节点列表是维护人员用来判断
 * 「我的两台机器状态如何」的唯一视图，被污染之后这个视图就失去了可信度。
 * 因此：<b>节点行只能由管理员在本页显式添加</b>；心跳只更新已存在的行。
 * 唯一例外是本机行 IP 为空时的回填（见 {@link #adoptLocalIp}），
 * 那是把「已经存在的本机身份」补齐，不产生新行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HaHeartbeatService {

    private static final String EVENT_HEARTBEAT = "HEARTBEAT";
    private static final String EVENT_SWITCHOVER = "SWITCHOVER";

    private final HaConfigMapper haConfigMapper;
    private final HaNodeMapper haNodeMapper;
    private final HaAlertNotifier alertNotifier;

    // ------------------------------------------------------------------
    // 上报处理
    // ------------------------------------------------------------------

    /**
     * 处理一次脚本上报（心跳 / 切换事件）。
     *
     * @throws BusinessException 缺少节点 IP
     */
    @Transactional(rollbackFor = Exception.class)
    public void report(HaHeartbeatRequest request) {
        String ip = trim(request.getNodeIp());
        if (!StringUtils.hasText(ip)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上报内容缺少节点 IP（nodeIp）");
        }
        String event = StringUtils.hasText(request.getEvent())
                ? request.getEvent().trim().toUpperCase(java.util.Locale.ROOT) : EVENT_HEARTBEAT;

        HaConfig config = currentConfig();
        HaNode node = resolveNode(ip, config);
        if (node == null) {
            // 未登记的节点：只记日志，不建行（见类头注释）
            log.warn("[主备] 收到未登记节点的心跳，已忽略：ip={}（请先在本页「添加备节点」中登记）", ip);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // 角色：只在确实带了合法值时才改。空值表示「本次不更新」——
        // 心跳每秒都在发，而角色几十秒才变一次；用空值覆盖会让角色在页面上闪回 STANDBY
        if (HaRole.of(trim(request.getRole())) != null && !request.getRole().trim().equals(node.getNodeRole())) {
            haNodeMapper.updateRole(node.getId(), request.getRole().trim());
            node.setNodeRole(request.getRole().trim());
        }

        // 状态：报心跳就说明它活着 —— 主节点=运行中，备节点=待命
        String status = HaRole.MASTER.name().equals(node.getNodeRole())
                ? HaNodeStatus.RUNNING.name() : HaNodeStatus.STANDBY.name();
        boolean recovered = HaNodeStatus.ABNORMAL.name().equals(node.getNodeStatus());
        haNodeMapper.touchHeartbeat(node.getId(), status, now);
        if (recovered) {
            // 恢复刻意不发告警：需求 [184] 只要求「断连 / 同步失败 / 切换」三类。
            // 再加一条「已恢复」会让告警条数翻倍，而它的信息量很低
            // —— 页面上的状态变绿已经说明了一切
            log.info("[主备] 节点已恢复心跳：ip={}（{} → {}）", ip, HaNodeStatus.ABNORMAL.name(), status);
        }

        applySyncSnapshot(config, request);

        if (EVENT_SWITCHOVER.equals(event)) {
            handleSwitchoverEvent(config, node, request);
        }

        log.debug("[主备] 心跳已处理：ip={} event={} role={} status={}", ip, event, node.getNodeRole(), status);
    }

    /**
     * 同步状态快照回填。
     *
     * <p>只有在本次上报<b>确实带了</b>同步信息时才写库：
     * 心跳每秒一发，而复制状态通常几十秒才查一次（查一次要连 MySQL 执行
     * {@code SHOW REPLICA STATUS}）。若每次心跳都覆盖，页面上
     * 「数据是否一致 / 同步延迟」会在一秒内反复跳变，维护人员根本无法读数。
     */
    private void applySyncSnapshot(HaConfig config, HaHeartbeatRequest request) {
        HaSyncState reported = HaSyncState.of(trim(request.getSyncState()));
        boolean hasSnapshot = reported != null
                || request.getDelaySeconds() != null
                || request.getLastSyncAt() != null;
        if (!hasSnapshot) {
            return;
        }

        HaSyncState previous = HaSyncState.of(trim(config.getSyncState()));
        HaSyncState next = reported != null ? reported : HaSyncState.UNKNOWN;

        haConfigMapper.updateSyncSnapshot(config.getId(), next.name(),
                request.getDelaySeconds(),
                request.getLastSyncAt() != null ? request.getLastSyncAt() : config.getLastSyncAt());

        // ⚠️ 幂等闸门：只在「跃迁到失败」的那一次告警。
        //    没有这个判断，复制中断期间每一轮上报都会推一条消息，
        //    几分钟就能把消息中心刷成一片红 —— 真正需要被看到的告警会被自己制造的噪音埋掉。
        if (next.needsAlert() && previous != HaSyncState.FAILED) {
            alertNotifier.notifySyncFailed(request.getNodeIp(), next.name(),
                    request.getDelaySeconds(), "由节点上报触发（上一次状态：" + label(previous) + "）");
        }
        log.info("[主备] 同步状态更新：{} → {}，延迟 {}",
                label(previous), next.name(), request.getDelaySeconds() == null ? "未知" : request.getDelaySeconds() + "s");
    }

    /**
     * 切换事件处理。
     *
     * <p>需求 [184] 要求「切换发生时通知管理员」，且这个通知<b>必须与真实切换绑定</b> ——
     * 只有 keepalived 真的完成了接管，它才会触发 {@code notify_master} 钩子、
     * 脚本才会把这条事件报上来。因此本方法收到的每一条 SWITCHOVER 都对应一次真实切换，
     * 不存在演练模式带来的误报（演练模式下脚本根本不会被执行，见
     * {@code HaDeploySupport#execute}）。
     */
    private void handleSwitchoverEvent(HaConfig config, HaNode node, HaHeartbeatRequest request) {
        LocalDateTime now = LocalDateTime.now();
        haConfigMapper.markSwitched(config.getId(), now);

        String trigger = StringUtils.hasText(request.getTrigger())
                ? request.getTrigger().trim() : "未上报（脚本未说明触发原因）";
        alertNotifier.notifySwitchover(node.getNodeIp(), node.getNodeRole(), trigger,
                "由节点上的 keepalived 通知脚本上报。切换后该节点的角色为 "
                        + HaRole.labelOf(node.getNodeRole()) + "。");
        log.warn("[主备] 收到切换事件：ip={} role={} trigger={}", node.getNodeIp(), node.getNodeRole(), trigger);
    }

    // ------------------------------------------------------------------
    // 超时扫描（由 HaHeartbeatMonitorJob 每 10 秒调用）
    // ------------------------------------------------------------------

    /**
     * 刷新本机心跳并判定对端超时。
     *
     * <p>未启用主备时<b>直接返回</b>：单机环境下这张表里可能只有一行本机节点，
     * 或者一行都没有。继续扫描会做两件无意义的事 ——
     * 每秒写一次库，以及在没有心跳脚本的单机环境里把本机之外的一切判成异常。
     *
     * @return 本轮新判定为异常的节点数
     */
    @Transactional(rollbackFor = Exception.class)
    public int scanAndMarkStale() {
        HaConfig config = currentConfig();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        refreshLocalHeartbeat(now);

        int timeout = HaConfigValidator.normalizeHeartbeat(config.getHeartbeatTimeoutSeconds());
        LocalDateTime cutoff = now.minusSeconds(timeout);
        List<HaNode> staleNodes = haNodeMapper.selectNewlyStale(cutoff);
        for (HaNode node : staleNodes) {
            haNodeMapper.markAbnormal(node.getId());
            alertNotifier.notifyNodeDisconnected(node, timeout);
        }
        if (!staleNodes.isEmpty()) {
            log.warn("[主备] 本轮判定 {} 个节点心跳超时（阈值 {} 秒）", staleNodes.size(), timeout);
        }
        return staleNodes.size();
    }

    /**
     * 刷新本机心跳。
     *
     * <p>本机节点的状态由本进程自己的运行事实决定（页面能打开就说明本进程活着），
     * 因此这里直接按角色映射状态：主节点=运行中、备节点=待命。
     * 角色为空（历史脏数据）时落 {@code UNKNOWN}，而不是随便挑一个 ——
     * 挑错会让「当前节点角色」显示一个不存在的结论。
     */
    private void refreshLocalHeartbeat(LocalDateTime now) {
        HaNode local = haNodeMapper.selectLocal();
        if (local == null) {
            return;
        }
        String role = local.getNodeRole();
        String status = HaRole.MASTER.name().equals(role) ? HaNodeStatus.RUNNING.name()
                : HaRole.STANDBY.name().equals(role) ? HaNodeStatus.STANDBY.name()
                : HaNodeStatus.UNKNOWN.name();
        haNodeMapper.touchHeartbeat(local.getId(), status, now);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 按 IP 定位节点；未登记时返回 {@code null}（调用方据此忽略本次上报）。
     *
     * <p>唯一的例外是本机行 IP 为空的情形（容器化部署探测不到地址，
     * 见 {@code HaLocalAddress} 类头注释）：此时把上报的 IP 认领为本机 IP，
     * 因为「本机行已经存在、只是缺地址」与「来了一个陌生 IP」是两件不同的事。
     */
    private HaNode resolveNode(String ip, HaConfig config) {
        HaNode node = haNodeMapper.selectByIp(ip);
        if (node != null) {
            return node;
        }
        HaNode local = haNodeMapper.selectLocal();
        if (local != null && !StringUtils.hasText(local.getNodeIp())) {
            return adoptLocalIp(local, ip, config);
        }
        return null;
    }

    /** 把首次上报的 IP 认领为本机 IP（不新建行，只补齐已存在的本机行） */
    private HaNode adoptLocalIp(HaNode local, String ip, HaConfig config) {
        haNodeMapper.updateIp(local.getId(), ip);
        haConfigMapper.updateLocalIdentity(config.getId(), local.getNodeName(), ip);
        local.setNodeIp(ip);
        log.info("[主备] 本机节点 IP 已由心跳上报回填：ip={}（登记时未能自动探测到地址）", ip);
        return local;
    }

    private HaConfig currentConfig() {
        HaConfig config = haConfigMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<HaConfig>lambdaQuery()
                        .orderByAsc(HaConfig::getId)
                        .last("LIMIT 1"));
        if (config == null) {
            // 正常路径下 V38 已播种；此处只做「不抛异常」的最小兜底 ——
            // 心跳服务不该因为配置行缺失就让脚本收到 500 并无限重试
            HaConfig fallback = new HaConfig();
            fallback.setId(null);
            fallback.setEnabled(false);
            fallback.setSyncState(HaSyncState.UNKNOWN.name());
            fallback.setHeartbeatTimeoutSeconds(HaConfigValidator.DEFAULT_HEARTBEAT_SECONDS);
            return fallback;
        }
        return config;
    }

    private String label(HaSyncState state) {
        return state == null ? "未上报" : state.getLabel();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
