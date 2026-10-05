package com.enterprise.ticket.module.applytype.controller;

import com.enterprise.ticket.module.applytype.dto.ApplyConfigCreateRequest;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.applytype.dto.ApplyTypeSaveRequest;
import com.enterprise.ticket.module.applytype.dto.ApplyTypeStatusRequest;
import com.enterprise.ticket.module.applytype.dto.FlowPreviewRequest;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeOptionVO;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeVO;
import com.enterprise.ticket.module.applytype.dto.vo.FlowPreviewVO;
import com.enterprise.ticket.module.applytype.service.ApplyTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 申请类型接口
 *
 * <h2>权限分两档</h2>
 * <ul>
 *   <li><b>管理类</b>（列表 / 新建 / 修改 / 删除 / 启停）：{@code apply_type:view|manage}。
 *       按需求约定 {@code admin} 默认只拿 {@code apply_type:view}（能看不能改）；</li>
 *   <li><b>提交类</b>（启用列表 / 详情）：仅要求已登录 —— 这是普通员工用的接口，
 *       他们不该、也不会有任何管理权限码。数据可见性由
 *       {@link ApplyTypeService#listEnabledForCurrentUser()} 在服务端按提交权限过滤。</li>
 * </ul>
 *
 * <p>审计：新建 / 修改 / 删除 / 启停全部标 {@link RiskLevel#HIGH}。理由与部门配置一致 ——
 * 申请类型决定「谁能提交什么、走不走审批」，属于配置源头，且删除会连带影响提交入口，
 * 属于事后无法从业务数据倒推的操作。
 */
@RestController
@RequestMapping("/api/apply-types")
@RequiredArgsConstructor
public class ApplyTypeController {

    private final ApplyTypeService applyTypeService;

    /** 管理列表（全部状态） */
    @GetMapping
    @PreAuthorize("@perm.has('apply_type:view')")
    public ApiResponse<List<ApplyTypeVO>> list() {
        return ApiResponse.success(applyTypeService.listAll());
    }

    /**
     * 当前用户可提交的类型（启用 + 提交权限过滤），供「提交申请」页卡片区。
     *
     * <p>仅要求登录：普通员工没有管理权限码，但提交申请是他们最核心的日常操作。
     * 路径段 {@code /enabled} 是字面量，优先级高于 {@code /{id}}，不会冲突。
     */
    @GetMapping("/enabled")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ApplyTypeOptionVO>> enabled() {
        return ApiResponse.success(applyTypeService.listEnabledForCurrentUser());
    }

    /** 类型详情（含 schema，提交页据此渲染动态表单） */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ApplyTypeVO> detail(@PathVariable Long id) {
        return ApiResponse.success(applyTypeService.getDetail(id));
    }

    /**
     * 审批流程预览：按当前表单数据算出命中路径，并给出「申请人自选审批人」的要求。
     *
     * <p>仅要求登录 —— 与提交同权（服务端内部走 {@code requireSubmittable} 校验），
     * 能提交的人才能预览，避免它变成探测流程配置的旁路。
     *
     * <p>路径段 {@code /flow-preview} 是字面量，与 {@code /{id}} 不冲突。
     */
    @PostMapping("/{id}/flow-preview")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FlowPreviewVO> flowPreview(@PathVariable Long id,
                                                 @RequestBody(required = false) FlowPreviewRequest request) {
        return ApiResponse.success(
                applyTypeService.flowPreview(id, request == null ? null : request.getFormData()));
    }

    /**
     * 「申请人自选」候选人的**分页搜索**（ · W4-D）。
     *
     * <p>{@code /flow-preview} 下发的候选池在「范围 = 全部员工」时会被截断成首屏若干条，
     * 本接口提供完整列表的翻页与关键字检索 —— 截断与搜索必须同时到位，
     * 只截断会让排在后面的人**真的选不到**（功能回归，不是优化）。
     *
     * <p>权限与预览一致（仅要求登录，服务端内部走 {@code requireSubmittable}）：
     * 能提交才拿得到候选人，避免它变成枚举在职员工的旁路。
     *
     * <p>路径段 {@code /choose-candidates} 是字面量，与 {@code /{id}} 不冲突（多一段）。
     */
    @GetMapping("/{id}/choose-candidates")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<FlowPreviewVO.Candidate>> chooseCandidates(
            @PathVariable Long id,
            @RequestParam String nodeKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(applyTypeService.chooseCandidates(id, nodeKey, keyword, page, size));
    }

    /**
     * 一步创建申请类型：建表单 + 建流程 + 建类型一次完成。
     *
     * <p>审计等级 HIGH：这个入口会同时产生一条可用的表单、一条可用的审批流程和一个可提交的申请类型，
     * 影响面比「改一个字段」大得多，值得与「新建申请类型」同级留痕。
     */
    @PostMapping("/full")
    @PreAuthorize("@perm.has('apply_type:manage')")
    @AuditLog(module = "SYSTEM", action = "APPLY_TYPE_CREATE_FULL",
            description = "一步创建申请类型（含表单与审批流程）", risk = RiskLevel.HIGH)
    public ApiResponse<Long> createFull(@Valid @RequestBody ApplyConfigCreateRequest request) {
        return ApiResponse.success("申请类型已创建", applyTypeService.createFull(request));
    }

    /** 新建申请类型 */
    @PostMapping
    @PreAuthorize("@perm.has('apply_type:manage')")
    @AuditLog(module = "SYSTEM", action = "APPLY_TYPE_CREATE",
            description = "新建自定义申请类型", risk = RiskLevel.HIGH)
    public ApiResponse<Long> create(@Valid @RequestBody ApplyTypeSaveRequest request) {
        return ApiResponse.success("申请类型已创建", applyTypeService.create(request));
    }

    /** 修改申请类型 */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('apply_type:manage')")
    @AuditLog(module = "SYSTEM", action = "APPLY_TYPE_UPDATE",
            description = "修改自定义申请类型", risk = RiskLevel.HIGH)
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody ApplyTypeSaveRequest request) {
        applyTypeService.update(id, request);
        return ApiResponse.success("申请类型已更新", null);
    }

    /** 启用 / 停用 */
    @PutMapping("/{id}/status")
    @PreAuthorize("@perm.has('apply_type:manage')")
    @AuditLog(module = "SYSTEM", action = "APPLY_TYPE_STATUS",
            description = "启用或停用自定义申请类型", risk = RiskLevel.HIGH)
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody ApplyTypeStatusRequest request) {
        applyTypeService.updateStatus(id, request.getStatus());
        return ApiResponse.success("状态已更新", null);
    }

    /** 删除（已被工单使用时返回 APPLY_TYPE_HAS_ORDER，只能停用） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('apply_type:manage')")
    @AuditLog(module = "SYSTEM", action = "APPLY_TYPE_DELETE",
            description = "删除自定义申请类型", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        applyTypeService.delete(id);
        return ApiResponse.success("申请类型已删除", null);
    }
}
