package com.enterprise.ticket.module.ha.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主备心跳 / 事件上报—— <b>内部通道</b>，非登录用户调用
 *
 * <p>由 {@code deploy/ha/scripts/} 下的运维脚本 POST 到
 * {@code /api/internal/ha/report}，与备份上报纸承同一套安全模型
 * （{@code permit-all} 白名单 + {@code X-Internal-Token} 共享密钥 + 未配置即 fail-closed），
 * 见 {@code InternalAlertController} 的类头注释。
 *
 * <h2>为什么心跳与切换共用一个接口，而不是各开一个</h2>
 * <p>它们在脚本侧是<b>同一个循环里的相邻两步</b>：keepalived 的
 * {@code notify_master} / {@code notify_backup} 钩子在切换的瞬间触发，
 * 而心跳循环每秒都在跑。若拆成两个接口，脚本要维护两套鉴权与重试逻辑，
 * 而这两套逻辑只要有一处写错，表现都是「切换没通知到」——
 * 恰恰是最不该失败的那条路径。合成一个接口、用一个 {@link #event} 字段区分，
 * 鉴权与失败重试就只有一份实现。
 *
 * <h2>字段刻意保持宽松（不启用强校验）</h2>
 * <p>与 {@code BackupAlertRequest} 同理由：告警通道的首要目标是
 * 「把消息送到」。脚本在异常路径上可能只拿得到部分上下文
 * （例如 keepalived 刚把它拉起、复制状态还没查出来），
 * 此时少一个字段也应该照样记录，而不是因为参数校验被整体拒绝 ——
 * 那等于把「不完整心跳」变成「完全没有心跳」，页面直接变红。
 *
 * <h2>关于 @Data + null 的取值约定</h2>
 * <p>{@link #syncState} / {@link #delaySeconds} / {@link #lastSyncAt} 为 null 时
 * 表示<b>本次上报没有携带同步信息</b>，服务层会保留库中已有的快照不动
 * （而不是用 null 覆盖成「未知」）—— 心跳每秒都在发，而复制状态通常几十秒才查一次，
 * 若每次心跳都覆盖，页面上「数据是否一致」会在一秒内反复跳变。
 */
@Data
public class HaHeartbeatRequest {

    /** 事件类型：HEARTBEAT（默认，心跳）/ SWITCHOVER（切换发生） */
    private String event;

    /** 上报方所在节点的 IP（用于对齐 {@code ha_node} 的行） */
    private String nodeIp;

    /** 上报方节点名（节点尚未登记时可据此补建） */
    private String nodeName;

    /** 上报方当前角色：MASTER / STANDBY */
    private String role;

    /**
     * 切换触发方式（仅 {@code event=SWITCHOVER} 时有意义）：如「自动接管」「计划性维护」。
     *
     * <p>为什么由脚本上报而不是后端猜：同一条 {@code notify_master} 钩子在
     * 「主节点宕机导致的对端接管」与「管理员执行 switchover.sh 后的主动让出」
     * 两种场景下都会触发，而它们的运维含义完全不同（前者是故障、后者是计划内动作）。
     * 后端看不到触发原因，只能由发起方告知；为空时后端会如实记为「未上报」，
     * 而不是擅自填一个「自动切换」—— 那会把计划内维护误报成故障。
     */
    private String trigger;

    /** 数据一致性：IN_SYNC / LAGGING / FAILED / UNKNOWN；null = 本次不更新 */
    private String syncState;

    /** 复制延迟（秒）；null = 本次不更新 */
    private Integer delaySeconds;

    /** 最近一次成功同步时间；null = 本次不更新 */
    private LocalDateTime lastSyncAt;
}
