package com.enterprise.ticket.module.user.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.module.user.dto.UserImportExecuteRequest;
import com.enterprise.ticket.module.user.dto.UserImportFailureReportRequest;
import com.enterprise.ticket.module.user.dto.vo.UserImportPreviewVO;
import com.enterprise.ticket.module.user.dto.vo.UserImportResultVO;
import com.enterprise.ticket.module.user.service.UserImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 员工批量导入（需求方 2026-09-18 小迭代 · ）
 *
 * <p>与设备批量导入完全同构（同模式、同参数、同三步向导）：
 * 下载模板 → 上传校验预览（不阻断，逐行给原因）→ 确认导入 → 失败明细导出后修正重导。
 *
 * <p><b>权限</b>：仅 super_admin（「仅 super_admin 能写操作」）。
 *
 * <p><b>审计</b>：确认导入由 Service 通过 {@code OperationLogService.recordCurrent} 显式留痕
 * （记录导入人 / 文件名 / 成功数 / 失败数，风险级别 HIGH ——  明确把批量导入列为
 * 必须同步留痕的高风险操作），不使用 {@code @AuditLog}：注解切面只能记录被截断的入参摘要，
 * 而本接口入参含用户填写的<b>初始密码</b>，绝不能让密码进入审计日志。
 *
 * <p><b>响应形态</b>：模板与失败明细为二进制 .xlsx 下载；预览与确认导入为统一 JSON 信封。
 */
@RestController
@RequestMapping("/api/users/import")
@RequiredArgsConstructor
public class UserImportController {

    /** xlsx 的 MIME 类型 */
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final String TEMPLATE_FILE_NAME = "员工导入模板.xlsx";

    private static final String FAILURE_SUFFIX = "-失败明细.xlsx";

    private final UserImportService userImportService;

    /**
     * 下载导入模板（表头 + 一行示例数据，示例行标注「导入前删除」，解析时自动跳过）
     *
     * <p>列：姓名、登录名、初始密码、部门名称、角色、显示名称。
     *
     * <p>测试路径：GET http://localhost:8080/api/users/import/template
     */
    @GetMapping("/template")
    @PreAuthorize("@perm.has('staff:import')")
    public ResponseEntity<byte[]> template() {
        return download(userImportService.buildTemplate(), TEMPLATE_FILE_NAME);
    }

    /**
     * 上传并校验，返回预览结果（成功 N 条 / 失败 M 条；<b>校验不阻断</b>）
     *
     * <p>逐行校验：姓名唯一、登录名唯一（两者都要同时查「库内已有」与「文件内重复」）、
     * 部门按名称匹配、角色枚举合法、必填项非空、密码强度。
     *
     * <p>限制：.xlsx、≤5MB、≤500 行。
     *
     * <p>测试路径：POST http://localhost:8080/api/users/import/preview（multipart，字段名 file）
     */
    @PostMapping("/preview")
    @PreAuthorize("@perm.has('staff:import')")
    public ApiResponse<UserImportPreviewVO> preview(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(userImportService.preview(file));
    }

    /**
     * 确认导入：只写入校验通过的行，每 50 条一个事务；新员工统一「首登强制改密」
     *
     * <p>测试路径：POST http://localhost:8080/api/users/import/execute
     */
    @PostMapping("/execute")
    @PreAuthorize("@perm.has('staff:import')")
    public ApiResponse<UserImportResultVO> execute(@Valid @RequestBody UserImportExecuteRequest request) {
        UserImportResultVO result = userImportService.execute(request);
        String message = result.getFailedCount() == 0
                ? "成功导入 " + result.getImportedCount() + " 名员工"
                : "成功导入 " + result.getImportedCount() + " 名员工，失败 " + result.getFailedCount() + " 行";
        return ApiResponse.success(message, result);
    }

    /**
     * 下载失败明细（与模板同列 + 「失败原因」列，修改后可直接重新导入）
     *
     * <p>测试路径：POST http://localhost:8080/api/users/import/failure-report
     */
    @PostMapping("/failure-report")
    @PreAuthorize("@perm.has('staff:import')")
    public ResponseEntity<byte[]> failureReport(@Valid @RequestBody UserImportFailureReportRequest request) {
        return download(userImportService.buildFailureReport(request), failureFileName(request.getFileName()));
    }

    private ResponseEntity<byte[]> download(byte[] content, String fileName) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(XLSX)
                .body(content);
    }

    /** 「xxx.xlsx」→「xxx-失败明细.xlsx」；无扩展名时直接追加后缀 */
    private String failureFileName(String original) {
        if (original == null || original.isBlank()) {
            return "员工导入" + FAILURE_SUFFIX;
        }
        String name = original.trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + FAILURE_SUFFIX;
    }
}
