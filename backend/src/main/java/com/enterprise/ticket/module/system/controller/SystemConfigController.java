package com.enterprise.ticket.module.system.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.permission.BuiltinAdmin;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.dto.ConfigTestMailRequest;
import com.enterprise.ticket.module.system.dto.ConfigTestSmsRequest;
import com.enterprise.ticket.module.system.dto.ConfigUpdateRequest;
import com.enterprise.ticket.module.system.dto.vo.ConfigCatalogVO;
import com.enterprise.ticket.module.system.entity.SystemConfig;
import com.enterprise.ticket.module.system.service.SystemConfigMailService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.service.SystemConfigSmsService;
import com.enterprise.ticket.module.system.support.MailSettings;
import com.enterprise.ticket.module.system.support.SmsSettings;
import com.enterprise.ticket.security.SecretCipher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统配置查询与修改（ 可配置参数、 菜单「超时与顺延参数设置」）
 *
 * <p><b>需求方三波·第一波·</b>：本控制器原本只有只读查询，
 * 「超时与顺延参数设置」菜单因此长期是个占位页 —— 改参数只能直接改数据库。
 * 现补齐写入能力，并做到<b>保存即生效</b>（服务层写入后立即刷新进程内缓存）。
 *
 * <p><b>（ · ）</b>：新增 {@code GET /catalog} 下发「分组 + 中文标签 +
 * 控件类型 + 单位 + 区间 + 说明」，供卡片式配置页渲染。
 * <b>刻意不改既有 {@code GET /api/system/configs} 的响应形状</b>（它直接返回数组）——
 * 那会连带改掉既有回归用例与旧调用方的契约，而收益只是「少一个接口」。
 *
 * <p><b>（ · ）</b>：新增 {@code POST /test-mail}，
 * 用当前表单值（未保存也可测）连 SMTP 发一封测试信，失败回传可读原因。
 *
 * <p><b>（ · ）</b>：新增 {@code POST /test-sms}，与 test-mail 同构。
 * 需要特别注意的是<b>这两个接口的成功语义不同</b>：test-mail 成功 = 信真的发出去了；
 * test-sms 因为短信网关尚未接入，成功只表示「参数校验通过」，
 * 返回体里用 {@code delivered=false} 明确区分（见 {@code SystemConfigSmsService}）。
 *
 * <p>权限沿用权限码：查看 {@code config:view}、修改 {@code config:manage}，
 * 默认都只授予 super_admin。修改参数属高风险操作，同步记审计日志。
 *
 * <p>测试路径：
 * <pre>
 * GET  http://localhost:8080/api/system/configs
 * GET  http://localhost:8080/api/system/configs/catalog
 * PUT  http://localhost:8080/api/system/configs   （body: {"values":{"lock_timeout_minutes":"10"}}）
 * POST http://localhost:8080/api/system/configs/test-mail （body: {"to":"me@example.com"}）
 * POST http://localhost:8080/api/system/configs/test-sms  （body: {"to":"13800000000"}）
 * </pre>
 */
@RestController
@RequestMapping("/api/system/configs")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    private final SystemConfigMailService mailService;

    /** 短信通道测试（的「发送测试短信」）；短信网关未接入时的诚实汇报见该类注释 */
    private final SystemConfigSmsService smsService;

    private final AppProperties appProperties;

    /**
     * 查询全部可管理的系统配置（扁平结构，供回归脚本与旧调用方使用）
     */
    @GetMapping
    @PreAuthorize("@perm.has('config:view')")
    public ApiResponse<List<SystemConfig>> list() {
        return ApiResponse.success(systemConfigService.listForAdmin());
    }

    /**
     * 系统参数目录（卡片式配置页的数据源）
     *
     * <p>与 {@link #list()} 的分工：这个是「给人看并直接渲染成表单」的，
     * 因此带分组、中文标签、控件类型、单位、区间与说明；密文项只回传掩码。
     */
    @GetMapping("/catalog")
    @PreAuthorize("@perm.has('config:view')")
    public ApiResponse<ConfigCatalogVO> catalog() {
        return ApiResponse.success(systemConfigService.catalog());
    }

    /**
     * 批量保存系统参数
     *
     * <p>返回实际发生变更的项数：值没变的项不写库，
     * 因此「保存成功但 affected=0」是一个有意义的信号（说明提交的值与现值相同），
     * 而不是错误。密文项提交掩码时按「未改动」跳过，不计入 affected。
     */
    @PutMapping
    @PreAuthorize("@perm.has('config:manage')")
    @AuditLog(module = "SYSTEM", action = "CONFIG_UPDATE", description = "修改系统参数", risk = RiskLevel.HIGH)
    public ApiResponse<Map<String, Integer>> update(@Valid @RequestBody ConfigUpdateRequest request) {
        int affected = systemConfigService.updateValues(request.getValues());
        return ApiResponse.success("已保存 " + affected + " 项参数（剩余项与现值一致，未重复写入）",
                Map.of("affected", affected));
    }

    /**
     * 发送测试邮件（）
     *
     * <p>用请求里带的表单值覆盖已保存配置（未保存也能测），连通后向 {@code to} 发一封测试信。
     *
     * <p><b>为什么要求内置超管</b>：SMTP 授权码与短信 Secret 归内置超管专管
     * （见 {@code SystemConfigServiceImpl#isAdminOnlyKey}），而「拿现有凭据往任意地址发信」
     * 正是这些凭据的滥用形态。若只校验 {@code config:manage}，
     * 其他超管就能借这个接口把系统当成自己的发信通道 —— 权限口径必须与「能改凭据」一致。
     */
    @PostMapping("/test-mail")
    @PreAuthorize("@perm.has('config:manage')")
    @AuditLog(module = "SYSTEM", action = "CONFIG_TEST_MAIL", description = "发送 SMTP 测试邮件",
            risk = RiskLevel.HIGH)
    public ApiResponse<Map<String, Object>> testMail(@Valid @RequestBody ConfigTestMailRequest request) {
        requireBuiltinAdmin("发送测试邮件");

        MailSettings.Settings settings = merge(systemConfigService.mailSettings(), request);
        mailService.sendTestMail(request.getTo(), settings);

        return ApiResponse.success("测试邮件已发送，请查收 " + request.getTo()
                + "（同时也请检查垃圾邮件目录）",
                Map.of("host", settings.host(), "port", settings.port(), "ssl", settings.ssl()));
    }

    /**
     * 把请求里的表单值合并到已保存配置上（空值 = 沿用）。
     *
     * <p>授权码是唯一特殊项：等于掩码 {@code ****} 时同样视为「沿用已保存值」，
     * 否则会把页面上回显的掩码当成真授权码去认证，测试必然失败。
     */
    private MailSettings.Settings merge(MailSettings.Settings saved, ConfigTestMailRequest request) {
        String host = pick(request.getHost(), saved.host());
        Integer port = request.getPort() != null ? request.getPort() : saved.port();
        String username = pick(request.getUsername(), saved.username());
        String fromName = pick(request.getFromName(), saved.fromName());
        Boolean ssl = request.getSsl() != null ? request.getSsl() : saved.ssl();

        String password = saved.password();
        String submitted = request.getPassword();
        if (StringUtils.hasText(submitted) && !SecretCipher.MASK.equals(submitted.trim())) {
            password = submitted.trim();
        }
        return new MailSettings.Settings(host, port, username, password, fromName, ssl);
    }

    /**
     * 发送测试短信（ · ）。
     *
     * <p>与 {@link #testMail} 同口径：用请求里带的表单值覆盖已保存配置（未保存也能测），
     * 并要求内置超管 —— 短信 AccessKey Secret 与 SMTP 授权码一样归内置超管专管，
     * 而「拿现有凭据往任意号码发消息」正是这些凭据的滥用形态。
     *
     * <p>注意返回体里的 {@code delivered}：短信网关本期尚未接入，
     * 因此它恒为 {@code false}，接口给的是「参数校验结果 + 未真实发送」的诚实结论，
     * 而不是一个会让人误以为配置已可用的「发送成功」。前端必须展示返回的 {@code message}。
     */
    @PostMapping("/test-sms")
    @PreAuthorize("@perm.has('config:manage')")
    @AuditLog(module = "SYSTEM", action = "CONFIG_TEST_SMS", description = "发送测试短信",
            risk = RiskLevel.HIGH)
    public ApiResponse<SystemConfigSmsService.SmsTestResult> testSms(
            @Valid @RequestBody ConfigTestSmsRequest request) {
        requireBuiltinAdmin("发送测试短信");

        SmsSettings.Settings settings = merge(smsService.effectiveSettings(), request);
        SystemConfigSmsService.SmsTestResult result = smsService.sendTestSms(request.getTo(), settings);

        return ApiResponse.success(result.message(), result);
    }

    /**
     * 把请求里的表单值合并到已保存的短信配置上（空值 = 沿用）。
     *
     * <p>与 {@link #merge(MailSettings.Settings, ConfigTestMailRequest)} 同构：
     * AccessKey Secret 等于掩码时视为「沿用已保存值」。
     */
    private SmsSettings.Settings merge(SmsSettings.Settings saved, ConfigTestSmsRequest request) {
        String provider = pick(request.getProvider(), saved.provider());
        String accessKeyId = pick(request.getAccessKeyId(), saved.accessKeyId());
        String signName = pick(request.getSignName(), saved.signName());
        String templateCode = pick(request.getTemplateCode(), saved.templateCode());

        String secret = saved.accessKeySecret();
        String submitted = request.getAccessKeySecret();
        if (StringUtils.hasText(submitted) && !SecretCipher.MASK.equals(submitted.trim())) {
            secret = submitted.trim();
        }
        return new SmsSettings.Settings(provider, accessKeyId, secret, signName, templateCode);
    }

    private String pick(String submitted, String saved) {
        return StringUtils.hasText(submitted) ? submitted.trim() : saved;
    }

    /** 通道测试仅内置超管可执行（见 {@link #testMail} 与 {@link #testSms} 的注释） */
    private void requireBuiltinAdmin(String what) {
        if (!BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "仅内置超级管理员 " + BuiltinAdmin.username(appProperties) + " 可" + what);
        }
    }
}
