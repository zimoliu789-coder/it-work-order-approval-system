package com.enterprise.ticket.module.role.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.enterprise.ticket.module.role.dto.RoleSaveRequest;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.entity.SysRole;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 角色与权限服务（需求方三波·第一波·）
 *
 * <h2>与既有 {@code RoleCode} 常量的关系</h2>
 * <p>{@code RoleCode} 仍保留，用于表达「内置角色」这一<b>结构性事实</b>
 * （超管不可改、不可被降级、不可被重置密码）。而「能做什么」一律查本服务，
 * 不再散落在各处的 {@code hasRole} 注解里。
 */
public interface RoleService extends IService<SysRole> {

    /** 数据权限：可看全部数据 */
    String SCOPE_ALL = "ALL";
    /** 数据权限：只看本部门 */
    String SCOPE_GROUP = "GROUP";
    /** 数据权限：只看自己 */
    String SCOPE_SELF = "SELF";

    /** 角色列表（含使用人数与权限数，不含权限明细） */
    List<RoleVO> listRoles();

    /** 角色详情（含权限明细） */
    RoleVO getRole(String roleCode);

    /** 由角色确认存在（不存在抛 ROLE_NOT_FOUND）；不存在时返回 null 的便捷方法见 {@link #exists(String)} */
    SysRole getByCode(String roleCode);

    SysRole getByCodeRequired(String roleCode);

    boolean exists(String roleCode);

    /** 角色是否存在且启用（用于「能否分配给员工」） */
    boolean isAssignable(String roleCode);

    /** 可分配的角色下拉项（启用的角色，按 sort_no 升序） */
    List<RoleVO> assignableRoles();

    /** 新建自定义角色 */
    RoleVO createRole(RoleSaveRequest request);

    /** 编辑角色（内置角色仅允许改名称 / 备注 / 数据权限） */
    RoleVO updateRole(String roleCode, RoleSaveRequest request);

    /** 删除角色（内置角色、仍被员工使用的角色不可删除） */
    void deleteRole(String roleCode);

    /**
     * 该角色拥有的权限码集合。
     *
     * <p>{@code super_admin} 返回目录全量 —— 与其他角色的「查库得到」不同，
     * 超管权限是<b>代码硬保证</b>，不依赖数据行，避免误删授权把自己锁死。
     */
    Set<String> permissionsOf(String roleCode);

    /** 该角色授权明细（超管返回全量，便于界面展示） */
    List<String> permissionCodesOf(String roleCode);

    /** 整体覆盖某角色的权限集合（返回实际写入的码数量） */
    int setPermissions(String roleCode, Collection<String> permissions);

    /** 是否拥有某权限码（超管恒 true） */
    boolean hasPermission(String roleCode, String permCode);

    /** 角色的数据权限范围；未知角色按最保守的 SELF 处理 */
    String dataScopeOf(String roleCode);

    /** 使用该角色的员工数 */
    long countUsers(String roleCode);

    /** 清空权限缓存（角色/授权变更后调用） */
    void evictCache();
}
