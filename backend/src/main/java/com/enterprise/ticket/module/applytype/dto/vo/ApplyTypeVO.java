package com.enterprise.ticket.module.applytype.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 申请类型视图
 *
 * <p>同一个 VO 服务两个场景：
 * <ul>
 *   <li>管理列表 / 详情：返回全部配置字段，供管理页编辑回填；</li>
 *   <li>提交页详情：额外带上 {@code schema}（见下）。</li>
 * </ul>
 * 刻意<b>不在列表中返回 {@code schema}</b>：字段定义可能几十个字段，
 * 列表一次展示几十条会把响应体撑大好几倍，而列表根本用不到。{@code schema} 仅详情接口返回。
 */
@Data
public class ApplyTypeVO {

    private Long id;

    private String typeCode;

    private String typeName;

    private String icon;

    private String description;

    private Integer sortOrder;

    /** ENABLED / DISABLED */
    private String status;

    private String statusLabel;

    private Long formTemplateVersionId;

    /** 关联模板名（便于管理列表直接展示「关联表单」列） */
    private String formTemplateName;

    /** 关联版本号 */
    private Integer formTemplateVersionNo;

    private String orderPrefix;

    /** NONE / GROUP / FLOW */
    private String approvalMode;

    private String approvalModeLabel;

    /** 绑定的审批流程版本 id（仅 approvalMode = FLOW 时非空） */
    private Long approvalFlowVersionId;

    /** 流程可读名（如「采购审批 v2」），管理列表展示用 */
    private String approvalFlowName;

    /** ALL / ROLE / GROUP */
    private String submitPermissionType;

    private String submitPermissionTypeLabel;

    /** 提交权限值原样（ROLE = 角色码；GROUP = 分组 id 的字符串形式） */
    private List<String> submitPermissionValues;

    /** 提交权限的可读文案（管理列表展示，如「角色：admin、user」/「全部」） */
    private String submitPermissionText;

    /** 是否已被工单使用（决定能否删除：用过只能停用） */
    private Boolean usedByOrder;

    /** 已被多少笔工单使用（管理列表提示用） */
    private Long orderCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 表单定义。
     *
     * <p>仅 {@code GET /api/apply-types/{id}} 详情接口返回；列表、启用列表一律为 {@code null}。
     * 提交页据此渲染动态表单 —— 普通员工没有 {@code form_template:view} 权限，
     * 因此不能走表单模板接口取 schema，必须由申请类型接口随详情下发。
     */
    private com.enterprise.ticket.common.form.FormSchema schema;
}
