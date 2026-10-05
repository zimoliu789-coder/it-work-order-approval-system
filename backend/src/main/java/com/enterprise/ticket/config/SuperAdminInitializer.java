package com.enterprise.ticket.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SetupKeys;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超级管理员初始化器（：生产环境不得硬编码 默认密码）
 *
 * <h2>本次改造：从「写死 administrator」改为「初始化向导 + 可选环境变量」</h2>
 * <ul>
 *   <li><b>不再有默认登录名</b>：{@code app.super-admin.username} 默认为空，也不再自动建号；</li>
 *   <li><b>首选初始化向导</b>：库中没有 super_admin 时，前端会把访问者引导到 {@code /setup}，
 *       由管理员现场设定账号名与密码（见 {@code SetupService}）；</li>
 *   <li><b>无人值守仍可用</b>：同时配置了 {@code SUPER_ADMIN_USERNAME} 与
 *       {@code SUPER_ADMIN_INIT_PASSWORD} 时，启动自动建号（自动化部署场景）；</li>
 *   <li><b>身份固化</b>：超管登录名在创建那一刻写入 {@code system_config.super_admin_username}
 *       （internal 分组，不可见不可改），之后系统据此识别「内置超管」，
 *       因此超管<b>不可改、不可被重置</b>；</li>
 *   <li><b>存量迁移</b>：既有部署（库中已有超管但无固化记录）会在启动时把其登录名固化下来，
 *       行为与改造前逐字一致。</li>
 * </ul>
 *
 * <p><b>本类绝不覆盖既有超管的登录名与密码</b>：库中已有 super_admin 时只做只读探测与身份固化。
 *
 * <p>安全约束：不硬编码默认密码 —— 未配置 {@code SUPER_ADMIN_INIT_PASSWORD} 且库中无超管时，
 * 应用仍可正常启动（由向导完成初始化）。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    /**
     * 登录名**最后兜底值**：仅在「库中无超管、且未固化任何身份」时用于
     * {@code BuiltinAdmin} 的比较，使其不至于拿到 null。
     *
     * <p>⚠️ 它<b>不参与任何账号创建</b> —— 建号来源只有「初始化向导」或
     * 「显式配置的 SUPER_ADMIN_USERNAME」。保留它是为了不在与超管无关的路径上抛 NPE。
     */
    public static final String DEFAULT_USERNAME = "administrator";

    private final AppProperties appProperties;
    private final UserService userService;
    private final SystemConfigService systemConfigService;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        List<User> existingSuperAdmins = userService.list(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, RoleCode.SUPER_ADMIN).orderByAsc(User::getId));

        if (!existingSuperAdmins.isEmpty()) {
            // 只读探测：绝不覆盖既有超管的登录名与密码。
            User superAdmin = existingSuperAdmins.get(0);
            syncIdentity(superAdmin.getUsername());
            log.info("检测到 {} 个超级管理员账号，跳过初始化（不覆盖登录名与密码）：username={}，displayName={}",
                    existingSuperAdmins.size(), superAdmin.getUsername(), superAdmin.getDisplayName());
            return;
        }

        AppProperties.SuperAdmin superAdmin = appProperties.getSuperAdmin();
        String username = superAdmin == null ? null : trimToNull(superAdmin.getUsername());
        String initPassword = superAdmin == null ? null : superAdmin.getInitPassword();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(initPassword)) {
            // 未提供环境变量 → 交给初始化向导。这是**正常路径**，不是错误。
            log.warn("========================================================================");
            log.warn("库中尚无超级管理员账号。请用浏览器访问系统，将自动跳转到「初始化向导」，");
            log.warn("由你设定超管账号名与密码。若需无人值守自动建号，请同时配置环境变量：");
            log.warn("  SUPER_ADMIN_USERNAME=<账号名>");
            log.warn("  SUPER_ADMIN_INIT_PASSWORD=<至少8位、含两类字符的强密码>");
            log.warn("========================================================================");
            return;
        }

        if (userService.getByUsername(username) != null) {
            log.error("登录名 [{}] 已被其它账号占用，为避免与「超管登录名不可改」约定冲突，"
                    + "本次不自动创建超管账号，请先调整该账号的登录名。", username);
            return;
        }

        String displayName = StringUtils.hasText(superAdmin.getDisplayName())
                ? superAdmin.getDisplayName().trim() : username;

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(initPassword));
        user.setRole(RoleCode.SUPER_ADMIN);
        user.setAuthType("LOCAL");
        user.setDepartmentId(null);
        // 账号名与密码都由部署方显式提供，无需再强制改密
        user.setForceChangePassword(false);
        user.setEnabled(true);
        user.setDimission(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userService.save(user);

        syncIdentity(username);

        log.warn("已按环境变量创建初始超级管理员账号 [{}]（id={}）。", username, user.getId());
        operationLogService.record(user.getId(), username, "SYSTEM", "INIT_SUPER_ADMIN",
                "应用启动时按环境变量创建初始超级管理员账号，username=" + username, true, RiskLevel.HIGH);
    }

    /**
     * 把「内置超管登录名」固化到 system_config，并同步到 {@link AppProperties}。
     *
     * <p>为什么必须固化：{@code BuiltinAdmin} 是纯函数（只读配置），而超管登录名现在由
     * 初始化向导在运行期决定；把它写进配置后，后续所有护栏
     * （角色不可改 / 不可重置 / 不可禁用 / 仅内置超管可改站点品牌）都能零改动地继续工作。
     */
    private void syncIdentity(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        String persisted = systemConfigService.superAdminUsername();
        if (!username.equals(persisted)) {
            systemConfigService.saveSuperAdminUsername(username);
            log.info("已固化内置超管登录名：{}（→ system_config.{})",
                    username, SetupKeys.KEY_SUPER_ADMIN_USERNAME);
        }
        if (appProperties.getSuperAdmin() != null) {
            appProperties.getSuperAdmin().setUsername(username);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
