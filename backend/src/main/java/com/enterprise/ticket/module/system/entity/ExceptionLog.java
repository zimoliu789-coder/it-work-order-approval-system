package com.enterprise.ticket.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 未预期异常日志。
 *
 * <p>与 {@code operation_log} 的区别是**记录对象不同**：操作日志记「谁在什么时候做了什么」，
 * 本表记「系统在什么时候因为什么坏了」。两者的检索维度、保留期、告警语义都不同，
 * 混在一张表里会互相污染（例如「全部工单」页的日志筛选会被异常行塞满）。
 */
@Data
@TableName("exception_log")
public class ExceptionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求链路 id，与响应体里的 traceId 同源 —— 用户报错时凭它一次串起前后端 */
    private String traceId;

    /** 分类枚举名：DATABASE / NETWORK / THIRD_PARTY / PARAM / BUSINESS / UNKNOWN */
    private String category;

    /** 分级短码：P0 / P1 / P2 */
    private String severity;

    /** 来源模块标识（由记录方传入，如 GLOBAL / ORDER / DEVICE） */
    private String module;

    private String exceptionClass;

    /** 异常消息（已截断到 {@code ExceptionClassifier#MAX_MESSAGE_LENGTH}） */
    private String message;

    /**
     * 异常堆栈（截断到 4000 字符）。
     *
     * <p>说明要「记录**详细**的异常日志」—— 只留一行消息是不够的，
     * 真正定位问题靠的是堆栈。截断而不是全存：一个深栈能到几十 KB，
     * 一次事故上万条就会把表撑爆，而前 4000 字符已经覆盖到业务代码的调用点。
     */
    private String stackTrace;

    /** 同类指纹（去重合并键），32 位 MD5 */
    private String stackDigest;

    private String requestUri;

    private String httpMethod;

    /** 触发用户 id；匿名请求为 null（刻意不加外键，见迁移脚本注释） */
    private Long userId;

    private String ip;

    /** 发生时间（毫秒精度，静默期判定依赖它） */
    private LocalDateTime occurredAt;

    /** PENDING 待汇总 / SENT 已告警 / SUPPRESSED 静默期内不重复告警 */
    private String alertState;

    private LocalDateTime alertedAt;

    private LocalDateTime createdAt;
}
