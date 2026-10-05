package com.enterprise.ticket.module.ad.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.RandomPasswordGenerator;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * AD 账号本地化实现
 *
 * <h2>四条规则</h2>
 * <ol>
 *   <li><b>超管不参与</b>：AD 里若存在与超管同名的账号，一律跳过 ——
 *       否则一次同步 / 一次登录就可能把唯一的超管改名或禁用，系统失去兜底入口；</li>
 *   <li><b>随机口令占位</b>：AD 账号的通行凭据是域口令，本地 {@code password_hash} 只是
 *       「占位且不可猜」。这一点是「AD 用户不能用本地旧密码绕过域控」的<b>结构性保证</b> ——
 *       不是靠某处 if 判断，而是因为根本不存在可用的本地口令；</li>
 *   <li><b>只写 AD 权威字段</b>：姓名 / 邮箱 / 手机号 / 部门 / 外部禁用状态 / DN / GUID。
 *       角色、部门、离职标记、本地手工禁用一律不动；
 *       <p>手机号是<b>半权威</b>的：{@code users.phone} 另有全局唯一索引与「本地绑定」两条约束，
 *       因此仅在「AD 非空且未与他人冲突」时才以 AD 为准，否则保持本地现值。
 *       详见 {@code resolveNewPhone} / {@code resolveRefreshedPhone} 的注释。</li>
 *   <li><b>姓名唯一性沿用本地规则</b>：冲突时回退用登录名（见
 *       {@code AdConfigValidator} 同级的项目约定「姓名唯一」）。</li>
 * </ol>
 *
 * <h2>第五道守卫：{@code auth_type = LOCAL} 的账号不归域控管</h2>
 * <p>「超管可以把 AD 用户转为本地用户」（）产出的账号，其 {@code auth_type} 被显式改为
 * {@code LOCAL}。此后域控<b>不再掌管</b>它：既不再刷新其属性，也不会因为「AD 里禁用了 / 删除了」
 * 而把它一起禁掉。缺了这道守卫，一次定时同步就会静默推翻管理员刚做出的「本地接管」决定 ——
 * 表现为「昨天刚转成本地，今天登录方式又变回域认证了」，属于最难排查的一类问题。
 *
 * <p>代价是已知且需向管理员交代的：转为本地后，域控侧的「禁用 / 删除」不再自动传导到本地，
 * 若域控侧发生了离职动作，需要管理员在员工管理页手工禁用（转换结果文案里会明确提示这一点）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdUserProvisioningServiceImpl implements AdUserProvisioningService {

    private static final String AUTH_LDAP = "LDAP";

    /** 账号来源：本地账号（由本地掌管口令，不受域控同步影响） */
    private static final String AUTH_TYPE_LOCAL = "LOCAL";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdConfigService adConfigService;
    private final RoleService roleService;

    @Override
    public ProvisionResult provision(AdUser adUser) {
        if (adUser == null || !adUser.usable()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "AD 用户缺少登录名，无法本地化");
        }
        User local = userMapper.selectByUsername(adUser.account());
        if (local == null) {
            return new ProvisionResult(create(adUser), true, false);
        }
        if (RoleCode.isSuperAdmin(local.getRole())) {
            log.warn("跳过超级管理员账号 [{}]：超管账号由本地独立管理，不受域控同步影响", local.getUsername());
            return new ProvisionResult(local, false, false);
        }
        if (AUTH_TYPE_LOCAL.equalsIgnoreCase(local.getAuthType())) {
            // 「转为本地用户」（）产出的账号：域控不再掌管它。
            // 若此处照常刷新，一次定时同步就会把它的姓名 / 部门改回 AD 值、
            // 甚至在 AD 禁用时把它一起禁掉 —— 管理员刚刚做出的「本地接管」决定会被静默推翻。
            // 因此这里显式跳过：AD 既不新建、也不更新、也不禁用一个已是 LOCAL 的账号。
            log.debug("跳过本地账号 [{}]：该账号已转为本地用户，不受域控同步影响", local.getUsername());
            return new ProvisionResult(local, false, false);
        }
        boolean changed = refresh(local, adUser);
        // 重新读一次，保证调用方拿到的是刷新后的状态（尤其是 enabled）
        User refreshed = userMapper.selectByUsername(adUser.account());
        return new ProvisionResult(refreshed == null ? local : refreshed, false, changed);
    }

    // ------------------------------------------------------------------
    // 新建
    // ------------------------------------------------------------------

    private User create(AdUser adUser) {
        String defaultRole = adConfigService.defaultRole();
        if (!roleService.isAssignable(defaultRole)) {
            // 这里必须显式失败而不是「先用着」：一个角色编码失效的账号建出来就登不进来，
            // 而且会在员工列表里留下无法编辑的脏数据（编辑入口会因角色校验失败而报错）
            throw new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE,
                    "AD 默认角色「" + defaultRole + "」不存在或已停用，请先在 AD 配置页调整");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(adUser.account());
        user.setRealName(uniqueRealName(adUser.name(), adUser.account(), null));
        user.setDisplayName(displayNameOf(adUser));
        user.setEmail(trimToNull(adUser.email()));
        user.setPhone(resolveNewPhone(adUser));
        user.setDepartment(trimToNull(adUser.department()));
        user.setPasswordHash(passwordEncoder.encode(RandomPasswordGenerator.generate()));
        user.setRole(defaultRole);
        user.setAuthType(AUTH_LDAP);
        user.setLdapDn(adUser.dn());
        user.setAdObjectGuid(trimToNull(adUser.objectGuid()));
        user.setAdSyncedAt(now);
        // 域账号改不了本地密码，强制改密标记只会让前端弹出一次「无法完成的引导」
        user.setForceChangePassword(false);
        user.setEnabled(!adUser.disabled());
        user.setDimission(false);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发（登录自动建号 与 定时同步 同时发生）下会落在这里：
            // 转成业务错误，让调用方按「账号已存在」重新读取，而不是 500
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS, "登录名已被占用：" + adUser.account());
        }
        log.info("AD 账号本地化：新建 [{}]（{}），角色 {}，AD 状态 {}",
                adUser.account(), displayNameOf(adUser), defaultRole, adUser.disabled() ? "已禁用" : "启用");
        return user;
    }

    // ------------------------------------------------------------------
    // 刷新既有账号
    // ------------------------------------------------------------------

    private boolean refresh(User local, AdUser adUser) {
        String realName = uniqueRealName(adUser.name(), adUser.account(), local.getId());
        String displayName = displayNameOf(adUser);
        String email = trimToNull(adUser.email());
        String phone = resolveRefreshedPhone(adUser, local);
        String department = trimToNull(adUser.department());
        String guid = trimToNull(adUser.objectGuid());

        boolean changed = !Objects.equals(local.getRealName(), realName)
                || !Objects.equals(local.getDisplayName(), displayName)
                || !Objects.equals(local.getEmail(), email)
                || !Objects.equals(local.getPhone(), phone)
                || !Objects.equals(local.getDepartment(), department)
                || !Objects.equals(local.getAdObjectGuid(), guid)
                || !Objects.equals(local.getLdapDn(), adUser.dn());

        // 单向同步：AD 禁用 → 本地禁用；AD 启用 <b>不</b>反向放开本地禁用。
        // 「AD 里账号是启用的」不等于「管理员希望它在本地可用」，
        // 本地手工禁用（如待配合的合规审查）必须被尊重。
        boolean needDisable = adUser.disabled() && Boolean.TRUE.equals(local.getEnabled());
        if (needDisable) {
            changed = true;
        }

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, local.getId())
                .set(User::getRealName, realName)
                .set(User::getDisplayName, displayName)
                .set(User::getEmail, email)
                .set(User::getPhone, phone)
                .set(User::getDepartment, department)
                .set(User::getAdObjectGuid, guid)
                .set(User::getLdapDn, adUser.dn())
                // 即便属性无变化也刷新同步时间：它的语义是「最后一次确认该账号在 AD 中存在」，
                // 只在变化时写会让运维无法区分「刚确认过」与「半年没同步了」
                .set(User::getAdSyncedAt, LocalDateTime.now()));

        if (needDisable) {
            // 「禁用 + 作废在途会话」由一条带条件的原子 UPDATE 完成：
            // 既避免并发重复自增 token_version（会让用户「重登又被踢」），
            // 也保证「改状态」与「踢会话」不会只成功一半
            userMapper.disableForAdRemoval(local.getId());
            log.info("AD 账号已禁用 [{}]（域控侧为禁用状态），其既有会话已作废", local.getUsername());
        } else if (changed) {
            log.info("AD 账号属性已刷新 [{}]：姓名={}，邮箱={}，部门={}",
                    local.getUsername(), realName, email, department);
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 生成唯一姓名
     *
     * <p>「姓名唯一」是本地新增 / 编辑 / 导入三个入口都强制的业务规则
     * （{@code UserServiceImpl#assertRealNameAvailable}）。AD 同步与首次登录建号
     * 是第四、第五条入口，必须遵守同一规则，否则会出现「列表里两个同名员工，
     * 编辑其中一个却报姓名已存在」的自相矛盾状态。
     *
     * <p>冲突回退顺序：AD 姓名 → AD 登录名。用登录名而不是「姓名_2」，
     * 是因为登录名在 AD 内唯一，天然不会二次冲突，且对使用者仍可辨识。
     */
    private String uniqueRealName(String adName, String account, Long excludeUserId) {
        String candidate = StringUtils.hasText(adName) ? adName.trim() : account;
        long conflict = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRealName, candidate)
                .ne(excludeUserId != null, User::getId, excludeUserId));
        if (conflict == 0) {
            return candidate;
        }
        if (!Objects.equals(candidate, account)) {
            log.info("AD 账号本地化：姓名 [{}] 与既有员工重复，改用登录名 [{}] 作为本地姓名", candidate, account);
        }
        return account;
    }

    private String displayNameOf(AdUser adUser) {
        return StringUtils.hasText(adUser.name()) ? adUser.name().trim() : adUser.account();
    }

    // ------------------------------------------------------------------
    // 手机号：AD 权威，但受两条本地约束（唯一性 / 不覆盖本地已绑定值）
    // ------------------------------------------------------------------

    /**
     * 新建账号时决定写入的手机号。
     *
     * <p>{@code users.phone} 上有<b>全局唯一索引</b>（{@code uk_users_phone}，用于短信找回密码）。
     * AD 的 {@code telephoneNumber} 并不保证唯一（共用工位电话、录入串号都可能重复），
     * 因此这里先做占用检查：冲突时<b>只跳过手机号</b>，其余字段照常写入，
     * 并留下告警。若直接照写，唯一索引会抛 {@code DuplicateKeyException} ——
     * 后果是这条用户被计为「同步失败」且一个字段都没写进去，而真正的原因
     * （另一个人在 AD 里用了同一个号码）在错误信息里完全看不出来。
     */
    private String resolveNewPhone(AdUser adUser) {
        String phone = trimToNull(adUser.phone());
        if (phone == null) {
            return null;
        }
        if (phoneTakenByOther(phone, null)) {
            log.warn("AD 账号 [{}] 的手机号 [{}] 已被其他员工占用，本次不同步该字段（其余字段照常写入）",
                    adUser.account(), phone);
            return null;
        }
        return phone;
    }

    /**
     * 刷新既有账号时决定写入的手机号。
     *
     * <p>三条规则（顺序不可颠倒）：
     * <ol>
     *   <li><b>AD 没给手机号 → 保持本地现值</b>。{@code telephoneNumber} 在很多目录里
     *       是空的，若把「AD 为空」当成「清空」，一次同步就会把员工自己通过
     *       「绑定联系方式」填进去的手机号抹掉 —— 而手机号是找回密码的唯一渠道，
     *       抹掉之后那个人再也拿不回密码。</li>
     *   <li><b>AD 给了、且已被别人占用 → 保持本地现值</b>（同上，避免唯一键冲突）。</li>
     *   <li>其余情况以 AD 为准。</li>
     * </ol>
     */
    private String resolveRefreshedPhone(AdUser adUser, User local) {
        String adPhone = trimToNull(adUser.phone());
        if (adPhone == null) {
            return local.getPhone();
        }
        if (Objects.equals(adPhone, local.getPhone())) {
            return adPhone;
        }
        if (phoneTakenByOther(adPhone, local.getId())) {
            log.warn("AD 账号 [{}] 的手机号 [{}] 已被其他员工占用，保留本地现值 [{}]",
                    local.getUsername(), adPhone, local.getPhone());
            return local.getPhone();
        }
        return adPhone;
    }

    /** 该手机号是否已被「别人」占用（{@code excludeUserId} 为本人时不计） */
    private boolean phoneTakenByOther(String phone, Long excludeUserId) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, phone)
                .ne(excludeUserId != null, User::getId, excludeUserId)) > 0;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
