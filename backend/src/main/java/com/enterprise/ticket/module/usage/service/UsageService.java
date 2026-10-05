package com.enterprise.ticket.module.usage.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.usage.dto.UsageQuery;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;

/**
 * 使用记录服务（需求方三波·第一波·）
 *
 * <p>替代  遗留的「使用记录」占位页：以设备与员工两个视角呈现完整借用历史。
 */
public interface UsageService {

    /** 分页查询使用记录（按当前登录人的数据权限范围自动收窄） */
    PageResult<UsageRecordVO> page(UsageQuery query);
}
