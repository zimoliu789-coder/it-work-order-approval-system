package com.enterprise.ticket.module.ha.dto.vo;

import com.enterprise.ticket.common.constant.HaNodeStatus;
import com.enterprise.ticket.common.constant.HaRole;
import com.enterprise.ticket.module.ha.entity.HaNode;

import java.time.LocalDateTime;

/**
 * 主备节点视图（，  [165]）
 *
 * <p>对应节点表格的一行：节点名称 / IP / 角色 / 状态 / 最后心跳时间。
 *
 * <h2>为什么同时下发「原始码」与「中文标签」</h2>
 * <p>与 {@code UpgradeTaskVO} 同做法：前端要用原始码做<b>颜色 / 图标映射</b>
 * （{@code ABNORMAL} 变红、{@code RUNNING} 变绿），用中文标签做<b>展示</b>。
 * 若只下发中文，前端就得靠字符串比较来判色 —— 后端改一次文案，
 * 前端的红绿映射会静默失效（页面全变灰，且没有任何报错）。
 *
 * <h2>为什么不直接下发实体</h2>
 * <p>实体字段名与列名绑定，一旦表结构演进（例如退某个列），
 * 前端契约会被动改变。VO 是显式的对外契约，多写一层换来的是「改表不影响前端」。
 *
 * @param id                 节点 ID
 * @param nodeName           节点名称（可能为空 —— 展示层应回落到 IP）
 * @param nodeIp             节点 IP
 * @param nodeRole           角色原始码（MASTER / STANDBY）
 * @param nodeRoleLabel      角色中文名（主节点 / 备节点）
 * @param nodeStatus         状态原始码（RUNNING / STANDBY / ABNORMAL / UNKNOWN）
 * @param nodeStatusLabel    状态中文名（运行中 / 待命 / 异常 / 未知）
 * @param isLocal            是否本机节点。
 *                           <b>⚠️ 序列化行为已实测</b>：record 的 {@code boolean isLocal}
 *                           经 Jackson 2.15 序列化后属性名仍是 {@code isLocal}（<b>不</b>裁掉
 *                           {@code is} 前缀），前端因此写 {@code row.isLocal}。这与<b>普通 POJO</b>
 *                           不同 —— 后者会按 JavaBean 约定变成 {@code local}。
 *                           若将来把这个 record 改成 POJO（或加 getter），前端的字段名必须同步改，
 *                           否则页面上的「本机」标记会静默消失（编译期不报错）。
 * @param lastHeartbeatAt    最后心跳时间；null = 尚未收到过心跳
 * @param heartbeatAgeSeconds 距最后一次心跳的秒数；null = 从未收到过（前端据此显示「从未上报」）
 * @param remark             备注
 * @param removable          是否允许移除（本机节点不可移除，避免把「自己」删掉）
 */
public record HaNodeVO(
        Long id,
        String nodeName,
        String nodeIp,
        String nodeRole,
        String nodeRoleLabel,
        String nodeStatus,
        String nodeStatusLabel,
        boolean isLocal,
        LocalDateTime lastHeartbeatAt,
        Long heartbeatAgeSeconds,
        String remark,
        boolean removable
) {

    /**
     * 由实体装配视图。
     *
     * <p>{@code heartbeatAgeSeconds} 在<b>服务层</b>算好再传进来，而不是在本方法里
     * 取 {@code LocalDateTime.now()}：后者会让装配逻辑隐式依赖系统时钟，
     * 单测里无法构造「3 秒前心跳」这样的确定性用例（必须靠 {@code Thread.sleep}）。
     */
    public static HaNodeVO of(HaNode node, Long heartbeatAgeSeconds) {
        return new HaNodeVO(
                node.getId(),
                node.getNodeName(),
                node.getNodeIp(),
                node.getNodeRole(),
                HaRole.labelOf(node.getNodeRole()),
                node.getNodeStatus(),
                HaNodeStatus.labelOf(node.getNodeStatus()),
                Boolean.TRUE.equals(node.getIsLocal()),
                node.getLastHeartbeatAt(),
                heartbeatAgeSeconds,
                node.getRemark(),
                // 本机不可移除：删掉本机行会让「当前节点角色」失去依据，页面立刻变成未知态
                !Boolean.TRUE.equals(node.getIsLocal()));
    }
}
