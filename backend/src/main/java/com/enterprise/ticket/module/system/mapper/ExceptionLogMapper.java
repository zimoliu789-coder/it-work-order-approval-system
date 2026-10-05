package com.enterprise.ticket.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.system.dto.vo.ExceptionDigestRow;
import com.enterprise.ticket.module.system.entity.ExceptionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常日志。
 *
 * <p>聚合下推到数据库（见 {@link ExceptionDigestRow} 的注释）：一次事故可能产生几万条
 * 同类异常，把它们全读进 JVM 再分组是自找 OOM，而按指纹 GROUP BY 只带回「一类一行」。
 */
@Mapper
public interface ExceptionLogMapper extends BaseMapper<ExceptionLog> {

    /**
     * 待告警的异常按同类指纹聚合。
     *
     * <p>聚合列用 {@code MAX(...)} 而不是把每一列都塞进 {@code GROUP BY}：
     * 分类 / 分级 / 异常类 / 模块 在**同一指纹内必然相同**（它们都是指纹的组成部分），
     * 写进 GROUP BY 只会让 SQL 更长、执行计划更差。取 {@code MAX} 是「随便取一个代表值」，
     * 语义上等价。
     *
     * <p>排序按「次数降序、最近发生降序」：摘要邮件里最该先看到的是
     * 「发生最多」与「刚刚还在发生」的那几类。
     */
    @Select("""
            SELECT stack_digest       AS digest,
                   MAX(category)      AS category,
                   MAX(severity)      AS severity,
                   MAX(exception_class) AS exceptionClass,
                   MAX(module)        AS module,
                   MAX(message)       AS message,
                   MAX(request_uri)   AS requestUri,
                   COUNT(*)           AS total,
                   MIN(occurred_at)   AS firstAt,
                   MAX(occurred_at)   AS lastAt
              FROM exception_log
             WHERE alert_state = 'PENDING'
             GROUP BY stack_digest
             ORDER BY total DESC, lastAt DESC
             LIMIT #{limit}
            """)
    List<ExceptionDigestRow> selectPendingDigests(@Param("limit") int limit);

    /**
     * 把某一类的全部 PENDING 行标记为已告警。
     *
     * <p>按**指纹**而不是按 id 列表更新：id 列表会随着「更新期间又来了新异常」而漏掉新行，
     * 导致同一类异常下一轮又被告警一次。按指纹 + {@code alert_state='PENDING'} 的条件更新
     * 是原子的，且天然幂等（重复执行第二次命中 0 行）。
     */
    @Update("""
            UPDATE exception_log
               SET alert_state = #{state}, alerted_at = #{alertedAt}
             WHERE alert_state = 'PENDING'
               AND stack_digest = #{digest}
            """)
    int markDigestAlerted(@Param("digest") String digest,
                          @Param("state") String state,
                          @Param("alertedAt") LocalDateTime alertedAt);

    /**
     * 把某一类的全部 PENDING 行标记为「静默期内不重复告警」。
     *
     * <p>与 {@link #markDigestAlerted} 是同一个动作、只是终态不同：
     * 静默期内重复出现的同类异常**仍然落库**（次数是排查的关键证据），
     * 只是不再触发告警 —— 这正是「不能发太频繁」的落地方式。
     */
    @Update("""
            UPDATE exception_log
               SET alert_state = 'SUPPRESSED'
             WHERE alert_state = 'PENDING'
               AND stack_digest = #{digest}
            """)
    int suppressDigest(@Param("digest") String digest);

    /**
     * 最近一段时间内某一类异常的出现次数 —— 静默期判定的依据。
     *
     * <p>用「时间窗内的条数」而不是「上一次告警时间」：前者能表达
     * 「这一类还在持续发生」（条数很多），后者只能表达「刚告警过」。
     * 摘要邮件里「近 5 分钟 37 次」比「刚告警过」有信息量得多。
     */
    @Select("""
            SELECT COUNT(*)
              FROM exception_log
             WHERE stack_digest = #{digest}
               AND occurred_at >= #{since}
            """)
    long countSince(@Param("digest") String digest, @Param("since") LocalDateTime since);

    /** 按状态统计条数（供页面概览与日报） */
    @Select("SELECT COUNT(*) FROM exception_log WHERE alert_state = #{state}")
    long countByState(@Param("state") String state);

    /**
     * 某一类异常**上一次被真正告警**的时间（没有任何一次告警时返回 null）。
     *
     * <p>静默期判定的第二个依据：如果这一类在静默期内已经告警过，本轮就跳过它 ——
     * 行仍然是 PENDING（次数照常累计），只是不再发一封。
     */
    @Select("""
            SELECT MAX(alerted_at)
              FROM exception_log
             WHERE stack_digest = #{digest}
               AND alert_state = 'SENT'
            """)
    LocalDateTime lastAlertedAt(@Param("digest") String digest);

    /** 按分级统计指定状态下的条数（日报用：P0/P1/P2 各多少） */
    @Select("""
            SELECT COUNT(*)
              FROM exception_log
             WHERE alert_state = #{state}
               AND severity = #{severity}
            """)
    long countByStateAndSeverity(@Param("state") String state, @Param("severity") String severity);

    /**
     * 清理超期日志。
     *
     * <p>分批删（{@code LIMIT}）而不是一次 {@code DELETE} 全表超期行：
     * 一次性删几十万行会长时间持锁，把正常请求堵住。
     */
    @Update("""
            DELETE FROM exception_log
             WHERE occurred_at < #{before}
             ORDER BY occurred_at
             LIMIT #{limit}
            """)
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
