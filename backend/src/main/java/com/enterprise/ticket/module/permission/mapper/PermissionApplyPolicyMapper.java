package com.enterprise.ticket.module.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.permission.entity.PermissionApplyPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限申请策略。
 *
 * <p>只有 BaseMapper 的 CRUD：策略表的行数等于权限码数量级（几十行），
 * 服务层一次性全量读出并在内存里合并代码默认值 —— 不值得为它写聚合 SQL。
 */
@Mapper
public interface PermissionApplyPolicyMapper extends BaseMapper<PermissionApplyPolicy> {
}
