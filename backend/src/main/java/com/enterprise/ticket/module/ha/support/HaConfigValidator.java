package com.enterprise.ticket.module.ha.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 主备配置校验—— <b>纯函数</b>，便于单测穷尽覆盖
 *
 * <h2>为什么必须有这一层</h2>
 * <p>主备配置是典型「填错了不报错、只在真出事时才暴露」的场景：
 * 虚拟 IP 写成一个不存在的地址、网卡名拼错一个字母、心跳超时设成 1 秒 ——
 * 这些都不会让应用启动失败，只会表现为「主节点挂掉后备节点不接管」，
 * 而那件事发生的时候，往往正是最需要它工作的时候（半夜、无人值守）。
 * 因此凡是不需要连机器就能判定的问题，一律在保存入口拦住。
 *
 * <h2>刻意不做的校验（以及为什么）</h2>
 * <ol>
 *   <li><b>不校验虚拟 IP 是否在本机网段 / 是否与业务 IP 冲突。</b>
 *       这需要读取本机网络接口，而在容器里读到的往往是容器网段，
 *       与宿主真实拓扑不同 —— 在这种信息基础上做判断，误报的概率高于漏报。
 *       正确的做法由 {@code deploy/ha/scripts/preflight-ha.sh} 在<b>目标机器上</b>做
 *       （它能拿到真实的 {@code ip addr} 与路由表）。</li>
 *   <li><b>不校验网卡名在本机是否存在。</b>同理：容器视角不可信。
 *       只校验名字的<b>形状</b>合法性，存在性交给 {@code preflight-ha.sh}。</li>
 *   <li><b>不校验域名是否真的解析到虚拟 IP。</b>那需要一次 DNS 查询，
 *       而保存配置时 DNS 多半还没配好（正确的顺序是先在这里填，再去改 DNS）。
 *       把它做成保存前置条件，会导致「必须先改 DNS 才能保存配置」这种无法自举的循环。</li>
 * </ol>
 * 这三条都指向同一个原则：<b>应用只校验「确定的、本地的、格式层面的」事实；
 * 「关于这台机器真实网络环境」的判断，交给跑在那台机器上的脚本。</b>
 */
public final class HaConfigValidator {

    private HaConfigValidator() {
    }

    /**
     * 心跳超时默认值 —— 需求文档 [174] 行原文写死为 10 秒，照抄不擅自改动。
     */
    public static final int DEFAULT_HEARTBEAT_SECONDS = 10;

    /**
     * 心跳超时下限 3 秒。
     *
     * <p>为什么不允许 1~2 秒：{@code keepalived} 模板里
     * {@code vrrp_script chk_ticket} 的 {@code interval 5} / {@code fall 2}，
     * 意味着单次健康判定本身就要 10 秒才成立。心跳阈值若小于这个量级，
     * 会出现「应用层判定断连、但 keepalived 还没判故障」的两套口径打架：
     * 页面已经标红报「节点断连」，而 VIP 还在原机上跑得好好的。
     * 让两套判据处在同一量级，界面与真实接管行为才不会互相矛盾。
     */
    public static final int MIN_HEARTBEAT_SECONDS = 3;

    /** 心跳超时上限 10 分钟：再长就等于「不检测」了 */
    public static final int MAX_HEARTBEAT_SECONDS = 600;

    /** VRRP 认证口令长度上限（keepalived PASS 认证上限 8 字符，超长会被静默截断） */
    public static final int HA_VRRP_AUTH_MAX_LENGTH = 8;

    /**
     * 校验配置保存请求。
     *
     * @param request         提交的配置
     * @param requireComplete 是否要求配置完整（启用时为 true）
     * @throws BusinessException {@link ErrorCode#PARAM_INVALID} 或 {@link ErrorCode#HA_CONFIG_INCOMPLETE}
     */
    public static void validateConfig(HaConfigRequest request, boolean requireComplete) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少主备配置内容");
        }

        // ---------------- 格式校验（总是执行） ----------------
        Integer timeout = request.getHeartbeatTimeoutSeconds();
        if (timeout != null && (timeout < MIN_HEARTBEAT_SECONDS || timeout > MAX_HEARTBEAT_SECONDS)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "心跳超时取值范围为 " + MIN_HEARTBEAT_SECONDS + "-" + MAX_HEARTBEAT_SECONDS + " 秒"
                            + "（需求建议 " + DEFAULT_HEARTBEAT_SECONDS + " 秒；不要设得比 keepalived 健康检查更快，"
                            + "否则页面判定断连时 VIP 可能还没漂移）");
        }

        String domain = trimToNull(request.getDomain());
        if (domain != null && !isValidDomain(domain)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "域名格式不正确（应形如 oa.company.com，不含 http:// 与端口）：" + domain);
        }

        String vipWeb = trimToNull(request.getVipWeb());
        if (vipWeb != null && !isValidIpv4(vipWeb)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "Web 虚拟 IP 格式不正确（当前仅支持 IPv4，如 192.168.1.100）：" + vipWeb);
        }
        String vipDb = trimToNull(request.getVipDb());
        if (vipDb != null && !isValidIpv4(vipDb)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "数据库虚拟 IP 格式不正确（当前仅支持 IPv4，如 192.168.1.101）：" + vipDb);
        }
        if (vipWeb != null && vipWeb.equals(vipDb)) {
            // 两个 VIP 相同 = 两个 vrrp_instance 抢同一个地址，必然脑裂。
            // 这是纯格式层面的自相矛盾，不需要任何网络信息就能判定，因此必须拦下。
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "Web 虚拟 IP 与数据库虚拟 IP 不能相同 —— 它们是两个相互独立的 vrrp_instance，"
                            + "用同一个地址会让两套实例互相抢占（脑裂）");
        }

        String iface = trimToNull(request.getVrrpIface());
        if (iface != null && !isValidIface(iface)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "网卡名格式不正确（只允许字母、数字、点、下划线、连字符，如 eth0 / ovs_eth0）：" + iface);
        }

        // ---------------- 完整性校验（仅启用时） ----------------
        if (!requireComplete) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (vipWeb == null) {
            missing.add("Web 虚拟 IP");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.HA_CONFIG_INCOMPLETE,
                    "启用主备前请先补全：" + String.join("、", missing)
                            + "。员工统一访问这个地址，主备切换时它在两台机器之间漂移。");
        }
    }

    /**
     * 校验备节点 IP（添加备节点时调用）。
     *
     * <p>只接受 IPv4：{@code keepalived} 模板的 {@code virtual_ipaddress}、
     * 以及 {@code .env.ha} 的 {@code HA_PEER_IP} 在当前部署资产里都是 IPv4 形态，
     * 放一个 IPv6 进来会在脚本渲染阶段才炸，而那时维护人员已经离开了页面。
     *
     * @throws BusinessException {@link ErrorCode#HA_NODE_IP_INVALID}
     */
    public static void validateNodeIp(String nodeIp) {
        String ip = trimToNull(nodeIp);
        if (ip == null) {
            throw new BusinessException(ErrorCode.HA_NODE_IP_INVALID, "请填写备节点 IP 地址");
        }
        if (!isValidIpv4(ip)) {
            throw new BusinessException(ErrorCode.HA_NODE_IP_INVALID,
                    "备节点 IP 格式不正确（当前仅支持 IPv4，如 192.168.1.101）：" + ip);
        }
    }

    /**
     * 心跳超时兜底（钳到合法区间）。
     *
     * <p><b>它作用于「从库里读出来的值」，不作用于「提交上来的值」</b> ——
     * 提交值先过 {@link #validateConfig}，越界会被明确拒绝（与 AD 配置同口径）。
     * 读库路径必须钳制，因为库里若残留 {@code 0}（人工 SQL、旧版本写入），
     * 超时判定会变成「任何时刻都判定断连」，页面全红且真实原因无法从界面看出。
     */
    public static int normalizeHeartbeat(Integer seconds) {
        if (seconds == null) {
            return DEFAULT_HEARTBEAT_SECONDS;
        }
        return Math.max(MIN_HEARTBEAT_SECONDS, Math.min(seconds, MAX_HEARTBEAT_SECONDS));
    }

    /**
     * 是否为合法 IPv4。
     *
     * <p>逐段解析而不是用正则：正则版本要么过宽（接受 {@code 999.1.1.1}），
     * 要么写得极其难读（四段 0-255 的完整展开）。逐段判断既准确又自解释。
     * 刻意拒绝前导零（{@code 010.1.1.1}）—— 部分系统会把它当八进制解析，
     * 从而指向一个与填写者预期完全不同的地址。
     */
    public static boolean isValidIpv4(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否为可用的域名。
     *
     * <p>不接受 {@code http://} 前缀与端口：这两个都会让「DNS 解析到 VIP」这条链路的
     * 含义发生变化（前者是 URL、后者是 host:port），而本字段要的是纯粹的<b>主机名</b>。
     * 校验层明确拒绝，比在部署文档里写一句「不要带 http://」更可靠。
     */
    public static boolean isValidDomain(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String domain = value.trim();
        if (domain.contains("://") || domain.contains("/") || domain.contains(":")
                || domain.contains(" ")) {
            return false;
        }
        if (domain.length() > 128 || !domain.contains(".")) {
            return false;
        }
        // 标签：字母/数字开头结尾，中间可有连字符；不用正则的完整 RFC 1123 表达，
        // 是因为内网常见下划线主机名（如 oa_test.company.local）需要被接受
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_';
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 网卡名格式：Linux 接口名允许字母/数字/点/下划线/连字符（覆盖 eth0 / ovs_eth0 / enp0s3 / br-lan） */
    public static boolean isValidIface(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
