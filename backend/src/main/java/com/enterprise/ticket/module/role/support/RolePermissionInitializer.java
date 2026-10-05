package com.enterprise.ticket.module.role.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.entity.SysRolePermission;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.role.mapper.SysRolePermissionMapper;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置角色与默认权限初始化（需求方三波·第一波·「初始化导入现有三角色权限」）
 *
 * <h2>为什么是 ApplicationRunner 而不是 Flyway SQL</h2>
 * <p>权限码的权威定义在 {@link PermissionCatalog}（代码资产）。若把授权行写死进 SQL，
 * 就会出现「代码里改了一个码，迁移脚本里的旧码还留着」的双份事实，
 * 时间一长必然对不上。这里让 SQL 只建表，授权由代码按目录写入，单一事实来源。
 *
 * <h2>幂等性设计（关键）——  改为「逐角色标记」</h2>
 * <p>原来只有一个全局标记 {@code rbac_seeded}：一旦置位就再也不播种。这在「只有 3 个
 * 内置角色、且不会再增加」的前提下成立，但  要**新增 4 个内置角色**
 * （IT主管 / IT执行人 / 部门经理组长 / 见 {@link RoleCode}），
 * 全局标记会让新角色在**存量环境**里永远拿不到默认授权 —— 上线即出现
 * 「IT主管登录后什么都看不到」。
 *
 * <p>因此改成两级判断：
 * <ol>
 *   <li><b>老环境的兼容</b>：若全局标记 {@code rbac_seeded} 已置位，说明 admin / user
 *       在某个历史版本里已经播过种。此时**先把这两个角色的逐角色标记补上**，
 *       否则下面那一轮会把它们的授权再插一遍，直接撞 {@code uk_role_perm} 唯一键；</li>
 *   <li><b>逐角色标记</b> {@code rbac_role_seeded:<code>}：只对「没播过种」的角色写授权。</li>
 * </ol>
 * <p>仍然<b>刻意不用「表里没数据就补」这种自愈式判断</b> ——
 * 那会把「运维主动清空了某角色的权限」误判为「还没初始化」，
 * 重启一次权限就全回来了，属于最典型的幽灵缺陷。
 *
 * <h2>与既有超管保护的关系</h2>
 * <p>{@code super_admin} 也建一行 sys_role（为了在角色列表里可见、可查看其全量权限），
 * 但<b>不写任何授权行</b>：它的权限由 {@code PermissionGuard} 直接放行，
 * 不依赖数据行。这样即便有人直接删库里的 super_admin 授权行，超管照样能进系统修数据。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class RolePermissionInitializer implements ApplicationRunner {

    /** 一次性初始化标记的配置键（由 V12 迁移写入，默认值 0）——是「历史整体初始化」的痕迹 */
    private static final String FLAG_KEY = "rbac_seeded";
    private static final String FLAG_DONE = "1";

    /** 逐角色播种标记的配置键前缀（ 新增） */
    private static final String ROLE_FLAG_PREFIX = "rbac_role_seeded:";

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SystemConfigService systemConfigService;
    private final RoleService roleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        try {
            backfillLegacyRoleFlags();
            ensureBuiltinRoles();
            seedPermissions();
            roleService.evictCache();
            log.info("角色权限初始化完成：内置角色 {} 个，权限目录 {} 项",
                    RoleCode.BUILTIN.size(), PermissionCatalog.allCodes().size());
        } catch (Exception e) {
            // 初始化失败不阻断启动：此时系统仍可登录（超管恒放行），
            // 运维可修复后重启；若直接抛异常导致应用起不来，反而把唯一的修复通道也堵死了。
            log.error("角色权限初始化失败（应用继续启动，超管不受影响）：{}", e.getMessage(), e);
        }
    }

    /**
     * 老环境兼容：把全局标记翻译成逐角色标记。
     *
     * <p>只在 {@code rbac_seeded=1}（历史版本已初始化过）时执行，
     * 且只补 admin / user 这两个「老角色」——新角色本来就该在这台机器上播种。
     */
    private void backfillLegacyRoleFlags() {
        if (!FLAG_DONE.equals(systemConfigService.getString(FLAG_KEY, "0"))) {
            return;
        }
        for (String code : List.of(RoleCode.ADMIN, RoleCode.USER)) {
            String key = ROLE_FLAG_PREFIX + code;
            if (!FLAG_DONE.equals(systemConfigService.getString(key, "0"))) {
                systemConfigService.updateValue(key, FLAG_DONE);
                log.info("老环境兼容：角色 {} 的播种标记由全局标记补写为已完成", code);
            }
        }
    }

    /**
     * 内置角色定义：编码 → [名称, 数据权限, 备注, 排序]。
     *
     * <p>顺序即角色列表的默认顺序，与 {@link RoleCode#BUILTIN} 保持一致。
     * <b>角色已存在时不覆盖</b> —— 运维可能改过角色名或备注，重启一次就被改回去是事故。
     */
    private Map<String, String[]> builtinMeta() {
        Map<String, String[]> builtin = new LinkedHashMap<>();
        builtin.put(RoleCode.SUPER_ADMIN, new String[]{
                "超级管理员", RoleService.SCOPE_ALL, "内置角色：拥有全部权限，不可修改、不可删除", "1"});
        builtin.put(RoleCode.ADMIN, new String[]{
                "管理员", RoleService.SCOPE_ALL, "内置角色：全部业务功能 + 系统设置（不含高级设置）", "2"});
        builtin.put(RoleCode.IT_MANAGER, new String[]{
                "IT主管", RoleService.SCOPE_ALL,
                "内置角色：审批借用、管理资产、批准时指定执行人（ 新增）", "3"});
        builtin.put(RoleCode.IT_EXECUTOR, new String[]{
                "IT执行人", RoleService.SCOPE_ALL,
                "内置角色：收到通知后发设备、点「已发放」（ 新增）", "4"});
        builtin.put(RoleCode.DEPT_MANAGER, new String[]{
                "部门经理/组长", RoleService.SCOPE_GROUP,
                "内置角色：审批本部门 / 本组的借用申请（ 新增）", "5"});
        builtin.put(RoleCode.USER, new String[]{
                "普通员工", RoleService.SCOPE_SELF, "内置角色：提交与查看自己的工单", "6"});
        return builtin;
    }

    /** 补齐缺失的内置角色行（已存在则保持原样，不覆盖运维改过的名称 / 备注） */
    private void ensureBuiltinRoles() {
        for (Map.Entry<String, String[]> entry : builtinMeta().entrySet()) {
            String code = entry.getKey();
            if (roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getRoleCode, code)) > 0) {
                continue;
            }
            String[] meta = entry.getValue();
            SysRole role = new SysRole();
            role.setRoleCode(code);
            role.setRoleName(meta[0]);
            role.setDataScope(meta[1]);
            role.setRemark(meta[2]);
            role.setBuiltin(true);
            role.setEnabled(true);
            role.setSortNo(Integer.parseInt(meta[3]));
            roleMapper.insert(role);
            log.info("内置角色已补齐：{}（{}）", meta[0], code);
        }
    }

    /**
     * 按目录为内置角色写入默认授权；超管不写（恒全量，见类注释）。
     *
     * <p>逐角色一次判断，已经播过种的直接跳过 —— 这样新增内置角色时，
     * 存量环境也会为**新角色**补上授权，而不会碰老角色的授权。
     */
    private void seedPermissions() {
        for (String code : RoleCode.BUILTIN) {
            if (RoleCode.SUPER_ADMIN.equals(code)) {
                continue;
            }
            String flagKey = ROLE_FLAG_PREFIX + code;
            if (FLAG_DONE.equals(systemConfigService.getString(flagKey, "0"))) {
                continue;
            }
            Set<String> defaults = PermissionCatalog.defaultPermissions(code);
            int inserted = 0;
            for (String perm : defaults) {
                // 兜底去重：正常路径下由标记守卫，但标记可能被人工清过。
                // 撞唯一键会让整轮初始化失败，代价远大于一次 COUNT。
                if (rolePermissionMapper.selectCount(Wrappers.<SysRolePermission>lambdaQuery()
                        .eq(SysRolePermission::getRoleCode, code)
                        .eq(SysRolePermission::getPermCode, perm)) > 0) {
                    continue;
                }
                SysRolePermission row = new SysRolePermission();
                row.setRoleCode(code);
                row.setPermCode(perm);
                rolePermissionMapper.insert(row);
                inserted++;
            }
            systemConfigService.updateValue(flagKey, FLAG_DONE);
            log.info("内置角色 {} 初始化默认权限 {} 项（新写入 {} 项）", code, defaults.size(), inserted);
        }
    }
}
