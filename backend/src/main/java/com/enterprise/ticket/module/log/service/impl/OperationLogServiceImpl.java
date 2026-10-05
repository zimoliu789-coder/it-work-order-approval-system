package com.enterprise.ticket.module.log.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.enterprise.ticket.common.log.AuditLogAsyncWriter;
import com.enterprise.ticket.common.log.AuditLogPersister;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.trace.TraceContext;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.log.entity.OperationLog;
import com.enterprise.ticket.module.log.mapper.OperationLogMapper;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 操作日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog>
        implements OperationLogService {

    /** 独立审计 Logger，输出到 ticket-system-audit.log，便于合规检查 */
    private static final org.slf4j.Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private final AuditLogPersister persister;
    private final AuditLogAsyncWriter asyncWriter;

    /**
     * 注意：使用 REQUIRES_NEW —— 高风险审计记录必须独立提交，
     * 即使外层业务事务回滚，审计痕迹也必须保留。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long operatorId, String operatorName, String module, String action,
                       String details, boolean success, RiskLevel risk) {
        doRecord(operatorId, operatorName, module, action, details, success, risk);
    }

    /**
     * 注意：本方法<strong>必须自行声明</strong> {@code REQUIRES_NEW}。
     * 若改为内部 {@code this.record(...)} 复用逻辑，自调用不经过 Spring 代理，
     * 事务注解会静默失效（审计记录将被并入外层业务事务，随业务回滚一起丢失）。
     * 因此把公共逻辑抽到私有 {@link #doRecord}，由两个 public 方法各自经代理进入。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordCurrent(String module, String action, String details, boolean success, RiskLevel risk) {
        LoginUser current = SecurityUtils.getCurrentUser();
        doRecord(current == null ? null : current.getId(),
                current == null ? null : current.getUsername(),
                module, action, details, success, risk);
    }

    private void doRecord(Long operatorId, String operatorName, String module, String action,
                          String details, boolean success, RiskLevel risk) {
        OperationLog entity = buildEntity(operatorId, operatorName, module, action, details, success, risk);
        writeAuditLine(entity);

        if (risk == RiskLevel.HIGH) {
            // 高风险操作：同步落库，写库失败直接抛异常，保证审计记录不丢
            persister.persist(entity);
        } else {
            asyncWriter.write(entity);
        }
    }

    @Override
    public int cleanExpired(int retentionDays) {
        LocalDateTime deadline = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        return baseMapper.delete(Wrappers.<OperationLog>lambdaQuery()
                .lt(OperationLog::getOperationTime, deadline));
    }

    private OperationLog buildEntity(Long operatorId, String operatorName, String module, String action,
                                     String details, boolean success, RiskLevel risk) {
        OperationLog entity = new OperationLog();
        entity.setOperatorId(operatorId);
        entity.setOperatorName(operatorName);
        entity.setOperationTime(LocalDateTime.now());
        entity.setModule(module);
        entity.setAction(action);
        entity.setDetails(details);
        entity.setResult(success ? "SUCCESS" : "FAILED");
        entity.setRiskLevel(risk.name());
        entity.setIp(SecurityUtils.getClientIp());
        entity.setTraceId(TraceContext.getTraceId());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private void writeAuditLine(OperationLog entity) {
        AUDIT.info("module={} action={} operator={}({}) result={} risk={} ip={} detail={}",
                entity.getModule(), entity.getAction(),
                entity.getOperatorName(), entity.getOperatorId(),
                entity.getResult(), entity.getRiskLevel(),
                entity.getIp(), entity.getDetails());
    }
}
