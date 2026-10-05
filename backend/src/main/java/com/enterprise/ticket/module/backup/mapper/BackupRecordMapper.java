package com.enterprise.ticket.module.backup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.backup.entity.BackupRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 备份记录 Mapper（P0）
 *
 * <h2>为什么「僵尸回收」与「并发保护」都写成条件 UPDATE</h2>
 * 同一条备份记录会被两条路径碰：定时任务线程与管理员手动触发。
 * 「先查再改」在两者交错时会互相覆盖 —— 典型的是管理员手点的备份正在跑，
 * 定时任务看到它仍是 RUNNING 就把它回收成 FAILED，而它其实还在写归档。
 * 因此所有状态迁移都把「状态必须仍是 X」写进 WHERE，
 * 命中 0 行即表示「已经被别人改过了」，调用方据此放弃而不覆盖。
 */
@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {

    /**
     * 是否已有备份正在执行（并发保护）。
     *
     * <p>判据是「存在 RUNNING 行」，而不是「本实例内有线程在跑」——
     * 后者在多实例部署下形同虚设（A 实例在跑，B 实例照样发起第二条）。
     */
    @Select("SELECT COUNT(*) FROM backup_record WHERE status = 'RUNNING'")
    int countRunning();

    /** 当天是否已有**定时**备份（补跑判据：每天最多一次，重启后仍成立） */
    @Select("SELECT COUNT(*) FROM backup_record "
            + "WHERE trigger_type = 'SCHEDULED' AND started_at >= #{dayStart}")
    int countScheduledSince(@Param("dayStart") LocalDateTime dayStart);

    /**
     * 僵尸回收：把残留的 RUNNING 标记为 FAILED。
     *
     * <p>覆盖的是「后端在备份过程中被杀掉」（发布、崩溃、机器重启）：
     * 记录停在 RUNNING，而写它的进程已经不在，不会有人再推进它 ——
     * 结果是一条「进行中」的备份永远挂在页面上，且它还会让
     * {@link #countRunning()} 恒 &gt; 0，把后续所有备份（含定时）全部挡掉。
     *
     * <p>另加 {@code started_at < cutoff} 兜底：正常情况下 RUNNING 不会超过备份超时，
     * 超过即说明写它的进程已经死了。
     */
    @Update("UPDATE backup_record SET status = 'FAILED', finished_at = NOW(), "
            + "error_message = #{reason} WHERE status = 'RUNNING' AND started_at < #{cutoff}")
    int failStaleRunning(@Param("cutoff") LocalDateTime cutoff, @Param("reason") String reason);

    /** 保留清理的候选：成功的、且开始时间早于保留线的记录（按时间正序，先删最旧的） */
    @Select("SELECT * FROM backup_record WHERE status = 'SUCCESS' AND started_at < #{before} "
            + "ORDER BY started_at ASC")
    List<BackupRecord> selectExpiredSuccess(@Param("before") LocalDateTime before);

    /** 概览用：最后一次成功备份 */
    @Select("SELECT * FROM backup_record WHERE status = 'SUCCESS' "
            + "ORDER BY started_at DESC LIMIT 1")
    BackupRecord selectLastSuccess();

    /** 概览用：最近一次失败 */
    @Select("SELECT * FROM backup_record WHERE status = 'FAILED' "
            + "ORDER BY started_at DESC LIMIT 1")
    BackupRecord selectLastFailure();
}
