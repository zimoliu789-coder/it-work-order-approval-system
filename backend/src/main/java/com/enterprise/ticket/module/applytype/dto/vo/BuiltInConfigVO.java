package com.enterprise.ticket.module.applytype.dto.vo;

import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.device.dto.vo.FaultRepairHandlerConfigVO;
import lombok.Data;

/**
 * 系统内置申请类型的配置面板数据（V48）
 *
 * <h2>为什么一个 GET 就返回这么一坨</h2>
 * 「配置」弹窗需要同时知道四件事：这个内置类型的说明、它的默认 / 当前流程长什么样、
 * 已绑流程的模板 id（改完要 update + publish，需要模板而不是版本）、
 * 以及条件分支能引用的字段有哪些。
 * 拆成四个接口会让弹窗打开时打四个请求、并自己在客户端拼状态；
 * 这里一次性算好下发 —— 与服务端已有的 {@code /apply-types/{id}/flow-preview} 同一取向。
 */
@Data
public class BuiltInConfigVO {

    private Long id;

    /** 类型编码（BORROW / FAULT_REPORT）—— 前端据此决定「内置表单说明」的文案与流程区是否可编辑 */
    private String typeCode;

    private String typeName;

    private String description;

    /** 恒为 true（本接口只服务内置类型），显式下发便于前端断言 */
    private boolean builtIn;

    /** 审批流程是否可编辑；故障报修为 false（它不产生工单，域模型里没有审批环节） */
    private boolean flowEditable;

    /**
     * 是否有「处理流程」（V50）：报修为 true。
     *
     * <p>与 {@link #flowEditable} 并列而不是互相推导 —— 它们回答的是两个问题：
     * 「能改审批流吗」与「有处理流吗」。故障报修恰好是 {@code false / true} 这个组合，
     * 页面必须能同时知道这两件事，才能既隐藏审批流编辑器、又渲染出处理流程配置区。
     */
    private boolean hasHandler;

    /** 「为什么流程区不可编辑 / 为什么表单不可改」的一句话说明，直接展示给管理员 */
    private String formNote;

    /**
     * 是否已绑定**自定义**流程。
     *
     * <p>{@code false} 时 {@link #flowDefinition} 给的是该内置类型的**默认流程**
     * （借用设备 = 系统预置三级流程）—— 管理员看到的是「现在实际会走什么」，
     * 而不是一张空白画布。这一点很关键：若给空白，管理员会以为当前「没有流程」。
     */
    private boolean flowBound;

    /** 已绑流程版本 id；未绑定时为 null */
    private Long approvalFlowVersionId;

    /**
     * 已绑流程对应的**模板** id。
     *
     * <p>保存时要在模板上「改草稿 → 发布新版本」，而模板级操作需要模板 id；
     * 版本 → 模板的反查在服务端做（前端不该知道 {@code approval_flow_version} 的存在）。
     */
    private Long flowTemplateId;

    private String flowCode;

    private String flowName;

    /** 供流程设计器使用的初始定义：已绑定时为当前版本定义，未绑定时为默认流程 */
    private FlowDefinition flowDefinition;

    /** 条件分支可引用的字段清单（借用域字段目录）；无流程可配时为 null */
    private FormSchema fieldSchema;

    /**
     * 内置类型当前绑定的表单模板版本 id（V59 / D3「表单完全放开」）。
     *
     * <p>改造前内置类型没有表单模板，这一项恒为 null，面板上也因此没有表单编辑区。
     * 现在借用 / 报修都播下了真实模板，管理员要能改它们的字段 ——
     * {@link #formSchema} 就是那一版的字段定义（快照语义：读的是**绑定的版本**，
     * 不是模板的最新草稿）。
     */
    private Long formTemplateVersionId;

    /** 已绑定表单版本的 schema（字段清单）；未绑定时为 null */
    private FormSchema formSchema;

    /**
     * 表单模板 id（V59）。
     *
     * <p>与 {@link #flowTemplateId} 同一理由：改表单要走「模板改草稿 → 发布新版本」，
     * 而模板级操作需要模板 id。版本 → 模板的反查在服务端做。
     */
    private Long formTemplateId;

    /**
     * 「处理流程」配置（V50）：通知给谁 / 实际会通知到几个人 / 三个下拉的候选。
     *
     * <p>只有 {@link #hasHandler} 为 true 的内置类型（当前即「故障报修」）才有这一块，
     * 其余为 {@code null} —— 用 {@code null} 而不是空对象，是为了让页面能用
     * {@code v-if="cfg.handler"} 直接决定整块配置区是否渲染，
     * 而不是去猜「字段都在但值为空」到底表示「没有这一项」还是「有但没配」。
     */
    private FaultRepairHandlerConfigVO handler;
}
