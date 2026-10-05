package com.enterprise.ticket.module.ha.dto.vo;

import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.module.ha.entity.HaConfig;

import java.time.LocalDateTime;

/**
 * 主备配置视图（， ）
 *
 * <p>对应页面顶部的「启用开关 + 域名 + 虚拟 IP」与底部的「同步状态」两块。
 *
 * <h2>为什么同步状态复用一个 {@link #syncStateLabel}</h2>
 * <p>界面需要区分「数据一致 / 同步落后 / 同步失败 / 未知」四种展示，
 * 而不是一个布尔。理由见 {@link com.enterprise.ticket.common.constant.HaSyncState} 的类头注释
 * （主从复制短暂落后是常态，一个布尔阈值会让页面每分钟红几百次）。
 *
 * <h2>{@link #lastSyncAt} / {@link #syncDelaySeconds} 的 null 语义</h2>
 * <p>null 表示「从未上报过」，前端必须显示「—」或「未知」，
 * <b>绝不能</b>用 {@code 0} / 当前时间来兜底 —— 那会让「从未同步过」
 * 看起来像「刚刚同步、延迟为零」，把一个真实的故障伪装成健康状态。
 *
 * @param enabled                    是否启用主备
 * @param nodeName                   本机节点名
 * @param nodeIp                     本机 IP
 * @param domain                     员工统一访问的域名（可为空 = 直连 VIP）
 * @param vipWeb                     Web 虚拟 IP
 * @param vipDb                      数据库虚拟 IP
 * @param vrrpIface                  keepalived 网卡名（可为空 = 脚本默认）
 * @param heartbeatTimeoutSeconds    心跳超时（秒）
 * @param syncState                  数据一致性原始码
 * @param syncStateLabel             数据一致性中文名
 * @param syncDelaySeconds           复制延迟（秒）；null = 从未上报
 * @param lastSyncAt                 最后同步时间；null = 从未同步
 * @param lastSwitchAt               最后切换时间；null = 从未切换
 * @param displayAddress             给维护人员看的「员工访问地址」（域名优先，否则 VIP），可为空
 */
public record HaConfigVO(
        boolean enabled,
        String nodeName,
        String nodeIp,
        String domain,
        String vipWeb,
        String vipDb,
        String vrrpIface,
        Integer heartbeatTimeoutSeconds,
        String syncState,
        String syncStateLabel,
        Integer syncDelaySeconds,
        LocalDateTime lastSyncAt,
        LocalDateTime lastSwitchAt,
        String displayAddress
) {

    /**
     * 由实体装配视图。
     *
     * <p>{@code displayAddress} 由服务端拼好下发，而不是让前端按「域名非空则用域名、
     * 否则用 VIP」自己拼 —— 这条规则与部署文档（{@code deploy/DEPLOY.md} 的「域名 + VIP」访问模型）
     * 必须一致，放在服务端才只有一份口径。
     */
    public static HaConfigVO of(HaConfig config) {
        return new HaConfigVO(
                Boolean.TRUE.equals(config.getEnabled()),
                config.getNodeName(),
                config.getNodeIp(),
                config.getDomain(),
                config.getVipWeb(),
                config.getVipDb(),
                config.getVrrpIface(),
                config.getHeartbeatTimeoutSeconds(),
                config.getSyncState(),
                HaSyncState.labelOf(config.getSyncState()),
                config.getSyncDelaySeconds(),
                config.getLastSyncAt(),
                config.getLastSwitchAt(),
                buildDisplayAddress(config));
    }

    /**
     * 员工实际访问的地址串。
     *
     * <p>规则：<b>域名优先</b>。因为需求 [166] 行的诉求是「员工不用管哪台是主」，
     * 而只有域名能在主备切换时保持不变（VIP 会漂移，IP 会变）。
     * 两者都为空时返回 null，由前端显示「尚未配置」。
     */
    private static String buildDisplayAddress(HaConfig config) {
        if (config.getDomain() != null && !config.getDomain().isBlank()) {
            return config.getDomain().trim();
        }
        if (config.getVipWeb() != null && !config.getVipWeb().isBlank()) {
            return config.getVipWeb().trim();
        }
        return null;
    }
}
