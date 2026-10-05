package com.enterprise.ticket.module.permission.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.module.permission.entity.UserPermission;
import com.enterprise.ticket.module.permission.mapper.UserPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户级授权—— 授予 / 撤销 / 查询有效权限码。
 *
 * <h2>它与角色的关系是「并集」而不是「覆盖」</h2>
 * 最终权限 = 角色权限 **∪** 用户级授权。刻意不做「用户级可以否决角色」——
 * 那会让「某人为什么进不去」变成一道需要交叉比对两张表的题，
 * 而本需求只需要「额外多给一项」。
 *
 * <h2>撤销为什么是标记而不是删行</h2>
 * 授权历史是审计证据：「这个人的设备台账权限是什么时候、因为哪张工单开的」
 * 必须查得到。删行会让「曾经开过、后来收了」这件事彻底消失，
 * 而这类信息恰恰是复盘时最需要的。
 *
 * <h2>为什么幂等由本类保证（而不是数据库唯一键）</h2>
 * 需求是「同一项权限可以反复授予与撤销」—— 撤销后必须能再授予，
 * 而 MySQL 没有「只对 revoked_at IS NULL 生效」的部分唯一索引。
 * 因此授予前先查未撤销行：有则直接返回 0（已经是开通状态），不插重复行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPermissionService {

    public static final String SOURCE_APPLY = "APPLY";
    public static final String SOURCE_MANUAL = "MANUAL";

    private final UserPermissionMapper userPermissionMapper;

    /** 某用户当前有效的附加权限码（未登录 / 用户不存在时返回空集合，不返回 null） */
    public Set<String> effectiveCodes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        List<String> codes = userPermissionMapper.selectEffectiveCodes(userId);
        return codes == null ? Set.of() : new LinkedHashSet<>(codes);
    }

    /**
     * 批量取多个用户的有效权限码（列表 / 导出用，避免 N+1）。
     *
     * @return userId → 权限码集合；没有附加授权的用户不会出现在 map 里
     */
    public Map<Long, Set<String>> effectiveCodesOf(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = userPermissionMapper.selectEffectiveCodesOf(userIds);
        Map<Long, Set<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object userId = row.get("userId");
            Object permCode = row.get("permCode");
            if (userId == null || permCode == null) {
                continue;
            }
            result.computeIfAbsent(Long.valueOf(String.valueOf(userId)), key -> new LinkedHashSet<>())
                    .add(String.valueOf(permCode));
        }
        return result;
    }

    /** 某用户的授权明细（含已撤销，供界面展示历史） */
    public List<UserPermission> listOf(Long userId) {
        return userPermissionMapper.selectList(Wrappers.<UserPermission>lambdaQuery()
                .eq(UserPermission::getUserId, userId)
                .orderByDesc(UserPermission::getGrantedAt));
    }

    /**
     * 授予一项权限（幂等）。
     *
     * <p>已经是有效状态时**不重复插行**，也**不刷新 granted_at**：
     * 重复申请同一项权限（用户可能忘了自己已经开过）不应该把「首次开通时间」往后推，
     * 那会让「这项权限开了多久」这个判断失真。
     *
     * @return 实际新增的行数（0 = 本来就已开通）
     */
    @Transactional(rollbackFor = Exception.class)
    public int grant(Long userId, String permCode, String source, Long orderId, Long grantedBy) {
        if (userId == null || permCode == null || permCode.isBlank()) {
            return 0;
        }
        Long existing = userPermissionMapper.selectCount(Wrappers.<UserPermission>lambdaQuery()
                .eq(UserPermission::getUserId, userId)
                .eq(UserPermission::getPermCode, permCode)
                .isNull(UserPermission::getRevokedAt));
        if (existing != null && existing > 0) {
            return 0;
        }
        UserPermission row = new UserPermission();
        row.setUserId(userId);
        row.setPermCode(permCode);
        row.setSource(source == null ? SOURCE_MANUAL : source);
        row.setOrderId(orderId);
        row.setGrantedBy(grantedBy);
        row.setGrantedAt(LocalDateTime.now());
        userPermissionMapper.insert(row);
        log.info("[权限授予] user={} code={} source={} order={}", userId, permCode, row.getSource(), orderId);
        return 1;
    }

    /**
     * 撤销一项权限（幂等）。
     *
     * @return 实际撤销的行数（0 = 本来就没开通）
     */
    @Transactional(rollbackFor = Exception.class)
    public int revoke(Long userId, String permCode, String reason) {
        if (userId == null || permCode == null || permCode.isBlank()) {
            return 0;
        }
        UserPermission patch = new UserPermission();
        patch.setRevokedAt(LocalDateTime.now());
        patch.setRevokeReason(reason);
        int rows = userPermissionMapper.update(patch, Wrappers.<UserPermission>lambdaUpdate()
                .eq(UserPermission::getUserId, userId)
                .eq(UserPermission::getPermCode, permCode)
                .isNull(UserPermission::getRevokedAt));
        if (rows > 0) {
            log.info("[权限撤销] user={} code={} reason={}", userId, permCode, reason);
        }
        return rows;
    }
}
