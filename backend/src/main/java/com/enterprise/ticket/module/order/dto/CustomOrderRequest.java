package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提交自定义申请请求
 *
 * <h2>为什么 {@code formData} 是「无类型 Map」而不是强类型 DTO</h2>
 * <p>表单字段由管理员在设计器里决定，编译期根本不存在对应的 Java 类型。
 * 这里用 {@code Map<String,Object>} 原样接收，再由
 * {@code FormDataValidator} 按「该申请类型绑定的那一版 schema」逐字段校验 ——
 * 类型安全来自 schema（运行期数据），而不是来自类定义（编译期常量）。
 *
 * <p>这也是本模块与项目其它模块最大的不同：其它接口的参数结构在代码里写死，
 * 这里的参数结构是<b>数据</b>。因此「校验不能省」这件事在这里格外重要 ——
 * 没有编译期类型兜底，只有校验器兜底。
 */
@Data
public class CustomOrderRequest {

    /** 申请类型 apply_type.id */
    @NotNull(message = "请选择申请类型")
    private Long applyTypeId;

    /** 表单数据：字段 key → 值（值可以是字符串 / 数字 / 数组） */
    private Map<String, Object> formData;

    /**
     * 申请人自选审批人（，仅 {@code approvalMode = FLOW} 且节点配置了
     * {@code APPLICANT_CHOOSE} 时使用）：流程节点 key → 所选审批人 user_id 列表。
     *
     * <h2>为什么单独一个字段，而不是塞进 formData</h2>
     * <p>它不是「表单字段」：不出现在表单设计器里、不参与条件求值、不落
     * {@code order_form_data}。它是<b>流程节点的入参</b>——同一个表单可以被多套流程复用，
     * 而「谁来审」属于流程，不属于表单。混进 formData 会让「表单数据」与「审批配置」
     * 两种生命周期完全不同的东西共用一份快照，日后必然互相污染。
     *
     * <h2>服务端必须二次校验</h2>
     * <p>前端会按 {@code POST /api/apply-types/{id}/flow-preview} 返回的范围渲染选择器，
     * 但那只是 UI 便利。直调提交接口即可绕过，所以提交时必须在服务端重新校验
     * 「人在可选范围内」「人数在 [min, max] 内」，否则 422 拒绝。
     */
    private Map<String, List<Long>> approverSelections;
}
