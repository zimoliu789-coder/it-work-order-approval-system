package com.enterprise.ticket.module.message.dto;

import lombok.Data;

import java.util.List;

/**
 * 消息批量操作请求（需求方三波·第二波·「消息批量删除」）
 *
 * <p>只在请求体里传 {@code ids}，<b>不传 userId</b>：归属由后端从登录态取得。
 * 若把 userId 放进请求体，就等于把「删谁的消息」交给客户端声明，
 * 必须再写一遍校验 —— 不如从结构上消除这个越权面。
 */
@Data
public class MessageBatchRequest {

    /** 待操作的消息 ID 列表 */
    private List<Long> ids;
}
