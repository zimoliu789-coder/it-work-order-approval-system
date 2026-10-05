package com.enterprise.ticket.module.inventory.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.inventory.dto.InventoryCheckRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryRemarkRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskCreateRequest;
import com.enterprise.ticket.module.inventory.dto.InventoryTaskQuery;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryItemVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryReportVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryScopeOptionsVO;
import com.enterprise.ticket.module.inventory.dto.vo.InventoryTaskVO;
import com.enterprise.ticket.module.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备盘点接口（P2）
 *
 * <p>权限两档，与「设备台账」同级别：
 * <ul>
 *   <li>{@code inventory:view} —— 看任务与报告；</li>
 *   <li>{@code inventory:manage} —— 创建 / 核对 / 完成 / 取消。</li>
 * </ul>
 * 与台账一致地授予 admin（见 {@code PermissionCatalog}；存量库由 V40 迁移补授权行）。
 *
 * <p>「扫码核对」用资产编号而不是明细 id：手机上扫到的就是资产编号，
 * 让客户端先查出 itemId 等于把匹配责任推给前端，而这件事服务端做得更准
 * （同任务内 asset_no 唯一，命中不了还能明确回「不在本次范围内」而不是写错地方）。
 */
@RestController
@RequestMapping("/api/inventories")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * 范围可选值
     *
     * <p>路径是字面量，声明放在 {@code /{id}} 之前仅为可读性 ——
     * Spring 的路径匹配本就优先精确段，不会把它当成 id。
     */
    @GetMapping("/scope-options")
    @PreAuthorize("@perm.has('inventory:view')")
    public ApiResponse<InventoryScopeOptionsVO> scopeOptions() {
        return ApiResponse.success(inventoryService.scopeOptions());
    }

    /** 任务列表 */
    @GetMapping
    @PreAuthorize("@perm.has('inventory:view')")
    public ApiResponse<PageResult<InventoryTaskVO>> page(InventoryTaskQuery query) {
        return ApiResponse.success(inventoryService.page(query));
    }

    /** 任务详情 */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('inventory:view')")
    public ApiResponse<InventoryTaskVO> detail(@PathVariable Long id) {
        return ApiResponse.success(inventoryService.detail(id));
    }

    /**
     * 任务明细（分页）。
     *
     * @param checkResult 为空不限；传 {@code UNCHECKED} 表示只看「尚未核对」（前端「待盘清单」用）
     */
    @GetMapping("/{id}/items")
    @PreAuthorize("@perm.has('inventory:view')")
    public ApiResponse<PageResult<InventoryItemVO>> items(
            @PathVariable Long id,
            @RequestParam(required = false) String checkResult,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(inventoryService.items(id, checkResult, page, size));
    }

    /** 盘点报告 */
    @GetMapping("/{id}/report")
    @PreAuthorize("@perm.has('inventory:view')")
    public ApiResponse<InventoryReportVO> report(@PathVariable Long id) {
        return ApiResponse.success(inventoryService.report(id));
    }

    /** 创建任务 */
    @PostMapping
    @PreAuthorize("@perm.has('inventory:manage')")
    public ApiResponse<Long> create(@Valid @RequestBody InventoryTaskCreateRequest request) {
        return ApiResponse.success(inventoryService.create(request));
    }

    /** 扫码核对一台 */
    @PostMapping("/{id}/check")
    @PreAuthorize("@perm.has('inventory:manage')")
    public ApiResponse<InventoryItemVO> check(@PathVariable Long id,
                                              @Valid @RequestBody InventoryCheckRequest request) {
        return ApiResponse.success(inventoryService.check(id, request));
    }

    /** 完成盘点（请求体可选；remark 为盘点结论） */
    @PostMapping("/{id}/complete")
    @PreAuthorize("@perm.has('inventory:manage')")
    public ApiResponse<Void> complete(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) InventoryRemarkRequest request) {
        inventoryService.complete(id, request == null ? null : request.getRemark());
        return ApiResponse.success(null);
    }

    /** 取消盘点 */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("@perm.has('inventory:manage')")
    public ApiResponse<Void> cancel(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) InventoryRemarkRequest request) {
        inventoryService.cancel(id, request == null ? null : request.getRemark());
        return ApiResponse.success(null);
    }
}
