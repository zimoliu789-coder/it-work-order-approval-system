package com.enterprise.ticket.module.department.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 设置部门主管（整体替换，）。
 *
 * <p>「可选多个」是需求文档明确要求的（正职 + 副职）。
 * 采用**整体替换**而不是增删两条端点：部门主管是一个「集合配置」，
 * 用两个端点会让并发下的中间态难以推理（A 加完、B 还没删，此刻谁是主管？）。
 */
@Data
public class DepartmentManagerRequest {

    /** 部门主管 user_id 列表；空列表 = 清空主管 */
    @NotNull(message = "userIds 不能为 null（清空主管请传空数组）")
    private List<Long> userIds;
}
