package com.enterprise.ticket.module.ha.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.ha.dto.vo.HaActionResultVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeployGuideVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeploymentVO;
import com.enterprise.ticket.module.ha.entity.HaConfig;
import com.enterprise.ticket.module.ha.entity.HaNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主备部署资产桥接（Phase 19 批次 G）—— 单元测试
 *
 * <h2>本类守的是整批的「诚实性」</h2>
 * <p>验收口径已提前拍板（{@code .docs/phase19-plan.md} 决策 5）：沙箱内 Docker 不可用、
 * 没有第二台机器，因此「真实心跳 / VIP 漂移 / keepalived 切换」<b>无法</b>验证。
 * 由此产生的最大风险是「静默降级成假成功」—— 按钮返回绿色提示，
 * 而实际上什么都没发生。运维界面上这种假成功比一个明确的报错危险得多。
 *
 * <p>因此本类逐条钉住三类事实：
 * <ol>
 *   <li><b>探针如实反映资产在不在</b>，并用一句人话解释「为什么现在不能真执行」；</li>
 *   <li><b>演练模式（{@code dry-run}，默认 true）绝不执行脚本</b> —— 断言
 *       {@code scriptRunner.run(..)} 一次都没被调用；</li>
 *   <li><b>脚本缺失 / 起不来时抛明确错误</b>，而不是返回 {@code success=false}
 *       （两者对维护人员的含义完全不同：一个要去部署，一个要去修环境）。</li>
 * </ol>
 *
 * <p>另外钉住「生成内容里绝不出现真实密钥」：所有口令一律 {@code __CHANGE_ME__}。
 * 这段文本会被复制粘贴、进聊天窗口、进工单，任何一处泄漏都等于泄漏两台机器的全部凭据。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主备部署资产桥接（探针 / 演练模式 / 部署片段生成）")
class HaDeploySupportTest {

    /** 与 deploy/ha/keepalived/keepalived.conf.tmpl 同形态的模板（含全部 11 个占位符） */
    private static final String TEMPLATE = """
            global_defs { router_id __ROUTER_ID__ }
            vrrp_instance VI_WEB {
              state MASTER
              interface __IFACE__
              virtual_router_id __VRRP_ID_WEB__
              priority __PRIORITY__
              unicast_src_ip __NODE_IP__
              unicast_peer { __PEER_IP__ }
              virtual_ipaddress { __VIP_WEB__/__VIP_MASK__ }
              authentication { auth_pass __VRRP_AUTH_PASS__ }
            }
            vrrp_instance VI_DB {
              virtual_router_id __VRRP_ID_DB__
              virtual_ipaddress { __VIP_DB__/__VIP_MASK__ }
            }
            """;

    @Mock
    private HaScriptRunner scriptRunner;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private HaDeploySupport support(String deployDir, boolean dryRun) {
        AppProperties properties = new AppProperties();
        properties.getHa().setDeployDir(deployDir);
        properties.getHa().setDryRun(dryRun);
        return new HaDeploySupport(properties, scriptRunner);
    }

    /** 造一份完整的 deploy/ha 资产目录，返回其根路径 */
    private Path completeAssets(Path root) throws IOException {
        Path scripts = root.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("switchover.sh"), "#!/bin/bash\nexit 0\n");
        Files.writeString(scripts.resolve("setup-replication.sh"), "#!/bin/bash\nexit 0\n");
        Path keepalived = root.resolve("keepalived");
        Files.createDirectories(keepalived);
        Files.writeString(keepalived.resolve("keepalived.conf.tmpl"), TEMPLATE);
        return root;
    }

    private HaConfig config() {
        HaConfig config = new HaConfig();
        config.setId(1L);
        config.setEnabled(true);
        config.setNodeName("ticket-master");
        config.setNodeIp("192.168.1.10");
        config.setDomain("oa.company.com");
        config.setVipWeb("192.168.1.100");
        config.setVipDb("192.168.1.101");
        config.setVrrpIface("ens192");
        config.setHeartbeatTimeoutSeconds(10);
        return config;
    }

    private HaNode localNode() {
        HaNode node = new HaNode();
        node.setId(1L);
        node.setNodeName("ticket-master");
        node.setNodeIp("192.168.1.10");
        node.setNodeRole(HaRole.MASTER.name());
        node.setIsLocal(true);
        return node;
    }

    private HaNode peerNode() {
        HaNode node = new HaNode();
        node.setId(2L);
        node.setNodeName("ticket-slave");
        node.setNodeIp("192.168.1.101");
        node.setNodeRole(HaRole.STANDBY.name());
        node.setIsLocal(false);
        return node;
    }

    private ErrorCode codeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    // ==================================================================
    // 探针
    // ==================================================================

    @Test
    @DisplayName("未配置部署目录 → 探针全部为假，并说明「开发/评审环境下这是正常形态」")
    void probeWithoutDeployDir() {
        HaDeploySupport support = support("", true);

        HaDeploymentVO probe = support.probe();

        assertFalse(probe.haDirConfigured());
        assertNull(probe.haDir());
        assertFalse(probe.deployDirExists());
        assertFalse(probe.switchoverScriptPresent());
        assertFalse(probe.switchoverExecutable());
        assertTrue(probe.dryRunEnabled());
        assertTrue(probe.hint().contains("尚未配置主备部署资产目录"), probe.hint());
        assertTrue(probe.hint().contains("正常形态"),
                "必须说清这是正常形态，否则维护人员会去追一个不存在的故障：" + probe.hint());
        assertNull(support.deployDirOrNull());
    }

    @Test
    @DisplayName("配置了目录但目录不存在 → 指出要去部署 / 改路径")
    void probeWithMissingDir() {
        HaDeploySupport support = support("/opt/ticket/deploy/ha-does-not-exist-xyz", true);

        HaDeploymentVO probe = support.probe();

        assertTrue(probe.haDirConfigured());
        assertEquals("/opt/ticket/deploy/ha-does-not-exist-xyz", probe.haDir());
        assertFalse(probe.deployDirExists());
        assertTrue(probe.hint().contains("不存在"), probe.hint());
        assertFalse(probe.switchoverExecutable());
        assertNotNull(support.deployDirOrNull());
    }

    @Test
    @DisplayName("资产完整 + 演练模式 → 提示「只会返回将要运行的命令」")
    void probeWithCompleteAssetsInDryRun(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);

        HaDeploymentVO probe = support.probe();

        assertTrue(probe.haDirConfigured());
        assertTrue(probe.deployDirExists());
        assertTrue(probe.switchoverScriptPresent());
        assertTrue(probe.setupReplicationScriptPresent());
        assertTrue(probe.keepalivedConfPresent());
        assertTrue(probe.dryRunEnabled());
        assertTrue(probe.switchoverExecutable());
        assertTrue(probe.hint().contains("演练模式"), probe.hint());
    }

    @Test
    @DisplayName("★ 资产完整 + 演练关闭 → 明确写出「会真实改变虚拟 IP 的归属」（危险要写在脸上）")
    void probeWithAssetsAndDryRunDisabled(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);

        HaDeploymentVO probe = support.probe();

        assertFalse(probe.dryRunEnabled());
        assertTrue(probe.hint().contains("真实改变虚拟 IP 的归属"), probe.hint());
    }

    @Test
    @DisplayName("缺 switchover.sh → 探针点出缺哪个文件，且判为不可执行")
    void probeWithMissingSwitchoverScript(@TempDir Path root) throws IOException {
        Path scripts = root.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("setup-replication.sh"), "#!/bin/bash\n");
        HaDeploySupport support = support(root.toString(), true);

        HaDeploymentVO probe = support.probe();

        assertTrue(probe.deployDirExists());
        assertFalse(probe.switchoverScriptPresent());
        assertFalse(probe.switchoverExecutable());
        assertTrue(probe.hint().contains("switchover.sh"), probe.hint());
    }

    @Test
    @DisplayName("keepalived 模板缺失不影响「能否发起切换」（那是 install-keepalived.sh 的职责）")
    void keepalivedTemplateMissingDoesNotBlockSwitchover(@TempDir Path root) throws IOException {
        Path scripts = root.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("switchover.sh"), "#!/bin/bash\n");
        HaDeploySupport support = support(root.toString(), true);

        HaDeploymentVO probe = support.probe();

        assertFalse(probe.keepalivedConfPresent());
        assertTrue(probe.switchoverExecutable(),
                "把无关条件塞进判定会让一个其实可执行的动作被误判为不可执行");
    }

    // ==================================================================
    // 演练模式：绝不真的执行
    // ==================================================================

    @Test
    @DisplayName("★ 演练模式切 to-peer：只返回命令，一次都不执行脚本")
    void switchoverDryRunNeverExecutes(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);
        when(scriptRunner.isWindows()).thenReturn(false);

        HaActionResultVO result = support.switchover("to-peer");

        assertTrue(result.dryRun(), "dryRun 必须如实下发，否则维护人员会把演练当真实切换");
        assertTrue(result.success(), "命令组装成功即算成功；失败的是「没有真的执行」，由 dryRun 表达");
        assertNull(result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.command().contains("switchover.sh"), result.command());
        assertTrue(result.command().endsWith("to-peer"), result.command());
        assertTrue(result.command().contains("sudo -n"), "必须带 -n，否则无 tty 下会挂住等密码");
        assertTrue(result.message().contains("演练模式"), result.message());

        verify(scriptRunner, never()).run(any(), anyInt());
    }

    @Test
    @DisplayName("★ 演练模式立即同步：走的是 setup-replication.sh，不是 switchover.sh（否则是假成功）")
    void syncNowDryRunUsesReplicationScript(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);
        when(scriptRunner.isWindows()).thenReturn(false);

        HaActionResultVO result = support.syncNow();

        assertTrue(result.dryRun());
        assertTrue(result.command().contains("setup-replication.sh"),
                "「立即同步」管的是数据同步，switchover.sh 只管 VIP 归属：" + result.command());
        verify(scriptRunner, never()).run(any(), anyInt());
    }

    @Test
    @DisplayName("真实执行（演练关闭）：回收退出码与输出")
    void switchoverExecutesAndReturnsOutput(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);
        when(scriptRunner.isWindows()).thenReturn(false);
        when(scriptRunner.run(any(), anyInt()))
                .thenReturn(new HaScriptRunner.Result(0, "已标记本机为维护状态，等待对端接管", false));

        HaActionResultVO result = support.switchover("to-peer");

        assertFalse(result.dryRun());
        assertTrue(result.success());
        assertEquals(0, result.exitCode().intValue());
        assertTrue(result.output().contains("等待对端接管"));
        assertTrue(result.message().contains("本机已让出虚拟 IP"), result.message());
        verify(scriptRunner).run(any(), anyInt());
    }

    @Test
    @DisplayName("脚本跑起来但退出码非 0 → success=false 且原样带回输出（翻成错误码会丢掉 ssh 报错）")
    void switchoverExitCodeNonZeroKeepsOutput(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);
        when(scriptRunner.isWindows()).thenReturn(false);
        when(scriptRunner.run(any(), anyInt()))
                .thenReturn(new HaScriptRunner.Result(3, "keepalived 未安装", false));

        HaActionResultVO result = support.switchover("to-peer");

        assertFalse(result.success());
        assertEquals(3, result.exitCode().intValue());
        assertEquals("keepalived 未安装", result.output(),
                "排障靠的就是这段原始输出，不能被吞掉");
    }

    @Test
    @DisplayName("脚本执行超时 → success=false，提示去目标机器手工执行确认实际状态")
    void switchoverTimeoutIsReported(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);
        when(scriptRunner.isWindows()).thenReturn(false);
        when(scriptRunner.run(any(), anyInt()))
                .thenReturn(new HaScriptRunner.Result(null, "部分输出", true));

        HaActionResultVO result = support.switchover("to-peer");

        assertFalse(result.success());
        assertTrue(result.message().contains("超时"), result.message());
        assertTrue(result.message().contains("手工执行"), result.message());
    }

    @Test
    @DisplayName("★ 脚本根本起不来（无 sudo / 无执行权限）→ HA_SCRIPT_FAILED，并提示改用 NOPASSWD")
    void switchoverLaunchFailureMapsToErrorCode(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);
        when(scriptRunner.isWindows()).thenReturn(false);
        when(scriptRunner.run(any(), anyInt()))
                .thenThrow(new HaScriptRunner.ScriptLaunchException("sudo -n bash x",
                        new IOException("sudo: a password is required")));

        BusinessException ex = assertThrows(BusinessException.class, () -> support.switchover("to-peer"));
        assertEquals(ErrorCode.HA_SCRIPT_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("sudo"), "应提示常见原因：" + ex.getMessage());
    }

    // ==================================================================
    // 请求本身不成立（要抛错，而不是返回 success=false）
    // ==================================================================

    @Test
    @DisplayName("未配置部署目录 → 切换与同步都给出 HA_DEPLOY_DIR_MISSING")
    void missingDeployDirUsesDistinctErrorCode() {
        HaDeploySupport support = support("", true);

        assertEquals(ErrorCode.HA_DEPLOY_DIR_MISSING, codeOf(() -> support.switchover("to-peer")));
        assertEquals(ErrorCode.HA_DEPLOY_DIR_MISSING, codeOf(support::syncNow));
        verify(scriptRunner, never()).run(any(), anyInt());
    }

    @Test
    @DisplayName("目录存在但缺 setup-replication.sh → 立即同步报 HA_SCRIPT_MISSING（点出缺哪个脚本）")
    void syncNowWithoutReplicationScript(@TempDir Path root) throws IOException {
        Path scripts = root.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("switchover.sh"), "#!/bin/bash\n");
        HaDeploySupport support = support(root.toString(), true);

        BusinessException ex = assertThrows(BusinessException.class, support::syncNow);
        assertEquals(ErrorCode.HA_SCRIPT_MISSING, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("setup-replication.sh"), ex.getMessage());
    }

    // ==================================================================
    // 部署片段生成
    // ==================================================================

    @Test
    @DisplayName("没有部署资产时，环境变量片段仍可生成（它只依赖本页已保存的配置值）")
    void guideWithoutAssetsStillBuildsEnvSnippet() {
        HaDeploySupport support = support("", true);

        HaDeployGuideVO guide = support.guide(config(), List.of(localNode(), peerNode()));

        assertFalse(guide.deployDirPresent());
        assertNull(guide.keepalivedConf(), "没有模板就渲染不出 keepalived 配置，如实返回 null");
        assertTrue(guide.envSnippet().contains("VIP_WEB=192.168.1.100"), guide.envSnippet());
        assertTrue(guide.envSnippet().contains("HA_PEER_IP=192.168.1.101"), guide.envSnippet());
        assertTrue(guide.note().contains("未找到 deploy/ha"), guide.note());
    }

    @Test
    @DisplayName("★ 生成的环境变量片段里所有密钥都是 __CHANGE_ME__（绝不出现真实口令）")
    void guideNeverEmitsRealSecrets(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);

        HaDeployGuideVO guide = support.guide(config(), List.of(localNode(), peerNode()));

        String snippet = guide.envSnippet();
        assertTrue(snippet.contains("HA_VRRP_AUTH_PASS=__CHANGE_ME__"), snippet);
        // 这些密钥由部署方在 .env.ha 里自行填写，本系统生成不出来也不该猜 ——
        // 生成片段里连键名都不出现，从源头上杜绝「不小心读了一个真实值填进去」
        assertFalse(snippet.contains("JWT_SECRET="), snippet);
        assertFalse(snippet.contains("INTERNAL_ALERT_TOKEN="), snippet);
        // 认证口令在 keepalived 配置里同样是占位符
        assertTrue(guide.keepalivedConf().contains("auth_pass __CHANGE_ME__"));
    }

    @Test
    @DisplayName("★ 生成的环境变量片段声明了「HA_NODE_ROLE 是节点标识，不是运行期角色」")
    void guideExplainsNodeRoleVsRuntimeRole(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);

        String snippet = support.guide(config(), List.of(localNode(), peerNode())).envSnippet();

        assertTrue(snippet.contains("HA_NODE_ROLE"), snippet);
        assertTrue(snippet.contains("节点标识"), "这两个概念混淆会导致填错配置：" + snippet);
        assertTrue(snippet.contains("运行期角色"), snippet);
    }

    @Test
    @DisplayName("主节点生成 keepalived 配置：优先级 150、VIP、网卡、router_id 全部渲染到位")
    void guideRendersKeepalivedConfForMaster(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);

        String conf = support.guide(config(), List.of(localNode(), peerNode())).keepalivedConf();

        assertNotNull(conf);
        assertTrue(conf.contains("router_id ticket-master"), conf);
        assertTrue(conf.contains("interface ens192"), conf);
        assertTrue(conf.contains("virtual_router_id 51"), conf);
        assertTrue(conf.contains("virtual_router_id 52"), conf);
        assertTrue(conf.contains("priority 150"), "本机是主节点，优先级应为 150：" + conf);
        assertTrue(conf.contains("unicast_src_ip 192.168.1.10"), conf);
        assertTrue(conf.contains("unicast_peer { 192.168.1.101 }"), conf);
        assertTrue(conf.contains("192.168.1.100/24"), conf);
        assertTrue(conf.contains("192.168.1.101/24"), conf);

        for (String leftover : new String[]{"__ROUTER_ID__", "__IFACE__", "__VRRP_ID_WEB__",
                "__VRRP_ID_DB__", "__PRIORITY__", "__NODE_IP__", "__PEER_IP__",
                "__VIP_WEB__", "__VIP_DB__", "__VIP_MASK__"}) {
            assertFalse(conf.contains(leftover), "占位符未替换：" + leftover + "\n" + conf);
        }
    }

    @Test
    @DisplayName("本机是备节点 → 优先级 100，且 Redis 指向对端")
    void guideRendersStandbyPriority(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);
        HaNode standby = localNode();
        standby.setNodeRole(HaRole.STANDBY.name());

        HaDeployGuideVO guide = support.guide(config(), List.of(standby, peerNode()));

        assertTrue(guide.keepalivedConf().contains("priority 100"), guide.keepalivedConf());
        assertTrue(guide.envSnippet().contains("HA_NODE_PRIORITY=100"), guide.envSnippet());
        assertTrue(guide.envSnippet().contains("REDIS_REPLICAOF=192.168.1.101 6379"),
                "备节点要跟随对端：" + guide.envSnippet());
    }

    @Test
    @DisplayName("配置值缺失时按占位符 / 默认值兜底，而不是渲染出空格")
    void guideFallsBackWhenValuesMissing(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);
        HaConfig bare = new HaConfig();
        bare.setId(1L);
        bare.setNodeName("");
        bare.setVipWeb("");
        bare.setVipDb("");
        bare.setVrrpIface("");

        HaDeployGuideVO guide = support.guide(bare, List.of());

        assertTrue(guide.envSnippet().contains("VIP_WEB=__CHANGE_ME__"), guide.envSnippet());
        assertTrue(guide.envSnippet().contains("HA_VRRP_IFACE=eth0"), guide.envSnippet());
        assertTrue(guide.keepalivedConf().contains("interface eth0"), guide.keepalivedConf());
        // 没有本机 IP / 对端 IP 时留占位符，绝不编造一个地址
        assertTrue(guide.keepalivedConf().contains("unicast_src_ip __CHANGE_ME__"),
                guide.keepalivedConf());
        assertTrue(guide.keepalivedConf().contains("unicast_peer { __CHANGE_ME__ }"),
                guide.keepalivedConf());
    }

    @Test
    @DisplayName("指引是「三步走」，且明确写出第 3 步必须在目标机器上以 root 执行")
    void guideProducesThreeSteps(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);

        HaDeployGuideVO guide = support.guide(config(), List.of(localNode(), peerNode()));
        String all = String.join("\n", guide.steps());

        assertTrue(all.contains("第 1 步"), all);
        assertTrue(all.contains("第 2 步"), all);
        assertTrue(all.contains("第 3 步"), all);
        assertTrue(all.contains("root"), "必须说清这一步系统代替不了：" + all);
        assertTrue(all.contains("preflight-ha.sh"), all);
        assertTrue(all.contains("setup-replication.sh"), all);
        assertTrue(all.contains("install-keepalived.sh"), all);
    }

    @Test
    @DisplayName("★ 演练关闭时，指引里额外出现一条「会发生真实切换」的提示")
    void guideWarnsWhenDryRunDisabled(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), false);

        String all = String.join("\n", support.guide(config(), List.of(localNode(), peerNode())).steps());

        assertTrue(all.contains("真实改变虚拟 IP 归属"), all);
    }

    @Test
    @DisplayName("含换行 / 引号的节点名会被清理（否则 keepalived 收到两行 router_id 直接报语法错）")
    void guideSanitizesConfigValues(@TempDir Path root) throws IOException {
        HaDeploySupport support = support(completeAssets(root).toString(), true);
        HaConfig dirty = config();
        dirty.setNodeName("ticket\nmaster\"1");

        String conf = support.guide(dirty, List.of(localNode(), peerNode())).keepalivedConf();

        assertTrue(conf.contains("router_id ticketmaster1"), conf);
        assertFalse(conf.contains("router_id ticket\n"), "换行必须被清掉：" + conf);
    }
}
