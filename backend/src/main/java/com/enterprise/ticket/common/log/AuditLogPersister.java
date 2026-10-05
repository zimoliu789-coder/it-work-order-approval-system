package com.enterprise.ticket.common.log;

import com.enterprise.ticket.module.log.entity.OperationLog;
import com.enterprise.ticket.module.log.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 操作日志持久化器：唯一的落库出口，避免 Service 与切面之间产生循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogPersister {

    private final OperationLogMapper operationLogMapper;

    public void persist(OperationLog entity) {
        operationLogMapper.insert(entity);
    }
}
