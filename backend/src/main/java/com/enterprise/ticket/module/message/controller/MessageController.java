package com.enterprise.ticket.module.message.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.message.dto.MessageBatchRequest;
import com.enterprise.ticket.module.message.dto.MessageQuery;
import com.enterprise.ticket.module.message.dto.vo.MessageVO;
import com.enterprise.ticket.module.message.service.MessageService;
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

import java.util.Map;

/**
 * 站内消息接口（ 右上角消息铃铛 /  站内消息通知系统）
 *
 * <p><b>权限</b>：仅要求已登录。所有端点都<b>只操作当前登录者自己的消息</b> ——
 * 用户维度不是通过参数传入再校验，而是直接取自登录态（{@code SecurityUtils}），
 * 因此结构上不存在「传入别人的 userId 查看他人消息」的越权面。
 *
 * <p>测试路径：
 * <pre>
 * GET  http://localhost:8080/api/messages?page=1&amp;size=10
 * GET  http://localhost:8080/api/messages/unread-count
 * PUT  http://localhost:8080/api/messages/1/read
 * PUT  http://localhost:8080/api/messages/read-all
 * DELETE http://localhost:8080/api/messages/1
 * POST http://localhost:8080/api/messages/batch-delete   { "ids": [1,2,3] }
 * </pre>
 */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /** 我的消息分页（unreadOnly=true 只看未读） */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<MessageVO>> mine(MessageQuery query) {
        return ApiResponse.success(messageService.pageMine(query));
    }

    /**
     * 未读消息数（铃铛红点）
     *
     * <p>返回对象而非裸数字：后续要加「按类型分组未读」时可以直接扩字段而不破坏前端契约。
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.success(Map.of("count", messageService.unreadCount()));
    }

    /** 标记单条已读（仅限本人消息） */
    @PutMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        messageService.markRead(id);
        return ApiResponse.success();
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        int affected = messageService.markAllRead();
        return ApiResponse.success("已将 " + affected + " 条消息标记为已读", Map.of("affected", affected));
    }

    /**
     * 删除我的一条消息（消息中心用）
     *
     * <p>测试路径：DELETE http://localhost:8080/api/messages/1
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        messageService.deleteMine(id);
        return ApiResponse.success("消息已删除", null);
    }

    /**
     * 批量删除我的消息（）
     *
     * <p>用 POST 而不是带请求体的 DELETE：部分网关 / 代理会丢弃 DELETE 的请求体，
     * 到时表现为「接口通了但一条都没删」，排查成本高。批量操作走 POST 更稳。
     *
     * <p>测试路径：POST http://localhost:8080/api/messages/batch-delete  {"ids":[1,2,3]}
     */
    @PostMapping("/batch-delete")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Integer>> batchDelete(@RequestBody MessageBatchRequest request) {
        int affected = messageService.deleteBatchMine(request == null ? null : request.getIds());
        return ApiResponse.success("已删除 " + affected + " 条消息", Map.of("affected", affected));
    }
}
