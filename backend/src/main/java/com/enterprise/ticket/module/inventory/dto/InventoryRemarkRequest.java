package com.enterprise.ticket.module.inventory.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 盘点结论 / 取消原因（P2）
 *
 * <p>用请求体而不是 query 参数：前端共享的 HTTP 客户端只支持
 * {@code post(url, data)} 形式（没有 config 参数），query 传参得手工拼 URL ——
 * 那会把「转义」这件事交给每个调用点，早晚出现备注里有 {@code &} 就被截断的问题。
 */
@Data
public class InventoryRemarkRequest {

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
