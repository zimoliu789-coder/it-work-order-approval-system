package com.enterprise.ticket.module.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.role.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联 Mapper（需求方三波·第一波·）
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
