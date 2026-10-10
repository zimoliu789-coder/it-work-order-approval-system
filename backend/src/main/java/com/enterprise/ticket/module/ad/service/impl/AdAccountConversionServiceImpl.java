package com.enterprise.ticket.module.ad.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.RandomPasswordGenerator;
import com.enterprise.ticket.module.ad.dto.AdAccountConvertVO;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdAccountConversionService;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 账号来源互转实现（；）
 *
 * <h2>两个方向都遵循「先确认可行性，再写库」</h2>
 * <p>两个方向都会让账号只能通过另一种方式登录，写错一步就是把账号弄成死号，
 * 因此所有前置校验（超管、方向、AD 是否启用、域控中是否存在该账号）<b>全部排在写入之前</b>。
 * 尤其「本地 → AD」要先真的去域控查一次 —— 查不到就直接拒绝，
 * 而不是先改库再报错（那会让本地口令已被随机化、域里又没有这个人）。
 *
 * <h2>一律作废既有会话</h2>
 * <p>转换改变了「凭据从哪里来」，那么用旧凭据换来的会话就不该继续有效。
 * 因此两个方向都在同一条 UPDATE 里做 {@code token_version = token_version + 1}，
 * 把 {@code SELECT ... FOR UPDATE} 之外的并发窗口一并消掉。
 *
 * <h2>临时口令绝不留痕</h2>
 * <p>「AD → 本地」生成的临时口令只出现在接口响应里，<b>不写审计、不写日志</b>：
 * 审计切面只序列化入参（本接口入参只有 userId），返回值不参与审计，
 * 因此口令不会经由框架漏进 {@code operation_log}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdAccountConversionServiceImpl implements AdAccountConversionService {

    private static final String AUTH_TYPE_LOCAL = "LOCAL";
    private static final String AUTH_TYPE_LDAP = "LDAP";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdConfigService adConfigService;
    private final AdDirectoryClient directoryClient;

    // ------------------------------------------------------------------
    // AD → 本地
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdAccountConvertVO convertToLocal(Long userId) {
        User user = requireUser(userId);
        assertNotSuperAdmin(user);
        if (!AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new BusinessException(ErrorCode.AD_ACCOUNT_ALREADY_LOCAL);
        }

        String temporaryPassword = RandomPasswordGenerator.generate();
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                // 只改「凭据来源」这一个语义：ldap_dn / ad_object_guid 刻意保留 ——
                // 它们是这个账号「从哪来」的历史，排查账号来源时是唯一线索；
                // 判定它是否还归域控管，一律以 auth_type 为准，不看这两个字段。
                .set(User::getAuthType, AUTH_TYPE_LOCAL)
                .set(User::getPasswordHash, passwordEncoder.encode(temporaryPassword))
                // 临时口令必须首登即改（）
                .set(User::getForceChangePassword, true)
                // 作废旧凭据换来的会话：改的是「凭据来源」，旧会话不该继续有效
                .setSql("token_version = token_version + 1"));

        log.warn("账号 [{}]（id={}）已由 AD 域账号转为本地账号，已生成一次性临时口令并作废其全部会话",
                user.getUsername(), userId);

        AdAccountConvertVO vo = base(user, AUTH_TYPE_LOCAL);
        vo.setTemporaryPassword(temporaryPassword);
        vo.setMessage("已转为本地账号。请把临时密码交给该员工（仅显示这一次），"
                + "他使用该密码登录后必须立即修改。注意：该账号此后不再受 AD 域控状态影响，"
                + "若域控侧发生禁用或删除，需在员工管理页手工禁用。");
        return vo;
    }

    // ------------------------------------------------------------------
    // 本地 → AD
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdAccountConvertVO convertToLdap(Long userId) {
        User user = requireUser(userId);
        assertNotSuperAdmin(user);
        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new BusinessException(ErrorCode.AD_ACCOUNT_ALREADY_LDAP);
        }
        if (!adConfigService.isEnabled()) {
            // 转成域账号却没启用 AD = 本地口令已被随机化 + 域认证不会被执行 = 永远登不进来
            throw new BusinessException(ErrorCode.AD_DISABLED,
                    "AD 域控认证当前未启用，无法把账号转为域账号（转过去会因既无本地口令、又不走域认证而无法登录）");
        }

        // 先确认真实存在于域控：这是唯一能避免「转完变死号」的手段
        AdUser adUser = findInDirectory(user.getUsername());

        LocalDateTime now = LocalDateTime.now();
        var update = Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAuthType, AUTH_TYPE_LDAP)
                // 原本地口令必须不可用：换成随机占位串，
                // 让「本地旧口令」在结构上就不再是一条可用的备用通道
                .set(User::getPasswordHash, passwordEncoder.encode(RandomPasswordGenerator.generate()))
                // 域账号改不了本地密码，强制改密标记会引导用户去做一件做不到的事
                .set(User::getForceChangePassword, false)
                .set(User::getLdapDn, adUser.dn())
                .set(User::getAdObjectGuid, trimToNull(adUser.objectGuid()))
                .set(User::getAdSyncedAt, now)
                .setSql("token_version = token_version + 1");
        if (adUser.disabled()) {
            // 域控侧已禁用：转换后不能反而把账号放开 —— 那等于用「转来源」这个动作绕过域控的离职管控
            update.set(User::getEnabled, false);
        }
        userMapper.update(null, update);

        log.warn("账号 [{}]（id={}）已由本地账号转为 AD 域账号（域 DN={}，域侧状态={}）",
                user.getUsername(), userId, adUser.dn(), adUser.disabled() ? "已禁用" : "启用");

        AdAccountConvertVO vo = base(user, AUTH_TYPE_LDAP);
        vo.setMessage(adUser.disabled()
                ? "已转为 AD 域账号。注意：该账号在域控中当前处于禁用状态，本地已同步置为禁用，"
                + "需先在域控中启用后才能登录。"
                : "已转为 AD 域账号。该员工今后使用域账号密码登录，原本地密码已失效并作废了既有会话。");
        return vo;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 在域控中按登录名查找用户，并把协议异常翻译成「管理员看得懂」的业务错误。
     *
     * <p>三类结局必须分开：<b>域里没有这个人</b>（可纠正：改登录名或先在 AD 建号）、
     * <b>域控连不上</b>（可重试，且本次未做任何修改）、<b>配置错</b>（要改 AD 配置）。
     * 若统一回一句「转换失败」，管理员会反复重试一个永远不会成功的操作。
     */
    private AdUser findInDirectory(String username) {
        AdConnection connection = adConfigService.activeConnection();
        try {
            return directoryClient.findByAccount(connection, username);
        } catch (AdDirectoryException e) {
            switch (e.getKind()) {
                case USER_NOT_FOUND -> throw new BusinessException(ErrorCode.AD_ACCOUNT_NOT_IN_DIRECTORY,
                        "域控中不存在登录名「" + username + "」的账号，请先在 AD 中创建或核对登录名后再转换");
                case UNAVAILABLE -> throw new BusinessException(ErrorCode.AD_TEST_FAILED,
                        "域控暂不可用，未能确认该账号是否存在，本次未做任何修改，请稍后重试：" + e.getMessage());
                default -> throw new BusinessException(ErrorCode.AD_TEST_FAILED,
                        "查询域控失败，本次未做任何修改：" + e.getMessage());
            }
        }
    }

    private User requireUser(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 超管不参与互转。
     *
     * <p> 第 1 条要求超管「始终走本地认证」，若允许把它转成域账号，
     * 这条保证就被从数据层破坏了 —— 域控一旦不可用，系统将没有任何可登录的管理入口。
     */
    private void assertNotSuperAdmin(User user) {
        if (RoleCode.isSuperAdmin(user.getRole())) {
            throw new BusinessException(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                    "超级管理员账号固定为本地认证，不可转换账号来源");
        }
    }

    private AdAccountConvertVO base(User user, String authType) {
        AdAccountConvertVO vo = new AdAccountConvertVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(StringUtils.hasText(user.getDisplayName())
                ? user.getDisplayName() : user.getUsername());
        vo.setAuthType(authType);
        vo.setAuthTypeLabel(AUTH_TYPE_LOCAL.equals(authType) ? "本地" : "AD");
        return vo;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
