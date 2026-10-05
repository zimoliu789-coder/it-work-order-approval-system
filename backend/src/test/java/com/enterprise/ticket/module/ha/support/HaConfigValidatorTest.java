package com.enterprise.ticket.module.ha.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主备配置校验（Phase 19 批次 G）—— <b>纯函数</b>穷尽覆盖
 *
 * <h2>本类守的是什么</h2>
 * <p>主备配置是「填错了不报错、只在真出事时才暴露」的典型：虚拟 IP 写错、
 * 网卡名拼错、心跳阈值设成 1 秒 —— 都不会让应用启动失败，只会表现为
 * 「主节点挂掉后备节点不接管」。而这些错都需要在<b>保存那一刻</b>被拦住，
 * 因为保存之后，下一次看到它多半就是半夜的故障。
 *
 * <h2>为什么断言的颗粒度这么细</h2>
 * <p>校验器里每一条规则都对应一个真实的部署事故形态。测试逐条钉住它们，
 * 是为了让「有人顺手放宽一个区间」这类改动立刻变红，而不是等到回归阶段
 * 靠人工比对才发现 —— 那时错误的配置可能已经写进演示库了。
 */
@DisplayName("主备配置校验（纯函数）")
class HaConfigValidatorTest {

    /** 一份完整且合法的配置，便于各用例按需破坏单个字段 */
    private HaConfigRequest complete() {
        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setNodeName("ticket-master");
        request.setDomain("oa.company.com");
        request.setVipWeb("192.168.1.100");
        request.setVipDb("192.168.1.101");
        request.setVrrpIface("eth0");
        request.setHeartbeatTimeoutSeconds(HaConfigValidator.DEFAULT_HEARTBEAT_SECONDS);
        return request;
    }

    private ErrorCode codeOf(HaConfigRequest request, boolean requireComplete) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> HaConfigValidator.validateConfig(request, requireComplete));
        return ex.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 常量契约（改动即代表需求被改，因此单测钉住）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认心跳超时锁死为 10 秒（需求书 [174] 原文写死，不擅自改动）")
    void defaultHeartbeatIsLocked() {
        assertEquals(10, HaConfigValidator.DEFAULT_HEARTBEAT_SECONDS,
                "需求书原文为 10 秒；改这个数字等于改需求，不应由重构顺手带过");
        assertEquals(3, HaConfigValidator.MIN_HEARTBEAT_SECONDS);
        assertEquals(600, HaConfigValidator.MAX_HEARTBEAT_SECONDS);
        assertEquals(8, HaConfigValidator.HA_VRRP_AUTH_MAX_LENGTH,
                "keepalived PASS 认证上限 8 字符，超长会被静默截断");
    }

    // ------------------------------------------------------------------
    // 格式校验：心跳阈值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整合法配置（启用态）校验通过")
    void completeConfigPasses() {
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(complete(), true));
    }

    @Test
    @DisplayName("心跳阈值边界：3 / 10 / 600 通过，2 与 601 被拒")
    void heartbeatBoundaries() {
        HaConfigRequest request = complete();

        request.setHeartbeatTimeoutSeconds(3);
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, false));

        request.setHeartbeatTimeoutSeconds(600);
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, false));

        // 2 秒：比 keepalived 的健康检查（interval 5 / fall 2）还快，
        // 会出现「页面判红说断连、VIP 其实还没漂移」的两套口径打架
        request.setHeartbeatTimeoutSeconds(2);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, false));

        request.setHeartbeatTimeoutSeconds(601);
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, false));
    }

    @Test
    @DisplayName("心跳阈值越界的报错文案要点出合法区间（维护人员据此自行修正）")
    void heartbeatOutOfRangeMessageMentionsRange() {
        HaConfigRequest request = complete();
        request.setHeartbeatTimeoutSeconds(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> HaConfigValidator.validateConfig(request, false));
        assertTrue(ex.getMessage().contains("3-600"), "应给出合法区间：" + ex.getMessage());
    }

    @Test
    @DisplayName("心跳阈值缺失 → 放行（由 normalizeHeartbeat 兜默认值 10）")
    void heartbeatNullable() {
        HaConfigRequest request = complete();
        request.setHeartbeatTimeoutSeconds(null);
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, true));
    }

    // ------------------------------------------------------------------
    // 格式校验：虚拟 IP
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Web 虚拟 IP 非法 → PARAM_INVALID")
    void invalidVipWeb() {
        HaConfigRequest request = complete();
        request.setVipWeb("192.168.1");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, true));
    }

    @Test
    @DisplayName("数据库虚拟 IP 非法 → PARAM_INVALID")
    void invalidVipDb() {
        HaConfigRequest request = complete();
        request.setVipDb("999.1.1.1");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, true));
    }

    @Test
    @DisplayName("★ 两个虚拟 IP 相同 → 拒绝（两个 vrrp_instance 抢同一地址必然脑裂）")
    void identicalVipsRejected() {
        HaConfigRequest request = complete();
        request.setVipDb(request.getVipWeb());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> HaConfigValidator.validateConfig(request, true));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能相同"), "应说明原因：" + ex.getMessage());
    }

    @Test
    @DisplayName("两个虚拟 IP 都为空白 → 不算「相同」（空白会被归一成 null，不误报）")
    void blankVipsAreNotTreatedAsEqual() {
        HaConfigRequest request = complete();
        request.setVipWeb("");
        request.setVipDb("   ");
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, false));
    }

    @Test
    @DisplayName("虚拟 IP 前后空白被容忍（复制粘贴常见）")
    void vipToleratesSurroundingSpaces() {
        HaConfigRequest request = complete();
        request.setVipWeb(" 192.168.1.100 ");
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, true));
    }

    // ------------------------------------------------------------------
    // 格式校验：域名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("域名带 http:// / 端口 / 空格 / 路径 → 拒绝（本字段要的是纯主机名）")
    void invalidDomainsRejected() {
        String[] bad = {"http://oa.company.com", "oa.company.com:8443", "oa company.com",
                "oa.company.com/portal", "company", ".company.com", "company.com.", "company..com"};
        for (String domain : bad) {
            HaConfigRequest request = complete();
            request.setDomain(domain);
            assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, true),
                    "应拒绝域名：" + domain);
        }
    }

    @Test
    @DisplayName("内网下划线主机名可接受（RFC 1123 严格版本会误杀这类真实内网命名）")
    void underscoreHostnameAccepted() {
        HaConfigRequest request = complete();
        request.setDomain("oa_test.company.local");
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, true));
    }

    @Test
    @DisplayName("域名留空 → 放行（纯 IP 访问的内网环境仍然成立）")
    void blankDomainAllowed() {
        HaConfigRequest request = complete();
        request.setDomain("");
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, true));
    }

    // ------------------------------------------------------------------
    // 格式校验：网卡名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("网卡名非法（首字符非字母数字 / 含空格）→ PARAM_INVALID")
    void invalidIfaceRejected() {
        HaConfigRequest request = complete();
        request.setVrrpIface("-eth0");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, true));

        request.setVrrpIface("eth 0");
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, true));
    }

    @Test
    @DisplayName("常见网卡名形态全部接受（eth0 / ovs_eth0 / enp0s3 / br-lan）")
    void commonIfacesAccepted() {
        for (String iface : new String[]{"eth0", "ovs_eth0", "enp0s3", "br-lan"}) {
            HaConfigRequest request = complete();
            request.setVrrpIface(iface);
            assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, true), iface);
        }
    }

    // ------------------------------------------------------------------
    // 完整性校验（仅启用时）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("启用但缺 Web 虚拟 IP → HA_CONFIG_INCOMPLETE，消息点出缺的是哪一项")
    void incompleteWhenEnabled() {
        HaConfigRequest request = complete();
        request.setVipWeb(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> HaConfigValidator.validateConfig(request, true));
        assertEquals(ErrorCode.HA_CONFIG_INCOMPLETE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Web 虚拟 IP"), "应指出缺失项：" + ex.getMessage());
    }

    @Test
    @DisplayName("未启用（requireComplete=false）允许不完整地暂存草稿 —— 否则拿不到 VIP 就无法保存")
    void draftAllowedWhenDisabled() {
        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        assertDoesNotThrow(() -> HaConfigValidator.validateConfig(request, false));
    }

    @Test
    @DisplayName("格式错误在「未启用」时同样被拒（格式校验与完整性校验的分工不能混）")
    void formatStillCheckedOnDraft() {
        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        request.setVipWeb("not-an-ip");
        // 注意：这里 requireComplete=false —— 仍然必须报格式错
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(request, false));
    }

    @Test
    @DisplayName("request 为 null → PARAM_INVALID")
    void nullRequest() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(null, true));
    }

    // ------------------------------------------------------------------
    // 备节点 IP 校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("备节点 IP：合法地址通过，空 / 非 IPv4 / 越界 / 前导零被拒")
    void validateNodeIp() {
        assertDoesNotThrow(() -> HaConfigValidator.validateNodeIp("192.168.1.101"));
        assertDoesNotThrow(() -> HaConfigValidator.validateNodeIp(" 192.168.1.101 "));

        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode(null));
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode(""));
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode("   "));
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode("192.168.1"));
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode("999.1.1.1"));
        // 前导零：部分系统按八进制解析，会指向与填写者预期完全不同的地址
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode("010.1.1.1"));
        // 当前部署资产的 keepalived / .env.ha 全是 IPv4 形态，IPv6 会在渲染阶段才炸
        assertEquals(ErrorCode.HA_NODE_IP_INVALID, nodeIpCode("::1"));
    }

    private ErrorCode nodeIpCode(String ip) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> HaConfigValidator.validateNodeIp(ip));
        return ex.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 静态工具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isValidIpv4：四段 0-255，拒绝越界 / 段数不对 / 字母 / 前导零")
    void isValidIpv4() {
        assertTrue(HaConfigValidator.isValidIpv4("0.0.0.0"));
        assertTrue(HaConfigValidator.isValidIpv4("255.255.255.255"));
        assertTrue(HaConfigValidator.isValidIpv4(" 1.2.3.4 "));

        assertFalse(HaConfigValidator.isValidIpv4("256.1.1.1"));
        assertFalse(HaConfigValidator.isValidIpv4("1.2.3"));
        assertFalse(HaConfigValidator.isValidIpv4("1.2.3.4.5"));
        assertFalse(HaConfigValidator.isValidIpv4("1.2.3.a"));
        assertFalse(HaConfigValidator.isValidIpv4("1.2.3."));
        assertFalse(HaConfigValidator.isValidIpv4(".1.2.3"));
        assertFalse(HaConfigValidator.isValidIpv4("01.2.3.4"));
        assertFalse(HaConfigValidator.isValidIpv4("1.2.3.04"));
        assertFalse(HaConfigValidator.isValidIpv4(""));
        assertFalse(HaConfigValidator.isValidIpv4(null));
    }

    @Test
    @DisplayName("isValidDomain：接受常规与下划线主机名，拒绝 URL / 端口 / 空格 / 无点 / 空标签")
    void isValidDomain() {
        assertTrue(HaConfigValidator.isValidDomain("oa.company.com"));
        assertTrue(HaConfigValidator.isValidDomain("oa_test.company.local"));
        assertTrue(HaConfigValidator.isValidDomain("a-b.company.com"));

        assertFalse(HaConfigValidator.isValidDomain("company"));
        assertFalse(HaConfigValidator.isValidDomain("http://oa.company.com"));
        assertFalse(HaConfigValidator.isValidDomain("oa.company.com:8443"));
        assertFalse(HaConfigValidator.isValidDomain("oa company.com"));
        assertFalse(HaConfigValidator.isValidDomain("oa.company.com/portal"));
        assertFalse(HaConfigValidator.isValidDomain(".company.com"));
        assertFalse(HaConfigValidator.isValidDomain("company..com"));
        assertFalse(HaConfigValidator.isValidDomain(""));
        assertFalse(HaConfigValidator.isValidDomain(null));
    }

    @Test
    @DisplayName("isValidIface：首字符须字母数字，长度上限 32")
    void isValidIface() {
        assertTrue(HaConfigValidator.isValidIface("eth0"));
        assertTrue(HaConfigValidator.isValidIface("a" + "b".repeat(31)));

        assertFalse(HaConfigValidator.isValidIface("a" + "b".repeat(32)));
        assertFalse(HaConfigValidator.isValidIface("-eth0"));
        assertFalse(HaConfigValidator.isValidIface("_eth0"));
        assertFalse(HaConfigValidator.isValidIface("eth 0"));
        assertFalse(HaConfigValidator.isValidIface(""));
        assertFalse(HaConfigValidator.isValidIface(null));
    }

    @Test
    @DisplayName("normalizeHeartbeat：null 取默认 10，越界钳到 3-600（读库路径必须钳制）")
    void normalizeHeartbeat() {
        assertEquals(HaConfigValidator.DEFAULT_HEARTBEAT_SECONDS,
                HaConfigValidator.normalizeHeartbeat(null));
        assertEquals(3, HaConfigValidator.normalizeHeartbeat(0));
        assertEquals(3, HaConfigValidator.normalizeHeartbeat(1));
        assertEquals(3, HaConfigValidator.normalizeHeartbeat(-5));
        assertEquals(600, HaConfigValidator.normalizeHeartbeat(601));
        assertEquals(10, HaConfigValidator.normalizeHeartbeat(10));
        assertEquals(30, HaConfigValidator.normalizeHeartbeat(30));
    }
}
