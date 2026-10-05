package com.enterprise.ticket.module.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.security.entity.IpBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IP 封禁。
 *
 * <p>⚠️ 「是否生效」一律用 {@code active = 1 AND (expire_at IS NULL OR expire_at > NOW())}
 * 判定，**不查 {@code active = 1} 就算数**：过期行在被定时任务标记之前仍然 active=1，
 * 只看这一列会让「30 分钟自动解封」变成「等下一次定时任务」——
 * 而任务停摆时它就永远不会解封。时间判定放在 SQL 里，与
 * {@link IpBlock#isEffective} 的口径逐字一致。
 */
@Mapper
public interface IpBlockMapper extends BaseMapper<IpBlock> {

    /** 某 IP 当前是否处于生效中的封禁 */
    @Select("""
            SELECT COUNT(*)
              FROM ip_block
             WHERE ip = #{ip}
               AND active = 1
               AND (expire_at IS NULL OR expire_at > #{now})
            """)
    long countEffectiveByIp(@Param("ip") String ip, @Param("now") LocalDateTime now);

    /** 当前生效中的封禁列表（页面上的「当前封禁」区） */
    @Select("""
            SELECT *
              FROM ip_block
             WHERE active = 1
               AND (expire_at IS NULL OR expire_at > #{now})
             ORDER BY blocked_at DESC
            """)
    List<IpBlock> selectEffective(@Param("now") LocalDateTime now);

    /**
     * 取某 IP 的「当前活跃行」（含已过期但尚未标记的）。
     *
     * <p>自动封禁复用它而不是每次插新行：同一个 IP 反复触发时，
     * 应该延长已有记录而不是堆出几十行 —— 否则「当前封禁列表」会被同一个 IP 刷屏。
     */
    @Select("""
            SELECT *
              FROM ip_block
             WHERE ip = #{ip} AND active = 1
             ORDER BY blocked_at DESC
             LIMIT 1
            """)
    IpBlock selectActiveRow(@Param("ip") String ip);

    /** 把已过期的封禁行标记为失效（仅供页面「历史」视图使用，不是解封的必要条件） */
    @Update("""
            UPDATE ip_block
               SET active = 0, unblocked_at = #{now}
             WHERE active = 1
               AND expire_at IS NOT NULL
               AND expire_at <= #{now}
            """)
    int markExpired(@Param("now") LocalDateTime now);

    /**
     * 人工解封。
     *
     * <p>不带 {@code expire_at} 条件：人工解封**必须**能解除永久封禁（expire_at 为 null），
     * 否则「永久封禁」就没有任何出口，一次误操作会留下一个永远打不开的门。
     */
    @Update("""
            UPDATE ip_block
               SET active = 0, unblocked_at = #{now}, unblocked_by = #{operatorId}
             WHERE id = #{id} AND active = 1
            """)
    int manualUnblock(@Param("id") Long id, @Param("now") LocalDateTime now,
                      @Param("operatorId") Long operatorId);

    /** 分批清理很久以前已失效的封禁记录（保留期与安全事件一致） */
    @Update("""
            DELETE FROM ip_block
             WHERE active = 0
               AND unblocked_at IS NOT NULL
               AND unblocked_at < #{before}
             LIMIT #{limit}
            """)
    int deleteUnblockedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
