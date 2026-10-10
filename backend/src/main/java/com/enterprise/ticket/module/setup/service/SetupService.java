package com.enterprise.ticket.module.setup.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.auth.service.PasswordPolicyService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.setup.dto.SetupInitializeRequest;
import com.enterprise.ticket.module.setup.dto.vo.SetupStatusVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 初始化向导（本次新增）——「第一次部署、库里没有超管」时由管理员现场建号。
 *
 * <h2>为什么不再自动建 administrator</h2>
 * <p>写死登录名 + 从环境变量取初始密码有两个问题：登录名是公开且固定的（攻击面固定），
 * 且部署方经常直接把口令写在 compose 文件里。改为向导后，「系统里到底有没有超管、
 * 超管叫什么」由第一次访问的人现场决定，并即时固化。
 *
 * <h2>三条安全边界</h2>
 * <ol>
 *   <li><b>只在没有超管时可调用</b>：已有超管即返回 {@code SETUP_ALREADY_INITIALIZED}，
 *       杜绝「事后重新初始化造出第二个超管」；</li>
 *   <li><b>账号名与密码都过校验</b>：账号名走独立的正则（允许字母，便于记忆），
 *       密码走与员工同源的密码策略；</li>
 *   <li><b>身份即时固化</b>：建号成功即写入 {@code system_config.super_admin_username}
 *       并同步到 {@code AppProperties}，此后该账号不可改、不可被重置。</li>
 * </ol>
 *
 * <h2>并发</h2>
 * <p>理论上存在「两个浏览器同时提交」的窗口。这里靠两层兜底：
 * 事务内先复查一次「是否已有超管」；再由 {@code employee.username} 唯一索引兜住同名的重复插入
 * （命中则回落为 {@code USER_USERNAME_EXISTS}）。不同账号名同时提交的极端情况
 * 会得到两个超管，这需要部署方在同一瞬间并发操作，风险可接受且可人工纠正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    /**
     * 超管账号名的允许字符：字母 / 数字 / 下划线 / 点 / 短横线，3~32 位。
     *
     * <p>刻意**不**沿用员工的「5 位以上纯数字」：那套规则的目的是「每人一个工号」，
     * 而超管账号是运维记忆的入口（如 {@code admin} / {@code it_admin}），
     * 强制纯数字只会让人把它写在便签上。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");

    /** 展示名最大长度（与 users.display_name 列宽一致） */
    private static final int MAX_DISPLAY_NAME = 32;

    private final UserService userService;
    private final SystemConfigService systemConfigService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final OperationLogService operationLogService;
    private final AppProperties appProperties;

    /** 初始化状态：库中是否已存在超级管理员 */
    public SetupStatusVO status() {
        SetupStatusVO vo = new SetupStatusVO();
        vo.setInitialized(hasSuperAdmin());
        return vo;
    }

    /**
     * 完成初始化：创建内置超级管理员。
     *
     * @throws BusinessException 已完成初始化 / 账号名非法或重复 / 两次密码不一致 / 密码强度不足
     */
    @Transactional(rollbackFor = Exception.class)
    public void initialize(SetupInitializeRequest request) {
        if (hasSuperAdmin()) {
            throw new BusinessException(ErrorCode.SETUP_ALREADY_INITIALIZED);
        }

        String username = trimToNull(request.getUsername());
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.SETUP_USERNAME_INVALID);
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.SETUP_PASSWORD_MISMATCH);
        }
        if (userService.getByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
        }
        // 与员工同源的密码策略（最小长度 / 字符种类 / 不能与登录名相同）
        passwordPolicyService.validate(request.getPassword(), username);

        String displayName = trimToNull(request.getDisplayName());
        if (displayName == null) {
            displayName = username;
        }
        if (displayName.length() > MAX_DISPLAY_NAME) {
            displayName = displayName.substring(0, MAX_DISPLAY_NAME);
        }

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(RoleCode.SUPER_ADMIN);
        user.setAuthType("LOCAL");
        user.setDepartmentId(null);
        // 账号名与密码都是管理员现场设定的，无需再强制改密
        user.setForceChangePassword(false);
        user.setEnabled(true);
        user.setDimission(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发下已被抢先创建（唯一索引兜底）
            throw new BusinessException(ErrorCode.SETUP_ALREADY_INITIALIZED,
                    "系统已完成初始化，无需重复设置");
        }

        // 固化身份：此后 BuiltinAdmin 据此识别「内置超管」，其角色不可改、密码不可被重置
        systemConfigService.saveSuperAdminUsername(username);
        if (appProperties.getSuperAdmin() != null) {
            appProperties.getSuperAdmin().setUsername(username);
        }

        operationLogService.record(user.getId(), username, "SYSTEM", "SETUP_INIT_SUPER_ADMIN",
                "通过初始化向导创建内置超级管理员账号，username=" + username, true, RiskLevel.HIGH);
        log.warn("初始化向导完成：已创建内置超级管理员 [{}]（id={}）", username, user.getId());
    }

    /** 库中是否已有超级管理员（含禁用/离职的：身份已存在就不该再走向导） */
    private boolean hasSuperAdmin() {
        Long count = userService.count(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, RoleCode.SUPER_ADMIN));
        return count != null && count > 0;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
