package com.enterprise.ticket.module.ops.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.module.ops.dto.BackupAlertRequest;
import com.enterprise.ticket.module.ops.service.BackupAlertService;
import com.enterprise.ticket.module.ops.support.InternalTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部告警入口（：备份失败不得静默）
 *
 * <h2>安全模型</h2>
 * <p>本接口在 {@code permit-all} 白名单里（脚本没有用户会话），因此必须自带凭据校验。
 * 校验逻辑已抽取到 {@link InternalTokenVerifier}（ 新增主备心跳上报时抽取）——
 * 抽取的原因见该类注释：<b>同一套安全判定绝不能有两份实现</b>。
 *
 * <h2>为什么这个端点必须存在</h2>
 * <p>备份由独立容器内的 cron 执行，脚本拿不到应用内部的消息服务。
 * 若只把结果写进容器日志，就等于「失败静默」—— 而备份失败恰恰是那种
 * 「平时没人看、发现时已经无法挽回」的问题。因此把结果回调进应用，
 * 由应用向超管发站内消息，让失败出现在用户每天都会看的界面里。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/alerts")
@RequiredArgsConstructor
public class InternalAlertController {

    private final InternalTokenVerifier internalTokenVerifier;
    private final BackupAlertService backupAlertService;

    /**
     * 备份结果上报
     *
     * @return {@code notified} = 实际收到失败告警的超管数量
     */
    @PostMapping("/backup")
    public ApiResponse<Map<String, Object>> backup(
            @RequestHeader(value = InternalTokenVerifier.HEADER_TOKEN, required = false) String token,
            @RequestBody BackupAlertRequest request) {
        internalTokenVerifier.verify(token);
        int notified = backupAlertService.report(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notified", notified);
        return ApiResponse.success(data);
    }
}
