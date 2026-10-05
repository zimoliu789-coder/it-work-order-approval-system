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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 主备部署资产桥接
 *
 * <h2>本类是整个批次「诚实性」的落点</h2>
 * <p>{@code .docs/phase19-plan.md} 的决策 5 已经把验收口径说死：沙箱内
 * <b>Docker 不可用、没有第二台机器</b>，因此「真实心跳 / 真实 VIP 漂移 /
 * 真实 keepalived 切换」<b>不可能</b>在沙箱里验证。本类因此只做三件事，
 * 并且每件都做到「如实反映现实」：
 * <ol>
 *   <li><b>探针</b> —— 部署资产到底在不在（{@link #probe()}）；</li>
 *   <li><b>桥接</b> —— 需要时调用真实脚本，但
 *       {@code app.ha.dry-run} 默认 true 时<b>只组装命令不执行</b>（{@link #switchover(String)}）；</li>
 *   <li><b>生成</b> —— 把系统已知的配置渲染成可直接使用的部署片段（{@link #guide}），
 *       这是需求 [157]/[158] 真正的痛点所在（维护人员不会写 .env.ha）。</li>
 * </ol>
 *
 * <h2>为什么脚本缺失时必须抛异常，而不是返回 {@code success=false} 的结果对象</h2>
 * <p>两者对维护人员的含义完全不同：
 * <ul>
 *   <li><b>脚本不存在</b> = 这台机器上根本没部署过主备资产 → 他要做的是「去部署」，
 *       动作在<b>页面之外</b>；这属于「请求本身不成立」，抛异常并给出
 *       「请按 deploy/DEPLOY.md 部署后再试」最合适；</li>
 *   <li><b>脚本跑了但失败</b> = 资产在、环境有问题（对端不可达、权限不足）→
 *       他要看的是脚本的原始输出，动作是<b>修环境</b>。这时把 output 原样带回去
 *       才有价值，翻成一个笼统的错误码会让 ssh / keepalived 的报错全部丢失。</li>
 * </ul>
 *
 * <h2>⚠️ 生成内容里绝不出现真实密钥</h2>
 * <p>{@link #guide} 产出的 {@code envSnippet} 里，唯一涉及口令的一行是
 * {@code HA_VRRP_AUTH_PASS=__CHANGE_ME__}；{@code JWT_SECRET} /
 * {@code INTERNAL_ALERT_TOKEN} 之类的密钥<b>连键名都不出现</b> ——
 * 它们由部署方在目标机的 {@code deploy/ha/.env.ha} 里自行填写。
 * 原因：这份文本会被复制到剪贴板、粘贴进聊天窗口、存进工单，
 * 任何一处泄漏都等于泄漏两台机器的全部凭据。系统<b>知道</b>不了这些值
 * （它们只存在于 {@code .env.ha} 里），因此也不存在「不小心写进去」的风险，
 * 但这条约束仍然显式写在这里，避免后续有人为了「方便」而从配置里读一个真实值填进去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HaDeploySupport {

    private static final String SCRIPTS_SUBDIR = "scripts";
    private static final String SWITCHOVER_SCRIPT = "switchover.sh";
    private static final String SETUP_REPLICATION_SCRIPT = "setup-replication.sh";
    private static final String KEEPALIVED_TMPL = "keepalived/keepalived.conf.tmpl";

    /** 主节点优先级（与 deploy/ha/.env.ha.example 的约定一致：node-a=150 / node-b=100） */
    private static final int PRIORITY_MASTER = 150;
    private static final int PRIORITY_STANDBY = 100;

    /** VIP 掩码位数默认 24（与 .env.ha.example 的默认一致） */
    private static final String DEFAULT_VIP_MASK = "24";

    /** 两个 vrrp_instance 的虚拟路由 ID（与 .env.ha.example 的默认一致） */
    private static final String DEFAULT_VRRP_ID_WEB = "51";
    private static final String DEFAULT_VRRP_ID_DB = "52";

    private final AppProperties appProperties;
    private final HaScriptRunner scriptRunner;

    // ------------------------------------------------------------------
    // 探针
    // ------------------------------------------------------------------

    /** 部署资产目录；未配置时返回 null */
    public Path deployDirOrNull() {
        String configured = appProperties.getHa().getDeployDir();
        if (!StringUtils.hasText(configured)) {
            return null;
        }
        return Paths.get(configured.trim());
    }

    /** 部署资产探针：目录与关键脚本是否存在 */
    public HaDeploymentVO probe() {
        String configured = appProperties.getHa().getDeployDir();
        boolean configuredFlag = StringUtils.hasText(configured);
        Path dir = deployDirOrNull();
        boolean dirExists = dir != null && Files.isDirectory(dir);
        boolean switchoverPresent = dirExists && Files.isRegularFile(dir.resolve(SCRIPTS_SUBDIR).resolve(SWITCHOVER_SCRIPT));
        boolean setupReplicationPresent = dirExists
                && Files.isRegularFile(dir.resolve(SCRIPTS_SUBDIR).resolve(SETUP_REPLICATION_SCRIPT));
        boolean keepalivedPresent = dirExists && Files.isRegularFile(dir.resolve(KEEPALIVED_TMPL));
        boolean dryRun = appProperties.getHa().isDryRun();

        return new HaDeploymentVO(
                configuredFlag,
                configuredFlag ? configured.trim() : null,
                dirExists,
                switchoverPresent,
                setupReplicationPresent,
                keepalivedPresent,
                dryRun,
                buildProbeHint(configuredFlag, dirExists, switchoverPresent, dryRun));
    }

    /**
     * 探针结论的一句话说明。
     *
     * <p>文案由服务端给，而不是让前端按几个布尔自己拼 —— 布尔组合有十几种，
     * 前端手写的分支必然漏掉若干种，结果是维护人员在最需要解释的场景下
     * 看到一句通用提示。放在这里，规则与文案在同一处演进。
     */
    private String buildProbeHint(boolean configured, boolean dirExists, boolean switchoverPresent, boolean dryRun) {
        if (!configured) {
            return "尚未配置主备部署资产目录（app.ha.deploy-dir）。"
                    + "当前环境只能查看状态与生成配置片段，无法执行真实的切换 / 同步动作。"
                    + "本机为开发或评审环境时，这是正常形态。";
        }
        if (!dirExists) {
            return "已配置部署资产目录，但该目录在服务器上不存在。"
                    + "请确认 deploy/ha 是否已上传到该路径，或修正 app.ha.deploy-dir。";
        }
        if (!switchoverPresent) {
            return "部署资产目录存在，但缺少 scripts/switchover.sh，无法执行主备切换。"
                    + "请确认 deploy/ha 上传完整。";
        }
        if (dryRun) {
            return "部署资产已就绪；当前处于演练模式（app.ha.dry-run=true），"
                    + "执行切换 / 同步只会返回将要运行的命令，不会真正执行。"
                    + "确认无误后可配置 HA_DRY_RUN=false 正式启用。";
        }
        return "部署资产已就绪，演练模式已关闭 —— 执行切换会真实改变虚拟 IP 的归属。";
    }

    // ------------------------------------------------------------------
    // 脚本桥接
    // ------------------------------------------------------------------

    /**
     * 触发一次主备切换动作。
     *
     * <p>三个动作走同一条通道，而不是各开一个方法：它们的<b>前置条件、演练模式处理、
     * 失败语义完全一致</b>，唯一不同的是子命令。分开写就会出现
     * 「to-peer 判了演练模式、status 忘了判」这类不对称，而漏判的那个动作
     * 会在所有人都以为是演练的环境里真的执行。
     *
     * @param action {@code to-peer}（本机让出，由对端接管）/ {@code back}（切回本机）/
     *               {@code status}（只查询本机维护标记，不改变任何东西）
     */
    public HaActionResultVO switchover(String action) {
        return runSwitchoverAction(action, "SWITCHOVER_" + action.replace('-', '_').toUpperCase());
    }

    /**
     * 「立即同步」：调用 {@code setup-replication.sh} 重建复制并触发全量同步。
     *
     * <h2>为什么「立即同步」用的不是 switchover.sh</h2>
     * <p>{@code switchover.sh} 只有 {@code to-peer / back / status} 三个子命令，
     * 它管的是虚拟 IP 的归属，与数据同步无关。真正的同步重建脚本是
     * {@code setup-replication.sh}（建立复制链路 + 首次全量同步）——
     * 需求 [167] 行的「立即同步」按钮对应的就是它。
     * 若这里错调 switchover.sh，按钮会「成功」但数据完全没有同步 ——
     * 一个最典型的假成功。
     */
    public HaActionResultVO syncNow() {
        HaDeploymentVO probe = probe();
        if (!probe.haDirConfigured() || !probe.deployDirExists()) {
            throw new BusinessException(ErrorCode.HA_DEPLOY_DIR_MISSING,
                    "无法执行立即同步：服务器上未找到部署资产目录。请按 deploy/DEPLOY.md 部署后重试。");
        }
        if (!probe.setupReplicationScriptPresent()) {
            throw new BusinessException(ErrorCode.HA_SCRIPT_MISSING,
                    "无法执行立即同步：缺少 scripts/" + SETUP_REPLICATION_SCRIPT
                            + "（「立即同步」由它重建复制并触发全量同步）。");
        }
        Path script = deployDirOrNull().resolve(SCRIPTS_SUBDIR).resolve(SETUP_REPLICATION_SCRIPT);
        return execute(script, List.of(), "SYNC",
                "复制链路已重建并触发全量同步。首次全量同步耗时取决于数据量，"
                        + "完成后本页的「最后同步时间」与「同步延迟」会开始更新。");
    }

    private HaActionResultVO runSwitchoverAction(String action, String actionCode) {
        HaDeploymentVO probe = probe();
        if (!probe.haDirConfigured() || !probe.deployDirExists()) {
            throw new BusinessException(ErrorCode.HA_DEPLOY_DIR_MISSING,
                    "无法执行主备动作：服务器上未找到部署资产目录。"
                            + "请按 deploy/DEPLOY.md 把 deploy/ha 部署到该机器后重试。");
        }
        if (!probe.switchoverScriptPresent()) {
            throw new BusinessException(ErrorCode.HA_SCRIPT_MISSING,
                    "无法执行主备动作：缺少 scripts/" + SWITCHOVER_SCRIPT + "。请确认 deploy/ha 上传完整。");
        }

        Path script = deployDirOrNull().resolve(SCRIPTS_SUBDIR).resolve(SWITCHOVER_SCRIPT);
        return execute(script, List.of(action), actionCode, describeActionEffect(actionCode));
    }

    /**
     * 统一的脚本执行 + 演练模式处理。
     *
     * <p>把「演练模式」放在这里而不是各自实现，是为了保证<b>所有</b>运维动作
     * 都遵循同一条诚实口径：只要 {@code app.ha.dry-run=true}，
     * 就没有任何一个动作能真的执行出去。若分散实现，将来新增一个动作时
     * 漏掉演练判断，那个动作就会在所有人都以为是演练的环境里真的执行 ——
     * 而它改的是线上虚拟 IP 的归属。
     */
    private HaActionResultVO execute(Path script, List<String> extraArgs, String actionCode, String successEffect) {
        boolean dryRun = appProperties.getHa().isDryRun();
        List<String> argv = buildScriptArgv(script, extraArgs);
        String display = String.join(" ", argv);

        if (dryRun) {
            // 演练模式：把将要执行的命令原样返回，让维护人员先核对。
            // 这里刻意**不**把 success 记为 false：命令组装是成功的，
            // 失败的是「没有真的执行」，而这一点已由 dryRun=true 表达。
            // 若把 success 置 false，前端会弹一个红色错误框，
            // 而演练模式下的这个结果其实是**预期且正常**的。
            log.info("[主备] 演练模式（dry-run），未执行：{}", display);
            return new HaActionResultVO(true, true, actionCode, display, null, "",
                    "演练模式：未真正执行。以上是系统将要运行的命令，可复制到目标机器上手工执行；"
                            + "确认无误后配置 HA_DRY_RUN=false 即可由本页直接执行。");
        }

        HaScriptRunner.Result result;
        try {
            result = scriptRunner.run(argv, appProperties.getHa().getScriptTimeoutSeconds());
        } catch (HaScriptRunner.ScriptLaunchException e) {
            // 进程根本起不来（sudo 缺失 / 脚本不可执行 / 无权限）
            log.error("[主备] 脚本无法启动：{}", display, e);
            throw new BusinessException(ErrorCode.HA_SCRIPT_FAILED,
                    "主备脚本无法启动：" + e.getMessage()
                            + "。常见原因：服务器未安装 sudo、脚本缺少可执行权限，"
                            + "或 sudo 配置为需要密码（本接口使用 sudo -n，不会也无法输入密码）。");
        }

        if (result.timedOut()) {
            return new HaActionResultVO(false, false, actionCode, display, null, result.output(),
                    "脚本执行超时（" + appProperties.getHa().getScriptTimeoutSeconds() + " 秒）已被强制终止，"
                            + "请到目标机器上手工执行以确认实际状态。");
        }
        boolean ok = result.ok();
        return new HaActionResultVO(ok, false, actionCode, display, result.exitCode(), result.output(),
                ok ? "脚本执行成功。" + successEffect
                   : "脚本执行失败（退出码 " + result.exitCode() + "），请查看下方输出定位原因。");
    }

    private String describeActionEffect(String actionCode) {
        return switch (actionCode) {
            case "SWITCHOVER_TO_PEER" -> "本机已让出虚拟 IP，等待对端接管（约 10~15 秒）。"
                    + "在 keepalived 配置了 nopreempt 的前提下，本机恢复后不会自动抢回。";
            case "SWITCHOVER_BACK" -> "已清除本机维护标记，等待 keepalived 重新仲裁虚拟 IP 归属。";
            default -> "";
        };
    }

    /**
     * 组装脚本命令。
     *
     * <p>Linux 上用 {@code sudo -n bash <script> [args...]}：
     * {@code -n} 让「需要密码」当场失败而不是挂住（见 {@link HaScriptRunner} 类头注释）。
     * Windows 上走 {@code cmd.exe /c}，只是为了开发机上也能把链路走到「调用」这一步 ——
     * 真实的 keepalived 只存在于 Linux。
     */
    private List<String> buildScriptArgv(Path script, List<String> extraArgs) {
        List<String> argv = new ArrayList<>();
        if (scriptRunner.isWindows()) {
            argv.add("cmd.exe");
            argv.add("/c");
            argv.add(script.toString());
        } else {
            argv.add("sudo");
            argv.add("-n");
            argv.add("bash");
            argv.add(script.toString());
        }
        argv.addAll(extraArgs);
        return argv;
    }

    // ------------------------------------------------------------------
    // 部署片段生成
    // ------------------------------------------------------------------

    /**
     * 生成「三步走」指引与可直接抄用的部署片段。
     *
     * @param config 当前主备配置
     * @param nodes  已登记的节点（用于取对端 IP）
     */
    public HaDeployGuideVO guide(HaConfig config, List<HaNode> nodes) {
        HaDeploymentVO probe = probe();
        String peerIp = resolvePeerIp(nodes);
        String localIp = resolveLocalIp(config, nodes);
        String localRole = resolveLocalRole(nodes);
        String localName = resolveLocalName(config, nodes);

        List<String> steps = buildSteps(probe, peerIp);
        String envSnippet = buildEnvSnippet(config, localName, localIp, peerIp, localRole, nodes);
        String keepalivedConf = buildKeepalivedConf(probe, config, localIp, peerIp, localRole);
        String note = probe.deployDirExists()
                ? "以上内容已按当前配置生成，请粘贴到两台机器的 deploy/ha/.env.ha 后按步骤执行。"
                + "密钥与口令保持 __CHANGE_ME__ 占位符，必须各自填写且两台机器完全一致。"
                : "服务器上未找到 deploy/ha 部署资产，keepalived 配置无法渲染。"
                + "环境变量片段仍可直接使用 —— 它只依赖本页已保存的配置值。"
                + "请先按 deploy/DEPLOY.md 部署资产目录，再回到本页生成完整片段。";

        return new HaDeployGuideVO(steps, envSnippet, keepalivedConf, probe.deployDirExists(), note);
    }

    private List<String> buildSteps(HaDeploymentVO probe, String peerIp) {
        List<String> steps = new ArrayList<>();
        steps.add("第 1 步（本页完成）：打开「启用主备」开关，填写员工访问域名与虚拟 IP（Web / 数据库），保存。"
                + "系统会自动把本机登记为主节点。");
        steps.add("第 2 步（本页完成）：点「添加备节点」，填入备机 IP"
                + (StringUtils.hasText(peerIp) ? "（当前：" + peerIp + "）" : "")
                + "。登记后系统开始等待该节点上报心跳。");
        steps.add("第 3 步（需在目标机器上以 root 执行一次 —— 这一步系统无法代替，"
                + "因为渲染 keepalived 配置、建立数据库复制都需要目标机的真实网络环境与 root 权限）：");
        steps.add("    3.1 把下方「环境变量片段」粘贴进两台机器的 deploy/ha/.env.ha，"
                + "并把其中的 __CHANGE_ME__ 换成真实值（两台机器的 JWT_SECRET 与 INTERNAL_ALERT_TOKEN 必须完全一致）；");
        steps.add("    3.2 两台机器各执行：bash scripts/preflight-ha.sh（检查端口、网卡、VIP 是否可用）；");
        steps.add("    3.3 在主节点执行：bash scripts/setup-replication.sh（建立复制并完成首次全量同步）；");
        steps.add("    3.4 两台机器各执行：sudo bash scripts/install-keepalived.sh"
                + "（安装 VIP 漂移配置与切换通知钩子）；");
        steps.add("    3.5 两台机器各执行：sudo bash scripts/install-ha-heartbeat.sh（安装心跳上报）。"
                + "本页的「节点状态」「最后心跳」与「同步延迟」全部由它更新 —— "
                + "不装这一步，节点会一直停在「未知」；");
        steps.add("    3.6 回到本页刷新 —— 节点状态应变为「运行中 / 待命」，"
                + "「最后同步时间」开始更新。若仍为「未知」，说明心跳尚未接通，"
                + "最常见的原因是两台机器的 INTERNAL_ALERT_TOKEN 不一致（上报会被 401 拒绝），"
                + "可用 bash scripts/ha-heartbeat.sh --once 手工执行一次看具体报错。");
        if (!probe.dryRunEnabled() && probe.switchoverExecutable()) {
            steps.add("提示：当前演练模式已关闭，页面上的「手动切换主备」会真实改变虚拟 IP 归属，请谨慎操作。");
        }
        return steps;
    }

    private String buildEnvSnippet(HaConfig config, String localName, String localIp,
                                   String peerIp, String localRole, List<HaNode> nodes) {
        boolean master = HaRole.MASTER.name().equals(localRole);
        StringBuilder sb = new StringBuilder();
        sb.append("# ---------------------------------------------------------------------\n");
        sb.append("# 主备双机高可用 · HA 段\n");
        sb.append("# 由「系统设置 → 主备配置」页按当前配置生成，请复制到两台机器的\n");
        sb.append("# deploy/ha/.env.ha 中，并按实际环境核对每一行。\n");
        sb.append("#\n");
        sb.append("# ⚠️ HA_NODE_ROLE 是【节点标识】（仅用于提示与文档对照，如 node-a / node-b），\n");
        sb.append("#    与页面上的【运行期角色】（主节点 / 备节点）不是同一件事：\n");
        sb.append("#    运行期角色由 keepalived 的 priority + nopreempt 决定，会随故障漂移；\n");
        sb.append("#    节点标识在部署时写死，永不变化。两者不要混为一谈。\n");
        sb.append("# ---------------------------------------------------------------------\n\n");
        sb.append("HA_NODE_NAME=").append(blankToPlaceholder(localName, "ticket-a")).append('\n');
        sb.append("HA_NODE_ROLE=").append(blankToPlaceholder(localName, "node-a")).append('\n');
        sb.append("HA_NODE_IP=").append(blankToPlaceholder(localIp, "__CHANGE_ME__")).append('\n');
        sb.append("HA_PEER_IP=").append(blankToPlaceholder(peerIp, "__CHANGE_ME__")).append('\n');
        // 优先级：主节点 150 / 备节点 100（与 .env.ha.example 的约定一致）
        sb.append("HA_NODE_PRIORITY=").append(master ? PRIORITY_MASTER : PRIORITY_STANDBY).append('\n');
        sb.append("MYSQL_NODE_CONF=").append(master ? "node-a.cnf" : "node-b.cnf").append('\n');
        sb.append("REDIS_REPLICAOF=").append(master ? "" : blankToPlaceholder(peerIp, "__CHANGE_ME__") + " 6379").append('\n');
        sb.append("REDIS_ANNOUNCE_IP=").append(blankToPlaceholder(localIp, "__CHANGE_ME__")).append('\n');
        sb.append('\n');
        sb.append("VIP_WEB=").append(blankToPlaceholder(config.getVipWeb(), "__CHANGE_ME__")).append('\n');
        sb.append("VIP_DB=").append(blankToPlaceholder(config.getVipDb(), "__CHANGE_ME__")).append('\n');
        sb.append("VIP_MASK=").append(DEFAULT_VIP_MASK).append('\n');
        sb.append("HA_VRRP_IFACE=").append(blankToPlaceholder(config.getVrrpIface(), "eth0")).append('\n');
        sb.append("HA_VRRP_ROUTER_ID_WEB=").append(DEFAULT_VRRP_ID_WEB).append('\n');
        sb.append("HA_VRRP_ROUTER_ID_DB=").append(DEFAULT_VRRP_ID_DB).append('\n');
        sb.append("HA_VRRP_AUTH_PASS=__CHANGE_ME__\n");
        sb.append('\n');
        // 已登记的对端节点，便于维护人员核对 HA_PEER_IP 是否正确
        if (nodes != null && !nodes.isEmpty()) {
            sb.append("# 已登记节点（本页「节点列表」）：\n");
            for (HaNode node : nodes) {
                sb.append("#   ").append(blankToPlaceholder(node.getNodeName(), "(未命名)"))
                        .append("  ").append(node.getNodeIp())
                        .append("  ").append(Boolean.TRUE.equals(node.getIsLocal()) ? "本机" : "对端")
                        .append("  ").append(HaRole.labelOf(node.getNodeRole()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 渲染 keepalived 配置。
     *
     * <p>模板里 {@code __VRRP_AUTH_PASS__} 一律替换成 {@code __CHANGE_ME__}：
     * 认证口令只存在于 {@code .env.ha}，系统不知道也不应该知道它的值（见类头注释）。
     */
    private String buildKeepalivedConf(HaDeploymentVO probe, HaConfig config,
                                       String localIp, String peerIp, String localRole) {
        if (!probe.deployDirExists() || !probe.keepalivedConfPresent()) {
            return null;
        }
        Path tmpl = deployDirOrNull().resolve(KEEPALIVED_TMPL);
        String text;
        try {
            text = Files.readString(tmpl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[主备] 读取 keepalived 模板失败：{}", e.getMessage());
            return null;
        }
        boolean master = HaRole.MASTER.name().equals(localRole);
        // 用 LinkedHashMap 而不是 Map.of：占位符有 11 个，超过 Map.of 的 10 对上限
        // （Map.of 最多支持 10 组键值；超过后会编译报「找不到合适的方法」）
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("__ROUTER_ID__", sanitize(config.getNodeName(), "ticket-a"));
        values.put("__IFACE__", sanitize(config.getVrrpIface(), "eth0"));
        values.put("__VRRP_ID_WEB__", DEFAULT_VRRP_ID_WEB);
        values.put("__VRRP_ID_DB__", DEFAULT_VRRP_ID_DB);
        values.put("__PRIORITY__", String.valueOf(master ? PRIORITY_MASTER : PRIORITY_STANDBY));
        values.put("__NODE_IP__", sanitize(localIp, "__CHANGE_ME__"));
        values.put("__PEER_IP__", sanitize(peerIp, "__CHANGE_ME__"));
        values.put("__VIP_WEB__", sanitize(config.getVipWeb(), "__CHANGE_ME__"));
        values.put("__VIP_DB__", sanitize(config.getVipDb(), "__CHANGE_ME__"));
        values.put("__VIP_MASK__", DEFAULT_VIP_MASK);
        values.put("__VRRP_AUTH_PASS__", "__CHANGE_ME__");
        // 按 key 长度降序替换：__VIP_MASK__ 之类的短名若是先替换，
        // 不会误伤其它 key，但保持这个习惯可以避免将来加入前缀重叠的占位符时踩坑
        for (Map.Entry<String, String> entry : values.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .collect(Collectors.toList())) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    // ------------------------------------------------------------------
    // 取值辅助
    // ------------------------------------------------------------------

    /** 对端（非本机）节点 IP；没有对端时返回 null */
    private String resolvePeerIp(List<HaNode> nodes) {
        if (nodes == null) {
            return null;
        }
        return nodes.stream()
                .filter(node -> !Boolean.TRUE.equals(node.getIsLocal()))
                .map(HaNode::getNodeIp)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    /** 本机 IP：优先取本机节点行，其次取配置上的 nodeIp */
    private String resolveLocalIp(HaConfig config, List<HaNode> nodes) {
        if (nodes != null) {
            for (HaNode node : nodes) {
                if (Boolean.TRUE.equals(node.getIsLocal()) && StringUtils.hasText(node.getNodeIp())) {
                    return node.getNodeIp();
                }
            }
        }
        return StringUtils.hasText(config.getNodeIp()) ? config.getNodeIp() : null;
    }

    /** 本机运行期角色；尚未登记本机节点时返回 null */
    private String resolveLocalRole(List<HaNode> nodes) {
        if (nodes == null) {
            return null;
        }
        return nodes.stream()
                .filter(node -> Boolean.TRUE.equals(node.getIsLocal()))
                .map(HaNode::getNodeRole)
                .findFirst()
                .orElse(null);
    }

    private String resolveLocalName(HaConfig config, List<HaNode> nodes) {
        if (nodes != null) {
            for (HaNode node : nodes) {
                if (Boolean.TRUE.equals(node.getIsLocal()) && StringUtils.hasText(node.getNodeName())) {
                    return node.getNodeName();
                }
            }
        }
        return config.getNodeName();
    }

    private String blankToPlaceholder(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    /**
     * 清理用于渲染配置文件的值：换行与引号会破坏配置文件结构。
     *
     * <p>本方法服务于「生成的文本要能直接被 keepalived 解析」这一目标：
     * 一个含换行的节点名会让 {@code router_id} 变成两行，keepalived 启动时
     * 直接报语法错误 —— 而这个错误要到维护人员把内容拷到目标机器后才发现。
     */
    private String sanitize(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String cleaned = value.trim().replaceAll("[\\r\\n\\t\"'\\\\]", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
