package com.enterprise.ticket.module.applytype.dto.vo;

import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.form.FormSchema;
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
}
