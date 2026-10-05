package com.enterprise.ticket.module.ha.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.ha.entity.HaNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主备节点 Mapper（{@code ha_node} 表）
 *
 * <h2>为什么「心跳更新」与「判超时」要拆成两条 SQL</h2>
 * <p>它们面向的是<b>不同的行</b>，而且必须能分别执行：
 * <ul>
 *   <li>{@link #touchHeartbeat} 更新「刚刚报了心跳的那一行」（通常是本机，或对端上报的那一行）；</li>
 *   <li>{@link #selectNewlyStale} 找出「该报没报」的行 —— 判据是时间戳，
 *       而不是「有没有人来改过它」。把两者合成一条「按行状态重算」的 SQL
 *       会丢掉一个关键信息：<b>哪一行刚刚从健康跌成异常</b>，
 *       而那正是告警要区分「持续异常」与「刚刚断连」的唯一依据。</li>
 * </ul>
 *
 * <h2>为什么 {@code is_local = 0} 出现在超时判定里</h2>
 * <p>本机节点的心跳由本进程自己刷新（见 {@code HaHeartbeatMonitor}），
 * 它天然不可能「超时」。若把本机也纳入超时判定，一旦监控线程因任何原因停摆，
 * 本机节点会被判定为「异常」—— 而此时页面本身正在正常渲染，
 * 于是出现「页面说本机异常、但页面明明可用」的自相矛盾状态。
 * 本机的存活由「页面能打开」自证，不需要也不应该再靠心跳推断。
 */
@Mapper
public interface HaNodeMapper extends BaseMapper<HaNode> {

    /** 按 IP 查节点（{@code uk_ha_node_ip} 保证最多一行） */
    @Select("SELECT * FROM ha_node WHERE node_ip = #{nodeIp} LIMIT 1")
    HaNode selectByIp(@Param("nodeIp") String nodeIp);

    /** 取本机节点（{@code is_local = 1}，正常最多一行） */
    @Select("SELECT * FROM ha_node WHERE is_local = 1 ORDER BY id LIMIT 1")
    HaNode selectLocal();

    /** 全部节点：本机优先，其次主节点、备节点，最后按登记顺序 */
    @Select("SELECT * FROM ha_node ORDER BY is_local DESC, node_role = 'MASTER' DESC, id ASC")
    List<HaNode> selectAllOrdered();

    /** 心跳到达：刷新状态与最后心跳时间（不触碰角色，角色由切换动作改写） */
    @Update("UPDATE ha_node SET node_status = #{status}, last_heartbeat_at = #{heartbeatAt} WHERE id = #{id}")
    int touchHeartbeat(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("heartbeatAt") LocalDateTime heartbeatAt);

    /**
     * 找出「刚刚超时」的对端节点（当前尚非异常、且心跳已过期或从未上报）。
     *
     * <p>返回行而不是直接一条 UPDATE：调用方需要用这组行做两件事 ——
     * ① 逐个置为 {@code ABNORMAL}；② 对每一个发出「节点断连」告警（需求 [184]）。
     * 一条裸 UPDATE 只能完成 ①，而 ② 恰恰是本需求的重点。
     *
     * <p>WHERE 里的 {@code node_status &lt;&gt; 'ABNORMAL'} 是<b>幂等闸门</b>：
     * 没有它，监控任务每分钟都会对同一台已经异常很久的机器重复告警，
     * 把消息中心刷成一片红 —— 真正的故障消息会被自己制造的噪音埋掉。
     */
    @Select("SELECT * FROM ha_node WHERE is_local = 0 AND node_status <> 'ABNORMAL' "
            + "AND (last_heartbeat_at IS NULL OR last_heartbeat_at < #{cutoff}) "
            + "ORDER BY id")
    List<HaNode> selectNewlyStale(@Param("cutoff") LocalDateTime cutoff);

    /** 置节点为异常（超时 / 上报失败） */
    @Update("UPDATE ha_node SET node_status = 'ABNORMAL' WHERE id = #{id}")
    int markAbnormal(@Param("id") Long id);

    /** 更新节点角色（主备切换时改写本机行的角色） */
    @Update("UPDATE ha_node SET node_role = #{role} WHERE id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    /**
     * 回填节点 IP。
     *
     * <p>只在一个场景使用：本机节点登记时环境探测没能拿到地址（容器化部署常见，
     * 见 {@code HaLocalAddress} 类头注释），行里的 {@code node_ip} 是空串；
     * 之后首次心跳上报带来了真实 IP，由心跳服务把本机行的 IP 补齐。
     * <b>绝不用它来「改一个已有 IP 的节点」</b>—— 那会让节点身份与实际机器脱节，
     * 而心跳会继续落到同一行（见 {@code HaNodeUpdateRequest} 类头注释「为什么 IP 不可改」）。
     */
    @Update("UPDATE ha_node SET node_ip = #{nodeIp} WHERE id = #{id}")
    int updateIp(@Param("id") Long id, @Param("nodeIp") String nodeIp);

    /** 清除全部「本机」标记（登记本机行之前调用，保证 is_local 唯一） */
    @Update("UPDATE ha_node SET is_local = 0 WHERE is_local = 1")
    int clearLocalFlags();

    /** 把本机行置为主节点并标记为本机（切换接管时使用） */
    @Update("UPDATE ha_node SET is_local = 1, node_role = #{role} WHERE id = #{id}")
    int markLocalWithRole(@Param("id") Long id, @Param("role") String role);
}
