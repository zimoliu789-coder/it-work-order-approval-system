package com.enterprise.ticket.module.ha.job;

import com.enterprise.ticket.module.ha.entity.HaConfig;
import com.enterprise.ticket.module.ha.entity.HaNode;
import com.enterprise.ticket.module.ha.mapper.HaConfigMapper;
import com.enterprise.ticket.module.ha.mapper.HaNodeMapper;
import com.enterprise.ticket.module.ha.support.HaLocalAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 主备心跳的**后端内置上报**。
 *
 * <h2>为什么要把心跳从外部脚本搬进后端</h2>
 * 用户的原话是「管理员不用复制 .env 文件、不用手动跑 preflight 和 setup-replication 脚本」。
 * 改造前心跳靠目标机上的 {@code deploy/ha/scripts/ha-heartbeat.sh} + systemd timer ——
 * 那意味着「要装 HA，先得会装一个 systemd 定时任务」，与「一键启用」直接冲突。
 * 搬进后端之后：**应用跑起来，心跳就在跑**。
 *
 * <p>外部脚本因此**降级为可选**（想用也可以，两条心跳不冲突：上报是幂等的「更新最后心跳时间」）。
 *
 * <h2>上报方向</h2>
 * 本机 → 每个已登记的**对端节点**的 {@code /api/internal/ha/report}。
 * 认证用与外部脚本完全相同的 {@code X-Internal-Token}
 * （= {@code INTERNAL_ALERT_TOKEN}，两台机器必须逐字一致 —— 这是部署时的硬约束，
 * 不一致的表现是「节点状态一直不更新」，故失败时日志里明确写出这一点）。
 *
 * <h2>失败只记日志，绝不抛</h2>
 * 对端不可达是**主备场景下的正常现象**（对端就是可能宕机）。
 * 抛异常会让调度线程受影响，而「对端不通」这件事本身由
 * {@code HaHeartbeatMonitorJob} 的超时判定负责发现 —— 两条路径职责不同，不要混。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HaHeartbeatReportJob {

    private final HaConfigMapper haConfigMapper;
    private final HaNodeMapper haNodeMapper;

    /** 与外部脚本共用同一个令牌；两台机器不一致时心跳会 403，日志里会点明 */
    @Value("${app.ops.internal-alert-token:}")
    private String internalToken;

    /** 复用同一个 HttpClient：每次新建会重复建连接池与线程池，10 秒一次会很快耗光资源 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 上报本机心跳到所有对端节点。
     *
     * <p>间隔由 {@code app.ha.heartbeat-report-interval-ms} 控制（默认 10 秒）——
     * 必须**明显小于**心跳超时阈值，否则对端会把「还在正常上报的本机」判成断连。
     * 用 {@code fixedDelay}（上一轮结束再等）天然避免重入，与其它任务同一取舍。
     */
    @Scheduled(fixedDelayString = "${app.ha.heartbeat-report-interval-ms:10000}")
    public void report() {
        try {
            HaConfig config = haConfigMapper.selectOne(null);
            if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
                // 未启用主备：单机环境下没有对端，跳过（不是错误）
                return;
            }
            if (!StringUtils.hasText(internalToken)) {
                // 未配置令牌时**不发**：发了必然 403，只会在对端日志里刷错误
                log.debug("[主备] 未配置 INTERNAL_ALERT_TOKEN，跳过后端内置心跳上报（可改用外部脚本）");
                return;
            }
            List<HaNode> peers = haNodeMapper.selectList(null).stream()
                    .filter(node -> !Boolean.TRUE.equals(node.getIsLocal()))
                    .filter(node -> StringUtils.hasText(node.getNodeIp()))
                    .toList();
            if (peers.isEmpty()) {
                return;
            }
            String localIp = HaLocalAddress.detectIpv4().orElse(config.getNodeIp());
            String body = buildBody(localIp, config.getNodeName(), localRole(config));
            for (HaNode peer : peers) {
                push(peer.getNodeIp(), body);
            }
        } catch (Exception e) {
            // 定时任务异常不得影响调度线程（与其它 job 同一约定）
            log.warn("[主备] 心跳上报失败：{}", e.getMessage());
        }
    }

    /**
     * 本机角色：本机节点行的 node_role；没有本机行时按 MASTER 兜底。
     *
     * <p>兜底成 MASTER 而不是 STANDBY：主节点是「默认那个一直在服务的」，
     * 而把一台其实在服务的主机报成备节点，会让对端的切换判定做出错误决策。
     */
    private String localRole(HaConfig config) {
        HaNode local = haNodeMapper.selectLocal();
        if (local != null && StringUtils.hasText(local.getNodeRole())) {
            return local.getNodeRole();
        }
        return "MASTER";
    }

    private String buildBody(String localIp, String nodeName, String role) {
        return "{"
                + "\"event\":\"HEARTBEAT\","
                + "\"nodeIp\":" + json(localIp) + ","
                + "\"nodeName\":" + json(nodeName) + ","
                + "\"role\":" + json(role)
                + "}";
    }

    private void push(String peerIp, String body) {
        // 端口固定用本机同端口：主备两台部署的是同一个应用，端口由部署约定保持一致
        String url = "http://" + peerIp + ":8080/api/internal/ha/report";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                // 403 只有一个原因：两台机器的 INTERNAL_ALERT_TOKEN 不一致。
                // 把它点明，否则维护人员会往「网络不通」的方向查很久。
                log.warn("[主备] 心跳被对端拒绝（403）：peer={} —— 请确认两台机器的 "
                        + "INTERNAL_ALERT_TOKEN 逐字一致", peerIp);
            } else if (response.statusCode() >= 400) {
                log.warn("[主备] 心跳上报异常：peer={} status={}", peerIp, response.statusCode());
            }
        } catch (Exception e) {
            // 对端不可达是主备场景下的正常现象，交给超时判定去发现，这里只记 debug 级
            log.debug("[主备] 心跳未送达：peer={} 原因={}", peerIp, e.getMessage());
        }
    }

    /** 极简 JSON 字符串转义（只处理引号与反斜杠，够用于 IP / 主机名） */
    private String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
