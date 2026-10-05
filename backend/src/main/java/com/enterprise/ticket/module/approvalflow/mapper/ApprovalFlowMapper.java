package com.enterprise.ticket.module.approvalflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审批流程模板 Mapper
 */
@Mapper
public interface ApprovalFlowMapper extends BaseMapper<ApprovalFlow> {
}
