package com.enterprise.ticket.module.export.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ExportStatus;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.excel.ExcelExportWriter;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.entity.ExportTask;
import com.enterprise.ticket.module.export.mapper.ExportTaskMapper;
import com.enterprise.ticket.module.export.support.ExportSheetBuilder;
import com.enterprise.ticket.module.export.support.ExportStorage;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出文件生成执行器
 *
 * <p>「生成并落盘」这件事<b>只有这一份实现</b>，同步与异步路径都调它（{@link #generateAndComplete}）：
 * 若各写一份，迟早出现「同步导出的文件与异步导出的文件内容不一致」。
 * 区别只在入口 —— 同步由请求线程直接调用，异步由 {@link #runAsync} 在线程池里调用。
 *
 * <p><b>幂等</b>：异步入口先用条件 UPDATE 把 {@code PENDING} 置为 {@code RUNNING}，
 * 命中 0 行说明任务已被别的线程领取（或已被同步路径完成），直接跳过。
 * 这样即便线程池策略/重试导致重复投递，也不会生成两份文件、发两条消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskRunner {

    /** 失败原因落库长度上限，与 {@code export_task.error_message} 的 500 对齐 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 与查询条件里的 scope 取值一致；大写比较，避免前端传小写导致「全部工单」被误判成「我的」 */
    static final String SCOPE_ALL = "ALL";

    private final ExportTaskMapper taskMapper;
    private final ExportSheetBuilder sheetBuilder;
    private final ExportStorage storage;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;
    /**
     * 导出文件保留天数的<b>事实源</b>（·）
     *
     * <p>过期时刻在生成完成时算一次、落进 {@code export_task.expire_at}，之后由
     * {@code ExportMaintenanceService} 按 {@code expire_at} 清理 —— 清理侧不需要知道保留天数，
     * 只需知道「这一条什么时候过期」。这样即便管理员事后改了保留天数，
     * 也不会让「已经生成好的文件」突然提前或延后过期（参数只影响<b>此后生成</b>的文件），
     * 避免「改了参数，昨天导出的报表今天就 404」这种难以解释的现象。
     */
    private final SystemConfigService systemConfigService;

    /** 生效的导出文件保留天数：优先取系统参数（配置页「文件存储」卡），缺失则回落部署配置 */
    private int exportRetentionDays() {
        return systemConfigService.exportRetentionDays();
    }

    /**
     * 异步生成入口（：超过阈值时改为异步生成，完成后站内消息通知下载）
     *
     * <p>执行器 {@code exportExecutor} 包装了 {@code DelegatingSecurityContextAsyncTaskExecutor}，
     * 因此本方法内复用列表 Service 取数时仍带着<b>提交人的安全上下文</b>：
     * 「我的工单」限定的是提交人自己，「全部工单」也仍按提交人当时的角色校验，
     * 不会因为换到异步线程就放开权限。
     */
    @Async("exportExecutor")
    public void runAsync(Long taskId) {
        if (!claim(taskId)) {
            log.info("导出任务已被领取或已完成，跳过本次执行：taskId={}", taskId);
            return;
        }
        generateAndComplete(taskId);
    }

    /**
     * 生成文件并回填任务（同步路径由请求线程调用）
     *
     * <p>任何异常都被收敛为「任务置 FAILED + 记录原因」，不外抛：
     * 异步路径抛出去只会进日志（用户什么都看不到），同步路径抛出去则会让用户
     * 拿到一个 500 而不知道「其实记录里写了原因」。
     * 同步路径上真正需要让用户当场知道的错误（例如行数超限）在调用前就已校验并抛出，
     * 走不到这里。
     */
    public void generateAndComplete(Long taskId) {
        ExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("导出任务不存在，跳过：taskId={}", taskId);
            return;
        }
        try {
            ExportType type = ExportType.of(task.getExportType());
            if (type == null) {
                throw new BusinessException(com.enterprise.ticket.common.api.ErrorCode.EXPORT_TYPE_INVALID);
            }
            ExportQuery query = readQuery(task.getQueryJson());
            boolean allScope = query != null && SCOPE_ALL.equalsIgnoreCase(query.getScope());

            List<SheetSpec> sheets = sheetBuilder.build(type, query, allScope);
            byte[] bytes = ExcelExportWriter.writeWorkbook(sheets);
            String relative = storage.store(new ByteArrayInputStream(bytes));

            LocalDateTime now = LocalDateTime.now();
            // 记录类导出的行数在「发起导出」时已算好并落库（见 ExportServiceImpl#export），
            // 生成阶段把它带上即可；报表类没有预存行数（发起时按设计存 0），
            // 由 totalRowsOf 改取首张工作表的行数得出。
            long countedRows = task.getTotalRows() == null ? 0L : task.getTotalRows();
            taskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, taskId)
                    .set(ExportTask::getStatus, ExportStatus.SUCCESS.name())
                    .set(ExportTask::getStoredPath, relative)
                    .set(ExportTask::getFileSize, (long) bytes.length)
                    .set(ExportTask::getTotalRows, sheetBuilder.totalRowsOf(type, countedRows, sheets))
                    .set(ExportTask::getErrorMessage, null)
                    .set(ExportTask::getFinishedAt, now)
                    .set(ExportTask::getExpireAt, now.plusDays(exportRetentionDays())));
            log.info("导出生成完成：taskId={} type={} size={}B", taskId, type.name(), bytes.length);
            notifyReady(task, type);
        } catch (Exception e) {
            log.error("导出生成失败：taskId={}", taskId, e);
            markFailed(taskId, e);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 条件 UPDATE 领取任务：PENDING → RUNNING；命中 0 行表示不可领取 */
    private boolean claim(Long taskId) {
        return taskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                .eq(ExportTask::getId, taskId)
                .eq(ExportTask::getStatus, ExportStatus.PENDING.name())
                .set(ExportTask::getStatus, ExportStatus.RUNNING.name())) > 0;
    }

    private void markFailed(Long taskId, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        try {
            taskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, taskId)
                    .set(ExportTask::getStatus, ExportStatus.FAILED.name())
                    .set(ExportTask::getErrorMessage, message)
                    .set(ExportTask::getFinishedAt, LocalDateTime.now()));
        } catch (Exception updateError) {
            // 连失败状态都写不进去时只能记日志，不能让它盖住原始异常
            log.error("导出任务失败状态回写失败：taskId={}", taskId, updateError);
        }
    }

    /** 生成完成后发站内消息（「完成后站内消息通知下载」） */
    private void notifyReady(ExportTask task, ExportType type) {
        String title = "导出完成：" + type.getLabel();
        String content = "「" + type.getLabel() + "」已生成，可前往「导出记录」下载，文件保留 "
                + exportRetentionDays() + " 天。";
        // send 是尽力而为（失败只记日志），消息发送失败不会影响已生成的文件
        messageService.send(task.getRequesterId(), MessageType.EXPORT_READY, title, content, null);
    }

    /** 反序列化导出条件；损坏时返回空条件而不是抛错，避免一条脏 JSON 让任务永远卡在 RUNNING */
    private ExportQuery readQuery(String queryJson) {
        if (queryJson == null || queryJson.isBlank()) {
            return new ExportQuery();
        }
        try {
            return objectMapper.readValue(queryJson, ExportQuery.class);
        } catch (Exception e) {
            log.warn("导出条件反序列化失败，按无条件处理：taskId={} json={}", queryJson, e.getMessage());
            return new ExportQuery();
        }
    }
}
