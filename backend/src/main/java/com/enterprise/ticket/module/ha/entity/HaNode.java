package com.enterprise.ticket.module.ha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主备节点（，  [165]）—— {@code ha_node} 表的实体
 *
 * <p>需求 [165] 行明确节点表格的列：「节点名称、IP地址、角色、状态、最后心跳时间」，
 * 本类字段与之一一对应，另加两项：
 * <ul>
 *   <li>{@link #isLocal} —— 标出「本机」，用于「当前节点」卡片与禁止自删自切；</li>
 *   <li>{@link #remark} —— 维护人员备注，属低成本高回报的自解释字段。</li>
 * </ul>
 *
 * <h2>为什么角色落在节点表而不是配置表</h2>
 * <p>「谁是主、谁是备」是<b>每一台</b>的属性，不是全局属性。放配置表就只能表达
 * 「本机角色」，一旦换成从备节点机器打开页面（同一套代码、同一张库），
 * 它读到的还是「主节点」—— 那是主节点写的，与它自己无关。
 * 角色落在节点行上，任何一台机器读到的都是客观拓扑。
 *
 * <p>{@link #isLocal} 只表达「这一行是本机」，<b>不</b>承担「本机角色」的职责：
 * 本机是主还是备，读的仍是本行的 {@link #nodeRole}（见 {@code HaConfigService#overview}）。
 *
 * <h2>为什么没有 {@code deleted} / {@code @TableLogic}</h2>
 * <p>低基数运维对象，物理删除即可；删除痕迹由操作审计承担。见 {@code V38} 设计要点 H。
 */
@Data
@TableName("ha_node")
public class HaNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 节点名称（展示用，如 ticket-master / ticket-slave） */
    private String nodeName;

    /** 节点 IP（维护人员填写原样保存） */
    private String nodeIp;

    /** 节点角色，取值见 {@link com.enterprise.ticket.common.constant.HaRole}：MASTER / STANDBY */
    private String nodeRole;

    /** 运行状态，取值见 {@link com.enterprise.ticket.common.constant.HaNodeStatus} */
    private String nodeStatus;

    /** 是否本机节点（0 否 / 1 是） */
    private Boolean isLocal;

    /** 最后一次收到心跳的时间；NULL = 尚未收到过心跳 */
    private LocalDateTime lastHeartbeatAt;

    /** 备注（留空表示不填） */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
