package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单自定义表单数据
 *
 * <p>与 {@code orders} <b>一对一</b>（{@code order_id} 唯一索引兜底）。
 *
 * <h2>为什么独立成表而不是给 orders 加 JSON 列</h2>
 * <ol>
 *   <li><b>列表性能</b>：工单列表（我的 / 待办 / 全部）不需要表单数据，
 *       放在 orders 宽表里会让每次列表查询都把几十 KB 的 JSON 拉回来；</li>
 *   <li><b>职责清晰</b>：orders 描述「工单走到哪一步」，本表描述「用户填了什么」。
 *       两者生命周期不同 —— 前者参与状态机流转，后者写入后基本不变；</li>
 *   <li><b>模板演进无影响</b>：字段定义随模板版本变化，宽表列无法预知。</li>
 * </ol>
 *
 * <p>{@code formTemplateVersionId} 在这里再存一份（而不是只靠 apply_type 关联）：
 * 申请类型可以被改（例如换绑到更新的表单版本），而「这笔工单当初填的是哪一版表单」
 * 必须固化。否则改一次申请类型，所有历史工单的回显都会跟着变。
 */
@Data
@TableName("order_form_data")
public class OrderFormData {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 orders.id（唯一） */
    private Long orderId;

    /** 填写时使用的表单模板版本 */
    private Long formTemplateVersionId;

    /** 用户填写的表单值 JSON：{字段key: 值} */
    private String formDataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
