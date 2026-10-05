package com.enterprise.ticket.module.system.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常日志的「同类聚合行」（ 汇总任务的输入）。
 *
 * <p>汇总任务不能逐条读 PENDING 行再在内存里分组 —— 一次事故可能产生几万条，
 * 全量读进 JVM 是自找 OOM。因此聚合下推到数据库：按指纹 GROUP BY，
 * 只把「一类一行」的结果带回来。本类就是那一行的形状。
 */
@Data
public class ExceptionDigestRow {

    /** 同类指纹（聚合键） */
    private String digest;

    /** 该类的分类（同指纹必然同分类：分类由异常类推导，而异常类是指纹的一部分） */
    private String category;

    /** 该类的分级 */
    private String severity;

    private String exceptionClass;

    private String module;

    /** 该类最近一条的消息，作为摘要里的样例 */
    private String message;

    /** 该类最近一条的请求路径 */
    private String requestUri;

    /** 该类在本次汇总窗口内的发生次数 */
    private Long total;

    /** 首次发生时间 */
    private LocalDateTime firstAt;

    /** 最近一次发生时间 */
    private LocalDateTime lastAt;
}
