package com.enterprise.ticket.common.constant;

/**
 * 导出任务状态
 *
 * <p>状态流转是单向的，没有回退：
 * <pre>
 *   同步导出：写库即 SUCCESS（同一事务内完成，对外不存在中间态）
 *   异步导出：PENDING → RUNNING → SUCCESS
 *                          ↘ FAILED（失败原因落 error_message，用户可在列表看到）
 * </pre>
 *
 * <p>为什么同步导出不写 RUNNING：同步路径在同一个请求里完成生成与回填，
 * 中间态对外不可见；引入 RUNNING 只会让「文件已就绪但状态还停在 RUNNING」这种
 * 异常态多一种可能。RUNNING 只服务异步路径 —— 它的作用是让运维能区分
 * 「排队中」与「正在生成」。
 */
public enum ExportStatus {

    /** 待生成（异步任务已受理，尚未开始） */
    PENDING("待生成"),

    /** 生成中（异步线程已取到任务） */
    RUNNING("生成中"),

    /** 已完成（文件已落盘，可下载） */
    SUCCESS("已完成"),

    /** 生成失败（原因见 error_message） */
    FAILED("生成失败");

    private final String label;

    ExportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ExportStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (ExportStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        ExportStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
