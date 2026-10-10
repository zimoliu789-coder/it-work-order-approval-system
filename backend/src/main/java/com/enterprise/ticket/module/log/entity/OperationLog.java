package com.enterprise.ticket.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 user_id，匿名操作为 null */
    private Long operatorId;

    /** 操作人姓名冗余，便于日志追溯 */
    private String operatorName;

    private LocalDateTime operationTime;

    /** 模块：AUTH / USER / DEVICE / ORDER ... */
    private String module;

    /** 动作：LOGIN / CHANGE_PASSWORD ... */
    private String action;

    /** 操作详情（禁止写入密码明文） */
    private String details;

    /** SUCCESS / FAILED */
    private String result;

    /** HIGH / NORMAL */
    private String riskLevel;

    private String ip;

    private String traceId;

    private LocalDateTime createdAt;
}
