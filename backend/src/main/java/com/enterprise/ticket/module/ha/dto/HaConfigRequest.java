package com.enterprise.ticket.module.ha.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 主备配置保存请求（， ）
 *
 * <p>对应页面上的「第 1 步：在主节点上打开『启用主备』开关，设置虚拟 IP」（需求 [170]）。
 * 域名与本机节点名是需求之外本项目补的两项，理由见下。
 *
 * <h2>为什么把「本机节点名」放在这里</h2>
 * <p>节点列表（{@code ha_node}）里的每一行都需要一个展示名，而本机那一行
 * 在开启开关的瞬间就要被创建（见 {@code HaConfigService#setEnabled}）。
 * 若此时没有名字，只能填一个如 {@code node-1} 的自动编号 —— 而现场通常
 * 两台机器分别叫 {@code ticket-master} / {@code ticket-slave}，
 * 让管理员自己指定名字，比让他事后去节点表里改一行要顺手得多。
 * 留空不算错误：服务层会按「本机 + 主机名」兜底生成。
 *
 * <h2>为什么有「域名」</h2>
 * <p>需求 [166] 行只写了「虚拟 IP：员工统一访问这个 IP」。但真实环境里
 * 让员工记住一个 IP 是不现实的，标准做法是给虚拟 IP 绑一个域名
 * （如 {@code oa.company.com}），DNS 指向 VIP，主备切换时 VIP 漂移、域名不变，
 * 员工完全无感 —— 这正是需求 [174] 行「员工无感知」的<b>可落地形态</b>。
 * 因此本系统把域名作为一等配置项，并写进部署文档（{@code deploy/DEPLOY.md}）。
 * 域名允许留空（纯 IP 访问的内网环境仍然成立），但一旦填写就做格式校验。
 *
 * <h2>⚠️ 「留空 = 清空」在本 DTO 上成立</h2>
 * <p>{@code domain} / {@code vipWeb} / {@code vipDb} / {@code vrrpIface} 留空即清空旧值。
 * 为保证这一点，服务层保存时<b>必须</b>用显式的 {@code LambdaUpdateWrapper} 逐列 {@code set}，
 * 不能用 {@code updateById(entity)} —— 全局 {@code update-strategy: not_null} 会让
 * 实体里的 null 字段不进 SET 子句，于是「清空」静默失效
 * （本项目已在 /  各踩过一次，见 {@code PROJECT_NOTES.md}）。
 */
@Data
public class HaConfigRequest {

    /** 是否启用主备双机热备 */
    private Boolean enabled;

    /** 本机节点名（展示用；留空则服务层按主机名兜底） */
    @Size(max = 64, message = "节点名称过长")
    private String nodeName;

    /** 员工统一访问的域名（如 oa.company.com）；留空表示直连虚拟 IP */
    @Size(max = 128, message = "域名过长")
    private String domain;

    /** Web 虚拟 IP（漂移 IP），如 192.168.1.100 */
    @Size(max = 64, message = "虚拟 IP 过长")
    private String vipWeb;

    /** 数据库虚拟 IP（与 Web VIP 相互独立） */
    @Size(max = 64, message = "虚拟 IP 过长")
    private String vipDb;

    /** keepalived VRRP 使用的网卡名（如 eth0）；留空则由脚本使用默认路由网卡 */
    @Size(max = 32, message = "网卡名过长")
    private String vrrpIface;

    /** 心跳超时（秒）：需求文档默认 10；范围由 HaConfigValidator 校验 */
    @Min(value = 1, message = "心跳超时取值范围为 3-600 秒")
    @Max(value = 3600, message = "心跳超时取值范围为 3-600 秒")
    private Integer heartbeatTimeoutSeconds;
}
