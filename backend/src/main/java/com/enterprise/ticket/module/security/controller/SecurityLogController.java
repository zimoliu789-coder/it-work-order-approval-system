package com.enterprise.ticket.module.security.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.log.dto.vo.LogOptionVO;
import com.enterprise.ticket.module.security.dto.vo.SecurityEventVO;
import com.enterprise.ticket.module.security.entity.SecurityEvent;
import com.enterprise.ticket.module.security.mapper.SecurityEventMapper;
import com.enterprise.ticket.module.security.service.IpBlockService;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全日志。
 *
 * <h2>权限只给超管（{@code security:view}）</h2>
 * 与异常日志同一取向：安全事件的处置动作（解封 IP、改白名单）是基础设施级操作，
 * 与「基础设施级告警只发超管」一致。管理员看不到本页，但**不影响他被保护** ——
 * 告警是发给超管的，封禁是系统自动做的。
 *
 * <h2>为什么没有「删除事件」</h2>
 * 与异常日志同理：安全事件是取证数据，能删就等于能销毁证据。
 * 超期行由每日清理任务按 {@code security_event_retention_days} 统一删除。
 */
@RestController
@RequestMapping("/api/security-logs")
@RequiredArgsConstructor
public class SecurityLogController {

    private static final long MAX_PAGE_SIZE = 200L;

    /** 概览里「失败最多的 IP」取前几名 —— 再多也没人会看，而它们正是最该被处置的那几个 */
    private static final int TOP_IP_LIMIT = 10;

    private final SecurityEventMapper securityEventMapper;
    private final IpBlockService ipBlockService;
    private final SystemConfigService systemConfigService;

    /**
     * 分页查询安全事件。
     *
     * <p>测试路径：GET http://localhost:8080/api/security-logs?page=1&size=20&eventType=LOGIN_FAIL
     */
    @GetMapping
    @PreAuthorize("@perm.has('security:view')")
    public ApiResponse<PageResult<SecurityEventVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);
        IPage<SecurityEvent> query = new Page<>(safePage, safeSize);
        IPage<SecurityEvent> result = securityEventMapper.selectPage(query,
                buildWrapper(eventType, ip, username, startTime, endTime));
        return ApiResponse.success(PageResult.of(result, SecurityEventVO::of));
    }

    /**
     * 概览：当前封禁列表 + 近期失败最多的 IP + 各类型计数。
     *
     * <p>合成一个接口而不是三个：这三块是**同一屏**上的内容（页面进来就要一起显示），
     * 拆成三个请求只会让首屏闪三次。
     *
     * <p>测试路径：GET http://localhost:8080/api/security-logs/overview
     */
    @GetMapping("/overview")
    @PreAuthorize("@perm.has('security:view')")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(defaultValue = "24") int hours) {

        int safeHours = Math.min(Math.max(hours, 1), 24 * 30);
        LocalDateTime since = LocalDateTime.now().minusHours(safeHours);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowHours", safeHours);
        result.put("blocks", ipBlockService.effectiveList().stream()
                .map(SecurityEventVO.BlockVO::of)
                .toList());
        result.put("topFailIps", securityEventMapper.topFailIpsSince(since, TOP_IP_LIMIT));
        result.put("typeCounts", securityEventMapper.countGroupByTypeSince(since));
        result.put("ipBlockEnabled", ipBlockService.ipBlockEnabled());
        result.put("whitelist", whitelistText());
        return ApiResponse.success(result);
    }

    /**
     * 筛选下拉选项（由枚举生成，前端不硬编码）。
     *
     * <p>测试路径：GET http://localhost:8080/api/security-logs/options
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('security:view')")
    public ApiResponse<Map<String, List<LogOptionVO>>> options() {
        Map<String, List<LogOptionVO>> options = new LinkedHashMap<>();
        options.put("eventTypes", Arrays.stream(SecurityEventType.values())
                .map(type -> new LogOptionVO(type.name(), type.label()))
                .toList());
        return ApiResponse.success(options);
    }

    /**
     * 人工解封。
     *
     * <p>用 POST 而不是 DELETE：这不是「删除一条封禁记录」，而是「改变它的状态」
     * （记录会保留，`unblocked_at` / `unblocked_by` 都要留痕）——
     * 用 DELETE 会让审计上看起来像「有人删了证据」。
     *
     * <p>审计为 HIGH：解封等于把一个来源重新放进门，与「改组织结构」同属
     * 会影响一批人的安全操作。
     *
     * <p>测试路径：POST http://localhost:8080/api/security-logs/blocks/12/unblock
     */
    @PostMapping("/blocks/{id}/unblock")
    @PreAuthorize("@perm.has('security:view')")
    @AuditLog(module = "SECURITY", action = "IP_UNBLOCK", risk = RiskLevel.HIGH,
            description = "人工解除 IP 封禁")
    public ApiResponse<Void> unblock(@PathVariable Long id) {
        boolean done = ipBlockService.unblock(id, SecurityUtils.getCurrentUserId());
        return done ? ApiResponse.success("已解除封禁", null)
                : ApiResponse.success("该封禁记录已失效，无需重复解除", null);
    }

    /**
     * 组装查询条件。
     *
     * <p>⚠️ IP 与账号名用**精确匹配**而不是 LIKE：这两个字段的值来自用户输入
     * （攻击者可以随便填账号名），LIKE 会让 `%` / `_` 变成通配符，
     * 一次查询扫全表。精确匹配还符合实际用法 —— 管理员是复制一个 IP 来查，不是模糊搜。
     */
    private LambdaQueryWrapper<SecurityEvent> buildWrapper(String eventType, String ip, String username,
                                                           LocalDateTime startTime, LocalDateTime endTime) {
        return Wrappers.<SecurityEvent>lambdaQuery()
                .eq(StringUtils.hasText(eventType), SecurityEvent::getEventType, eventType)
                .eq(StringUtils.hasText(ip), SecurityEvent::getIp, ip == null ? null : ip.trim())
                .eq(StringUtils.hasText(username), SecurityEvent::getUsername,
                        username == null ? null : username.trim())
                .ge(startTime != null, SecurityEvent::getOccurredAt, startTime)
                .le(endTime != null, SecurityEvent::getOccurredAt, endTime)
                .orderByDesc(SecurityEvent::getOccurredAt)
                .orderByDesc(SecurityEvent::getId);
    }

    private String whitelistText() {
        try {
            return systemConfigService.securityIpWhitelist();
        } catch (Exception e) {
            return "";
        }
    }
}
