package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 催办记录视图（；规范 V1.1 未覆盖）
 *
 * <p>供工单详情页时间线展示（需求方  「工单详情：时间线增加转交记录、催办记录」）。
 */
@Data
public class OrderUrgeVO {

    private Long id;

    private Long orderId;

    private String orderNo;

    /** APPROVAL 审批催办 / RETURN 归还催办 */
    private String urgeType;

    private String urgeTypeLabel;

    /** 审批催办锁定的节点 id（归还催办为空） */
    private Long nodeId;

    /** 审批催办时该节点是第几步（归还催办为空），让时间线能显示「已催办第 2 步审批人」 */
    private Integer nodeStepOrder;

    /** 被催办人（审批催办＝审批人；归还催办＝借用人） */
    private Long targetUserId;

    private String targetUserName;

    /** 催办发起人（审批催办＝申请人；归还催办＝实际执行人） */
    private Long operatorId;

    private String operatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
