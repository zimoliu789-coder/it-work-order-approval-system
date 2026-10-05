package com.enterprise.ticket.module.ha.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.ha.entity.HaConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 主备配置 Mapper（{@code ha_config} 单行表）
 *
 * <h2>为什么读取不需要自定义 SQL，而「写运行态」却需要</h2>
 * <p>本表只有一行，读取用 {@code selectOne} 取首条即可，没有可优化的查询形态。
 * 但运行态快照（{@code sync_state} / {@code sync_delay_seconds} / {@code last_sync_at}）
 * 的写入必须走<b>手写 SQL</b>，不能用 {@code updateById(entity)}：
 * 全局 {@code update-strategy: not_null} 会让实体里的 null 字段不进 SET 子句，
 * 而「本次上报没有延迟数据」恰恰需要把旧延迟<b>清成 NULL</b> ——
 * 用实体更新会留下上一轮的旧数字，界面上显示一个过期很久的「延迟 3 秒」，
 * 比显示「未知」更糟（它看起来像是新数据）。
 *
 * <p>手写 SQL 逐列赋值则不受全局策略影响：写什么就是什么，null 就是 NULL。
 */
@Mapper
public interface HaConfigMapper extends BaseMapper<HaConfig> {

    /**
     * 回填「同步运行态快照」（由内部心跳上报调用）。
     *
     * <p>刻意<b>不</b>触碰任何人工配置列（域名 / 虚拟 IP / 网卡 / 心跳阈值）：
     * 心跳是秒级的，若与配置混在同一条 UPDATE 里，一次上报就可能把管理员
     * 正在编辑并已保存的域名覆盖成上报内容里的空值。
     */
    @Update("UPDATE ha_config SET sync_state = #{syncState}, sync_delay_seconds = #{delaySeconds}, "
            + "last_sync_at = #{lastSyncAt} WHERE id = #{id}")
    int updateSyncSnapshot(@Param("id") Long id,
                           @Param("syncState") String syncState,
                           @Param("delaySeconds") Integer delaySeconds,
                           @Param("lastSyncAt") LocalDateTime lastSyncAt);

    /**
     * 记录一次主备切换时间（手动或自动）。
     *
     * <p>只有这一个列，单独成方法而不是复用 {@link #updateSyncSnapshot}：
     * 切换发生时同步状态<b>未必</b>同时上报（自动切换由对端 keepalived 完成，
     * 本应用可能根本收不到那一刻的上报），把两件事绑在一起会逼着调用方
     * 为一个字段的更新去编造另外三个的值。
     */
    @Update("UPDATE ha_config SET last_switch_at = #{at} WHERE id = #{id}")
    int markSwitched(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 回填本机身份（节点名 / IP）。
     *
     * <p>单独成方法的原因同 {@link #markSwitched}：本机 IP 由运行环境决定，
     * 与管理员填的虚拟 IP / 域名是两类数据，不应混在一次写里。
     */
    @Update("UPDATE ha_config SET node_name = #{nodeName}, node_ip = #{nodeIp} WHERE id = #{id}")
    int updateLocalIdentity(@Param("id") Long id,
                            @Param("nodeName") String nodeName,
                            @Param("nodeIp") String nodeIp);
}
