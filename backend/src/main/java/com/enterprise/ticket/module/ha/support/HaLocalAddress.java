package com.enterprise.ticket.module.ha.support;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 本机 IPv4 地址探测
 *
 * <h2>为什么需要它，以及它为什么只是「提示」而不是「真相」</h2>
 * <p>生成主备部署片段时，{@code HA_NODE_IP} 是必填的一行，而系统本身并不知道
 * 自己跑在哪台机器的哪个网卡上 —— 只能去问操作系统。于是就有了本类。
 *
 * <p>但必须诚实说明它的<b>局限</b>：
 * <ul>
 *   <li><b>容器化部署时</b>，本类拿到的是<b>容器网段</b>地址（如 {@code 172.17.0.x}），
 *       而不是宿主机的业务 IP —— 因为容器看到的网络命名空间就是它自己那一份；</li>
 *   <li><b>多网卡机器</b>上可能有多个候选地址，本类只能给出启发式排序（见下），
 *       无法知道哪一个才是「业务网卡」；</li>
 *   <li><b>systemd 直接部署</b>（本项目 HA 的生产推荐形态，见 {@code deploy/ha/.env.ha.example}
 *       的 {@code UPGRADE_DEPLOY_MODE=systemd}）下，探测结果<b>通常就是正确的业务 IP</b>。</li>
 * </ul>
 *
 * <p>因此本类的产出在前端一律标注「自动探测，请核对」，而<b>不</b>直接当作既成事实写入。
 * 这与本项目对「虚拟 IP / 网卡名」的处理原则一致：应用的判断只覆盖「确定的事实」，
 * 关于真实网络拓扑的最终确认交给 {@code preflight-ha.sh} 与维护人员
 * （见 {@code HaConfigValidator} 类头注释「刻意不做的校验」）。
 *
 * <h2>排序规则</h2>
 * <p>按「最可能是业务地址」的顺序返回候选：私网地址优先于公网地址，
 * 且在私网内部，{@code 192.168.x} / {@code 10.x} / {@code 172.16-31.x}
 * 依次优先 —— 这覆盖了绝大多数企业内网的地址规划习惯。
 * 容器网段（{@code 172.17.0.0/16}，Docker 默认 bridge）刻意排在最后：
 * 它最容易被误当成业务地址，而实际上出了这台机器就不可路由。
 */
@Slf4j
public final class HaLocalAddress {

    private HaLocalAddress() {
    }

    /**
     * 探测本机最可能的业务 IPv4 地址。
     *
     * @return 地址字符串；一个都没探测到时返回空（此时前端提示维护人员手工填写）
     */
    public static Optional<String> detectIpv4() {
        List<String> candidates = candidates();
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /** 全部候选地址（已按「最可能是业务地址」排序），供页面展示与人工挑选 */
    public static List<String> candidates() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                // 跳过已关闭 / 回环接口：回环地址（127.0.0.1）永远不是业务地址，
                // 而把 127.0.0.1 填进 HA_NODE_IP 会让 keepalived 的
                // unicast_src_ip 指向本机回环，两台机器永远握不上手
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (ip != null && !ip.isBlank() && !result.contains(ip)) {
                        result.add(ip);
                    }
                }
            }
        } catch (SocketException e) {
            // 探测失败不是错误：返回空列表，界面提示手工填写即可。
            // 绝不能因为「探测不到 IP」而让主备配置页打不开
            log.warn("[主备] 探测本机网络地址失败（不影响配置页使用）：{}", e.getMessage());
        }
        result.sort((a, b) -> Integer.compare(score(b), score(a)));
        return result;
    }

    /**
     * 「像业务地址」的程度打分，越大越像。
     *
     * <p>刻意用打分 + 排序，而不是「返回第一个非回环地址」：后者的结果取决于
     * 网卡枚举顺序（与操作系统、驱动、网卡插入顺序都有关），
     * 在多网卡机器上会时对时错 —— 而「时对时错」正是最难被发现的缺陷。
     */
    private static int score(String ip) {
        if (ip.startsWith("192.168.")) {
            return 100;
        }
        if (ip.startsWith("10.")) {
            return 90;
        }
        if (is172Private(ip)) {
            // Docker 默认 bridge 网段（172.17.0.0/16）最容易被误认为业务地址，压到最低
            return ip.startsWith("172.17.") ? 10 : 80;
        }
        if (ip.startsWith("169.254.")) {
            // 链路本地地址：说明 DHCP 没拿到地址，它连不上任何东西
            return 1;
        }
        return 50;
    }

    private static boolean is172Private(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
