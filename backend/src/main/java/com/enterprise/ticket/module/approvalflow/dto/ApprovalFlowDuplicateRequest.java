package com.enterprise.ticket.module.approvalflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 流程模板「另存为 / 复制」请求（ · W4-B）。
 *
 * <h2>为什么只有名称与编码，没有 definition</h2>
 * <p>复制的内容**来自源模板**（当前草稿优先，无草稿取最新已发布版本），调用方不需要、也不应该
 * 把定义再传一遍 —— 否则就不是"另存为"，而是"用别人的名字新建"。入参只保留"新资产叫什么"。
 *
 * <h2>为什么 {@code flowCode} 是可选的</h2>
 * <p>方案原文写的是「入参 {@code {name}}」。实现时补了一个可选的 {@code flowCode}，原因是
 * <b>流程编码在既有设计里创建后不可修改</b>：{@code designer.vue} 把编码输入框置为
 * {@code disabled}，用户此后没有任何机会改它。若复制时不给用户指定编码的机会，
 * 编码就会被服务端派生结果永久钉死；而"复制"这个动作恰恰最可能连着做多次
 * （一次复制出多份分头改），派生名会变成 {@code XXX_COPY} / {@code XXX_COPY2} …
 * 让用户无从分辨哪份是哪份。因此：
 * <ul>
 *   <li>前端对话框**预填**「{原名} 副本」与「{原编码}_COPY」，两个字段都可改；</li>
 *   <li>留空时由服务端按源编码派生（{@code {源编码}_COPY}，冲突则 {@code _COPY2} / {@code _COPY3} …），
 *       保证程序化调用方（脚本 / 测试）不必自己规避唯一约束。</li>
 * </ul>
 *
 * <p>重名 / 重码一律**沿用新建流程的既有规则与错误码**（{@code FLOW_CODE_INVALID} /
 * {@code FLOW_CODE_EXISTS}），不新增错误码 —— 两个入口对同一件事给出同一种提示，
 * 用户才不会觉得"复制比新建更严格"。
 */
@Data
public class ApprovalFlowDuplicateRequest {

    /** 新流程名称（必填） */
    @NotBlank(message = "流程名称不能为空")
    @Size(max = 64, message = "流程名称不能超过 64 个字符")
    private String flowName;

    /**
     * 新流程编码（选填）。
     *
     * <p>留空 = 服务端按源编码派生。格式与唯一性校验与新建流程完全一致。
     */
    private String flowCode;

    /**
     * 新流程说明（选填）。
     *
     * <p>留空时**沿用源流程的说明**（说明属于模板内容，随内容一起复制；而申请类型 / 部门
     * 那些"指向源模板的绑定关系"则不复制）。传了非空值就以传入值为准。
     *
     * <p>长度上限对齐库列 {@code VARCHAR(255)}：新端点在服务端就把话说完，
     * 而不是让一句超长说明变成一个看不懂的 500。
     * （{@code ApprovalFlowSaveRequest} 目前两者都没有 —— 那是既有端点的行为，
     * 本轮不动它，收敛已挂账。）
     */
    @Size(max = 255, message = "流程说明不能超过 255 个字符")
    private String description;
}
