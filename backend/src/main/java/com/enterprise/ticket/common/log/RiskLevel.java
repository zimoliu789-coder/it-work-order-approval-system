package com.enterprise.ticket.common.log;

/**
 * 审计日志风险级别
 *
 * <ul>
 *   <li>{@link #HIGH} —— 高风险操作，必须可靠、同步地写入审计日志（不允许异步）</li>
 *   <li>{@link #NORMAL} —— 普通访问日志，可异步处理</li>
 * </ul>
 */
public enum RiskLevel {

    /** 权限/角色/审批配置变更、密码重置、设备删除报废、归还确认、工单转交、附件下载、导入导出、强制解锁、超时转发 */
    HIGH,

    /** 普通操作 */
    NORMAL
}
