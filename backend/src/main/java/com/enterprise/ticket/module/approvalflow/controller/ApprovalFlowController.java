package com.enterprise.ticket.module.approvalflow.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.FlowScope;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowDuplicateRequest;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowSaveRequest;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowStatusRequest;
import com.enterprise.ticket.module.approvalflow.dto.FlowValidateRequest;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowDetailVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVersionVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.FlowDesignMetaVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.FlowValidateResultVO;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
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
 * 审批流程模板管理接口。
 *
 * <p>权限（需求方明确"超管才能管理流程模板，admin 只读"）：
 * 查询 {@code approval_flow:view}，变更 {@code approval_flow:manage}。
 * 与一期表单模板一样，{@code manage} 只归 super_admin —— 流程决定了工单"谁来批"，
 * 属于配置源头，写权限收得紧一些是刻意的。
 *
 * <p>审计：发布与删除标 {@link RiskLevel#HIGH}（同步写审计）——
 * 发布把草稿冻结成不可改的历史版本，删除会移除配置，两者都"无法从业务数据倒推"。
 * 创建与保存草稿标普通级（异步）：草稿随时可覆盖，留痕足够。
 *
 * <p>前端菜单隐藏只是体验，后端全部接口独立校验。
 */
@RestController
@RequestMapping("/api/approval-flows")
@RequiredArgsConstructor
public class ApprovalFlowController {

    private final ApprovalFlowService approvalFlowService;
    private final FormTemplateService formTemplateService;

    /** 流程列表 */
    @GetMapping
    @PreAuthorize("@perm.has('approval_flow:view')")
    public ApiResponse<List<ApprovalFlowVO>> list() {
        return ApiResponse.success(approvalFlowService.listFlows());
    }

    /**
     * 设计器元数据（ · W4-D / C8）——下发「深度上限 + 各域禁用规则集 + 各来源参数槽位」。
     *
     * <h2>为什么用 {@code approval_flow:view} 而不是 {@code manage}</h2>
     * <p>它是**只读约束说明**：不含任何配置数据，也不读表单 schema（与 {@code /validate}
     * 需要读 schema 因而收在 {@code manage} 下不同）。admin 是只读审阅者，
     * 打开设计器就要用它来做即时提示 —— 若收在 {@code manage} 下，admin 会看到
     * "设计器可用但提示全失效"的半残状态。
     *
     * <h2>路径冲突说明</h2>
     * <p>{@code /design-meta} 是字面量单段路径，与 {@code GET /{id}} 形式上重叠。
     * Spring 的路径匹配**字面量优先于变量**，因此它稳定命中本方法；
     * 若哪天它真的落到 {@code detail} 上，{@code Long id} 会因无法转换而报 400
     * （不会静默取到错误的流程）。实机回归脚本 {@code _w4d-verify.sh} 对这个端点做了显式探测。
     */
    @GetMapping("/design-meta")
    @PreAuthorize("@perm.has('approval_flow:view')")
    public ApiResponse<FlowDesignMetaVO> designMeta() {
        return ApiResponse.success(approvalFlowService.designMeta());
    }

    /** 流程详情（含定义：有草稿给草稿） */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('approval_flow:view')")
    public ApiResponse<ApprovalFlowDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(approvalFlowService.getDetail(id));
    }

    /**
     * 校验一份流程定义（M4a）——**不落库、不抛错，返回全部问题**。
     *
     * <p>为什么返回 {@code ApiResponse.success(问题清单)} 而不是错误码：校验"未通过"是
     * 这个端点的**正常业务结果**（它就是个问答接口），不是异常。返回 200 + 清单能让前端
     * 一次性拿到所有问题并逐条展示，比"一个错误一个弹窗"体验好得多。
     *
     * <p>权限与"保存草稿"一致（{@code manage}）：草稿可任意不完整，能被设计者反复调用，
     * 但它会读表单 schema（若传了 {@code formVersionId}），因此不对外开放读权限。
     */
    @PostMapping("/validate")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    public ApiResponse<FlowValidateResultVO> validate(@RequestBody FlowValidateRequest request) {
        FormSchema schema = request.getFormVersionId() == null
                ? null
                : formTemplateService.getVersion(request.getFormVersionId()).getSchema();
        // 业务域（M1）：借用流程与自定义表单流程的禁用规则集不同，
        // 必须按调用方声明的域校验，否则预检通过但发布失败。
        //
        // 域解析必须"要么是合法枚举、要么是 null（=CUSTOM）"，**不能把拼错的域静默降级成 CUSTOM**：
        // 那样调用方以为自己在校验借用域，实际校验的是自定义域，结果"预检全绿、发布被拒"——
        // 正是本接口最该避免的误导。因此这里对"非空但解析不出"直接报错，而不是退回默认值。
        FlowScope scope = resolveScope(request.getScope());
        List<String> problems = approvalFlowService.validateDefinition(request.getDefinition(), schema, scope);
        return ApiResponse.success(new FlowValidateResultVO(problems));
    }

    /**
     * 解析业务域入参（M1）：null 或空白 → null（由校验器按 CUSTOM 处理）；
     * 非空但非合法枚举 → 抛错，**绝不静默降级**（否则调用方会拿到一份按错误域校验出的结论）。
     */
    private FlowScope resolveScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        FlowScope scope = FlowScope.of(raw);
        if (scope == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "校验业务域不合法：" + raw);
        }
        return scope;
    }

    /** 新建流程（同时创建 v1 草稿） */
    @PostMapping
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_CREATE", description = "新建审批流程")
    public ApiResponse<Long> create(@Valid @RequestBody ApprovalFlowSaveRequest request) {
        return ApiResponse.success("审批流程已创建", approvalFlowService.createFlow(request));
    }

    /**
     * 另存为 / 复制流程（ · W4-B）——从源模板复制出一份**新的**草稿。
     *
     * <p>审计级别与「新建」一致（普通级、异步）：它产出的就是一份普通草稿，
     * 既不冻结任何历史版本、也不改变任何既有配置的指向。真正"不可逆"的动作是发布与删除，
     * 那两者才标 HIGH。把复制也标 HIGH 只会稀释高危级别的信号。
     */
    @PostMapping("/{id}/duplicate")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_DUPLICATE", description = "复制审批流程")
    public ApiResponse<Long> duplicate(@PathVariable Long id,
                                       @Valid @RequestBody ApprovalFlowDuplicateRequest request) {
        return ApiResponse.success("流程已复制", approvalFlowService.duplicateFlow(id, request));
    }

    /** 保存草稿（无草稿时自动另开一版；已发布版本不受影响） */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_UPDATE", description = "保存审批流程草稿")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody ApprovalFlowSaveRequest request) {
        approvalFlowService.updateFlow(id, request);
        return ApiResponse.success("草稿已保存", null);
    }

    /** 发布当前草稿为新版本（此版本随后可被申请类型引用） */
    @PostMapping("/{id}/publish")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_PUBLISH",
            description = "发布审批流程版本", risk = RiskLevel.HIGH)
    public ApiResponse<Long> publish(@PathVariable Long id) {
        return ApiResponse.success("流程已发布，可用于申请类型", approvalFlowService.publish(id));
    }

    /** 状态切换（启用 / 停用） */
    @PutMapping("/{id}/status")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_STATUS", description = "审批流程状态切换")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody ApprovalFlowStatusRequest request) {
        approvalFlowService.updateStatus(id, request.getStatus());
        return ApiResponse.success("状态已更新", null);
    }

    /** 版本列表（倒序，含草稿） */
    @GetMapping("/{id}/versions")
    @PreAuthorize("@perm.has('approval_flow:view')")
    public ApiResponse<List<ApprovalFlowVersionVO>> versions(@PathVariable Long id) {
        return ApiResponse.success(approvalFlowService.listVersions(id));
    }

    /**
     * 某版本详情（含完整定义），用于版本历史预览。
     *
     * <p>路径段 {@code /versions/...} 是字面量，Spring 优先命中它，不会与 {@code /{id}} 冲突。
     */
    @GetMapping("/versions/{vid}")
    @PreAuthorize("@perm.has('approval_flow:view')")
    public ApiResponse<ApprovalFlowVersionVO> version(@PathVariable Long vid) {
        return ApiResponse.success(approvalFlowService.getVersion(vid));
    }

    /** 删除流程（被申请类型引用时拒绝，只能停用） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('approval_flow:manage')")
    @AuditLog(module = "SYSTEM", action = "APPROVAL_FLOW_DELETE",
            description = "删除审批流程", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        approvalFlowService.deleteFlow(id);
        return ApiResponse.success("审批流程已删除", null);
    }
}
