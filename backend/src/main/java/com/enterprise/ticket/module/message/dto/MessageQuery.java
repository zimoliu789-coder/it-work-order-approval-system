package com.enterprise.ticket.module.message.dto;

import lombok.Data;

/**
 * 站内消息查询条件
 */
@Data
public class MessageQuery {

    /** 页码，从 1 开始 */
    private long page = 1L;

    /** 每页条数 */
    private long size = 10L;

    /**
     * 只看未读。
     *
     * <p>用 {@code Boolean} 而非 {@code boolean}：三者语义不同 ——
     * {@code null}=全部、{@code true}=仅未读、{@code false}=仅已读。
     */
    private Boolean unreadOnly;

    /**
     * 按消息类型筛选（取值见 {@link com.enterprise.ticket.common.constant.MessageType}）。
     *
     * <p>为空表示不筛选；给了非法值由服务层返回 {@code PARAM_INVALID} 而不是静默忽略 ——
     * 静默忽略会让用户以为「筛了但结果为空」是数据问题。
     */
    private String messageType;

    /** 关键词：匹配标题或正文 */
    private String keyword;
}
