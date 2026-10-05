package com.enterprise.ticket.module.ha.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.common.constant.HaSyncState;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeCreateRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeUpdateRequest;
import com.enterprise.ticket.module.ha.dto.vo.HaActionResultVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeploymentVO;
import com.enterprise.ticket.module.ha.dto.vo.HaNodeVO;
import com.enterprise.ticket.module.ha.dto.vo.HaOverviewVO;
import com.enterprise.ticket.module.ha.entity.HaConfig;
import com.enterprise.ticket.module.ha.entity.HaNode;
import com.enterprise.ticket.module.ha.mapper.HaConfigMapper;
import com.enterprise.ticket.module.ha.mapper.HaNodeMapper;
import com.enterprise.ticket.module.ha.service.impl.HaConfigServiceImpl;
import com.enterprise.ticket.module.ha.support.HaDeploySupport;
import com.enterprise.ticket.module.ha.support.HaLocalAddress;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主备配置服务（Phase 19 批次 G）—— 单元测试
 *
 * <h2>为什么保存路径断言的是「SET 子句」而不是「实体字段」</h2>
 * <p>这是本项目<b>已经踩过两次</b>的坑（批次 D 的部门解绑 / 清空备注、
 * 批次 E 的 AD 基础 DN / 手机号属性）：全局 {@code update-strategy: not_null}
 * 会让实体里的 null 字段<b>不进 SET 子句</b>，于是「留空 = 清空」静默失效。
 * 而当时那些单测断言的是「实体上被设置了 null」，<b>恰好把 bug 判成绿的</b>。
 *
 * <p>因此本类沿用批次 E 确立的口径：捕获 {@code LambdaUpdateWrapper}，
 * 把它展开成「列名 → 落库值」，直接断言<b>真正会发出去的 SQL</b>。
 * 两条同时成立才算「清空生效」：
 * <ul>
 *   <li>{@code sets.containsKey(列)} —— 该列确实进了 SET 子句；</li>
 *   <li>{@code sets.get(列)} 是空串 —— 文本列在 DDL 里是 {@code NOT NULL DEFAULT ''}，
 *       「没有值」的既有表示就是空串（写 NULL 会被库直接拒绝）。</li>
 * </ul>
 *
 * <h2>另一条被钉住的口径：演练模式不许记账</h2>
 * <p>{@code switchover} 在 dry-run 下只返回命令、不执行脚本。若这时仍然
 * 改写角色 / 落 {@code last_switch_at} / 发切换告警，页面上就会出现
 * 「3 分钟前切换过」这种凭空造出来的事实 —— 而它最能误导排障方向。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主备配置服务（清空语义 / 节点管理 / 切换记账）")
class HaConfigServiceTest {

    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(HaConfig.class, HaNode.class);
    }

    @Mock
    private HaConfigMapper haConfigMapper;
    @Mock
    private HaNodeMapper haNodeMapper;
    @Mock
    private HaDeploySupport deploySupport;
    @Mock
    private HaAlertNotifier alertNotifier;

    @InjectMocks
    private HaConfigServiceImpl service;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 库里那一行配置（V38 播种的唯一一行） */
    private HaConfig storedConfig(boolean enabled) {
        HaConfig config = new HaConfig();
        config.setId(1L);
        config.setSingletonKey(1);
        config.setEnabled(enabled);
        config.setNodeName("ticket-master");
        config.setNodeIp("192.168.1.10");
        config.setDomain("oa.old.com");
        config.setVipWeb("192.168.1.100");
        config.setVipDb("192.168.1.101");
        config.setVrrpIface("eth0");
        config.setHeartbeatTimeoutSeconds(10);
        config.setSyncState(HaSyncState.UNKNOWN.name());
        return config;
    }

    private HaNode localNode() {
        HaNode node = new HaNode();
        node.setId(1L);
        node.setNodeName("ticket-master");
        node.setNodeIp("192.168.1.10");
        node.setNodeRole(HaRole.MASTER.name());
        node.setNodeStatus(HaNodeStatus.RUNNING.name());
        node.setIsLocal(true);
        node.setLastHeartbeatAt(LocalDateTime.now());
        return node;
    }

    private HaNode peerNode() {
        HaNode node = new HaNode();
        node.setId(2L);
        node.setNodeName("ticket-slave");
        node.setNodeIp("192.168.1.101");
        node.setNodeRole(HaRole.STANDBY.name());
        node.setNodeStatus(HaNodeStatus.UNKNOWN.name());
        node.setIsLocal(false);
        return node;
    }

    private HaDeploymentVO deploymentVO() {
        return new HaDeploymentVO(false, null, false, false, false, false, true, "未配置部署资产");
    }

    /** 跑一次 save，把将要执行的 SET 子句展开成「列名 → 落库值」 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> saveAndCaptureSets(HaConfigRequest request) {
        service.save(request);
        ArgumentCaptor<LambdaUpdateWrapper<HaConfig>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(haConfigMapper).update(nullable(HaConfig.class), captor.capture());
        return setsOf(captor.getValue());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> updateAndCaptureNodeSets(HaNodeUpdateRequest request) {
        ArgumentCaptor<LambdaUpdateWrapper<HaNode>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        service.updateNode(2L, request);
        verify(haNodeMapper).update(nullable(HaNode.class), captor.capture());
        return setsOf(captor.getValue());
    }

    /**
     * 展开 {@code sqlSet}。MyBatis-Plus 的 {@code set()} 按调用顺序给每个值分配
     * {@code MPGENVALn} 占位符，因此「sqlSet 里列的顺序」与「占位符编号」一一对应。
     */
    private static Map<String, Object> setsOf(LambdaUpdateWrapper<?> wrapper) {
        Map<String, Object> sets = new LinkedHashMap<>();
        Matcher m = Pattern
                .compile("([A-Za-z_]+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
                .matcher(wrapper.getSqlSet());
        while (m.find()) {
            sets.put(m.group(1), wrapper.getParamNameValuePairs().get(m.group(2)));
        }
        return sets;
    }

    private ErrorCode codeOf(Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        return ex.getErrorCode();
    }

    // ==================================================================
    // 保存：清空语义（本模块最容易被静默破坏的地方）
    // ==================================================================

    @Test
    @DisplayName("★ 全部可清空列留空时，每一列都必须仍进 SET 子句并以空串落库")
    void saveClearsEveryClearableColumn() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);   // 存草稿：不触发完整性校验
        Map<String, Object> sets = saveAndCaptureSets(request);

        for (String column : new String[]{"node_name", "domain", "vip_web", "vip_db", "vrrp_iface"}) {
            assertTrue(sets.containsKey(column),
                    "清空时 " + column + " **必须**出现在 SET 子句里；缺席意味着旧值被留下了"
                            + "（update-strategy=not_null 的静默跳过）");
            assertEquals("", sets.get(column),
                    column + " 落库值应为空串（该列 NOT NULL DEFAULT ''，写 NULL 会被库拒绝）");
        }
        assertFalse((Boolean) sets.get("enabled"));
    }

    @Test
    @DisplayName("保存正常值：逐列落库，心跳缺失时兜默认 10")
    void savePersistsProvidedValues() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        request.setNodeName("ticket-a");
        request.setDomain("oa.company.com");
        request.setVipWeb("192.168.1.100");
        request.setVipDb("192.168.1.101");
        request.setVrrpIface("ens192");
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertEquals("ticket-a", sets.get("node_name"));
        assertEquals("oa.company.com", sets.get("domain"));
        assertEquals("192.168.1.100", sets.get("vip_web"));
        assertEquals("192.168.1.101", sets.get("vip_db"));
        assertEquals("ens192", sets.get("vrrp_iface"));
        assertEquals(10, sets.get("heartbeat_timeout_seconds"),
                "未填心跳阈值时应落默认 10，而不是留空导致读库路径再兜一次");
    }

    @Test
    @DisplayName("保存时对文本做 trim（复制粘贴常带空白，空白若入库会污染 keepalived 渲染）")
    void saveTrimsTextValues() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        request.setDomain("  oa.company.com  ");
        request.setVrrpIface(" eth0 ");
        Map<String, Object> sets = saveAndCaptureSets(request);

        assertEquals("oa.company.com", sets.get("domain"));
        assertEquals("eth0", sets.get("vrrp_iface"));
    }

    @Test
    @DisplayName("保存时心跳阈值越界 → 在校验处被拒，一行都不写库")
    void saveRejectsOutOfRangeHeartbeat() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        request.setHeartbeatTimeoutSeconds(2);

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save(request)));
        verify(haConfigMapper, never()).update(nullable(HaConfig.class), any());
    }

    @Test
    @DisplayName("启用但缺 Web 虚拟 IP → HA_CONFIG_INCOMPLETE，拒绝保存")
    void saveRejectsIncompleteWhenEnabling() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setVipWeb(null);

        assertEquals(ErrorCode.HA_CONFIG_INCOMPLETE, codeOf(() -> service.save(request)));
        verify(haConfigMapper, never()).update(nullable(HaConfig.class), any());
    }

    @Test
    @DisplayName("两个虚拟 IP 相同 → 拒绝保存（防脑裂）")
    void saveRejectsIdenticalVips() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        request.setVipWeb("192.168.1.100");
        request.setVipDb("192.168.1.100");

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save(request)));
    }

    // ==================================================================
    // 保存：启用时自动登记本机节点（三种情形）
    // ==================================================================

    @Test
    @DisplayName("★ 情形①：已有本机节点 → 什么都不做（重复保存不产生第二行本机节点）")
    void enableKeepsExistingLocalNode() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));
        when(haNodeMapper.selectLocal()).thenReturn(localNode());

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setVipWeb("192.168.1.100");
        service.save(request);

        verify(haNodeMapper, never()).insert(any(HaNode.class));
        verify(haNodeMapper, never()).clearLocalFlags();
        verify(haNodeMapper, never()).markLocalWithRole(any(), any());
    }

    @Test
    @DisplayName("★ 情形②：本机 IP 已作为备节点登记过 → 认领为本机主节点，而不是插第二行（会撞唯一键）")
    void enableAdoptsPeerRowAsLocal() {
        HaConfig stored = storedConfig(false);
        when(haConfigMapper.selectOne(any())).thenReturn(stored);
        when(haNodeMapper.selectLocal()).thenReturn(null);
        // IP 无论探测成什么（沙箱里可能探测到真实网卡地址，也可能是 config.nodeIp），
        // 一律让它命中「已登记过的备节点行」—— 这样用例与运行环境无关
        when(haNodeMapper.selectByIp(any())).thenReturn(peerNode());

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setVipWeb("192.168.1.100");
        request.setNodeName("ticket-a");
        service.save(request);

        verify(haNodeMapper).clearLocalFlags();
        verify(haNodeMapper).markLocalWithRole(eq(2L), eq(HaRole.MASTER.name()));
        verify(haNodeMapper, never()).insert(any(HaNode.class));
        // 本机身份（名字 / IP）必须同步写回配置行
        verify(haConfigMapper).updateLocalIdentity(eq(1L), eq("ticket-a"), any());
    }

    @Test
    @DisplayName("★ 情形③：都没有 → 新建本机行（主节点 / 未知态 / is_local=1）")
    void enableCreatesLocalRow() {
        HaConfig stored = storedConfig(false);
        when(haConfigMapper.selectOne(any())).thenReturn(stored);
        when(haNodeMapper.selectLocal()).thenReturn(null);
        when(haNodeMapper.selectByIp(any())).thenReturn(null);

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(true);
        request.setVipWeb("192.168.1.100");
        request.setNodeName("   ");   // 留空 → 走兜底名
        service.save(request);

        ArgumentCaptor<HaNode> captor = ArgumentCaptor.forClass(HaNode.class);
        verify(haNodeMapper).insert(captor.capture());
        HaNode created = captor.getValue();

        assertEquals("ticket-master", created.getNodeName(), "留空应兜底为 ticket-master");
        assertEquals(HaRole.MASTER.name(), created.getNodeRole());
        assertEquals(HaNodeStatus.UNKNOWN.name(), created.getNodeStatus(),
                "新登记的节点状态必须是「未知」，直接标「运行中」会让页面撒谎");
        assertEquals(true, created.getIsLocal());
        assertEquals("", created.getRemark());
        // IP 由环境探测决定（容器下是容器地址），因此只断言「与探测结果一致」；
        // 绝不编造一个地址是这个方法的硬约定
        String expectedIp = HaLocalAddress.detectIpv4().orElse("192.168.1.10");
        assertEquals(expectedIp, created.getNodeIp());
    }

    @Test
    @DisplayName("未启用时不登记本机节点（单机环境不该凭空多出一行）")
    void disabledDoesNotRegisterLocalNode() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaConfigRequest request = new HaConfigRequest();
        request.setEnabled(false);
        service.save(request);

        verify(haNodeMapper, never()).selectLocal();
        verify(haNodeMapper, never()).insert(any(HaNode.class));
    }

    // ==================================================================
    // 节点管理
    // ==================================================================

    @Test
    @DisplayName("添加备节点：落 STANDBY + UNKNOWN + is_local=0，名称按 IP 兜底")
    void addNodeCreatesPeer() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(null);
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(localNode()));

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1.101");
        HaNodeVO vo = service.addNode(request);

        ArgumentCaptor<HaNode> captor = ArgumentCaptor.forClass(HaNode.class);
        verify(haNodeMapper).insert(captor.capture());
        HaNode created = captor.getValue();

        assertEquals("192.168.1.101", created.getNodeIp());
        assertEquals("备节点 192.168.1.101", created.getNodeName());
        assertEquals(HaRole.STANDBY.name(), created.getNodeRole());
        assertEquals(HaNodeStatus.UNKNOWN.name(), created.getNodeStatus());
        assertEquals(false, created.getIsLocal());
        assertEquals("", created.getRemark());
        assertTrue(vo.removable(), "备节点可移除");
        assertNotNull(vo.nodeRoleLabel());
    }

    @Test
    @DisplayName("★ 添加备节点：管理员密码字段在实体层面不存在（结构性保证不落库）")
    void addNodeNeverPersistsAdminPassword() {
        assertThrows(NoSuchFieldException.class, () -> HaNode.class.getDeclaredField("adminPassword"),
                "ha_node 实体不应有密码字段 —— 「不落库」要靠结构保证，而不是靠记得不写");
        assertThrows(NoSuchFieldException.class, () -> HaConfig.class.getDeclaredField("adminPassword"));

        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(null);
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(localNode()));

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1.101");
        request.setAdminPassword("S3cret-Root-Pass");   // 即使填了也不该有任何痕迹
        service.addNode(request);

        ArgumentCaptor<HaNode> captor = ArgumentCaptor.forClass(HaNode.class);
        verify(haNodeMapper).insert(captor.capture());
        HaNode created = captor.getValue();
        for (String value : new String[]{created.getNodeName(), created.getNodeIp(),
                created.getRemark(), created.getNodeRole(), created.getNodeStatus()}) {
            assertFalse(value != null && value.contains("S3cret"),
                    "落库的节点字段里出现了管理员口令：" + value);
        }
    }

    @Test
    @DisplayName("添加备节点：未启用主备 → HA_DISABLED（第 1 步没做就不能做第 2 步）")
    void addNodeRejectsWhenDisabled() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1.101");

        assertEquals(ErrorCode.HA_DISABLED, codeOf(() -> service.addNode(request)));
    }

    @Test
    @DisplayName("添加备节点：IP 非法 → HA_NODE_IP_INVALID")
    void addNodeRejectsInvalidIp() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1");

        assertEquals(ErrorCode.HA_NODE_IP_INVALID, codeOf(() -> service.addNode(request)));
        verify(haNodeMapper, never()).insert(any(HaNode.class));
    }

    @Test
    @DisplayName("添加备节点：IP 已登记 → HA_NODE_IP_EXISTS")
    void addNodeRejectsDuplicateIp() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(haNodeMapper.selectByIp("192.168.1.101")).thenReturn(peerNode());

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1.101");

        assertEquals(ErrorCode.HA_NODE_IP_EXISTS, codeOf(() -> service.addNode(request)));
    }

    @Test
    @DisplayName("★ 添加备节点：本系统是主备双机形态，第二个备节点被拒（否则生成连不上的部署片段）")
    void addNodeRejectsSecondPeer() {
        HaConfig config = storedConfig(true);
        when(haConfigMapper.selectOne(any())).thenReturn(config);
        when(haNodeMapper.selectByIp("192.168.1.102")).thenReturn(null);
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(localNode(), peerNode()));

        HaNodeCreateRequest request = new HaNodeCreateRequest();
        request.setNodeIp("192.168.1.102");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.addNode(request));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("只能登记一个备节点"), "应说明原因：" + ex.getMessage());
    }

    @Test
    @DisplayName("修改节点：名称与备注都允许清空（逐列 set，不用 updateById）")
    void updateNodeClearsNameAndRemark() {
        HaNode before = peerNode();
        HaNode after = peerNode();
        after.setNodeName("");
        after.setRemark("");
        when(haNodeMapper.selectById(2L)).thenReturn(before, after);

        HaNodeUpdateRequest request = new HaNodeUpdateRequest();
        request.setNodeName("   ");
        request.setRemark(null);
        Map<String, Object> sets = updateAndCaptureNodeSets(request);

        assertTrue(sets.containsKey("node_name"));
        assertTrue(sets.containsKey("remark"));
        assertEquals("", sets.get("node_name"));
        assertEquals("", sets.get("remark"));
    }

    @Test
    @DisplayName("修改节点：IP 与角色不可改（请求里根本没有这两个字段，改身份必须走「删+加」）")
    void updateNodeCannotChangeIpOrRole() {
        assertThrows(NoSuchFieldException.class,
                () -> HaNodeUpdateRequest.class.getDeclaredField("nodeIp"));
        assertThrows(NoSuchFieldException.class,
                () -> HaNodeUpdateRequest.class.getDeclaredField("nodeRole"));

        HaNode before = peerNode();
        when(haNodeMapper.selectById(2L)).thenReturn(before, before);

        HaNodeUpdateRequest request = new HaNodeUpdateRequest();
        request.setNodeName("ticket-b");
        Map<String, Object> sets = updateAndCaptureNodeSets(request);

        assertFalse(sets.containsKey("node_ip"), "更新语句里不应出现 node_ip");
        assertFalse(sets.containsKey("node_role"), "更新语句里不应出现 node_role");
    }

    @Test
    @DisplayName("修改节点：ID 不存在 → HA_NODE_NOT_FOUND")
    void updateNodeRejectsMissingId() {
        when(haNodeMapper.selectById(99L)).thenReturn(null);

        assertEquals(ErrorCode.HA_NODE_NOT_FOUND,
                codeOf(() -> service.updateNode(99L, new HaNodeUpdateRequest())));
    }

    @Test
    @DisplayName("★ 移除本机节点 → HA_SELF_NODE_FORBIDDEN（删掉它会让「当前节点角色」永久失去依据）")
    void removeNodeRejectsLocal() {
        when(haNodeMapper.selectById(1L)).thenReturn(localNode());

        assertEquals(ErrorCode.HA_SELF_NODE_FORBIDDEN, codeOf(() -> service.removeNode(1L)));
        verify(haNodeMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("移除备节点 → 物理删除")
    void removeNodeDeletesPeer() {
        when(haNodeMapper.selectById(2L)).thenReturn(peerNode());

        service.removeNode(2L);

        verify(haNodeMapper).deleteById(2L);
    }

    @Test
    @DisplayName("移除节点：id 为 null 或不存在 → HA_NODE_NOT_FOUND")
    void removeNodeRejectsMissing() {
        assertEquals(ErrorCode.HA_NODE_NOT_FOUND, codeOf(() -> service.removeNode(null)));
        when(haNodeMapper.selectById(99L)).thenReturn(null);
        assertEquals(ErrorCode.HA_NODE_NOT_FOUND, codeOf(() -> service.removeNode(99L)));
    }

    // ==================================================================
    // 运维动作：切换
    // ==================================================================

    @Test
    @DisplayName("切换动作白名单：未知动作 → PARAM_INVALID（且不触碰脚本）")
    void switchoverRejectsUnknownAction() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.switchover("to-master")));
        verify(deploySupport, never()).switchover(any());
    }

    @Test
    @DisplayName("切换动作容忍大小写与空白（前端偶发传 TO-PEER 不该报「不支持」）")
    void switchoverNormalizesAction() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.switchover("to-peer")).thenReturn(dryRunResult("SWITCHOVER_TO_PEER"));

        assertNotNull(service.switchover("  TO-PEER "));
        verify(deploySupport).switchover("to-peer");
    }

    @Test
    @DisplayName("切换动作：未启用 → HA_DISABLED")
    void switchoverRejectsWhenDisabled() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        assertEquals(ErrorCode.HA_DISABLED, codeOf(() -> service.switchover("to-peer")));
        verify(deploySupport, never()).switchover(any());
    }

    @Test
    @DisplayName("★ 演练模式（dry-run）不记账：不改角色、不落切换时间、不发告警")
    void switchoverDryRunDoesNotAccount() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.switchover("to-peer")).thenReturn(dryRunResult("SWITCHOVER_TO_PEER"));

        HaActionResultVO result = service.switchover("to-peer");

        assertTrue(result.dryRun());
        verify(haNodeMapper, never()).updateRole(any(), any());
        verify(haConfigMapper, never()).markSwitched(any(), any());
        verify(alertNotifier, never()).notifySwitchover(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 真实执行成功 → 改角色 / 落切换时间 / 发切换告警（三件事缺一不可）")
    void switchoverRealSuccessAccounts() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(haNodeMapper.selectLocal()).thenReturn(localNode());
        when(deploySupport.switchover("to-peer")).thenReturn(
                new HaActionResultVO(true, false, "SWITCHOVER_TO_PEER",
                        "sudo -n bash /opt/ticket/deploy/ha/scripts/switchover.sh to-peer",
                        0, "已标记本机为维护状态", "脚本执行成功。"));

        service.switchover("to-peer");

        // to-peer = 本机让出 ⇒ 本机角色变备节点
        verify(haNodeMapper).updateRole(eq(1L), eq(HaRole.STANDBY.name()));
        verify(haConfigMapper).markSwitched(eq(1L), any());
        verify(alertNotifier).notifySwitchover(eq("192.168.1.10"), eq(HaRole.STANDBY.name()),
                eq("手动切换"), any());
    }

    @Test
    @DisplayName("切回本机（back）→ 本机角色变主节点")
    void switchoverBackSetsMasterRole() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        HaNode stoodDown = localNode();
        stoodDown.setNodeRole(HaRole.STANDBY.name());
        when(haNodeMapper.selectLocal()).thenReturn(stoodDown);
        when(deploySupport.switchover("back")).thenReturn(
                new HaActionResultVO(true, false, "SWITCHOVER_BACK", "sudo -n bash x back", 0, "", ""));

        service.switchover("back");

        verify(haNodeMapper).updateRole(eq(1L), eq(HaRole.MASTER.name()));
        verify(alertNotifier).notifySwitchover(any(), eq(HaRole.MASTER.name()), any(), any());
    }

    @Test
    @DisplayName("★ status 只查询，永不记账（即使不是演练模式）")
    void switchoverStatusNeverAccounts() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.switchover("status")).thenReturn(
                new HaActionResultVO(true, false, "STATUS", "sudo -n bash x status", 0, "MAINT 不存在", ""));

        service.switchover("status");

        verify(haNodeMapper, never()).updateRole(any(), any());
        verify(haConfigMapper, never()).markSwitched(any(), any());
        verify(alertNotifier, never()).notifySwitchover(any(), any(), any(), any());
    }

    @Test
    @DisplayName("真实执行失败 → 不改角色、不记账、不发告警（失败不能留下「切换过」的假事实）")
    void switchoverFailureDoesNotAccount() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.switchover("to-peer")).thenReturn(
                new HaActionResultVO(false, false, "SWITCHOVER_TO_PEER", "cmd", 1, "ssh: 连接超时", ""));

        service.switchover("to-peer");

        verify(haNodeMapper, never()).updateRole(any(), any());
        verify(haConfigMapper, never()).markSwitched(any(), any());
        verify(alertNotifier, never()).notifySwitchover(any(), any(), any(), any());
    }

    @Test
    @DisplayName("脚本缺失等「请求本身不成立」的失败原样上抛（不是成功率 0，而是错误码）")
    void switchoverPropagatesScriptMissing() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.switchover("to-peer"))
                .thenThrow(new BusinessException(ErrorCode.HA_DEPLOY_DIR_MISSING, "未找到部署资产目录"));

        assertEquals(ErrorCode.HA_DEPLOY_DIR_MISSING, codeOf(() -> service.switchover("to-peer")));
    }

    // ==================================================================
    // 运维动作：立即同步
    // ==================================================================

    @Test
    @DisplayName("立即同步：未启用 → HA_DISABLED")
    void syncNowRejectsWhenDisabled() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(false));

        assertEquals(ErrorCode.HA_DISABLED, codeOf(() -> service.syncNow()));
        verify(deploySupport, never()).syncNow();
    }

    @Test
    @DisplayName("★ 立即同步成功刻意不发告警（每次成功都推消息会把消息中心刷成噪音）")
    void syncNowDoesNotAlertOnSuccess() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(deploySupport.syncNow()).thenReturn(
                new HaActionResultVO(true, false, "SYNC", "sudo -n bash x setup-replication.sh", 0, "ok", ""));

        service.syncNow();

        verify(alertNotifier, never()).notifySyncFailed(any(), any(), any(), any());
        verify(alertNotifier, never()).notifySwitchover(any(), any(), any(), any());
        verify(alertNotifier, never()).notifyNodeDisconnected(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================================================================
    // 总览
    // ==================================================================

    @Test
    @DisplayName("总览：本机角色 / 状态、节点顺序，以及「距最后心跳秒数」由服务端计算")
    void overviewAssemblesNodeState() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));

        HaNode local = localNode();
        local.setLastHeartbeatAt(LocalDateTime.now());
        HaNode peer = peerNode();
        peer.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(30));
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(local, peer));
        when(deploySupport.probe()).thenReturn(deploymentVO());

        HaOverviewVO overview = service.overview();

        assertTrue(overview.enabled());
        assertEquals(HaRole.MASTER.name(), overview.localRole());
        assertEquals("主节点", overview.localRoleLabel());
        assertEquals(HaNodeStatus.RUNNING.name(), overview.localStatus());
        assertEquals(2, overview.nodes().size());

        HaNodeVO peerVO = overview.nodes().get(1);
        assertNotNull(peerVO.heartbeatAgeSeconds());
        assertTrue(peerVO.heartbeatAgeSeconds() >= 29 && peerVO.heartbeatAgeSeconds() <= 32,
                "应约等于 30 秒，实际 " + peerVO.heartbeatAgeSeconds());
        assertTrue(peerVO.removable(), "备节点可移除");
        assertFalse(overview.nodes().get(0).removable(), "本机节点不可移除");
        assertNotNull(overview.config().displayAddress());
        assertEquals("oa.old.com", overview.config().displayAddress(), "域名优先于 VIP");
    }

    @Test
    @DisplayName("★ 距最后心跳秒数被钳到 ≥0（客户端与服务端时钟不一致时不出现负值）")
    void heartbeatAgeIsClampedToZero() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));

        HaNode local = localNode();
        // 模拟时钟回拨 / 上报方时间超前：心跳时间落在未来
        local.setLastHeartbeatAt(LocalDateTime.now().plusSeconds(120));
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(local));
        when(deploySupport.probe()).thenReturn(deploymentVO());

        HaOverviewVO overview = service.overview();

        assertEquals(0L, overview.nodes().get(0).heartbeatAgeSeconds().longValue(),
                "未来时间戳必须钳成 0，而不是展示一个负的「-120 秒前」");
    }

    @Test
    @DisplayName("总览：从未收到心跳 → heartbeatAgeSeconds 为 null（前端据此显示「从未上报」）")
    void heartbeatAgeNullWhenNeverReported() {
        when(haConfigMapper.selectOne(any())).thenReturn(storedConfig(true));
        when(haNodeMapper.selectAllOrdered()).thenReturn(List.of(peerNode()));
        when(deploySupport.probe()).thenReturn(deploymentVO());

        HaOverviewVO overview = service.overview();

        assertNull(overview.nodes().get(0).heartbeatAgeSeconds());
        assertNull(overview.localRole(), "尚未登记本机节点时角色为 null");
        assertEquals(HaNodeStatus.UNKNOWN.name(), overview.localStatus());
    }

    @Test
    @DisplayName("配置行缺失时兜底补建一行，而不是 500（迁移被人工跳过的环境也要能打开页面）")
    void configFallsBackToDefaultRow() {
        when(haConfigMapper.selectOne(any())).thenReturn(null);

        HaConfig config = service.current();

        assertEquals(false, config.getEnabled());
        assertEquals("", config.getVipWeb(), "文本列显式给空串，不依赖数据库默认值");
        assertEquals(10, config.getHeartbeatTimeoutSeconds());
        verify(haConfigMapper).insert(any(HaConfig.class));
    }

    private HaActionResultVO dryRunResult(String actionCode) {
        return new HaActionResultVO(true, true, actionCode,
                "sudo -n bash /opt/ticket/deploy/ha/scripts/switchover.sh to-peer",
                null, "", "演练模式：未真正执行。");
    }
}
