package com.enterprise.ticket.module.upgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 升级任务 Mapper
 *
 * <h2>为什么状态迁移一律用「条件 UPDATE（CAS）」而不是「先查再改」</h2>
 * <p>对同一条任务做状态迁移的路径不止一条：
 * <ul>
 *   <li>上传请求线程（校验 → 备份 → 落盘 → 待应用）；</li>
 *   <li>启动对账（APPLYING → SUCCESS / ROLLED_BACK / FAILED）；</li>
 *   <li>管理员操作（应用 / 回滚）；</li>
 *   <li>双节点启动时两边的对账线程。</li>
 * </ul>
 * 「先查再改」在这些路径交错时会互相覆盖：典型的是「回滚成功」被随后到达的
 * 「升级成功」回执覆盖成 SUCCESS —— 界面上显示升级成功，实际系统跑的是回滚后的旧版本。
 * 条件 UPDATE 把「状态必须仍是 X」写进 WHERE，命中 0 行即表示「已经被别人改过了」，
 * 调用方据此放弃本次迁移而不是覆盖它。
 *
 * <h2>为什么 {@code selectActive()} 与 V30 的 {@code active_flag} 要同时存在</h2>
 * <p>{@code active_flag}（生成列 + 唯一索引）是**真正的并发保护**，
 * 它由数据库保证，双节点也挡得住；{@code selectActive()} 只负责在撞上唯一键之前
 * 先给管理员一句人能看懂的话，而不是让他看到 DuplicateKeyException 的堆栈。
 * 两者的活跃集合定义必须与 {@code UpgradeStatus#isActive()} 和 V30 的 CASE 表达式
 * <b>三处同时一致</b> —— 任何一处漏改，都会出现「界面说没有进行中的任务、
 * 但插库被唯一键拦下」这种自相矛盾的现象。
 */
@Mapper
public interface UpgradeTaskMapper extends BaseMapper<UpgradeTask> {

    /** 查询当前活跃（进行中）的升级任务，取最新一条 */
    @Select("SELECT * FROM upgrade_tasks "
            + "WHERE status IN ('PENDING', 'VALIDATING', 'BACKING_UP', 'STAGING', 'READY_TO_APPLY', 'APPLYING') "
            + "ORDER BY created_at DESC LIMIT 1")
    UpgradeTask selectActive();

    /** 查询全部处于 APPLYING 的任务（启动对账用） */
    @Select("SELECT * FROM upgrade_tasks WHERE status = 'APPLYING' ORDER BY created_at DESC")
    List<UpgradeTask> selectApplying();

    /** 升级历史（倒序，条数由调用方按配置限制） */
    @Select("SELECT * FROM upgrade_tasks ORDER BY created_at DESC LIMIT #{limit}")
    List<UpgradeTask> selectHistory(@Param("limit") int limit);

    /**
     * 查询「处理中」但已过期的僵尸任务（启动对账用）。
     *
     * <p>覆盖的是「后端在升级处理过程中被杀掉」：任务行停在 VALIDATING / BACKING_UP /
     * STAGING，而处理它的进程已经不在了 —— 不会有人再推进它，
     * 而它会一直占住活跃唯一键，让后续每一次升级都被拒绝。
     * 判据用 {@code created_at} 而不是 {@code updated_at}：后者带
     * {@code ON UPDATE CURRENT_TIMESTAMP}，任何一次状态推进都会刷新它，
     * 用它反而看不出「这个任务已经很久没人碰了」。
     */
    @Select("SELECT * FROM upgrade_tasks "
            + "WHERE status IN ('PENDING', 'VALIDATING', 'BACKING_UP', 'STAGING') AND created_at < #{cutoff} "
            + "ORDER BY created_at DESC")
    List<UpgradeTask> selectStaleProcessing(@Param("cutoff") LocalDateTime cutoff);

    /** 过程态推进（VALIDATING / BACKING_UP / STAGING）：不带状态前置条件，由调用方保证顺序 */
    @Update("UPDATE upgrade_tasks SET status = #{status}, step = #{step}, progress = #{progress}, "
            + "message = #{message} WHERE id = #{id}")
    int updateStage(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("step") String step,
                    @Param("progress") Integer progress,
                    @Param("message") String message);

    /**
     * 回填「包信息」（校验通过后立刻调用）。
     *
     * <p>{@link #updateStage} 只写状态列，而任务创建时还不知道版本号与哈希
     * （要等包校验完）。因此必须有一次单独的落库 —— 否则这些值只存在于内存对象里，
     * 进程一断就永远丢了，库里只剩一行「目标版本 -」的失败记录，
     * 事后完全无法回答「当时上传的是哪个包」。
     *
     * <p>回填时机刻意放在<b>备份之前</b>：这样即便进程在备份阶段被杀，
     * 库里也已经记下了目标版本与包哈希 —— 那正是排查时最需要的两条信息。
     */
    @Update("UPDATE upgrade_tasks SET source_version = #{sourceVersion}, target_version = #{targetVersion}, "
            + "package_sha256 = #{packageSha256}, package_size = #{packageSize} WHERE id = #{id}")
    int updatePackageInfo(@Param("id") Long id,
                          @Param("sourceVersion") String sourceVersion,
                          @Param("targetVersion") String targetVersion,
                          @Param("packageSha256") String packageSha256,
                          @Param("packageSize") Long packageSize);

    /** 回填产物路径（备份与落盘完成后调用） */
    @Update("UPDATE upgrade_tasks SET backup_path = #{backupPath}, staging_path = #{stagingPath} "
            + "WHERE id = #{id}")
    int updateArtifacts(@Param("id") Long id,
                        @Param("backupPath") String backupPath,
                        @Param("stagingPath") String stagingPath);

    /**
     * 待应用 → 应用中（CAS）。
     *
     * <p>WHERE 里必须带上 {@code status = 'READY_TO_APPLY'}：管理员完全可能在
     * 两个浏览器标签里各点一次「应用」，第二次必须失败而不是把已发起的外部命令
     * 再发一遍（那会导致两个脚本同时替换同一份产物）。
     */
    @Update("UPDATE upgrade_tasks SET status = 'APPLYING', step = 'APPLY', progress = 90, "
            + "message = #{message} WHERE id = #{id} AND status = 'READY_TO_APPLY'")
    int markApplying(@Param("id") Long id, @Param("message") String message);

    /**
     * 应用中 → 待应用（回退）。
     *
     * <p>只在一处使用：外部命令<b>根本没起来</b>（可执行文件不存在 / 无权限）时。
     * 此时产物完好、包也校验过，把任务退回 READY_TO_APPLY 让管理员修好配置后重试，
     * 比直接判 FAILED 让他重新上传一遍要合理得多。
     */
    @Update("UPDATE upgrade_tasks SET status = 'READY_TO_APPLY', step = 'READY', progress = 80, "
            + "message = #{message} WHERE id = #{id} AND status = 'APPLYING'")
    int revertToReady(@Param("id") Long id, @Param("message") String message);

    /**
     * 终态迁移（CAS）：仅当行仍处于 {@code expected} 状态时才写入新状态。
     *
     * @return 受影响行数；0 表示该行已被别的路径迁移过（调用方据此放弃本次迁移，
     *         而不是把旧结果覆盖掉）
     */
    @Update("UPDATE upgrade_tasks SET status = #{status}, step = NULL, progress = 100, "
            + "message = #{message}, finished_at = NOW() "
            + "WHERE id = #{id} AND status = #{expected}")
    int finish(@Param("id") Long id,
               @Param("expected") String expected,
               @Param("status") String status,
               @Param("message") String message);
}
