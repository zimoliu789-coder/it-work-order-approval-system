package com.enterprise.ticket.module.log.dto.vo;

import com.enterprise.ticket.module.log.entity.OperationLog;
import com.enterprise.ticket.module.log.support.OperationLogLabels;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志视图（：中文化展示）
 *
 * <p>与实体 {@link OperationLog} 的区别：
 * <ul>
 *   <li>额外提供 {@code moduleLabel} / {@code actionLabel} / {@code resultLabel} / {@code riskLabel} 中文标签；</li>
 *   <li>{@code summary} 是<b>人类可读的描述</b>（列表「详情」列默认显示）；</li>
 *   <li>{@code details} 保留<b>原始技术详情</b>（类/方法/参数/耗时），仅在「查看详情」展开时显示。</li>
 * </ul>
 * 原始编码 {@code module} / {@code action} 仍然保留，供筛选与前后端对齐使用。
 */
@Data
public class OperationLogVO {

    private Long id;

    /** 操作人 user_id（匿名操作为空） */
    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    /** 模块编码（筛选用） */
    private String module;

    /** 模块中文名 */
    private String moduleLabel;

    /** 动作编码（筛选用） */
    private String action;

    /** 动作中文名 */
    private String actionLabel;

    /** 人类可读的详情摘要（列表默认展示；失败时含失败原因，不含堆栈） */
    private String summary;

    /** 原始技术详情（查看详情展开：类/方法/参数/耗时/错误） */
    private String details;

    private String result;

    private String resultLabel;

    private String riskLevel;

    private String riskLabel;

    private String ip;

    private String traceId;

    public static OperationLogVO of(OperationLog log) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(log.getId());
        vo.setOperatorId(log.getOperatorId());
        // 操作人姓名兜底：匿名操作（登录失败等）没有操作人，回退为「未知用户」
        vo.setOperatorName(log.getOperatorName() == null ? "未知用户" : log.getOperatorName());
        vo.setOperationTime(log.getOperationTime());
        vo.setModule(log.getModule());
        vo.setModuleLabel(OperationLogLabels.moduleLabel(log.getModule()));
        vo.setAction(log.getAction());
        vo.setActionLabel(OperationLogLabels.actionLabel(log.getAction()));
        vo.setSummary(OperationLogLabels.summary(log.getAction(), log.getDetails(), log.getResult()));
        vo.setDetails(log.getDetails());
        vo.setResult(log.getResult());
        vo.setResultLabel(OperationLogLabels.resultLabel(log.getResult()));
        vo.setRiskLevel(log.getRiskLevel());
        vo.setRiskLabel(OperationLogLabels.riskLabel(log.getRiskLevel()));
        vo.setIp(log.getIp());
        vo.setTraceId(log.getTraceId());
        return vo;
    }
}
