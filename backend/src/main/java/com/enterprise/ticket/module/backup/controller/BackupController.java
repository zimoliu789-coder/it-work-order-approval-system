package com.enterprise.ticket.module.backup.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.backup.dto.vo.BackupOverviewVO;
import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;
import com.enterprise.ticket.module.backup.service.BackupService;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库备份接口（P0）
 *
 * <h2>权限为什么是「读 / 写」两个码，而不是复用 {@code job:run}</h2>
 * 手动触发在语义上确实是「跑一次定时任务」，也已在 {@code JobAdminController} 里
 * 挂了 {@code job=database-backup}（那是给运维排障与验收用的统一入口）。
 * 但本接口是 <b>「备份记录」页面上的一个业务动作</b>：它需要把「本次备份的结果
 * （成功/失败 + 原因 + 文件大小）」直接回给页面，而 {@code job:run} 返回的是通用的
 * {@code JobResult} 列表。两个入口共用同一个服务方法，不存在逻辑重复 ——
 * 差别只在返回结构面向谁。
 *
 * <h2>为什么备份失败<b>不</b>抛异常</h2>
 * mysqldump 失败属于<b>环境问题</b>（目录不可写 / 磁盘满 / 未装客户端）。
 * 若抛 400，管理员会以为是自己的操作错了。正确做法是返回一条 {@code status=FAILED}
 * 的记录，让页面把确切原因展示出来（同时系统已向超管发出告警）。
 * ⇒ 本接口唯一会抛的异常是「已有备份正在执行」（{@code BACKUP_ALREADY_RUNNING}），
 * 那才是真正的「本次请求不该被执行」。
 */
@RestController
@RequestMapping("/api/system/backups")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /** 备份概览：开关 / 时刻 / 保留天数 / 目录可用性 / 最后一次成功备份 */
    @GetMapping("/overview")
    @PreAuthorize("@perm.has('backup:view')")
    public ApiResponse<BackupOverviewVO> overview() {
        return ApiResponse.success(backupService.overview());
    }

    /** 备份记录分页列表（按开始时间倒序） */
    @GetMapping
    @PreAuthorize("@perm.has('backup:view')")
    public ApiResponse<IPage<BackupRecordVO>> page(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(backupService.page(page, size));
    }

    /**
     * 立即备份（同步执行）。
     *
     * <p>刻意<b>同步</b>而不是丢后台任务：备份是运维动作，管理员点完就要知道
     * 「这份到底成没成」。异步化会让页面只能显示「已提交」，
     * 而最需要立刻知道的恰恰是失败原因。
     * 代价是大库会让请求等待较久 —— 由按钮上的 loading 与「大库耗时长」提示承担。
     */
    @PostMapping
    @PreAuthorize("@perm.has('backup:manage')")
    @AuditLog(module = "BACKUP", action = "BACKUP_MANUAL_RUN",
            description = "手动触发数据库备份", risk = RiskLevel.HIGH)
    public ApiResponse<BackupRecordVO> run() {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(backupService.runManually(operatorId));
    }
}
