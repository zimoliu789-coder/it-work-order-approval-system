package com.enterprise.ticket.module.form.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateDetailVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVersionVO;
import com.enterprise.ticket.module.form.service.FormTemplateService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 表单模板管理接口
 *
 * <p>权限：查询 {@code form_template:view}、变更 {@code form_template:manage}。
 * 按需求约定这两个码默认只归 super_admin（{@code PermissionCatalog} 的默认授权里
 * 没有给 admin），因此该模块整体是<b>超管专属</b> —— 表单定义决定了后续所有自定义申请
 * 长什么样，属于「配置源头」，收得紧一些是刻意的。
 *
 * <p>审计：发布与删除标 {@link RiskLevel#HIGH}（同步写审计，）——
 * 发布把一份草稿冻结成历史版本（此后再不可改），删除会移除配置，
 * 两者都是「事后无法从业务数据倒推」的操作。创建与保存草稿标普通级（异步）：
 * 草稿随时可被覆盖，留痕足够。
 *
 * <p>前端菜单隐藏只是体验，后端全部接口均独立校验。
 */
@RestController
@RequestMapping("/api/form/templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService formTemplateService;

    /** 模板列表 */
    @GetMapping
    @PreAuthorize("@perm.has('form_template:view')")
    public ApiResponse<List<FormTemplateVO>> list() {
        return ApiResponse.success(formTemplateService.listTemplates());
    }

    /** 模板详情（含 schema：有草稿给草稿，无草稿给最新已发布版本） */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('form_template:view')")
    public ApiResponse<FormTemplateDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(formTemplateService.getDetail(id));
    }

    /** 新建模板（同时创建 v1 草稿） */
    @PostMapping
    @PreAuthorize("@perm.has('form_template:manage')")
    @AuditLog(module = "SYSTEM", action = "FORM_TEMPLATE_CREATE", description = "新建表单模板")
    public ApiResponse<Long> create(@Valid @RequestBody FormTemplateSaveRequest request) {
        return ApiResponse.success("表单模板已创建", formTemplateService.createTemplate(request));
    }

    /** 更新草稿（无草稿时自动新建一版草稿；已发布版本不受影响） */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('form_template:manage')")
    @AuditLog(module = "SYSTEM", action = "FORM_TEMPLATE_UPDATE", description = "保存表单模板草稿")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody FormTemplateSaveRequest request) {
        formTemplateService.updateTemplate(id, request);
        return ApiResponse.success("草稿已保存", null);
    }

    /** 发布当前草稿为新版本（此版本随后可被申请类型引用） */
    @PostMapping("/{id}/publish")
    @PreAuthorize("@perm.has('form_template:manage')")
    @AuditLog(module = "SYSTEM", action = "FORM_TEMPLATE_PUBLISH",
            description = "发布表单模板版本", risk = RiskLevel.HIGH)
    public ApiResponse<Long> publish(@PathVariable Long id) {
        return ApiResponse.success("表单已发布，可用于创建申请类型", formTemplateService.publish(id));
    }

    /** 版本列表（倒序，含草稿） */
    @GetMapping("/{id}/versions")
    @PreAuthorize("@perm.has('form_template:view')")
    public ApiResponse<List<FormTemplateVersionVO>> versions(@PathVariable Long id) {
        return ApiResponse.success(formTemplateService.listVersions(id));
    }

    /**
     * 某版本详情（含 schema，供版本历史预览）。
     *
     * <p>路径段 {@code /versions/...} 是<b>字面量</b>，Spring 的路径匹配会优先命中它，
     * 因此不会与上面的 {@code /{id}} 冲突（{@code "versions"} 也解析不成 Long）。
     */
    @GetMapping("/versions/{vid}")
    @PreAuthorize("@perm.has('form_template:view')")
    public ApiResponse<FormTemplateVersionVO> version(@PathVariable Long vid) {
        return ApiResponse.success(formTemplateService.getVersion(vid));
    }

    /** 删除模板（被申请类型引用时拒绝，只能停用） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('form_template:manage')")
    @AuditLog(module = "SYSTEM", action = "FORM_TEMPLATE_DELETE",
            description = "删除表单模板", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        formTemplateService.deleteTemplate(id);
        return ApiResponse.success("表单模板已删除", null);
    }
}
