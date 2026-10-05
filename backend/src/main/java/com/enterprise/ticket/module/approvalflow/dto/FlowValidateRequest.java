package com.enterprise.ticket.module.approvalflow.dto;

import com.enterprise.ticket.common.flow.FlowDefinition;
import lombok.Data;

/**
 * 流程定义校验请求（M4a）。
 *
 * <p>用于「保存草稿 / 发布」前的**服务端权威校验**：前端把当前设计器里的定义发过来，
 * 服务端返回**全部**问题清单，前端据此提示并决定是否放行发布。
 *
 * <p>为什么需要一个这样"不落库"的端点：原先前端持有一份 {@code validateFlowForPublish}
 * 的规则镜像，与后端 {@code FlowDefinitionValidator} 各自演化，且后端首错即抛、
 * 前端返回全部问题，两侧口径不一致（后端第 2 个问题前端永远看不到）。
 * 有了这个端点，前端发布**以后端结果为准**，本地镜像只做「即时预检」，漂移被根治。
 *
 * <p>{@code formVersionId} 可空：借用单等**无动态表单**的场景传空，
 * 此时条件字段的存在性校验被跳过——与发布路径 {@code validate(definition, null)} 完全同源。
 */
@Data
public class FlowValidateRequest {

    /** 待校验的流程定义（草稿亦可，允许不完整） */
    private FlowDefinition definition;

    /** 绑定的表单模板版本 id；为空表示无表单上下文（如借用单流程） */
    private Long formVersionId;

    /**
     * 业务域（M1）：{@code CUSTOM}（缺省）或 {@code BORROW}。
     *
     * <p>为什么必须让调用方指定：M1 之后同一个设计器既要产出自定义表单流程，也要产出
     * 借用单流程，而两者的**禁用规则集不同**（借用域不支持表单人员字段 / 申请人自选）。
     * 若不传，发布借用流程时预检会说「通过」、真正发布时才被拦下 ——
     * 这恰好是 M4a 要消灭的「预检与发布口径不一致」。
     *
     * <p>缺省（{@code null}）按 {@code CUSTOM} 处理，既有调用点行为逐字节不变。
     */
    private String scope;
}
