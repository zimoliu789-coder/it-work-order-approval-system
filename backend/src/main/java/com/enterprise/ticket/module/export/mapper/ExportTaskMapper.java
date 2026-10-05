package com.enterprise.ticket.module.export.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.export.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 导出任务 Mapper
 *
 * <p>大部分读写都是「按主键 / 按发起人 + 状态」的单表操作，{@link BaseMapper} 已足够。
 * 状态回填用条件 UPDATE（见 Service / Runner）而不是「先查再改」，避免并发下把已完成的任务
 * 重新置为 RUNNING。
 *
 * <p>下方的 {@link #failZombies} 是运维任务专用：把长时间卡在 PENDING / RUNNING 的
 * 「僵尸任务」批量置为失败（）。
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    /**
     * 回收僵尸导出任务：把创建时间早于 {@code cutoff} 且仍处于 PENDING / RUNNING 的任务置为 FAILED。
     *
     * <p>为什么需要它：异步导出由独立线程池生成文件，若应用在「已领取任务、尚未回填结果」时重启
     * （发版、容器漂移、OOM 被杀），那一行会永远停在 RUNNING —— 界面上永远显示「生成中…」，
     * 用户既等不到文件也等不到失败提示，只能反复重试堆积更多僵尸任务。
     *
     * <p>条件 UPDATE 自带幂等：只有仍处于这两个状态的行会被改写，重复执行命中 0 行。
     *
     * @return 被置为失败的任务数
     */
    @Update("UPDATE export_tasks SET status = 'FAILED', error_message = #{reason}, finished_at = NOW() "
            + "WHERE status IN ('PENDING', 'RUNNING') AND created_at < #{cutoff}")
    int failZombies(@Param("cutoff") LocalDateTime cutoff, @Param("reason") String reason);
}
