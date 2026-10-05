package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 借用单审批路径预览（ 迁入 order 模块）。
 *
 * <h2>它为什么从 {@code bizgroup.dto.vo} 搬到这里</h2>
 * <p>原实现挂在「业务分组」模块下，而业务分组已被部门体系取代（V31）。
 * 预览回答的是「这笔借用会走哪几级审批」，本质是**工单**的能力，
 * 因此随 {@code OrderService#previewBorrowFlow} 一起搬到 order 模块 ——
 * 否则 order 模块要反向依赖一个已退役的模块。
 *
 * <h2>契约（与前端 {@code types/department.ts} 的 {@code BorrowFlowPreview} 逐字对应）</h2>
 * <p>{@link #bound} 为 {@code false} 时前端**不展示**路径块。
 *  之后这**只在异常路径上出现**（部门绑定的流程版本被删/被下线）——
 * 未绑流程的部门改走系统预置流程，同样会返回一条真实路径，
 * 只是 {@link #flowVersionLabel} 是「设备借用审批流程（系统预置）」而不是「XX v3」。
 */
@Data
public class BorrowFlowPreviewVO {

    /** 是否解析出了真实路径；{@code false} 时前端不展示路径块 */
    private boolean bound;

    /** 路径来源的展示名（如「借用审批 v3」/「设备借用审批流程（系统预置）」）；未解析出时为 {@code null} */
    private String flowVersionLabel;

    /** 命中路径上的节点（按 stepOrder 升序） */
    private List<PreviewNode> nodes = new ArrayList<>();

    /** 本单不会经过的节点（条件分支未命中）—— 保留是为了让用户看到「为什么没走这一级」 */
    private List<PreviewNode> skippedNodes = new ArrayList<>();

    /**
     * 路径上的单个节点。
     *
     * <p>字段与 {@code FlowPathResolver.ResolvedNode} 一一对应，但**刻意不复用后者**：
     * 后者是流程内核的内部结构（带 {@code ApproverRule} 等实现细节），
     * 直接下发会把内核类型泄漏成 API 契约，日后改内核即破坏前端。
     */
    @Data
    public static class PreviewNode {

        /** 流程节点 key；未落库的临时节点为 {@code null} */
        private String nodeKey;

        private String nodeName;

        /** {@code APPROVAL} 审批 / {@code CC} 抄送 */
        private String nodeType;

        /** 已解析出的审批人姓名；为空表示将由超管兜底或提交后才确定 */
        private List<String> approverNames = new ArrayList<>();

        /** 「为什么走了这条分支」的说明；无条件路径上为空 */
        private String conditionDesc;

        /** 该节点的审批时限（小时）；{@code null} 表示不限时 */
        private Integer timeLimitHours;
    }
}
