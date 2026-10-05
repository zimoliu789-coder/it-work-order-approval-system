package com.enterprise.ticket.module.applytype.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自定义申请类型
 *
 * <h2>它是「自定义申请」这件事的全部配置</h2>
 * <p>一条 {@code apply_type} 回答四个问题：
 * <ol>
 *   <li><b>叫什么</b>：{@code typeCode} / {@code typeName} / {@code icon} / {@code description}；</li>
 *   <li><b>填什么</b>：{@code formTemplateVersionId} 指向一份<b>已发布</b>的表单版本；</li>
 *   <li><b>怎么走</b>：{@code approvalMode}（无审批 / 分组审批）、{@code orderPrefix}（工单号前缀）；</li>
 *   <li><b>谁能提</b>：{@code submitPermissionType} + {@code submitPermissionValue}。</li>
 * </ol>
 *
 * <h2>为什么指向「版本」而不是「模板」</h2>
 * <p>模板是可变的（可以继续发新版本），而申请类型一旦被工单使用，它当时的表单口径
 * 就必须被固化下来。若指向模板，模板发了 v2 之后，<b>历史工单的详情页会跟着变成 v2 的样子</b> ——
 * 这直接违背快照语义。指向具体版本后，「这个类型当初用的是哪一版表单」
 * 是数据里写死的事实。
 *
 * <h2>{@code submitPermissionValue} 为什么存字符串数组</h2>
 * <p>它在 ROLE 模式下是角色码（如 {@code ["admin","user"]}），在 GROUP 模式下是分组 id。
 * 两种模式的值类型不同，若用 {@code List<Long>} 或 {@code List<String>} 强约束某一侧，
 * 另一侧就要做特殊转换。统一按「字符串数组」存（GROUP 模式下存数字的字符串形式），
 * 读取时按 {@code submitPermissionType} 解释 —— 转换只发生在服务层一处。
 */
@Data
@TableName("apply_type")
public class ApplyType {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 类型编码（全局唯一，字母开头） */
    private String typeCode;

    /** 类型名称 */
    private String typeName;

    /** 图标（Element Plus 图标组件名） */
    private String icon;

    /** 类型说明 */
    private String description;

    /** 排序号（升序） */
    private Integer sortOrder;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 关联的表单模板版本（必须为已发布版本） */
    private Long formTemplateVersionId;

    /** 工单编号前缀（可空；空则用系统默认规则） */
    private String orderPrefix;

    /** 审批方式：NONE 无审批 / GROUP 走分组审批流 / FLOW 使用独立审批流程模板 */
    private String approvalMode;
    /** 绑定的已发布审批流程版本 approval_flow_version.id；NULL = 不使用独立流程 */
    private Long approvalFlowVersionId;

    /** 提交权限类型：ALL / ROLE / GROUP */
    private String submitPermissionType;

    /** 提交权限值 JSON 数组（ROLE = 角色码；GROUP = 分组 id；ALL = 空数组） */
    private String submitPermissionValue;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
