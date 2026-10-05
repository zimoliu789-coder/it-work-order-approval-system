package com.enterprise.ticket.module.export.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.export.dto.ExportDownload;
import com.enterprise.ticket.module.export.dto.ExportRequest;
import com.enterprise.ticket.module.export.dto.vo.ExportResultVO;
import com.enterprise.ticket.module.export.dto.vo.ExportTaskVO;
import com.enterprise.ticket.module.export.entity.ExportTask;
import com.enterprise.ticket.module.export.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Excel 导出与下载
 *
 * <p><b>为什么下载不走静态文件直链</b>： 对附件已明确「附件资源访问必须后端鉴权，
 * 不能直接暴露 NAS 静态文件地址」，导出文件同理 —— 它可能包含全量工单与员工姓名。
 * 因此下载统一经 {@code /api/exports/{id}/download}，由服务层校验「本人或管理员」
 * 以及「状态与过期时间」。
 *
 * <p><b>审计</b>： 要求「所有导入导出操作记入审计日志」。
 * 发起导出与下载文件分别记 {@code EXPORT_CREATE} / {@code EXPORT_DOWNLOAD}；
 * 设备导入与员工导入的审计已在各自模块落地。
 *
 * <p>权限只在服务层判（导出类型决定是「仅管理员」还是「登录用户 + 自己数据范围」），
 * 这里统一 {@code isAuthenticated()}，避免把「设备台账仅管理员」这条业务规则
 * 写进注解后与服务层判定分叉。
 */
@RestController
@RequestMapping("/api/exports")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * 发起导出（同步返回文件 / 异步返回任务）
     *
     * <p>测试路径：POST http://localhost:8080/api/exports
     * <pre>
     * { "type": "DEVICE", "device": { "status": "AVAILABLE" } }
     * { "type": "REPORT_DEVICE_USAGE", "year": 2026, "month": 9 }
     * </pre>
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "EXPORT", action = "EXPORT_CREATE", risk = RiskLevel.NORMAL, description = "发起导出")
    public ApiResponse<ExportResultVO> export(@RequestBody ExportRequest request) {
        return ApiResponse.success(exportService.export(ExportType.of(request.getType()), request));
    }

    /** 我的导出记录（管理员可见全部；用于「异步导出完成后回来下载」） */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ExportTaskVO>> tasks(@RequestParam(required = false) String type,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(exportService.recentTasks(ExportType.of(type), limit));
    }

    /**
     * 导出记录分页（导出记录页使用）
     *
     * <p>测试路径：GET http://localhost:8080/api/exports/page?page=1&size=10&amp;type=DEVICE
     */
    @GetMapping("/page")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<ExportTaskVO>> page(@RequestParam(required = false) String type,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(exportService.pageTasks(ExportType.of(type), page, size));
    }

    /** 导出记录详情 */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ExportTaskVO> detail(@PathVariable Long id) {
        return ApiResponse.success(exportService.getTask(id));
    }

    /**
     * 删除导出记录（仅本人或管理员）
     *
     * <p>删除属「对既有数据的破坏性操作」，记 {@code HIGH} 风险审计；同时删除磁盘文件。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "EXPORT", action = "EXPORT_DELETE", risk = RiskLevel.HIGH, description = "删除导出记录")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        exportService.deleteTask(id);
        return ApiResponse.success(null);
    }

    /**
     * 下载导出文件
     *
     * <p>测试路径：GET http://localhost:8080/api/exports/1/download
     *
     * <p>渲染类型固定为 xlsx，且显式带 {@code X-Content-Type-Options: nosniff}：
     * 导出文件的内容部分来自用户输入（工单原因、备注等），不能被浏览器嗅探成其它类型渲染。
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "EXPORT", action = "EXPORT_DOWNLOAD", risk = RiskLevel.NORMAL, description = "下载导出文件")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        ExportDownload download = exportService.download(id);
        ExportTask task = download.task();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(task.getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(download.resource());
    }
}
