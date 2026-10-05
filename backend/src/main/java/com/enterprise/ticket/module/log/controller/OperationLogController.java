package com.enterprise.ticket.module.log.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.log.dto.vo.LogOptionVO;
import com.enterprise.ticket.module.log.dto.vo.OperationLogVO;
import com.enterprise.ticket.module.log.entity.OperationLog;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.log.support.OperationLogLabels;
import com.enterprise.ticket.module.log.support.OperationLogQuerySupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志查询（：只有 super_admin 可以查看日志；：展示中文化）
 *
 * <p>返回 {@link OperationLogVO} 而非实体：模块 / 动作以中文标签呈现，详情列默认给「人类可读摘要」，
 * 原始技术详情挂在 {@code details} 字段里，前端「查看详情」再展开（ 1~6）。
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志
     *
     * <p>测试路径：GET http://localhost:8080/api/logs?page=1&size=20&module=ORDER&result=FAILED
     */
    @GetMapping
    @PreAuthorize("@perm.has('log:view')")
    public ApiResponse<PageResult<OperationLogVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        // 分页参数必须有上下界：size<=0 在 MP 分页拦截器里行为不可依赖（可能退化为全量扫描）
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), 200L);
        IPage<OperationLog> query = new Page<>(safePage, safeSize);
        // 条件构造与导出共用同一处（OperationLogQuerySupport）——
        // 两处各写一份的话，只要有一处漏了某个筛选，导出就会比列表多几行，
        // 而现象看起来像数据出了问题，很难往「条件写得不一致」上想。
        IPage<OperationLog> resultPage = operationLogService.page(query,
                OperationLogQuerySupport.wrapper(module, action, result, operatorName, startTime, endTime));
        return ApiResponse.success(PageResult.of(resultPage, OperationLogVO::of));
    }

    /**
     * 筛选下拉选项（模块 / 动作的中文映射）
     *
     * <p>前端筛选下拉用「编码作值、中文作展示」，避免把中文映射表复制到前端（两份映射必然漂移）。
     *
     * <p>测试路径：GET http://localhost:8080/api/logs/options
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('log:view')")
    public ApiResponse<Map<String, List<LogOptionVO>>> options() {
        List<LogOptionVO> modules = OperationLogLabels.modules().entrySet().stream()
                .map(entry -> new LogOptionVO(entry.getKey(), entry.getValue()))
                .toList();
        List<LogOptionVO> actions = OperationLogLabels.actions().entrySet().stream()
                .map(entry -> new LogOptionVO(entry.getKey(), entry.getValue()))
                .toList();
        Map<String, List<LogOptionVO>> options = new LinkedHashMap<>();
        options.put("modules", modules);
        options.put("actions", actions);
        return ApiResponse.success(options);
    }
}
