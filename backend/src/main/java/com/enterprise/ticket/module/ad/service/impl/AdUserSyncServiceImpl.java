package com.enterprise.ticket.module.ad.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdSyncResultVO;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService;
import com.enterprise.ticket.module.ad.service.AdUserSyncService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AD 用户同步实现（；）
 *
 * <h2>本类只做三件事</h2>
 * <ol>
 *   <li>拉取目录用户列表（协议层交给 {@code AdDirectoryClient}）；</li>
 *   <li>逐个交给 {@code AdUserProvisioningService} 本地化（账号口径与「首次登录自动建号」完全一致）；</li>
 *   <li>收尾：把「AD 里已经不存在的本地域账号」禁用掉。</li>
 * </ol>
 *
 * <h2>三条安全不变式（每条都对应一次真实可能发生的灾难）</h2>
 * <ol>
 *   <li><b>目录返回 0 个用户时，绝不执行禁用阶段</b>：过滤器写错、基础 DN 写错、
 *       目录短暂返回空集，都会让列表为空；此时若照常执行「本地有、AD 无 → 禁用」，
 *       一次同步就能把全部域账号禁用。<b>宁可「该禁的没禁」（有告警可查），
 *       也不能「全部禁用」</b> —— 前者是延迟，后者是事故。</li>
 *   <li><b>只禁用、不删除</b>：本地用户被工单、审批节点、附件、操作日志大量引用，
 *       物理删除会把历史数据变成无主记录（需求明确要求保留历史工单）。</li>
 *   <li><b>绝不触碰超级管理员</b>：若 AD 里恰好有个被禁用的 {@code administrator}，
 *       按「AD 禁用 → 本地禁用」照做就会把唯一的超管禁掉，系统当场失去所有管理入口。
 *       超管账号一律跳过（{@code AdUserProvisioningServiceImpl} 与本文都有这道判断，
 *       两处独立成立：前者管「不修改」，后者管「不禁用」）。</li>
 * </ol>
 *
 * <h2>为什么整体不加事务</h2>
 * <p>一次同步可能涉及上千个用户。放进单个事务里有三个问题：长事务导致锁与 undo 膨胀、
 * 单条脏数据回滚整批（前面几百条白跑）、失败重试代价高。
 * 这里改为「逐条独立提交 + 逐条捕获异常计入 failures」，语义是<b>尽力同步</b>：
 * 单条失败不阻断其余，结果里明确报告失败条数，运维可重跑（同步本身幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdUserSyncServiceImpl implements AdUserSyncService {

    /** 本地账号来源：AD 域账号 */
    private static final String AUTH_LDAP = "LDAP";

    /** 失败明细最多返回条数（真正的定位手段是服务端日志） */
    private static final int MAX_FAILURES = 20;

    private final AdConfigService adConfigService;
    private final AdDirectoryClient directoryClient;
    private final AdUserProvisioningService provisioningService;
    private final UserMapper userMapper;

    @Override
    public AdSyncResultVO sync() {
        AdSyncResultVO result = new AdSyncResultVO();
        result.setStartedAt(LocalDateTime.now());

        // 未启用 / 缺配置时由 AdConfigService 抛出带指向性的错误（告诉管理员去哪里补什么）
        AdConnection connection = adConfigService.activeConnection();

        List<AdUser> adUsers;
        try {
            adUsers = directoryClient.fetchAll(connection, 0);
        } catch (AdDirectoryException e) {
            log.error("AD 用户同步失败：目录不可访问（{}）", e.getMessage());
            throw new BusinessException(ErrorCode.AD_SYNC_FAILED, "无法从域控拉取用户列表：" + e.getMessage());
        }

        result.setTotal(adUsers.size());
        log.info("AD 同步开始：目录返回 {} 个用户", adUsers.size());

        // AD 的 sAMAccountName 大小写不敏感，MySQL utf8mb4_general_ci 亦然；
        // 两边口径必须一致，否则会出现「刚刚同步过、下一次却判为 AD 已删除」的抖动
        Set<String> adAccounts = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();

        for (AdUser adUser : adUsers) {
            if (!adUser.usable()) {
                // 匹配到过滤器的组 / 计算机等条目：不建号、不计数，也不报错
                log.debug("跳过无登录名的目录条目：dn={}", adUser.dn());
                continue;
            }
            adAccounts.add(adUser.account().toLowerCase(Locale.ROOT));
            try {
                AdUserProvisioningService.ProvisionResult provision =
                        provisioningService.provision(adUser);
                if (provision.created()) {
                    result.setCreated(result.getCreated() + 1);
                } else if (provision.changed()) {
                    result.setUpdated(result.getUpdated() + 1);
                } else {
                    result.setUnchanged(result.getUnchanged() + 1);
                }
            } catch (Exception e) {
                result.setFailed(result.getFailed() + 1);
                String detail = adUser.account() + "：" + rootMessage(e);
                if (failures.size() < MAX_FAILURES) {
                    failures.add(detail);
                }
                log.warn("AD 同步单条失败：{}", detail, e);
            }
        }

        disableMissingAccounts(adAccounts, result, failures);
        result.setFailures(failures);
        result.setFinishedAt(LocalDateTime.now());
        result.setMessage(buildSummary(result));
        adConfigService.markSyncResult(result.getMessage());
        log.info("AD 同步完成：{}", result.getMessage());
        return result;
    }

    // ------------------------------------------------------------------
    // 收尾：AD 已删除 / 已禁用账号的禁用阶段
    // ------------------------------------------------------------------

    private void disableMissingAccounts(Set<String> adAccounts, AdSyncResultVO result, List<String> failures) {
        // 不变式 1：目录返回空集时绝不执行禁用阶段
        if (adAccounts.isEmpty()) {
            String warning = "域控返回 0 个用户，已跳过「AD 已删除 → 本地禁用」阶段"
                    + "（请核对基础 DN 与用户搜索过滤器；本次不执行任何禁用，避免批量误禁用）";
            log.warn(warning);
            failures.add(warning);
            return;
        }

        List<User> localLdapUsers = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getAuthType, AUTH_LDAP));
        if (localLdapUsers.isEmpty()) {
            return;
        }

        for (User local : localLdapUsers) {
            // 不变式 3：超管绝不参与
            if (RoleCode.isSuperAdmin(local.getRole())) {
                continue;
            }
            if (Boolean.FALSE.equals(local.getEnabled())) {
                continue;
            }
            String account = local.getUsername() == null ? "" : local.getUsername().toLowerCase(Locale.ROOT);
            if (adAccounts.contains(account)) {
                continue;
            }
            // 条件 UPDATE（WHERE enabled = 1）：并发同步下只会有一条命中，
            // 避免 token_version 被自增两次（会让用户「重新登录后又被踢」）
            if (userMapper.disableForAdRemoval(local.getId()) > 0) {
                result.setDisabled(result.getDisabled() + 1);
            }
            log.info("AD 同步禁用账号 [{}]：该账号已不在域控中（本地保留记录，不物理删除）", local.getUsername());
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String buildSummary(AdSyncResultVO result) {
        StringBuilder sb = new StringBuilder();
        sb.append("共同步 ").append(result.getTotal()).append(" 个域账号：新建 ")
                .append(result.getCreated()).append(" 个、更新 ").append(result.getUpdated())
                .append(" 个、禁用 ").append(result.getDisabled()).append(" 个、无变化 ")
                .append(result.getUnchanged()).append(" 个");
        if (result.getFailed() > 0) {
            sb.append("，失败 ").append(result.getFailed()).append(" 个（详见服务端日志）");
        }
        return sb.toString();
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 6) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
