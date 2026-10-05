package com.enterprise.ticket.module.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.security.entity.SecurityEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全事件。
 */
@Mapper
public interface SecurityEventMapper extends BaseMapper<SecurityEvent> {

    /**
     * 某 IP 在时间窗内的失败次数 —— IP 自动封禁的判据。
     *
     * <p>只数 {@code LOGIN_FAIL}：账号锁定（ACCOUNT_LOCKED）是失败的结果而非又一次尝试，
     * 把它一起数进去会让阈值实际变成「5 次失败 + 1 次锁定」，与页面上写的数字对不上。
     */
    @Select("""
            SELECT COUNT(*)
              FROM security_event
             WHERE event_type = 'LOGIN_FAIL'
               AND ip = #{ip}
               AND occurred_at >= #{since}
            """)
    long countLoginFailByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

    /**
     * 按类型统计时间窗内的事件数（顶部概览用）。
     *
     * <p>返回 {@code Map} 而不是自定义 DTO：只有两列、且调用方立刻转成
     * 「类型 → 数量」的映射，为它单独造一个类没有信息量。
     */
    @Select("""
            SELECT event_type AS eventType, COUNT(*) AS total
              FROM security_event
             WHERE occurred_at >= #{since}
             GROUP BY event_type
            """)
    List<Map<String, Object>> countGroupByTypeSince(@Param("since") LocalDateTime since);

    /** 时间窗内失败次数最多的 IP（趋势视图：一眼看出谁在扫） */
    @Select("""
            SELECT ip AS ip, COUNT(*) AS total
              FROM security_event
             WHERE event_type = 'LOGIN_FAIL'
               AND occurred_at >= #{since}
               AND ip IS NOT NULL
             GROUP BY ip
             ORDER BY total DESC, ip ASC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> topFailIpsSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 分批清理超期事件（一次删几十万行会长时间持锁） */
    @Update("""
            DELETE FROM security_event
             WHERE occurred_at < #{before}
             ORDER BY occurred_at
             LIMIT #{limit}
            """)
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
