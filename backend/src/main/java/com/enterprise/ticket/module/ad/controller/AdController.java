package com.enterprise.ticket.module.ad.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.ad.dto.AdAccountConvertVO;
import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.dto.AdConfigVO;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewRequest;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewVO;
import com.enterprise.ticket.module.ad.dto.AdSyncResultVO;
import com.enterprise.ticket.module.ad.dto.AdTestResultVO;
import com.enterprise.ticket.module.ad.service.AdAccountConversionService;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD 域控配置接口（； / 一.2）
 *
 * <h2>权限</h2>
 * <p>全部端点仅 {@code super_admin}（权限码 {@code ad:view} / {@code ad:manage}，
 * 在 {@code PermissionCatalog} 中<b>不</b>授予 admin 角色）。
 * 理由：AD 配置包含服务账号的绑定密码、以及「谁能进系统」的判定来源 ——
 * 改错一处就能让全公司登不进来，权限面必须收到最小。
 *
 * <h2>审计</h2>
 * <p>「配置变更写高危审计日志」+ 一.5「所有 AD 相关操作写审计日志」：
 * 四个端点全部带 {@code @AuditLog}。其中「保存配置」与「立即同步」为
 * {@link RiskLevel#HIGH}（REQUIRES_NEW，独立事务，业务回滚也留痕）——
 * 它们分别代表「改认证入口」和「批量改账号」，都是事后必须能说清「谁在什么时候做的」的操作。
 *
 * <h2>测试路径</h2>
 * <pre>
 * GET  http://localhost:8080/api/ad/config
 * PUT  http://localhost:8080/api/ad/config
 * POST http://localhost:8080/api/ad/test-connection
 * POST http://localhost:8080/api/ad/sync
 * </pre>
 */
@RestController
@RequestMapping("/api/ad")
@RequiredArgsConstructor
public class AdController {

    private final AdConfigService adConfigService;
    private final AdUserSyncService adUserSyncService;
    private final AdAccountConversionService conversionService;

    /**
     * 读取 AD 配置（绑定密码脱敏为 {@code ****}）
     *
     * <p>脱敏在服务层完成（{@code AdConfigVO} 只暴露掩码与「是否已配置」），
     * 而不是在 Controller 里改字段 —— 后者一旦有人加了新的返回路径就会漏。
     */
    @GetMapping("/config")
    @PreAuthorize("@perm.has('ad:view')")
    public ApiResponse<AdConfigVO> config() {
        return ApiResponse.success(adConfigService.view());
    }

    /**
     * 保存 AD 配置
     *
     * <p>绑定密码留空表示「保持原值不变」；提交 {@code ****} 会被服务层识别为脱敏占位符并忽略。
     */
    @PutMapping("/config")
    @PreAuthorize("@perm.has('ad:manage')")
    @AuditLog(module = "AD", action = "AD_CONFIG_UPDATE", risk = RiskLevel.HIGH,
            description = "修改 AD 域控配置（含绑定密码加密留存）")
    public ApiResponse<AdConfigVO> save(@Valid @RequestBody AdConfigRequest request) {
        adConfigService.save(request);
        return ApiResponse.success("AD 配置已保存", adConfigService.view());
    }

    /**
     * 测试连接（连通性 + 绑定认证 + 基础 DN 下探测一条）
     *
     * <p>刻意不抛异常而是返回 {@code ok=false} + 指向性文案：这是运维工具而非业务接口，
     * 「失败原因」本身就是它最有价值的输出，用异常表达会丢掉那些细节。
     */
    @PostMapping("/test-connection")
    @PreAuthorize("@perm.has('ad:manage')")
    @AuditLog(module = "AD", action = "AD_TEST_CONNECTION",
            description = "测试 AD 域控连通性与绑定认证")
    public ApiResponse<AdTestResultVO> testConnection(@Valid @RequestBody AdConfigRequest request) {
        AdTestResultVO result = adConfigService.testConnection(request);
        return ApiResponse.success(result.getMessage(), result);
    }

    /**
     * DN 自动推导预览（；「系统自动处理」）
     *
     * <p>维护人员主表单里只填「域服务器地址 / 域管理员账号」，
     * 真正落库的 {@code base_dn} / {@code bind_dn} 由这两项推导而来。
     * 本端点让界面能实时展示「系统将使用什么」，且与保存流程共用同一份规则。
     *
     * <p>权限取 {@code ad:view} 而非 {@code ad:manage}：纯计算、无副作用、
     * 不涉及密码，与 {@code GET /config} 同一敏感级别。
     */
    @PostMapping("/derive-dn")
    @PreAuthorize("@perm.has('ad:view')")
    public ApiResponse<AdDnPreviewVO> deriveDn(@RequestBody AdDnPreviewRequest request) {
        return ApiResponse.success(adConfigService.previewDns(request));
    }

    /**
     * 立即同步域用户（增量）
     *
     * <p>与定时同步共用 {@link AdUserSyncService#sync()}，结果分类计数返回给配置页展示。
     */
    @PostMapping("/sync")
    @PreAuthorize("@perm.has('ad:manage')")
    @AuditLog(module = "AD", action = "AD_SYNC", risk = RiskLevel.HIGH,
            description = "手动同步 AD 域用户（增量）")
    public ApiResponse<AdSyncResultVO> sync() {
        AdSyncResultVO result = adUserSyncService.sync();
        return ApiResponse.success(result.getMessage(), result);
    }

    /**
     * AD 域账号 → 本地账号（）
     *
     * <p>返回体里带一次性临时口令（仅此一次可见）。参数只有路径上的 userId ——
     * 这一点很重要：审计切面会序列化入参，因此口令绝不会经由审计落库。
     */
    @PostMapping("/accounts/{userId}/convert-to-local")
    @PreAuthorize("@perm.has('ad:manage')")
    @AuditLog(module = "AD", action = "AD_ACCOUNT_TO_LOCAL", risk = RiskLevel.HIGH,
            description = "把 AD 域账号转为本地账号（生成临时密码并作废其会话）")
    public ApiResponse<AdAccountConvertVO> convertToLocal(@PathVariable Long userId) {
        return ApiResponse.success("已转为本地账号", conversionService.convertToLocal(userId));
    }

    /**
     * 本地账号 → AD 域账号（「反之亦然」）
     *
     * <p>转换前会真的去域控确认该登录名存在；确认失败则不做任何修改（见
     * {@code AdAccountConversionServiceImpl#findInDirectory}）。
     */
    @PostMapping("/accounts/{userId}/convert-to-ldap")
    @PreAuthorize("@perm.has('ad:manage')")
    @AuditLog(module = "AD", action = "AD_ACCOUNT_TO_LDAP", risk = RiskLevel.HIGH,
            description = "把本地账号转为 AD 域账号（本地口令失效并作废其会话）")
    public ApiResponse<AdAccountConvertVO> convertToLdap(@PathVariable Long userId) {
        return ApiResponse.success("已转为 AD 域账号", conversionService.convertToLdap(userId));
    }
}
