package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.module.device.dto.DeviceImportExecuteRequest;
import com.enterprise.ticket.module.device.dto.DeviceImportFailureReportRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportPreviewVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportResultVO;
import com.enterprise.ticket.module.device.service.DeviceImportService;
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
 * 设备批量导入
 *
 * <p><b>权限</b>：与「新增设备」完全一致 —— super_admin / admin（「admin：设备台账管理」）。
 *
 * <p><b>审计</b>：确认导入由 Service 通过 {@code OperationLogService.recordCurrent} 显式留痕
 * （记录导入人 / 文件名 / 成功数 / 失败数），不使用 {@code @AuditLog} —— 注解切面只能记录
 * 被截断的入参摘要，无法表达批量导入的核心事实。
 *
 * <p><b>响应形态</b>：模板与失败明细为二进制 .xlsx 下载；预览与确认导入为统一 JSON 信封。
 */
@RestController
@RequestMapping("/api/devices/import")
@RequiredArgsConstructor
public class DeviceImportController {

    /** xlsx 的 MIME 类型 */
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final String TEMPLATE_FILE_NAME = "设备导入模板.xlsx";

    private static final String FAILURE_SUFFIX = "-失败明细.xlsx";

    private final DeviceImportService deviceImportService;

    /**
     * 下载导入模板（表头 + 一行示例数据，示例行标注「导入前删除」，解析时自动跳过）
     *
     * <p>测试路径：GET http://localhost:8080/api/devices/import/template
     */
    @GetMapping("/template")
    @PreAuthorize("@perm.has('device:import')")
    public ResponseEntity<byte[]> template() {
        return download(deviceImportService.buildTemplate(), TEMPLATE_FILE_NAME);
    }

    /**
     * 上传并校验，返回预览结果（成功 N 条 / 失败 M 条；<b>校验不阻断</b>）
     *
     * <p>限制：.xlsx、≤5MB、≤500 行。
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/import/preview（multipart，字段名 file）
     */
    @PostMapping("/preview")
    @PreAuthorize("@perm.has('device:import')")
    public ApiResponse<DeviceImportPreviewVO> preview(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(deviceImportService.preview(file));
    }

    /**
     * 确认导入：只写入校验通过的行，每 50 条一个事务；新设备一律「可用」
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/import/execute
     */
    @PostMapping("/execute")
    @PreAuthorize("@perm.has('device:import')")
    public ApiResponse<DeviceImportResultVO> execute(@Valid @RequestBody DeviceImportExecuteRequest request) {
        DeviceImportResultVO result = deviceImportService.execute(request);
        String message = result.getFailedCount() == 0
                ? "成功导入 " + result.getImportedCount() + " 台"
                : "成功导入 " + result.getImportedCount() + " 台，失败 " + result.getFailedCount() + " 行";
        return ApiResponse.success(message, result);
    }

    /**
     * 下载失败明细（与模板同列 + 「失败原因」列，修改后可直接重新导入）
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/import/failure-report
     */
    @PostMapping("/failure-report")
    @PreAuthorize("@perm.has('device:import')")
    public ResponseEntity<byte[]> failureReport(@Valid @RequestBody DeviceImportFailureReportRequest request) {
        return download(deviceImportService.buildFailureReport(request), failureFileName(request.getFileName()));
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
            return "设备导入" + FAILURE_SUFFIX;
        }
        String name = original.trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + FAILURE_SUFFIX;
    }
}
