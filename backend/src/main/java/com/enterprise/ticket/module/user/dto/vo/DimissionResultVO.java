package com.enterprise.ticket.module.user.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 标记离职 / 恢复在职的处理结果（，）
 *
 * <p>返回被影响的工单明细，让管理员在操作后能立刻核对「系统到底动了哪几笔工单」，
 * 而不是只看到一个成功提示。数据量受限于单个员工名下的在办工单数，不会造成响应体膨胀。
 */
@Data
public class DimissionResultVO {

    private Long userId;

    private String displayName;

    /** 因离职被自动转入「待收回」的工单数量 */
    private Integer orderCount;

    /** 被回收的设备数量（与工单一一对应） */
    private Integer deviceCount;

    /** 被转入「待收回」的工单编号，便于管理员抄送给实际执行人 */
    private List<String> orderNos;

    /** 该员工仍待其审批的在途节点数量（>0 时需管理员手工改派， 未要求自动改派） */
    private Integer pendingApprovalCount;

    /**
     * ：其名下「在办」工单中被<b>自动转交</b>给同组在职成员的笔数
     *
     * <p>覆盖该员工作为 {@code actual_final_handler_id} 的工单（待交付 / 使用中 / 待收回），
     * 解决  遗留的「执行人离职后待办无人处理」限制（需求方  ）。
     */
    private Integer transferredOrderCount;

    /**
     * ：因<b>小组内无其他在职成员</b>而未能自动转交的笔数（>0 时需管理员手工处理）
     *
     * <p>刻意不计入失败：这类工单保留原执行人，由管理员介入，不阻断离职流程本身。
     */
    private Integer transferSkippedCount;

    /** 一句话结果说明，前端可直接展示 */
    private String message;
}
