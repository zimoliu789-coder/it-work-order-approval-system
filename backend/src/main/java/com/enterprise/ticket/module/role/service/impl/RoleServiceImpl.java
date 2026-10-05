package com.enterprise.ticket.module.role.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.role.dto.RoleSaveRequest;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.entity.SysRolePermission;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.role.mapper.SysRolePermissionMapper;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 角色与权限服务实现（需求方三波·第一波·）
 *
 * <h2>权限缓存策略</h2>
 * <p>权限是<b>每请求都要判定</b>的热数据（每个受保护端点都会走一次
 * {@code PermissionGuard}），若每次查库会让接口的 DB 往返翻倍。
 * 因此进程内缓存 {@code roleCode → Set<permCode>}，仅在授权变更时精确失效。
 *
 * <p><b>为什么不用定时全量刷新</b>：角色表是低频写入的配置数据，而「改了权限但要等
 * 60 秒才生效」在验收时会被当成 bug。这里改为写入即失效，代价只是一次 Map 清理。
 * 多实例部署时靠 {@link #refresh()} 的兜底定时刷新感知他人变更（60 秒），
 * 与 {@code SystemConfigServiceImpl} 的策略保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements RoleService {

    private static final int DEFAULT_SORT_NO = 100;

    private static final Map<String, String> SCOPE_LABELS = Map.of(
            SCOPE_ALL, "全部数据",
            SCOPE_GROUP, "本部门",
            SCOPE_SELF, "仅本人"
    );

    private final SysRolePermissionMapper rolePermissionMapper;
    private final UserMapper userMapper;

    /** 角色编码 → 权限码集合；仅缓存「已加载过」的角色 */
    private final Map<String, Set<String>> permCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<RoleVO> listRoles() {
        List<SysRole> roles = list(Wrappers.<SysRole>lambdaQuery()
                .orderByAsc(SysRole::getSortNo)
                .orderByAsc(SysRole::getId));
        // 一次性取全部授权行后在内存分组：角色数是个位数，逐角色查库反而更慢
        Map<String, Long> permCounts = rolePermissionMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(SysRolePermission::getRoleCode, Collectors.counting()));

        List<RoleVO> result = new ArrayList<>(roles.size());
        for (SysRole role : roles) {
            RoleVO vo = toVO(role, false);
            if (RoleCode.isSuperAdmin(role.getRoleCode())) {
                // 超管权限是代码硬保证（恒全量），库里可能一行授权都没有，不能用 count 展示
                vo.setPermissionCount(PermissionCatalog.allCodes().size());
            } else {
                vo.setPermissionCount(permCounts.getOrDefault(role.getRoleCode(), 0L).intValue());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public RoleVO getRole(String roleCode) {
        SysRole role = getByCodeRequired(roleCode);
        RoleVO vo = toVO(role, true);
        vo.setPermissionCount(permissionsOf(roleCode).size());
        return vo;
    }

    @Override
    public SysRole getByCode(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return null;
        }
        return getOne(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleCode, roleCode.trim()), false);
    }

    @Override
    public SysRole getByCodeRequired(String roleCode) {
        SysRole role = getByCode(roleCode);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    @Override
    public boolean exists(String roleCode) {
        return getByCode(roleCode) != null;
    }

    @Override
    public boolean isAssignable(String roleCode) {
        SysRole role = getByCode(roleCode);
        return role != null && Boolean.TRUE.equals(role.getEnabled());
    }

    @Override
    public List<RoleVO> assignableRoles() {
        return list(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getEnabled, true)
                .orderByAsc(SysRole::getSortNo)
                .orderByAsc(SysRole::getId))
                .stream()
                .map(role -> toVO(role, false))
                .toList();
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleSaveRequest request) {
        String code = request.getRoleCode().trim();
        if (exists(code)) {
            throw new BusinessException(ErrorCode.ROLE_CODE_EXISTS);
        }
        // 内置角色的编码是结构性的：即使库里因为人为干预缺失了 admin 行，
        // 也不允许新建一个「同名但可被随意删改」的角色来冒充它。
        if (isBuiltinCode(code)) {
            throw new BusinessException(ErrorCode.ROLE_BUILTIN_PROTECTED, "该编码为内置角色保留，请更换");
        }
        SysRole role = new SysRole();
        role.setRoleCode(code);
        role.setRoleName(request.getRoleName().trim());
        role.setDataScope(normalizeScope(request.getDataScope()));
        role.setRemark(trimToNull(request.getRemark()));
        role.setBuiltin(false);
        role.setEnabled(request.getEnabled() == null || request.getEnabled());
        role.setSortNo(request.getSortNo() == null ? DEFAULT_SORT_NO : request.getSortNo());
        save(role);

        if (request.getPermissions() != null) {
            writePermissions(code, request.getPermissions());
        }
        log.info("新建角色：{}（{}），权限 {} 项", code, role.getRoleName(), permissionsOf(code).size());
        return getRole(code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateRole(String roleCode, RoleSaveRequest request) {
        SysRole role = getByCodeRequired(roleCode);
        boolean builtin = Boolean.TRUE.equals(role.getBuiltin());
        boolean superAdmin = RoleCode.isSuperAdmin(roleCode);

        // 内置角色：编码不可改（请求里的 roleCode 一律忽略），身份性字段保持原值
        role.setRoleName(request.getRoleName().trim());
        role.setDataScope(normalizeScope(request.getDataScope()));
        role.setRemark(trimToNull(request.getRemark()));
        if (!builtin) {
            role.setEnabled(request.getEnabled() == null || request.getEnabled());
            role.setSortNo(request.getSortNo() == null ? DEFAULT_SORT_NO : request.getSortNo());
        }
        updateById(role);

        if (request.getPermissions() != null) {
            if (superAdmin) {
                // 超管权限恒为全量：允许界面提交（前端会禁用勾选），但服务端必须硬拒，
                // 否则一个构造请求就能把超管削成「什么都干不了」，系统再无救火入口
                throw new BusinessException(ErrorCode.ROLE_SUPER_ADMIN_READONLY);
            }
            writePermissions(roleCode, request.getPermissions());
        } else if (superAdmin) {
            // 未提交权限时放行（只改名称/备注），但同样不做任何权限写入
        }
        log.info("编辑角色：{}（{}），权限 {} 项", roleCode, role.getRoleName(), permissionsOf(roleCode).size());
        return getRole(roleCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String roleCode) {
        SysRole role = getByCodeRequired(roleCode);
        if (Boolean.TRUE.equals(role.getBuiltin()) || isBuiltinCode(roleCode)) {
            throw new BusinessException(ErrorCode.ROLE_BUILTIN_PROTECTED);
        }
        long used = countUsers(roleCode);
        if (used > 0) {
            // 先查人数再删，避免删完发现员工带着一个不存在的角色值
            throw new BusinessException(ErrorCode.ROLE_IN_USE,
                    "该角色仍被 " + used + " 名员工使用，请先调整这些员工的角色");
        }
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleCode, roleCode));
        removeById(role.getId());
        permCache.remove(roleCode);
        log.info("删除角色：{}", roleCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int setPermissions(String roleCode, Collection<String> permissions) {
        getByCodeRequired(roleCode);
        if (RoleCode.isSuperAdmin(roleCode)) {
            throw new BusinessException(ErrorCode.ROLE_SUPER_ADMIN_READONLY);
        }
        int written = writePermissions(roleCode, permissions);
        log.info("调整角色权限：{} → {} 项", roleCode, written);
        return written;
    }

    /**
     * 覆盖式写入权限：先删后插。
     *
     * <p>用「先删后插」而不是「算差集增删」的原因：授权界面提交的是完整集
     * （PUT 语义），差集计算在并发下容易算出错误结果；而单角色授权行的数量级只有几十，
     * 全删全插的代价可以忽略，换来的语义确定性更值。
     */
    private int writePermissions(String roleCode, Collection<String> permissions) {
        Set<String> codes = new LinkedHashSet<>();
        if (permissions != null) {
            for (String raw : permissions) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                String code = raw.trim();
                if (!PermissionCatalog.exists(code)) {
                    throw new BusinessException(ErrorCode.ROLE_PERMISSION_INVALID, "无法识别的权限码：" + code);
                }
                codes.add(code);
            }
        }
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleCode, roleCode));
        for (String code : codes) {
            SysRolePermission row = new SysRolePermission();
            row.setRoleCode(roleCode);
            row.setPermCode(code);
            rolePermissionMapper.insert(row);
        }
        permCache.put(roleCode, Set.copyOf(codes));
        return codes.size();
    }

    // ------------------------------------------------------------------
    // 权限判定
    // ------------------------------------------------------------------

    @Override
    public Set<String> permissionsOf(String roleCode) {
        if (RoleCode.isSuperAdmin(roleCode)) {
            return PermissionCatalog.allCodes();
        }
        if (!StringUtils.hasText(roleCode)) {
            return Set.of();
        }
        Set<String> cached = permCache.get(roleCode);
        if (cached != null) {
            return cached;
        }
        List<SysRolePermission> rows = rolePermissionMapper.selectList(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleCode, roleCode));
        Set<String> codes = rows.stream()
                .map(SysRolePermission::getPermCode)
                // 过滤掉目录中已不存在的历史码（权限目录是代码资产，会随版本演进）
                .filter(PermissionCatalog::exists)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> snapshot = Set.copyOf(codes);
        permCache.put(roleCode, snapshot);
        return snapshot;
    }

    @Override
    public List<String> permissionCodesOf(String roleCode) {
        // 保持目录顺序（而不是库里的插入顺序），界面展示才稳定
        Set<String> granted = permissionsOf(roleCode);
        return PermissionCatalog.allCodeList().stream().filter(granted::contains).toList();
    }

    @Override
    public boolean hasPermission(String roleCode, String permCode) {
        if (RoleCode.isSuperAdmin(roleCode)) {
            return true;
        }
        return StringUtils.hasText(permCode) && permissionsOf(roleCode).contains(permCode);
    }

    @Override
    public String dataScopeOf(String roleCode) {
        SysRole role = getByCode(roleCode);
        if (role == null) {
            // 未知角色（例如刚被删除但仍有员工持该值）按最保守范围处理，不放宽任何数据可见性
            return SCOPE_SELF;
        }
        return normalizeScope(role.getDataScope());
    }

    @Override
    public long countUsers(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return 0L;
        }
        return userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getRole, roleCode));
    }

    @Override
    public void evictCache() {
        permCache.clear();
    }

    /**
     * 兜底刷新：清空缓存，让下一次判定重新查库。
     *
     * <p>单实例部署下写入即失效，本任务几乎无副作用；多实例部署时它让其它实例
     * 最多 60 秒后感知到授权变更。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void refresh() {
        if (!permCache.isEmpty()) {
            permCache.clear();
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 内置角色编码（结构性保留，不允许被自建角色占用） */
    private boolean isBuiltinCode(String code) {
        return RoleCode.SUPER_ADMIN.equals(code) || RoleCode.ADMIN.equals(code) || RoleCode.USER.equals(code);
    }

    private String normalizeScope(String scope) {
        if (SCOPE_ALL.equals(scope) || SCOPE_GROUP.equals(scope) || SCOPE_SELF.equals(scope)) {
            return scope;
        }
        return SCOPE_SELF;
    }

    private RoleVO toVO(SysRole role, boolean withPermissions) {
        RoleVO vo = new RoleVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setDataScope(role.getDataScope());
        vo.setDataScopeLabel(SCOPE_LABELS.getOrDefault(role.getDataScope(), role.getDataScope()));
        vo.setRemark(role.getRemark());
        vo.setBuiltin(Boolean.TRUE.equals(role.getBuiltin()));
        vo.setEnabled(Boolean.TRUE.equals(role.getEnabled()));
        vo.setSortNo(role.getSortNo());
        vo.setUserCount(countUsers(role.getRoleCode()));
        if (withPermissions) {
            vo.setPermissions(permissionCodesOf(role.getRoleCode()));
        }
        return vo;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || Objects.equals(trimmed, "null") ? null : trimmed;
    }
}
