package com.enterprise.ticket.common.api;

import lombok.Data;

import java.util.List;

/**
 * 批量操作的逐条结果（P3 批量操作）
 *
 * <h2>为什么返回「逐条结果」而不是一个成功/失败标记</h2>
 * 批量改 100 台设备时，用户最需要知道的不是「失败了」，而是<b>哪几台失败、为什么</b> ——
 * 否则他只能一台台去试。因此即使只失败 1 条，也要把失败的 id、展示名与原因原样返回，
 * 前端据此列出明细并让失败项保持选中，便于只重试那几条。
 *
 * <h2>为什么不做整批事务</h2>
 * 整批回滚的后果是「一台违规 ⇒ 全部白做」，而用户从「失败」两个字里**无从知道该改哪一台**。
 * 逐条独立处理后：合法的已经生效、非法的逐条给出原因，用户只需要处理剩下的几台。
 * 每条自身的写操作仍在其自己的事务里（事务边界清晰，符合 对明确事务边界的口径）。
 */
@Data
public class BatchResultVO {

    /** 去重后的总条目数 */
    private int total;

    /** 成功条数 */
    private int succeeded;

    /** 失败条数（= {@code failures.size()}） */
    private int failed;

    /** 失败明细 */
    private List<Failure> failures;

    public static BatchResultVO of(int total, int succeeded, List<Failure> failures) {
        BatchResultVO vo = new BatchResultVO();
        vo.setTotal(total);
        vo.setSucceeded(succeeded);
        vo.setFailures(failures == null ? List.of() : failures);
        vo.setFailed(vo.getFailures().size());
        return vo;
    }

    /** 单条失败明细 */
    @Data
    public static class Failure {

        /** 记录 id */
        private Long id;

        /** 展示名：设备用资产编号、员工用「姓名（登录名）」；记录已不存在时回落 {@code id=<n>} */
        private String name;

        /** 失败原因（取自业务异常的用户可读文案） */
        private String reason;
    }
}
