package com.enterprise.ticket.module.ha.dto.vo;

import java.util.List;

/**
 * 主备配置总览—— 页面打开时拉的一个接口
 *
 * <h2>为什么合成一个接口而不是三个</h2>
 * <p>页面上的四块内容（开关与虚拟 IP、当前节点角色与状态、节点列表、同步状态）
 * 是<b>同一时刻的同一份现实</b>。若拆成多个请求，它们会分别落在不同的时间点上 ——
 * 典型坏结果：节点列表已经反映出「备机断连」但顶部的「同步状态」还是上一次查询的
 * 「数据一致」，两块自相矛盾地并排显示；维护人员只能靠刷新猜哪个是真的。
 * 一个接口、一次快照，从结构上消除这种不一致。
 *
 * <h2>为什么部署探针也一起下发</h2>
 * <p>{@link #deployment} 决定页面上「手动切换主备 / 立即同步」两个按钮
 * 是<b>可点</b>还是<b>置灰并附原因</b>。把「能不能做」这个判断放在服务端，
 * 前端只渲染结果 —— 否则沙箱 / 未挂载部署目录的环境里，按钮会一直可点，
 * 点一次报一次错，而不是一开始就诚实地告诉维护人员「这台机器上没有部署资产」。
 *
 * @param enabled          是否启用主备
 * @param config           配置明细（开关 / 域名 / 虚拟 IP / 同步状态）
 * @param localRole        本机角色原始码（MASTER / STANDBY）；null = 尚未登记本机节点
 * @param localRoleLabel   本机角色中文名
 * @param localStatus      本机运行状态原始码
 * @param localStatusLabel 本机运行状态中文名
 * @param nodes            节点列表（本机优先）
 * @param deployment       部署资产探针结果
 */
public record HaOverviewVO(
        boolean enabled,
        HaConfigVO config,
        String localRole,
        String localRoleLabel,
        String localStatus,
        String localStatusLabel,
        List<HaNodeVO> nodes,
        HaDeploymentVO deployment
) {
}
