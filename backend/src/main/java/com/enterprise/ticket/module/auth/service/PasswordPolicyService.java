package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.auth.dto.PasswordPolicyVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 密码策略校验
 *
 * <ul>
 *   <li>密码至少 8 位（可配置 password_min_length）</li>
 *   <li>至少包含两类字符：大写、小写、数字、特殊字符（可配置 password_min_char_types）</li>
 *   <li>禁止常见弱密码</li>
 *   <li>禁止密码包含用户名</li>
 * </ul>
 *
 * <p> 收尾优化· 追加 {@link #describe(String)}：
 * 「密码管理」页需要把当前策略讲给用户听，而策略阈值是可配置的 ——
 * 说明文案与校验逻辑必须同源，否则调完参数页面就在教一条过期规则。
 */
@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    /** 密码长度硬上限：不做成配置项，它防的是「超长口令把 Argon2 的耗时打爆」这一类资源攻击 */
    private static final int MAX_LENGTH = 64;

    /**
     * 常见弱密码字典（小写比较）
     *
     * <p><b>刻意不含演示口令</b>：
     * 演示 / 测试员工统一口令由环境变量配置」，且管理员在「新增员工 / 重置密码 / 批量导入」时也会直接使用它。
     * 若把它留在字典里，这三条入口会一律报「密码过于常见」，与需求直接冲突。
     * 该口令仅面向演示 / 测试数据；生产部署时必须改写演示数据开关并另行设置强口令
     * （见 {@code app.demo.enabled} 与）。
     */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "87654321", "11111111", "88888888", "00000000",
            "password", "password1", "password123", "passw0rd", "p@ssw0rd", "passwd123",
            "qwertyui", "qwerty123", "1qaz2wsx", "1q2w3e4r", "asdfghjkl", "zxcvbnm123",
            "abc12345", "a1234567", "administrator", "root1234", "letmein1",
            "iloveyou", "welcome1", "monkey123", "dragon123", "football", "sunshine",
            "zhangsan", "lisi1234", "woaini1314", "5201314520", "aA123456", "Aa123456"
    );

    private final SystemConfigService systemConfigService;

    /**
     * 校验原始密码是否符合策略，不符合抛 PasswordWeak
     *
     * @param rawPassword 原始密码
     * @param username    登录名，用于「密码不得包含用户名」校验，可为空
     */
    public void validate(String rawPassword, String username) {
        if (!StringUtils.hasText(rawPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_WEAK, "密码不能为空");
        }
        int minLength = systemConfigService.passwordMinLength();
        if (rawPassword.length() < minLength) {
            throw new BusinessException(ErrorCode.PASSWORD_WEAK, "密码长度不能少于 " + minLength + " 位");
        }
        if (rawPassword.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_WEAK, "密码长度不能超过 " + MAX_LENGTH + " 位");
        }

        int charTypes = 0;
        if (UPPER.matcher(rawPassword).find()) {
            charTypes++;
        }
        if (LOWER.matcher(rawPassword).find()) {
            charTypes++;
        }
        if (DIGIT.matcher(rawPassword).find()) {
            charTypes++;
        }
        if (SPECIAL.matcher(rawPassword).find()) {
            charTypes++;
        }
        int minCharTypes = systemConfigService.passwordMinCharTypes();
        if (charTypes < minCharTypes) {
            throw new BusinessException(ErrorCode.PASSWORD_WEAK,
                    "密码至少需包含 " + minCharTypes + " 类字符（大写、小写、数字、特殊字符）");
        }

        String lower = rawPassword.toLowerCase();
        if (WEAK_PASSWORDS.contains(lower)) {
            throw new BusinessException(ErrorCode.PASSWORD_WEAK, "密码过于常见，请更换更强的密码");
        }

        if (StringUtils.hasText(username)) {
            String normalizedUsername = username.toLowerCase();
            if (normalizedUsername.length() >= 3
                    && (lower.contains(normalizedUsername) || normalizedUsername.contains(lower))) {
                throw new BusinessException(ErrorCode.PASSWORD_WEAK, "密码不能包含登录名（姓名）");
            }
        }
    }

    /**
     * 生成当前生效的密码策略说明（「显示密码策略说明」）
     *
     * @param authType 当前登录用户的账号来源；{@code LDAP} 时前端会把整页切换为
     *                 「域账号请在 AD 中修改密码」，此时策略说明仅作参考
     */
    public PasswordPolicyVO describe(String authType) {
        int minLength = systemConfigService.passwordMinLength();
        int minCharTypes = systemConfigService.passwordMinCharTypes();

        PasswordPolicyVO vo = new PasswordPolicyVO();
        vo.setMinLength(minLength);
        vo.setMaxLength(MAX_LENGTH);
        vo.setMinCharTypes(minCharTypes);
        vo.setWeakPasswordChecked(true);
        vo.setUsernameRule(true);
        vo.setAuthType(authType);

        List<String> rules = new ArrayList<>();
        rules.add("长度不少于 " + minLength + " 位，不超过 " + MAX_LENGTH + " 位");
        rules.add("至少包含 " + minCharTypes + " 类字符：大写字母、小写字母、数字、特殊字符");
        rules.add("不能是常见弱密码（如 12345678、password 等）");
        rules.add("不能包含本人的登录名（姓名）");
        rules.add("修改成功后，本人在其它设备 / 浏览器上的登录会立即失效，需要重新登录");
        vo.setRules(rules);
        return vo;
    }
}
