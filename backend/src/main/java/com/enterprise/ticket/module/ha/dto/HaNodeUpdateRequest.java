package com.enterprise.ticket.module.ha.dto;

import lombok.Data;

/**
 * 主备节点修改请求
 *
 * <p>只允许改<b>展示名</b>与<b>备注</b>两项，且都是可空的（留空 = 清空）。
 *
 * <h2>为什么 IP 不可改</h2>
 * <p>{@code node_ip} 是节点的身份（{@code uk_ha_node_ip} 唯一键），
 * 心跳上报也按 IP 对齐行。允许改 IP 等于允许「把一台机器的身份挪到另一台上」——
 * 心跳会继续落到同一行，界面上却显示新 IP 与旧心跳，产生一条无法解释的记录。
 * 改 IP 的正确做法是「删掉旧节点、添加新节点」，这样两条动作都会留下审计。
 *
 * <p>同理，<b>角色也不可在此修改</b>：角色变化属于「切换」这一独立动作
 * （{@code POST /api/ha/switchover}），它要落 {@code last_switch_at} 并发告警；
 * 混进一个通用编辑接口里，会让「谁把主备换过来了」失去唯一的取证点。
 */
@Data
public class HaNodeUpdateRequest {

    /** 节点名称（留空 = 清空，展示时会回落到 IP） */
    private String nodeName;

    /** 备注（留空 = 清空） */
    private String remark;
}
