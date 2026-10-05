package com.enterprise.ticket.module.backup.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ticket.module.backup.dto.vo.BackupOverviewVO;
import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;

/**
 * 数据库备份服务（P0）
 *
 * <h2>为什么「执行一次备份」要拆成手动与定时两个入口</h2>
 * 两者的<b>准入条件不同</b>：
 * <ul>
 *   <li>{@link #runManually}：管理员显式点按钮 ⇒ 只要没有正在跑的备份就必须执行，
 *       <b>不受「今天已经跑过」限制</b>（否则「我刚改了数据想立刻备份一份」会被拒，
 *       而拒绝的理由「今天已经备份过」从这里看毫无道理）；也<b>不看开关</b>
 *       —— 开关管的是「自动」，不是「允许备份」。</li>
 *   <li>{@link #runScheduled}：定时唤醒 ⇒ 必须先判开关，再判「今天是否已跑过」
 *       （补跑语义，见 {@code DatabaseBackupJob}）。</li>
 * </ul>
 * 合成一个方法就得靠布尔参数区分，调用点很容易传错，而传错的表现是
 * 「点了按钮没反应」或「一天备份十几次」，都不报错。
 */
public interface BackupService {

    /**
     * 手动触发一次备份（同步执行，返回本次记录）。
     *
     * @param operatorId 操作人（记入 backup_record.operator_id，便于审计）
     * @throws com.enterprise.ticket.common.exception.BusinessException 已有备份在跑时拒绝
     */
    BackupRecordVO runManually(Long operatorId);

    /**
     * 定时触发一次备份。
     *
     * @return 本次记录；因「未启用」或「今天已跑过」而跳过时返回 {@code null}
     */
    BackupRecordVO runScheduled();

    /**
     * 僵尸回收：把残留的 RUNNING 记录标记为 FAILED。
     *
     * @return 回收条数
     */
    int recycleStaleRunning();

    /** 分页列表（按开始时间倒序） */
    IPage<BackupRecordVO> page(long page, long size);

    /** 概览 */
    BackupOverviewVO overview();
}
