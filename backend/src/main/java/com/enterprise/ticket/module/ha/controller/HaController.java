package com.enterprise.ticket.module.ha.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import com.enterprise.ticket.module.ha.dto.HaJoinClusterRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeCreateRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeUpdateRequest;
import com.enterprise.ticket.module.ha.dto.vo.HaActionResultVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeployGuideVO;
import com.enterprise.ticket.module.ha.dto.vo.HaNodeVO;
import com.enterprise.ticket.module.ha.dto.vo.HaOverviewVO;
import com.enterprise.ticket.module.ha.service.HaConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主备双机热备配置接口（， ）
 *
 * <h2>权限：两个不同的码，均只归超管</h2>
 * <p>{@code ha:view} 看（总览 / 指引），{@code ha:manage} 做（保存配置 / 增删节点 /
 * 手动切换 / 立即同步）。两者都<b>不下发给 admin</b>：本模块能把虚拟 IP 的归属
 * 从一台机器挪到另一台，等价于决定全公司的系统由哪台机器提供服务 ——
 * 这远超业务管理员的职责范围（与 {@code ad:*} / {@code system:upgrade:*} 同一取向）。
 *
 * <p>两个权限码在 重构权限目录时<b>已经预埋</b>（见 {@code PermissionCatalog}），
 * 且不在 {@code DEFAULT_PERMISSIONS} 内，因此本次<b>不需要授权迁移</b> ——
 * 超管由 {@code PermissionGuard} 全量短路放行，{@code sys_role_permission} 里
 * 不存在也不需要 {@code ha:%} 行。
 *
 * <h2>为什么所有写操作都是 {@code RiskLevel.HIGH}</h2>
 * <p>{@code HIGH} 表示审计走 {@code REQUIRES_NEW} 独立事务、<b>同步</b>落库。
 * 本模块的写操作会触发外部脚本、改变线上流量走向 ——
 * 这类动作事后最需要回答的就是「谁在什么时候把主备换过来了」，
 * 而异步审计在进程被切换脚本重启时可能还没落库，那就等于没有留痕。
 */
@Slf4j
@RestController
@RequestMapping("/api/ha")
@RequiredArgsConstructor
public class HaController {

    private final HaConfigService haConfigService;

    /** 页面总览：配置 + 本机角色状态 + 节点列表 + 部署探针 */
    @GetMapping("/overview")
    @PreAuthorize("@perm.has('ha:view')")
    public ApiResponse<HaOverviewVO> overview() {
        return ApiResponse.success(haConfigService.overview());
    }

    /** 三步走指引 + 可直接抄用的部署片段（含环境变量片段与 keepalived 配置） */
    @GetMapping("/guide")
    @PreAuthorize("@perm.has('ha:view')")
    public ApiResponse<HaDeployGuideVO> guide() {
        return ApiResponse.success(haConfigService.guide());
    }

    /**
     * 保存配置（第 1 步：开关 + 域名 + 虚拟 IP）。
     *
     * <p>测试路径：{@code PUT /api/ha/config}
     */
    @PutMapping("/config")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_CONFIG_SAVE", risk = RiskLevel.HIGH,
            description = "保存主备配置")
    public ApiResponse<Void> save(@RequestBody HaConfigRequest request) {
        haConfigService.save(request);
        return ApiResponse.success("主备配置已保存", null);
    }

    /**
     * 添加备节点（第 2 步）。
     *
     * <p>测试路径：{@code POST /api/ha/nodes}
     */
    @PostMapping("/nodes")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_NODE_ADD", risk = RiskLevel.HIGH,
            description = "添加主备备节点")
    public ApiResponse<HaNodeVO> addNode(@RequestBody HaNodeCreateRequest request) {
        return ApiResponse.success("备节点已登记，等待其上报心跳", haConfigService.addNode(request));
    }

    /** 修改节点展示名 / 备注（IP 与角色不可改） */
    @PutMapping("/nodes/{id}")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_NODE_UPDATE", risk = RiskLevel.NORMAL,
            description = "修改主备节点信息")
    public ApiResponse<HaNodeVO> updateNode(@PathVariable Long id,
                                            @RequestBody HaNodeUpdateRequest request) {
        return ApiResponse.success("节点信息已更新", haConfigService.updateNode(id, request));
    }

    /** 移除备节点（本机节点不可移除） */
    @DeleteMapping("/nodes/{id}")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_NODE_REMOVE", risk = RiskLevel.HIGH,
            description = "移除主备节点")
    public ApiResponse<Void> removeNode(@PathVariable Long id) {
        haConfigService.removeNode(id);
        return ApiResponse.success("节点已移除", null);
    }

    /**
     * 手动切换主备。
     *
     * <p>测试路径：{@code POST /api/ha/switchover?action=to-peer}（或 {@code back} / {@code status}）。
     *
     * <p>返回体里 {@code dryRun=true} 表示「本次只是演练，没有真的执行」——
     * 沙箱与未配置部署资产的环境都属于这一类。前端必须把这一点明确展示出来，
     * 不能让维护人员把一次演练当成真实切换。
     */
    /**
     * 一键启用主节点。
     *
     * <p>测试路径：{@code POST /api/ha/enable}
     */
    @PostMapping("/enable")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_ENABLE", risk = RiskLevel.HIGH,
            description = "一键启用主节点（写配置 + 登记本机 + 起内置心跳）")
    public ApiResponse<HaActionResultVO> enable(@RequestBody HaConfigRequest request) {
        return ApiResponse.success("主节点已启用", haConfigService.enable(request));
    }

    /**
     * 一键加入集群（备节点，）。
     *
     * <p>只需要「主节点 IP + 加入令牌」两样东西 —— 这正是「管理员不用复制 .env 文件」的落点：
     * 其余配置（域名 / VIP / 心跳阈值）由主节点下发。
     *
     * <p>测试路径：{@code POST /api/ha/join-cluster}
     */
    @PostMapping("/join-cluster")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_JOIN_CLUSTER", risk = RiskLevel.HIGH,
            description = "一键加入集群（拉取主节点配置 + 登记备节点）")
    public ApiResponse<HaActionResultVO> joinCluster(@RequestBody HaJoinClusterRequest request) {
        return ApiResponse.success("已加入集群",
                haConfigService.joinCluster(request.getMasterIp(), request.getJoinToken()));
    }

    @PostMapping("/switchover")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_SWITCHOVER", risk = RiskLevel.HIGH,
            description = "手动切换主备")
    public ApiResponse<HaActionResultVO> switchover(@RequestParam("action") String action) {
        HaActionResultVO result = haConfigService.switchover(action);
        return ApiResponse.success(result.dryRun()
                ? "演练模式：已生成切换命令，未真正执行" : "切换动作已执行", result);
    }

    /**
     * 立即同步：重建复制链路并触发全量同步。
     *
     * <p>测试路径：{@code POST /api/ha/sync}
     */
    @PostMapping("/sync")
    @PreAuthorize("@perm.has('ha:manage')")
    @AuditLog(module = "SYSTEM", action = "HA_SYNC_NOW", risk = RiskLevel.HIGH,
            description = "触发主备立即同步")
    public ApiResponse<HaActionResultVO> sync() {
        HaActionResultVO result = haConfigService.syncNow();
        return ApiResponse.success(result.dryRun()
                ? "演练模式：已生成同步命令，未真正执行" : "同步动作已执行", result);
    }
}
