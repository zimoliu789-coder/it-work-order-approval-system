package com.enterprise.ticket.module.department.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.department.entity.DivisionHandlerGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分部 ↔ 处理小组绑定 Mapper（V53）。
 *
 * <p>与 {@link DepartmentMapper} 同样的取舍：只声明 {@link BaseMapper}，
 * 查询用 LambdaQueryWrapper 表达。这张表只有「按分部查 / 按组查 / 全量列出」三种访问，
 * 手写 SQL 没有收益。
 */
@Mapper
public interface DivisionHandlerGroupMapper extends BaseMapper<DivisionHandlerGroup> {
}
