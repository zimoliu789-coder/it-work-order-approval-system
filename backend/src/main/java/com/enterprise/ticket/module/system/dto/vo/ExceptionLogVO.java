package com.enterprise.ticket.module.system.dto.vo;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.ExceptionCategory;
import com.enterprise.ticket.module.system.entity.ExceptionLog;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常日志列表行。
 *
 * <p>刻意**不含**堆栈：一次事故可能上万行，把堆栈塞进列表响应会让接口变成几十 MB。
 * 堆栈只在详情接口返回（{@link ExceptionLogDetailVO}）。
 *
 * <p>分类与分级都以「编码 + 中文标签」成对下发：编码给前端做筛选值与颜色映射，
 * 标签给用户看。只下发编码会逼前端复制一份中文映射表（两份必然漂移）。
 */
@Data
public class ExceptionLogVO {

    private Long id;

    private String traceId;

    /** 分类编码：DATABASE / NETWORK / ... */
    private String category;

    /** 分类中文标签 */
    private String categoryLabel;

    /** 分级短码：P0 / P1 / P2 */
    private String severity;

    /** 分级中文标签 */
    private String severityLabel;

    private String module;

    private String exceptionClass;

    private String message;

    private String requestUri;

    private String httpMethod;

    private Long userId;

    private String ip;

    private LocalDateTime occurredAt;

    /** PENDING / SENT / SUPPRESSED */
    private String alertState;

    private LocalDateTime alertedAt;

    public static ExceptionLogVO of(ExceptionLog entity) {
        ExceptionLogVO vo = new ExceptionLogVO();
        vo.setId(entity.getId());
        vo.setTraceId(entity.getTraceId());
        vo.setCategory(entity.getCategory());
        vo.setCategoryLabel(ExceptionCategory.fromName(entity.getCategory()).label());
        vo.setSeverity(entity.getSeverity());
        vo.setSeverityLabel(AlertLevel.fromCode(entity.getSeverity()).label());
        vo.setModule(entity.getModule());
        vo.setExceptionClass(entity.getExceptionClass());
        vo.setMessage(entity.getMessage());
        vo.setRequestUri(entity.getRequestUri());
        vo.setHttpMethod(entity.getHttpMethod());
        vo.setUserId(entity.getUserId());
        vo.setIp(entity.getIp());
        vo.setOccurredAt(entity.getOccurredAt());
        vo.setAlertState(entity.getAlertState());
        vo.setAlertedAt(entity.getAlertedAt());
        return vo;
    }
}
