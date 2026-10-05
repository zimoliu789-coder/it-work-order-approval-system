package com.enterprise.ticket.common.log;

import com.enterprise.ticket.module.log.entity.OperationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 普通操作日志异步落库器（：普通访问日志可异步处理）
 *
 * <p>独立成类的原因：{@code @Async} 依赖 Spring 代理，同类内部调用不会生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogAsyncWriter {

    private final AuditLogPersister persister;

    @Async("auditLogExecutor")
    public void write(OperationLog entity) {
        try {
            persister.persist(entity);
        } catch (Exception e) {
            // 普通日志允许丢失，但不能影响主流程
            log.warn("异步写入操作日志失败 module={} action={} err={}",
                    entity.getModule(), entity.getAction(), e.getMessage());
        }
    }
}
