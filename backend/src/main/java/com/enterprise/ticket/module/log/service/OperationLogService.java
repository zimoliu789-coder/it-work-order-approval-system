package com.enterprise.ticket.module.log.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.log.entity.OperationLog;

/**
 * 操作日志服务
 *
 * <p>写入策略：
 * <ul>
 *   <li>{@link RiskLevel#HIGH} —— 同步写库，写库失败向上抛异常，保证审计不丢；</li>
 *   <li>{@link RiskLevel#NORMAL} —— 异步写库，失败仅记日志，不影响主流程。</li>
 * </ul>
 */
public interface OperationLogService extends IService<OperationLog> {

    /**
     * 记录一条审计日志
     *
     * @param operatorId   操作人 user_id，可为空（匿名）
     * @param operatorName 操作人姓名
     * @param module       模块
     * @param action       动作
     * @param details      详情（禁止写入密码明文）
     * @param success      是否成功
     * @param risk         风险级别
     */
    void record(Long operatorId, String operatorName, String module, String action,
                String details, boolean success, RiskLevel risk);

    /**
     * 记录当前登录用户的审计日志，自动填充 IP 与 traceId
     */
    void recordCurrent(String module, String action, String details, boolean success, RiskLevel risk);

    /**
     * 清理超过保留期的历史日志（ 默认保留 90 天）
     *
     * @return 删除条数
     */
    int cleanExpired(int retentionDays);
}
