package com.enterprise.ticket.module.upgrade.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.upgrade.dto.vo.UpgradeOverviewVO;
import com.enterprise.ticket.module.upgrade.dto.vo.UpgradeTaskVO;
import com.enterprise.ticket.module.upgrade.service.UpgradeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 在线一键升级接口
 *
 * <h2>权限：两个不同的码，均只归超管</h2>
 * <p>{@code system:upgrade:view} 看（总览 / 历史 / 详情），
 * {@code system:upgrade:execute} 做（上传 / 应用 / 回滚）。
 * 两者都<b>不下发给 admin</b> —— 升级接口能替换服务器上的可执行文件，
 * 等价于代码执行能力，远超业务管理员的职责范围（与 {@code ad:*} 同一取向）。
 *
 * <h2>为什么上传用裸字节流（{@code application/octet-stream}）而不是 multipart</h2>
 * <p>升级包可达数百 MB，而 multipart 的体量上限是<b>全局配置</b>
 * （{@code spring.servlet.multipart.max-file-size}，当前 20MB）。
 * 为了一个偶发的大文件把这个全局值抬到几百 MB，等于同时放宽了附件上传的容器层防护 ——
 * 而那一层正是「业务层限额之外再挡一道」的设计所在。
 * 改用裸流后：体量上限由<b>本模块自己</b>在「边读边计数」中执行（超限即断），
 * 既不碰全局配置，也不会把整个包缓冲进内存。
 *
 * <h2>为什么三个写操作都是 {@code RiskLevel.HIGH}</h2>
 * <p>{@code HIGH} 表示审计走 {@code REQUIRES_NEW} 独立事务、<b>同步</b>落库。
 * 升级这类操作有一个特点：<b>它会把后端进程重启掉</b>。
 * 普通（异步）审计此时可能还没落库就被进程消失带走了 ——
 * 那就成了「系统升级过、但日志里查不到谁做的」。高风险 + 同步是唯一可靠的选择。
 */
@Slf4j
@RestController
@RequestMapping("/api/system/upgrade")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService upgradeService;

    /** 能力总览：开关 / 当前版本 / 是否有活跃任务 */
    @GetMapping("/overview")
    @PreAuthorize("@perm.has('system:upgrade:view')")
    public ApiResponse<UpgradeOverviewVO> overview() {
        return ApiResponse.success(upgradeService.overview());
    }

    /** 升级历史（倒序） */
    @GetMapping("/tasks")
    @PreAuthorize("@perm.has('system:upgrade:view')")
    public ApiResponse<List<UpgradeTaskVO>> tasks() {
        return ApiResponse.success(upgradeService.history());
    }

    /** 单条任务详情（前端轮询进度用） */
    @GetMapping("/tasks/{taskNo}")
    @PreAuthorize("@perm.has('system:upgrade:view')")
    public ApiResponse<UpgradeTaskVO> detail(@PathVariable String taskNo) {
        return ApiResponse.success(upgradeService.detail(taskNo));
    }

    /**
     * 上传升级包（校验 → 备份 → 落盘 → 待应用）。
     *
     * <p>测试路径：{@code POST /api/system/upgrade/package?fileName=ticket-1.1.0.zip}
     * （{@code Content-Type: application/octet-stream}，body 为包内容）
     *
     * <p>{@code recordArgs = false}：入参里有一个 {@code HttpServletRequest}
     * 与一个尚未读取的输入流，把它们交给审计切面去 toString 没有意义
     * （得到的是类名），而真正需要留痕的文件名与任务号已经落进 {@code upgrade_tasks} 表。
     */
    @PostMapping(value = "/package", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("@perm.has('system:upgrade:execute')")
    @AuditLog(module = "SYSTEM", action = "UPGRADE_UPLOAD", risk = RiskLevel.HIGH,
            description = "上传升级包", recordArgs = false)
    public ApiResponse<UpgradeTaskVO> upload(@RequestParam(value = "fileName", required = false) String fileName,
                                            HttpServletRequest request) throws IOException {
        return ApiResponse.success("升级包已上传并校验通过，可择机应用",
                upgradeService.upload(fileName, request.getInputStream()));
    }

    /**
     * 发起应用（调用外部编排脚本完成替换与重启）。
     *
     * <p>测试路径：{@code POST /api/system/upgrade/tasks/{taskNo}/apply}
     */
    @PostMapping("/tasks/{taskNo}/apply")
    @PreAuthorize("@perm.has('system:upgrade:execute')")
    @AuditLog(module = "SYSTEM", action = "UPGRADE_APPLY", risk = RiskLevel.HIGH,
            description = "发起在线升级应用")
    public ApiResponse<UpgradeTaskVO> apply(@PathVariable String taskNo) {
        return ApiResponse.success("已发起应用，后端可能在替换过程中重启，请稍后刷新页面查看结果",
                upgradeService.apply(taskNo));
    }

    /**
     * 手动回滚（把当前产物还原为该任务备份的版本）。
     *
     * <p>测试路径：{@code POST /api/system/upgrade/tasks/{taskNo}/rollback}
     */
    @PostMapping("/tasks/{taskNo}/rollback")
    @PreAuthorize("@perm.has('system:upgrade:execute')")
    @AuditLog(module = "SYSTEM", action = "UPGRADE_ROLLBACK", risk = RiskLevel.HIGH,
            description = "回滚在线升级")
    public ApiResponse<UpgradeTaskVO> rollback(@PathVariable String taskNo) {
        return ApiResponse.success("已回滚，需重启后端 / 重建容器后生效", upgradeService.rollback(taskNo));
    }
}
