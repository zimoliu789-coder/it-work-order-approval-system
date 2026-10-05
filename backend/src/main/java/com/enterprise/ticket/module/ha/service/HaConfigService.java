package com.enterprise.ticket.module.ha.service;

import com.enterprise.ticket.module.ha.dto.HaConfigRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeCreateRequest;
import com.enterprise.ticket.module.ha.dto.HaNodeUpdateRequest;
import com.enterprise.ticket.module.ha.dto.vo.HaActionResultVO;
import com.enterprise.ticket.module.ha.dto.vo.HaDeployGuideVO;
import com.enterprise.ticket.module.ha.dto.vo.HaNodeVO;
import com.enterprise.ticket.module.ha.dto.vo.HaOverviewVO;
import com.enterprise.ticket.module.ha.entity.HaConfig;

/**
 * 主备配置服务（， ）
 *
 * <p>接口与实现分离，与 {@code AdConfigService} / {@code UpgradeService} 保持一致：
 * 控制层只依赖本接口，便于单测替换实现。
 *
 * <h2>读写边界（读 / 写分得比其它模块更严的原因）</h2>
 * <p>本模块的写操作里有三个会<b>改变线上运行形态</b>：保存配置（改虚拟 IP）、
 * 手动切换主备（改 VIP 归属）、立即同步（重建复制）。因此：
 * <ul>
 *   <li>所有<b>读</b>接口（{@link #overview()} / {@link #guide()}）只要求 {@code ha:view}；</li>
 *   <li>所有<b>写</b>接口（{@link #save} / {@link #addNode} / {@link #switchover} / {@link #syncNow}）
 *       要求 {@code ha:manage}，且一律记高危审计。</li>
 * </ul>
 * 两个权限码都<b>只归超级管理员</b>（{@code PermissionCatalog} 中不在 {@code DEFAULT_PERMISSIONS} 内），
 * 与 {@code ad:*} / {@code system:upgrade:*} 同一取向 —— 能把虚拟 IP 挪走的人，
 * 等价于能决定全公司的系统从哪台机器提供服务。
 */
public interface HaConfigService {

    /**
     * 当前配置行（单行表）。
     *
     * <p>缺表 / 缺行时按默认值补建并返回，保证配置页永远能打开 ——
     * 而不是抛一个 500 让维护人员在最需要看配置的时候看到一个错误页。
     */
    HaConfig current();

    /** 页面总览：配置 + 本机角色与状态 + 节点列表 + 部署探针 */
    HaOverviewVO overview();

    /** 保存配置（第 1 步：开关 + 域名 + 虚拟 IP） */
    void save(HaConfigRequest request);

    /** 添加备节点（第 2 步） */
    HaNodeVO addNode(HaNodeCreateRequest request);

    /** 修改节点展示名 / 备注（IP 与角色不可改，见 {@code HaNodeUpdateRequest}） */
    HaNodeVO updateNode(Long id, HaNodeUpdateRequest request);

    /** 移除备节点（本机节点不可移除） */
    void removeNode(Long id);

    /**
     * 手动切换主备。
     *
     * @param action {@code to-peer}（本机让出）/ {@code back}（切回本机）/ {@code status}（只查询）
     */
    HaActionResultVO switchover(String action);

    /** 立即同步：重建复制链路并触发全量同步 */
    HaActionResultVO syncNow();

    /** 三步走指引 + 可直接抄用的部署片段 */
    HaDeployGuideVO guide();

    // ------------------------------------------------------------------
    // 一键化：把「读指引 → 在目标机手工跑脚本」压缩成页面上的两个按钮
    // ------------------------------------------------------------------

    /**
     * 一键启用本机为主节点。
     *
     * <p>等价于「把 enabled 置真并保存」+「登记本机节点」，但**语义更明确**：
     * 它是一条「我决定让这台机器承担服务」的声明，而不是一次配置保存。
     * 因此它顺带给出「接下来该做什么」的说明（见返回值里的 message）。
     *
     * <p>⚠️ 返回值里必须**如实**写明「故障后需手工或脚本切 DNS / VIP」——
     * 本系统按用户拍板**不引入 keepalived**，不会自动切换。
     * 假装能自动切，比明确说不能切危险得多。
     */
    HaActionResultVO enable(HaConfigRequest request);

    /**
     * 一键加入集群（备节点）。
     *
     * @param masterIp  主节点 IP
     * @param joinToken 主节点的内部通道令牌（{@code INTERNAL_ALERT_TOKEN}），
     *                  在主页面上可见并可直接复制；两台机器必须逐字一致
     */
    HaActionResultVO joinCluster(String masterIp, String joinToken);

    /**
     * 导出本节点配置，供备节点拉取。
     *
     * <p>这是「不用复制 .env 文件」的落点：备节点只需要一个 IP 与一个令牌，
     * 其余配置（域名 / VIP / 心跳阈值 / 主节点身份）由主节点下发。
     */
    java.util.Map<String, Object> exportConfig();
}
