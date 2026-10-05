package com.enterprise.ticket.module.ha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主备双机热备配置（， ）—— {@code ha_config} 表的全局单行实体
 *
 * <p><b>本表有且只有一行</b>（由 {@code uk_ha_config_singleton} 唯一索引保证）。
 * 为什么独立成表而不是拆成 {@code system_config} 的键值对，见 {@code V38} 迁移脚本设计要点 A。
 *
 * <h2>三类字段的语义边界（阅读本类时最先要分清的事）</h2>
 * <ol>
 *   <li><b>能力开关</b>：{@link #enabled}；</li>
 *   <li><b>人工配置</b>：{@link #domain} / {@link #vipWeb} / {@link #vipDb} /
 *       {@link #vrrpIface} / {@link #heartbeatTimeoutSeconds} —— 由维护人员在页面上填，
 *       保存时走「显式 UpdateWrapper 逐列 set」以支持清空（见 {@code HaConfigServiceImpl#save}）；</li>
 *   <li><b>运行态快照</b>：{@link #syncState} / {@link #syncDelaySeconds} /
 *       {@link #lastSyncAt} / {@link #lastSwitchAt} —— 由内部心跳上报写入，
 *       页面只读。它们与人工配置<b>分开更新</b>：心跳每秒都在改，
 *       若与配置混在同一个 UPDATE 里，一次心跳就可能把管理员刚填的域名覆盖掉。</li>
 * </ol>
 *
 * <h2>⚠️ NULL 的语义</h2>
 * <p>文本列在 DDL 里全部是 {@code NOT NULL DEFAULT ''} ——「没有值」的既有表示是<b>空串</b>，
 * 写 NULL 会被数据库直接拒绝（这正是/E 各踩过一次的坑）。
 * 全表刻意保留为 NULL 的只有运行态的
 * {@link #syncDelaySeconds} / {@link #lastSyncAt} / {@link #lastSwitchAt}：
 * 它们的 NULL 有确切语义 ——「从未上报过延迟 / 从未同步过 / 从未切换过」，
 * 与「延迟 0 秒」「同步时间就是现在」完全不同。
 */
@Data
@TableName("ha_config")
public class HaConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单行约束位：恒为 1（业务代码不需要读写它） */
    private Integer singletonKey;

    /** 是否启用主备双机热备；关闭时页面上其余配置全部置灰 */
    private Boolean enabled;

    /** 本机节点名（展示用，如 ticket-master） */
    private String nodeName;

    /** 本机 IP（心跳上报填充，用于和节点表对齐「谁是本机」） */
    private String nodeIp;

    /** 员工统一访问的域名（如 oa.company.com）；DNS 解析到虚拟 IP，主备切换时不变 */
    private String domain;

    /** Web 虚拟 IP（漂移 IP），如 192.168.1.100 */
    private String vipWeb;

    /** 数据库虚拟 IP（与 Web VIP 相互独立） */
    private String vipDb;

    /** keepalived VRRP 使用的网卡名；留空则由脚本使用节点默认路由网卡 */
    private String vrrpIface;

    /** 心跳超时（秒）：需求文档默认 10 */
    private Integer heartbeatTimeoutSeconds;

    /** 数据一致性，取值见 {@link com.enterprise.ticket.common.constant.HaSyncState} */
    private String syncState;

    /** 复制延迟（秒）；NULL = 从未上报过 */
    private Integer syncDelaySeconds;

    /** 最近一次成功同步的时间；NULL = 从未同步过 */
    private LocalDateTime lastSyncAt;

    /** 最近一次主备切换的时间；NULL = 从未切换过 */
    private LocalDateTime lastSwitchAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
