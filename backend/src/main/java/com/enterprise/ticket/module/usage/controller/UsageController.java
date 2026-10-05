package com.enterprise.ticket.module.usage.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.usage.dto.UsageQuery;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;
import com.enterprise.ticket.module.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用记录接口（需求方三波·第一波·）
 *
 * <p>替代  遗留的「使用记录」占位页（ 菜单 + 「形成完整使用记录」）。
 *
 * <p><b>权限</b>：{@code usage:view}，默认授予 admin 与 super_admin；
 * 进入后按角色的 {@code data_scope} 自动收窄可见范围（见 {@code UsageServiceImpl}）。
 *
 * <p>测试路径：
 * <pre>
 * GET http://localhost:8080/api/usage/records?page=1&amp;size=20
 * GET http://localhost:8080/api/usage/records?scope=DEVICE&amp;targetId=1
 * GET http://localhost:8080/api/usage/records?scope=USER&amp;targetId=2
 * GET http://localhost:8080/api/usage/records?keyword=BO2026&amp;status=RETURNED
 * </pre>
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    /** 使用记录分页查询 */
    @GetMapping("/records")
    @PreAuthorize("@perm.has('usage:view')")
    public ApiResponse<PageResult<UsageRecordVO>> records(UsageQuery query) {
        return ApiResponse.success(usageService.page(query));
    }
}
