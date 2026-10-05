package com.enterprise.ticket.module.applytype.dto;

import lombok.Data;

import java.util.Map;

/**
 * 审批流程预览请求
 *
 * <p>只带表单数据：预览要回答的问题就是「按你现在填的这份表，会走哪条分支」。
 * 用户、申请类型等身份信息一律从登录态取，不由请求体传入 ——
 * 否则就能拿别人的身份去预览。
 *
 * <p>{@code formData} 允许为空对象：提交页刚打开、用户还没填任何字段时也会调一次预览，
 * 用来展示「按默认走向」的初始链路。条件求值对缺失值按「空」处理，不会因此报错。
 */
@Data
public class FlowPreviewRequest {

    /** 表单数据：字段 key → 值 */
    private Map<String, Object> formData;
}
