package com.enterprise.ticket.module.system.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据接口（需求方三波·第三波· 消息类型元数据； 权限目录）
 *
 * <h2>为什么要有这个接口</h2>
 * <p>改造前，消息类型的「编码 → 中文名」映射同时存在于后端枚举、
 * 前端 {@code types/message.ts}、以及各处的标签色映射里 ——
 * 后端新增一个 {@code MessageType} 却忘了同步前端，界面就会出现英文编码
 * （这个问题在 b / 8 / 9 连续复发三次）。把映射收敛到后端一处、
 * 由前端启动时拉取，是唯一能根治的做法。
 *
 * <p>前端仍保留一份<b>内置兜底</b>：元数据接口不可用时按兜底表渲染，
 * 避免整个消息中心因为一个辅助接口挂掉而白屏。
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    /**
     * 消息类型元数据（编码 + 中文名）。
     *
     * <p>权限：仅要求已登录 —— 这是纯展示用的静态字典，不含任何业务数据。
     */
    @GetMapping("/message-types")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, String>>> messageTypes() {
        List<Map<String, String>> list = new ArrayList<>();
        for (MessageType type : MessageType.values()) {
            // 用 LinkedHashMap 而不是 Map.of：顺序稳定，便于对照排查
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", type.name());
            item.put("label", type.getLabel());
            list.add(item);
        }
        return ApiResponse.success(list);
    }

    /**
     * 权限目录树（菜单 + 操作），供角色授权界面渲染勾选树。
     *
     * <p>权限：{@code role:view}（默认仅超管）。目录本身不是秘密，但没有任何理由
     * 向普通员工暴露系统的权限结构。
     */
    @GetMapping("/permissions")
    @PreAuthorize("@perm.has('role:view')")
    public ApiResponse<List<PermissionCatalog.PermNode>> permissions() {
        return ApiResponse.success(PermissionCatalog.tree());
    }

    /** 数据权限范围字典（角色编辑表单的下拉项） */
    @GetMapping("/data-scopes")
    @PreAuthorize("@perm.has('role:view')")
    public ApiResponse<List<Map<String, String>>> dataScopes() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(scope("ALL", "全部数据", "可见全部业务数据"));
        list.add(scope("GROUP", "本部门", "仅可见本部门的工单"));
        list.add(scope("SELF", "仅本人", "仅可见本人相关数据"));
        return ApiResponse.success(list);
    }

    private Map<String, String> scope(String code, String label, String desc) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("description", desc);
        return item;
    }
}
