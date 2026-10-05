package com.enterprise.ticket.module.ha.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeCreateRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeUpdateRequest;
import com.enterprise.ticket.module.ha.dto.vo.HaActionResultVO;
import com.enterprise.ticket.module.ha.dto.vo.HaConfigVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeployGuideVO;
import com.enterprise.ticket.module.ha.dto.vo.HaNodeVO;
import com.enterprise.ticket.module.ha.dto.vo.HaOverviewVO;
import com.enterprise.ticket.module.ha.entity.HaConfig;
import com.enterprise.ticket.module.ha.entity.HaNode;
import com.enterprise.ticket.module.ha.mapper.HaConfigMapper;
import com.enterprise.ticket.module.ha.mapper.HaNodeMapper;
import com.enterprise.ticket.module.ha.service.HaAlertNotifier;
import com.enterprise.ticket.module.ha.service.HaConfigService;
import com.enterprise.ticket.module.ha.support.HaConfigValidator;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.module.ha.entity.HaNode;
import com.enterprise.ticket.module.ha.support.HaDeploySupport;
import com.enterprise.ticket.module.ha.support.HaLocalAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主备配置服务实现
 *
 * <h2>⚠️ 保存为什么必须用 {@code LambdaUpdateWrapper} 而不是 {@code updateById(entity)}</h2>
 * <p>这是本项目<b>已经踩过两次</b>的坑（ 的部门解绑 / 清空备注、 的 AD 基础 DN /
 * 手机号属性），必须在这里显式说明，避免第三次复发：
 * 全局配置是 {@code update-strategy: not_null}，实体里的 null 字段<b>不会进 SET 子句</b>。
 * 于是「把域名留空 = 改成直连 IP 访问」这类操作会<b>静默失效</b> ——
 * 旧域名留在库里，界面预览显示的是新值，而真实访问入口还是老的。
 *
 * <p>配套的类型约束：{@code domain / vip_web / vip_db / vrrp_iface / node_name}
 * 在 DDL 里全是 {@code NOT NULL DEFAULT ''} ——「没有值」的既有表示是<b>空串</b>，
 * 写成 NULL 会被数据库直接拒掉（{@code Column 'domain' cannot be null}）。
 *
 * <h2>启用的两种「副作用」以及为什么它们放在保存里</h2>
 * <ol>
 *   <li><b>自动登记本机节点</b>：需求 [163] 要求「打开开关后下面才可用」，
 *       而节点列表里必须至少有一行本机，否则页面顶部「当前节点角色」永远是未知态。
 *       把它放在「保存并启用」这个动作里，是因为那是系统第一次知道
 *       「这台机器要参与主备」的时刻。</li>
 *   <li><b>不自动登记备节点</b>：备节点必须由维护人员显式添加（第 2 步），
 *       系统<b>不会</b>因为收到某个 IP 的心跳就凭空建一行节点 ——
 *       那等于让任何一个能访问内网心跳端点的人都能往节点列表里塞机器。
 *       详见 {@code HaHeartbeatService#report}。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HaConfigServiceImpl implements HaConfigService {

    /** 本机节点在维护人员没起名字时的兜底名（与 deploy/ha/.env.ha.example 的示例一致） */
    private static final String DEFAULT_LOCAL_NODE_NAME = "ticket-master";

    /** 备节点的兜底名前缀（后接 IP，便于在列表里一眼分辨） */
    private static final String DEFAULT_PEER_NODE_NAME_PREFIX = "备节点 ";

    /**
     * 允许的切换动作。
     *
     * <p>白名单校验必须存在：{@code action} 会被拼进交给 {@code ProcessBuilder} 的参数列表。
     * 虽然 {@code ProcessBuilder} 不经过 shell（结构上无注入风险），
     * 但一个拼错的动作名会让脚本以「未知子命令」退出，错误信息又长又难懂；
     * 在入口直接拒绝，维护人员拿到的是一句能看懂的话。
     */
    private static final java.util.Set<String> SWITCHOVER_ACTIONS =
            java.util.Set.of("to-peer", "back", "status");

    private final HaConfigMapper haConfigMapper;
    private final HaNodeMapper haNodeMapper;
    private final HaDeploySupport deploySupport;
    private final HaAlertNotifier alertNotifier;

    /** 复用同一个客户端：joinCluster 是低频动作，但每次新建会重复建连接池 */
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    @Override
    public HaConfig current() {
        HaConfig config = haConfigMapper.selectOne(Wrappers.<HaConfig>lambdaQuery()
                .orderByAsc(HaConfig::getId)
                .last("LIMIT 1"));
        if (config == null) {
            // 正常路径下 V38 已播种唯一一行；此处兜底是为了「迁移被人工跳过」的环境
            // 也能打开配置页，而不是直接 500（与 AdConfigServiceImpl#current 同款兜底）
            HaConfig created = new HaConfig();
            created.setSingletonKey(1);
            created.setEnabled(false);
            // 文本列在 DDL 里是 NOT NULL DEFAULT ''，此处显式给空串 —— 让「兜底插入」
            // 不依赖数据库默认值也能成立（否则 insert 会带 null 被拒）
            created.setNodeName("");
            created.setNodeIp("");
            created.setDomain("");
            created.setVipWeb("");
            created.setVipDb("");
            created.setVrrpIface("");
            created.setHeartbeatTimeoutSeconds(HaConfigValidator.DEFAULT_HEARTBEAT_SECONDS);
            created.setSyncState(HaSyncState.UNKNOWN.name());
            haConfigMapper.insert(created);
            log.warn("ha_config 无数据行，已按默认值补建（请到「主备配置」页补全后启用）");
            return created;
        }
        return config;
    }

    @Override
    public HaOverviewVO overview() {
        HaConfig config = current();
        List<HaNode> nodes = haNodeMapper.selectAllOrdered();
        LocalDateTime now = LocalDateTime.now();

        List<HaNodeVO> nodeVOs = nodes.stream()
                .map(node -> HaNodeVO.of(node, heartbeatAgeSeconds(node, now)))
                .toList();

        HaNode local = nodes.stream()
                .filter(node -> Boolean.TRUE.equals(node.getIsLocal()))
                .findFirst()
                .orElse(null);

        String localRole = local == null ? null : local.getNodeRole();
        String localStatus = local == null ? HaNodeStatus.UNKNOWN.name() : local.getNodeStatus();

        return new HaOverviewVO(
                Boolean.TRUE.equals(config.getEnabled()),
                HaConfigVO.of(config),
                localRole,
                localRole == null ? null : HaRole.labelOf(localRole),
                localStatus,
                HaNodeStatus.labelOf(localStatus),
                nodeVOs,
                deploySupport.probe());
    }

    /**
     * 距最后心跳的秒数。
     *
     * <p>在服务层算好而不是让前端拿 {@code lastHeartbeatAt} 自己减：
     * 前端的时钟与服务器时钟<b>不一定一致</b>（客户端时区 / 时间未同步都很常见），
     * 用本地时间做减法会把「服务器 3 分钟前的心跳」算成「59 分钟后」这类荒谬结果。
     * 服务端算，客户端只展示。
     */
    private Long heartbeatAgeSeconds(HaNode node, LocalDateTime now) {
        LocalDateTime last = node.getLastHeartbeatAt();
        if (last == null) {
            return null;
        }
        long seconds = Duration.between(last, now).getSeconds();
        return Math.max(seconds, 0L);
    }

    // ------------------------------------------------------------------
    // 保存配置（第 1 步）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(HaConfigRequest request) {
        HaConfig config = current();
        boolean enable = Boolean.TRUE.equals(request.getEnabled());

        // 完整性校验只在启用时强制：允许先存草稿，否则维护人员拿不到 VIP 时无法暂存
        HaConfigValidator.validateConfig(request, enable);

        // 文本列一律落空串（DDL 是 NOT NULL DEFAULT ''），保证「留空 = 清空」在类型层面成立
        String nodeName = trimToEmpty(request.getNodeName());
        String domain = trimToEmpty(request.getDomain());
        String vipWeb = trimToEmpty(request.getVipWeb());
        String vipDb = trimToEmpty(request.getVipDb());
        String iface = trimToEmpty(request.getVrrpIface());
        int heartbeat = HaConfigValidator.normalizeHeartbeat(request.getHeartbeatTimeoutSeconds());

        // ⚠️ 显式逐列 set（见类头注释）。这里刻意把每一个可清空的列都列出来，
        //    让「本方法支持清空哪些列」在代码里一眼可见 —— 新增一个可清空字段时，
        //    漏加一行会让它静默无法清空，而那种缺陷不会有任何报错。
        LambdaUpdateWrapper<HaConfig> update = Wrappers.<HaConfig>lambdaUpdate()
                .eq(HaConfig::getId, config.getId())
                .set(HaConfig::getEnabled, enable)
                .set(HaConfig::getNodeName, nodeName)
                .set(HaConfig::getDomain, domain)
                .set(HaConfig::getVipWeb, vipWeb)
                .set(HaConfig::getVipDb, vipDb)
                .set(HaConfig::getVrrpIface, iface)
                .set(HaConfig::getHeartbeatTimeoutSeconds, heartbeat);
        haConfigMapper.update(null, update);

        if (enable) {
            ensureLocalNodeRegistered(config, nodeName);
        }

        log.info("[主备] 配置已保存：enabled={}，domain={}，vipWeb={}，vipDb={}，iface={}，心跳超时={}s",
                enable, display(domain), display(vipWeb), display(vipDb), display(iface), heartbeat);
    }

    /**
     * 启用时确保本机节点已登记（幂等）。
     *
     * <p>三种情形的处理顺序是刻意的：
     * <ol>
     *   <li>已有本机节点 → 什么都不做（重复保存不会产生第二行本机节点）；</li>
     *   <li>本机 IP 已作为「备节点」登记过 → 把它<b>提升</b>为本机主节点。
     *       这个情形真实存在：维护人员在两台机器上都打开了配置页，
     *       在 A 机上把 B 机的 IP 加成了备节点，之后又在 B 机上点了启用 ——
     *       此时 B 机会发现「我的 IP 已经有一行了」，正确地做法是把它认领为本机，
     *       而不是插入第二行同 IP（那会直接撞唯一键报错）。</li>
     *   <li>都没有 → 新建本机行。</li>
     * </ol>
     */
    private void ensureLocalNodeRegistered(HaConfig config, String nodeName) {
        if (haNodeMapper.selectLocal() != null) {
            return;
        }
        // 本机 IP 由环境探测（容器下得到的是容器地址，见 HaLocalAddress 类头注释）；
        // 探测不到就留空串，等心跳上报时再回填 —— 绝不编造一个地址
        String localIp = HaLocalAddress.detectIpv4()
                .orElseGet(() -> trimToEmpty(config.getNodeIp()));

        if (StringUtils.hasText(localIp)) {
            HaNode existing = haNodeMapper.selectByIp(localIp);
            if (existing != null) {
                haNodeMapper.clearLocalFlags();
                haNodeMapper.markLocalWithRole(existing.getId(), HaRole.MASTER.name());
                haConfigMapper.updateLocalIdentity(config.getId(),
                        StringUtils.hasText(nodeName) ? nodeName : existing.getNodeName(), localIp);
                log.info("[主备] 本机 IP {} 已作为备节点登记过，已认领为本机主节点", localIp);
                return;
            }
        }

        haNodeMapper.clearLocalFlags();
        HaNode node = new HaNode();
        node.setNodeName(StringUtils.hasText(nodeName) ? nodeName : DEFAULT_LOCAL_NODE_NAME);
        node.setNodeIp(localIp);
        node.setNodeRole(HaRole.MASTER.name());
        node.setNodeStatus(HaNodeStatus.UNKNOWN.name());
        node.setIsLocal(true);
        node.setRemark("");
        haNodeMapper.insert(node);
        haConfigMapper.updateLocalIdentity(config.getId(), node.getNodeName(), localIp);
        log.info("[主备] 已登记本机节点：name={} ip={}（IP 为空表示尚未探测到，将由心跳上报回填）",
                node.getNodeName(), display(localIp));
    }

    // ------------------------------------------------------------------
    // 节点（第 2 步）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HaNodeVO addNode(HaNodeCreateRequest request) {
        HaConfig config = current();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.HA_DISABLED,
                    "请先打开「启用主备」开关并保存，再添加备节点。");
        }
        HaConfigValidator.validateNodeIp(request.getNodeIp());
        String ip = request.getNodeIp().trim();

        if (haNodeMapper.selectByIp(ip) != null) {
            throw new BusinessException(ErrorCode.HA_NODE_IP_EXISTS,
                    "IP " + ip + " 的节点已登记，请勿重复添加。");
        }

        // ⚠️ request.getAdminPassword() 在此刻意不使用：既不落库、不进日志、也不出现在响应里。
        //    本行注释即为它在本模块中的全部痕迹 —— 设计理由见 HaNodeCreateRequest 类头注释
        //    （本系统不做「拿维护人员的口令去登录备机」这件事）。

        // 本系统的主备部署资产是严格的双机形态（keepalived 模板的 unicast_peer 只接受一个对端），
        // 因此只允许一个备节点。放开这个限制会生成一份「配置里有三台、实际只能跑两台」的部署片段，
        // 而那正是需求 [157] 抱怨的「文档与实际对不上」。
        long peerCount = haNodeMapper.selectAllOrdered().stream()
                .filter(node -> !Boolean.TRUE.equals(node.getIsLocal()))
                .count();
        if (peerCount >= 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "本系统采用主备双机形态，只能登记一个备节点。"
                            + "如需更换备机，请先移除现有备节点再添加。");
        }

        HaNode node = new HaNode();
        node.setNodeName(StringUtils.hasText(request.getNodeName())
                ? request.getNodeName().trim() : DEFAULT_PEER_NODE_NAME_PREFIX + ip);
        node.setNodeIp(ip);
        node.setNodeRole(HaRole.STANDBY.name());
        // 状态从「未知」开始：此时系统还没收到过它任何心跳，
        // 直接标成「待命」会让人以为握手已经完成
        node.setNodeStatus(HaNodeStatus.UNKNOWN.name());
        node.setIsLocal(false);
        node.setRemark(trimToEmpty(request.getRemark()));
        haNodeMapper.insert(node);

        log.info("[主备] 已登记备节点：name={} ip={}", node.getNodeName(), ip);
        return HaNodeVO.of(node, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HaNodeVO updateNode(Long id, HaNodeUpdateRequest request) {
        HaNode node = requireNode(id);
        // 同样用逐列 set：这两列都允许「留空 = 清空」
        LambdaUpdateWrapper<HaNode> update = Wrappers.<HaNode>lambdaUpdate()
                .eq(HaNode::getId, node.getId())
                .set(HaNode::getNodeName, trimToEmpty(request.getNodeName()))
                .set(HaNode::getRemark, trimToEmpty(request.getRemark()));
        haNodeMapper.update(null, update);
        HaNode updated = haNodeMapper.selectById(node.getId());
        log.info("[主备] 节点信息已更新：id={} ip={}", id, node.getNodeIp());
        return HaNodeVO.of(updated, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeNode(Long id) {
        HaNode node = requireNode(id);
        if (Boolean.TRUE.equals(node.getIsLocal())) {
            // 删掉本机行会让「当前节点角色」失去依据，页面立刻退化为未知态 ——
            // 那不是一次操作失败，而是把一个可持续观察的状态永久破坏掉
            throw new BusinessException(ErrorCode.HA_SELF_NODE_FORBIDDEN,
                    "不能移除本机节点。本机节点由「启用主备」开关自动登记，用于标识当前机器的主备角色。");
        }
        haNodeMapper.deleteById(id);
        log.info("[主备] 已移除备节点：id={} ip={}", id, node.getNodeIp());
    }

    private HaNode requireNode(Long id) {
        HaNode node = id == null ? null : haNodeMapper.selectById(id);
        if (node == null) {
            throw new BusinessException(ErrorCode.HA_NODE_NOT_FOUND);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // 运维动作
    // ------------------------------------------------------------------

    @Override
    public HaActionResultVO switchover(String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SWITCHOVER_ACTIONS.contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的切换动作：「" + action + "」。可取值为 to-peer（本机让出）、"
                            + "back（切回本机）、status（仅查询维护标记）。");
        }
        HaConfig config = current();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.HA_DISABLED,
                    "主备未启用，无法执行切换。请先打开「启用主备」开关。");
        }

        HaActionResultVO result = deploySupport.switchover(normalized);

        // ⚠️ 只有在「真实执行且成功」时才改角色 / 记切换时间 / 发告警。
        //    演练模式（dry-run）下什么都没发生 —— 若在这里记账，就会出现
        //    「页面上写着 3 分钟前切换过、实际上一次都没切」这种凭空造出来的事实，
        //    而它恰好是最能误导排障方向的一类假数据。
        if (!"status".equals(normalized) && !result.dryRun() && result.success()) {
            applySwitchoverEffects(config, normalized, result.command());
        }
        return result;
    }

    /**
     * 切换成功后的记账与告警。
     *
     * <p>三件事必须同时发生，缺一都会留下不自洽的状态：
     * <ol>
     *   <li>改本机节点角色 —— 否则页面顶部还显示着旧角色；</li>
     *   <li>写 {@code last_switch_at} —— 否则「最近一次切换」永远是空的；</li>
     *   <li>发切换告警 —— 需求 [184] 明确要求，且手动切换同样需要留痕
     *       （「谁在什么时候因为什么把主备换过来了」是运维审计里必查的一条）。</li>
     * </ol>
     */
    private void applySwitchoverEffects(HaConfig config, String action, String command) {
        String newRole = "to-peer".equals(action) ? HaRole.STANDBY.name() : HaRole.MASTER.name();
        HaNode local = haNodeMapper.selectLocal();
        if (local != null && !newRole.equals(local.getNodeRole())) {
            haNodeMapper.updateRole(local.getId(), newRole);
        }
        LocalDateTime now = LocalDateTime.now();
        haConfigMapper.markSwitched(config.getId(), now);

        String nodeIp = local == null ? config.getNodeIp() : local.getNodeIp();
        alertNotifier.notifySwitchover(nodeIp, newRole, "手动切换",
                "由管理员在本页点击「手动切换主备」触发，执行命令：" + command);
    }

    @Override
    public HaActionResultVO syncNow() {
        HaConfig config = current();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.HA_DISABLED,
                    "主备未启用，无法执行同步。请先打开「启用主备」开关。");
        }
        // 成功刻意不发告警：与备份告警同一口径 ——
        // 「每次同步成功都推一条消息」会在 30 天内堆出 30 条没人读的噪音，
        // 最终让真正失败的告警也一起被忽略。
        return deploySupport.syncNow();
    }

    // ------------------------------------------------------------------
    // 一键化
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HaActionResultVO enable(HaConfigRequest request) {
        request.setEnabled(true);
        // 复用既有 save：它已经做了「完整性校验 + 逐列显式 set + 登记本机节点」三件事。
        // 刻意不另写一份 —— 两条路径的校验一旦漂移，会出现「按钮能启用、保存不能启用」这类
        // 只在一侧复现的问题。
        save(request);
        return new HaActionResultVO(true, false, "ENABLE", "", null, "",
                "主节点已启用：本机已登记为 MASTER，心跳由后端内置任务自动上报（无需外部脚本）。\n"
                        + "⚠️ 本系统不引入 keepalived：故障后需要**手工或脚本切换 DNS / 虚拟 IP**，"
                        + "不会自动切换。请在部署文档里记录切换步骤。");
    }

    @Override
    public java.util.Map<String, Object> exportConfig() {
        HaConfig config = current();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("enabled", config.getEnabled());
        data.put("nodeName", config.getNodeName());
        data.put("domain", config.getDomain());
        data.put("vipWeb", config.getVipWeb());
        data.put("vipDb", config.getVipDb());
        data.put("vrrpIface", config.getVrrpIface());
        data.put("heartbeatTimeoutSeconds", config.getHeartbeatTimeoutSeconds());
        // 主节点 IP 由**主节点自己探测**后下发：备节点无法从外部可靠地推断出
        // 「主节点认为自己是哪个地址」（多网卡 / 容器环境下尤其如此）。
        data.put("masterIp", HaLocalAddress.detectIpv4().orElse(config.getNodeIp()));
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HaActionResultVO joinCluster(String masterIp, String joinToken) {
        if (!StringUtils.hasText(masterIp)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写主节点 IP");
        }
        if (!StringUtils.hasText(joinToken)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "请填写加入令牌（主节点页面上的「内部通道令牌」）");
        }
        String ip = masterIp.trim();

        // 1) 拉主节点配置（失败必须**明确报错**，不能静默用本地旧配置 —— 那会让两台机器
        //    的域名 / VIP / 心跳阈值各不相同，而页面显示「已加入集群」）
        java.util.Map<String, Object> exported = fetchMasterConfig(ip, joinToken.trim());

        // 2) 落本地配置（enabled=true，沿用主节点的全部参数）
        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setNodeName(str(exported.get("nodeName")));
        request.setDomain(str(exported.get("domain")));
        request.setVipWeb(str(exported.get("vipWeb")));
        request.setVipDb(str(exported.get("vipDb")));
        request.setVrrpIface(str(exported.get("vrrpIface")));
        Object heartbeat = exported.get("heartbeatTimeoutSeconds");
        request.setHeartbeatTimeoutSeconds(heartbeat instanceof Number n ? n.intValue() : null);
        save(request);

        // 3) 本机登记为**备节点**（save 里默认登记成 MASTER，这里改过来 ——
        //    备节点把自己报成 MASTER 会让主节点的心跳判定做出错误决策）
        HaNode local = haNodeMapper.selectLocal();
        if (local != null) {
            haNodeMapper.markLocalWithRole(local.getId(), HaRole.STANDBY.name());
        }

        // 4) 把主节点登记为对端（否则心跳没有发送目标，页面永远只有一个节点）
        ensurePeerNodeRegistered(ip, str(exported.get("nodeName")));

        log.info("[主备] 已加入集群：master={}，本机角色=STANDBY", ip);
        return new HaActionResultVO(true, false, "JOIN_CLUSTER", "", null, "",
                "已加入集群：配置已从主节点下发，本机登记为备节点，心跳由后端内置任务自动上报。\n"
                        + "⚠️ MySQL 单向主从复制需要主节点上执行一次建复制账号的脚本（需要 root），"
                        + "页面无法代劳；未建复制时数据不会同步，备节点只承担「顶上」的角色。");
    }

    /**
     * 拉取主节点配置。
     *
     * <p>403 单独给一句能定位的话：它只可能来自「令牌不一致」，
     * 而维护人员的第一反应通常是去查网络。
     */
    private java.util.Map<String, Object> fetchMasterConfig(String masterIp, String token) {
        String url = "http://" + masterIp + ":8080/api/internal/ha/config";
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("X-Internal-Token", token)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "主节点拒绝了加入请求：加入令牌不正确（两台机器的 INTERNAL_ALERT_TOKEN 必须逐字一致）");
            }
            if (response.statusCode() >= 400) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "主节点返回 HTTP " + response.statusCode() + "，无法拉取配置");
            }
            return parseConfigJson(response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "无法连接主节点 " + masterIp + "：请确认该 IP 可达、应用已启动、端口为 8080。原因：" + e.getMessage());
        }
    }

    /**
     * 解析主节点返回的配置。
     *
     * <p>用 Jackson 的 {@code readTree} 而不是定义一个 DTO：这是**跨版本**的内部通道
     * （两台机器可能跑着不同版本的 jar），用 Map 取值天然容忍「对方多给了一个字段」，
     * 而 DTO 会在字段不匹配时抛反序列化异常 —— 那会把「版本略有差异」升级成「加不进集群」。
     */
    private java.util.Map<String, Object> parseConfigJson(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "主节点返回的配置为空");
            }
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            data.fields().forEachRemaining(entry -> map.put(entry.getKey(), asPlain(entry.getValue())));
            return map;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "主节点返回的配置无法解析：" + e.getMessage());
        }
    }

    private Object asPlain(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 把主节点登记为对端节点（幂等）。
     *
     * <p>没有这一步，心跳上报就没有目标（{@code HaHeartbeatReportJob} 只发给「非本机」的节点行），
     * 页面会一直只有一个节点 —— 现象像「加入失败」，其实只是缺一行。
     */
    private void ensurePeerNodeRegistered(String masterIp, String masterName) {
        if (haNodeMapper.selectByIp(masterIp) != null) {
            return;
        }
        HaNode peer = new HaNode();
        peer.setNodeName(StringUtils.hasText(masterName) ? masterName : (DEFAULT_PEER_NODE_NAME_PREFIX + masterIp));
        peer.setNodeIp(masterIp);
        peer.setNodeRole(HaRole.MASTER.name());
        peer.setNodeStatus(HaNodeStatus.UNKNOWN.name());
        peer.setIsLocal(false);
        haNodeMapper.insert(peer);
        log.info("[主备] 已登记主节点为对端：{}", masterIp);
    }

    @Override
    public HaDeployGuideVO guide() {
        return deploySupport.guide(current(), haNodeMapper.selectAllOrdered());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
