package com.enterprise.ticket.module.department.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.department.entity.Department;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门 Mapper。
 *
 * <p>只声明 {@link BaseMapper}：本模块的查询全部是「条件简单、可组合」的那类
 * （取全部、按 path 前缀取子树、按 handler_group 取最终处理部门），
 * 用 MyBatis-Plus 的 LambdaQueryWrapper 表达即可。
 * 手写 SQL 一旦进来，就得跟着列改名一起维护，这正是这次重构想避免的成本。
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {
}
