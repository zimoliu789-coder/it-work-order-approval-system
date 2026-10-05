package com.enterprise.ticket.module.setup.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.module.setup.dto.SetupInitializeRequest;
import com.enterprise.ticket.module.setup.dto.vo.SetupStatusVO;
import com.enterprise.ticket.module.setup.service.SetupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 初始化向导（本次新增）。
 *
 * <h2>为什么这两个端点必须免认证</h2>
 * <p>它们的整个意义就是「<b>还没人能用系统</b>时先建出第一个超管」——
 * 此时库里没有任何账号，要求登录才能初始化是自相矛盾的。
 *
 * <p>放行它们不等于不设防（三条约束并集）：
 * <ol>
 *   <li>{@code /initialize} 只在「库中没有 super_admin」时生效，已初始化后一律拒绝 ——
 *       系统一旦有了超管，这两个端点就只是两个只读/无副作用的探针；</li>
 *   <li>账号名与密码都过服务端校验（正则 + 密码策略）；</li>
 *   <li>仍受全局匿名限流约束（{@code /status} 已加入限流跳过表以免被高频轮询拖累，
 *       而 {@code /initialize} 保持计数）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;

    /** 初始化状态：前端据此决定是否把访问者引导到向导页 */
    @GetMapping("/status")
    public ApiResponse<SetupStatusVO> status() {
        return ApiResponse.success(setupService.status());
    }

    /** 完成初始化：创建内置超级管理员（仅在系统尚无超管时可用） */
    @PostMapping("/initialize")
    public ApiResponse<Void> initialize(@Valid @RequestBody SetupInitializeRequest request) {
        setupService.initialize(request);
        return ApiResponse.success();
    }
}
