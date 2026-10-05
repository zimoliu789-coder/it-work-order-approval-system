package com.enterprise.ticket.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.ExceptionCategory;
import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.log.dto.vo.LogOptionVO;
import com.enterprise.ticket.module.system.dto.vo.ExceptionLogDetailVO;
import com.enterprise.ticket.module.system.dto.vo.ExceptionLogVO;
import com.enterprise.ticket.module.system.entity.ExceptionLog;
import com.enterprise.ticket.module.system.mapper.ExceptionLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常日志查询。
 *
 * <h2>为什么只有查询、没有删除</h2>
 * 异常日志是**取证数据**：能删就等于能销毁证据。超期行由每日清理任务按
 * {@code exception_log_retention_days} 统一删除 —— 那是「策略」，不是「手滑」。
 * 这与操作日志同一取向（操作日志也没有单条删除接口）。
 *
 * <h2>权限只给超管（{@code exception:view}）</h2>
 * 异常堆栈是给能改代码的人看的。给业务管理员开放只会产生「看不懂但也不敢忽略」的噪音，
 * 与「基础设施级告警只发超管」同一取向。
 */
@RestController
@RequestMapping("/api/exception-logs")
@RequiredArgsConstructor
public class ExceptionLogController {

    /** 分页上限：与操作日志一致，防止 size 被传成极大值退化为全量扫描 */
    private static final long MAX_PAGE_SIZE = 200L;

    private final ExceptionLogMapper exceptionLogMapper;

    /**
     * 分页查询异常日志。
     *
     * <p>测试路径：GET http://localhost:8080/api/exception-logs?page=1&size=20&category=DATABASE
     */
    @GetMapping
    @PreAuthorize("@perm.has('exception:view')")
    public ApiResponse<PageResult<ExceptionLogVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String alertState,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);
        IPage<ExceptionLog> query = new Page<>(safePage, safeSize);
        IPage<ExceptionLog> resultPage = exceptionLogMapper.selectPage(query, buildWrapper(
                category, severity, alertState, keyword, startTime, endTime));
        return ApiResponse.success(PageResult.of(resultPage, ExceptionLogVO::of));
    }

    /**
     * 单条详情（含完整堆栈）。
     *
     * <p>测试路径：GET http://localhost:8080/api/exception-logs/123
     */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('exception:view')")
    public ApiResponse<ExceptionLogDetailVO> detail(@PathVariable Long id) {
        ExceptionLog entity = exceptionLogMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "异常日志不存在或已被清理任务删除");
        }
        return ApiResponse.success(ExceptionLogDetailVO.of(entity));
    }

    /**
     * 筛选下拉选项。
     *
     * <p>选项由**枚举**生成而不是前端硬编码：分类与分级的取值会随代码演进，
     * 前端抄一份必然漂移（新增一个分类后，筛选项里没有它，用户以为没这类异常）。
     *
     * <p>测试路径：GET http://localhost:8080/api/exception-logs/options
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('exception:view')")
    public ApiResponse<Map<String, List<LogOptionVO>>> options() {
        Map<String, List<LogOptionVO>> options = new LinkedHashMap<>();
        options.put("categories", Arrays.stream(ExceptionCategory.values())
                .map(category -> new LogOptionVO(category.name(), category.label()))
                .toList());
        options.put("severities", Arrays.stream(AlertLevel.values())
                .map(level -> new LogOptionVO(level.code(), level.label() + "（" + level.delivery() + "）"))
                .toList());
        options.put("alertStates", List.of(
                new LogOptionVO("PENDING", "待告警"),
                new LogOptionVO("SENT", "已告警"),
                new LogOptionVO("SUPPRESSED", "未告警")));
        return ApiResponse.success(options);
    }

    /**
     * 顶部概览计数（各状态各多少条）—— 管理员进来第一眼要看到「现在积了多少」。
     *
     * <p>测试路径：GET http://localhost:8080/api/exception-logs/stats
     */
    @GetMapping("/stats")
    @PreAuthorize("@perm.has('exception:view')")
    public ApiResponse<Map<String, Long>> stats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("pending", exceptionLogMapper.countByState("PENDING"));
        stats.put("sent", exceptionLogMapper.countByState("SENT"));
        stats.put("suppressed", exceptionLogMapper.countByState("SUPPRESSED"));
        stats.put("total", exceptionLogMapper.selectCount(null));
        return ApiResponse.success(stats);
    }

    /**
     * 组装查询条件。
     *
     * <p>⚠️ 关键词用 {@code .and(w -> w.like(...).or().like(...))} **整体包住**：
     * 不包的话 {@code or} 会把前面的分类 / 时间条件一起「或」掉 ——
     * 结果是「筛了数据库分类，却查出所有分类」这种静默越权式的错误结果。
     */
    private LambdaQueryWrapper<ExceptionLog> buildWrapper(String category, String severity,
                                                          String alertState, String keyword,
                                                          LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<ExceptionLog> wrapper = Wrappers.<ExceptionLog>lambdaQuery()
                .eq(StringUtils.hasText(category), ExceptionLog::getCategory, category)
                .eq(StringUtils.hasText(severity), ExceptionLog::getSeverity, severity)
                .eq(StringUtils.hasText(alertState), ExceptionLog::getAlertState, alertState)
                .ge(startTime != null, ExceptionLog::getOccurredAt, startTime)
                .le(endTime != null, ExceptionLog::getOccurredAt, endTime);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(ExceptionLog::getMessage, trimmed)
                    .or().like(ExceptionLog::getExceptionClass, trimmed)
                    .or().like(ExceptionLog::getRequestUri, trimmed));
        }
        return wrapper.orderByDesc(ExceptionLog::getOccurredAt).orderByDesc(ExceptionLog::getId);
    }
}
