package com.enterprise.ticket.module.ha.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.module.ha.dto.HaHeartbeatRequest;
import com.enterprise.ticket.module.ha.service.HaConfigService;
import com.enterprise.ticket.module.ha.service.HaHeartbeatService;
import com.enterprise.ticket.module.ops.support.InternalTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主备心跳 / 切换事件上报入口—— <b>内部通道</b>
 *
 * <h2>安全模型</h2>
 * <p>与备份上报纸承完全一致的一套：本接口在 {@code permit-all} 白名单里
 * （上报方是跑在目标机器上的脚本，没有用户会话），因此自带凭据校验 ——
 * 请求头 {@code X-Internal-Token} 必须等于 {@code INTERNAL_ALERT_TOKEN}。
 * 校验实现见 {@link InternalTokenVerifier}（常量时间比较 + 未配置即 fail-closed）。
 *
 * <h2>为什么上报失败（如令牌不对）必须返回明确的 403 而不是静默 200</h2>
 * <p>脚本侧通常会在心跳失败时重试并打日志。若这里对错误的令牌「假装成功」，
 * 脚本就不会报错，而维护人员看到的现象是「页面上节点状态一直不更新」——
 * 排查方向会指向应用、指向数据库、指向网络，唯独不会指向
 * 「两台机器的 INTERNAL_ALERT_TOKEN 不一致」这个真实原因。
 * {@code deploy/ha/.env.ha.example} 里已经把这条列为必查项，接口这边要配合它把错误暴露出来。
 *
 * <h2>为什么请求体校验刻意宽松</h2>
 * <p>见 {@link HaHeartbeatRequest} 类头注释：脚本在异常路径上可能只拿得到部分上下文，
 * 此时「少一个字段」不应该导致整条心跳被拒 —— 那会把「不完整心跳」变成
 * 「完全没有心跳」，页面直接变红。唯一强制的是 {@code nodeIp}
 * （没有它就无法定位是哪台机器在报，这条信息无法推断）。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/ha")
@RequiredArgsConstructor
public class InternalHaController {

    private final InternalTokenVerifier internalTokenVerifier;
    private final HaHeartbeatService haHeartbeatService;
    private final HaConfigService haConfigService;

    /**
     * 导出本节点配置（供备节点「一键加入集群」拉取，）。
     *
     * <p>与心跳上报共用同一个内部令牌：备节点在页面上填的就是这个令牌，
     * 因此不需要第二套凭据 —— 多一套凭据就多一处「两台机器不一致」的可能。
     *
     * <p>测试路径：{@code GET /api/internal/ha/config}（需 {@code X-Internal-Token}）
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> exportConfig(
            @RequestHeader(value = InternalTokenVerifier.HEADER_TOKEN, required = false) String token) {
        internalTokenVerifier.verify(token);
        return ApiResponse.success(haConfigService.exportConfig());
    }

    /**
     * 心跳 / 切换事件上报。
     *
     * @return {@code accepted=true} 表示已受理（不代表「节点已登记」——
     *         未登记的 IP 会被忽略，具体见应用日志）
     */
    @PostMapping("/report")
    public ApiResponse<Map<String, Object>> report(
            @RequestHeader(value = InternalTokenVerifier.HEADER_TOKEN, required = false) String token,
            @RequestBody HaHeartbeatRequest request) {
        internalTokenVerifier.verify(token);
        haHeartbeatService.report(request);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", true);
        data.put("event", StringUtils.hasText(request.getEvent())
                ? request.getEvent().trim().toUpperCase(java.util.Locale.ROOT) : "HEARTBEAT");
        data.put("nodeIp", request.getNodeIp());
        return ApiResponse.success(data);
    }
}
