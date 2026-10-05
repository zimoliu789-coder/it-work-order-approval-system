package com.enterprise.ticket.module.permission.support;

import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.permission.service.PermissionGrantOnApprovalService;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 「系统权限申请」表单的可选项对齐（P1 安全修复）。
 *
 * <h2>要修的缺陷</h2>
 * <p> 播种时把 {@code permissionCodes} 的选项**固化**进了表单模板版本，而  又引入了
 * {@code permission_apply_policy} 作为「哪些权限可以申请」的事实源 —— 两者是**两套事实源**，
 * 于是出现：
 * <ul>
 *   <li>策略标「不可申请」的提权类（{@code system:upgrade:view / execute}）在表单里**仍可勾选**，
 *       提交后走到审批终点却**静默不授权**；</li>
 *   <li> 新增的 {@code security:view} / {@code exception:view} 标了「可申请」却在表单里**选不到**。</li>
 * </ul>
 *
 * <h2>修法</h2>
 * <p>把「申请表单下发给申请人时的 {@code permissionCodes} 选项」改为**按当前策略动态生成** ——
 * 与角色页的「可申请权限配置」永远同源。选项顺序取权限目录顺序（稳定，与角色页一致）。
 *
 * <p>本类只改「选项」，不改字段的其它属性（必填 / 类型 / 宽度）。字段的**原始定义**仍完整保留在
 * 表单模板版本里，因此「管理员在高级表单里看到的模板」与「申请人实际看到的选项」在结构上一致，
 * 差异仅在选项集合 —— 那正是策略驱动的部分。
 *
 * <h2>⚠️ 前置条件</h2>
 * <p>传入的 {@code schema} 必须是**一次性反序列化**出来的对象（{@code requirePublishedSchema}
 * 每次调用都会重新解析 JSON，满足该前提）；本类会新建 {@link FormSchema} 与权限字段的副本，
 * 不修改传入对象，因此即便上游改为缓存也安全。
 */
public final class PermissionApplySchemaSupport {

    private PermissionApplySchemaSupport() {
    }

    /** 权限申请表单里的字段 key（与  播种、 授权读取三处逐字一致） */
    public static final String FIELD_PERMISSION_CODES = PermissionGrantOnApprovalService.FIELD_PERMISSION_CODES;

    /** schema 是否含「申请开通的权限」字段（= 这是权限申请表单） */
    public static boolean hasPermissionField(FormSchema schema) {
        if (schema == null) {
            return false;
        }
        for (FormField field : schema.getFields()) {
            if (field != null && FIELD_PERMISSION_CODES.equals(field.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用当前**可申请**的权限码重建 {@code permissionCodes} 的选项。
     *
     * <p>不含该字段的 schema（普通自定义申请）原样返回，调用方可无条件调用本方法。
     *
     * @param schema           表单定义（一次性反序列化对象）
     * @param applicableCodes  当前可申请的权限码集合（来自 {@code PermissionApplyPolicyService#applicableCodes()}）
     */
    public static FormSchema withApplicableOptions(FormSchema schema, Collection<String> applicableCodes) {
        if (!hasPermissionField(schema)) {
            return schema;
        }
        Set<String> allowed = applicableCodes == null ? Set.of() : new LinkedHashSet<>(applicableCodes);
        List<FormOption> options = buildOptions(allowed);

        FormSchema result = new FormSchema();
        List<FormField> fields = new ArrayList<>();
        for (FormField field : schema.getFields()) {
            if (field != null && FIELD_PERMISSION_CODES.equals(field.getKey())) {
                FormField copy = new FormField();
                BeanUtils.copyProperties(field, copy);
                copy.setOptions(options);
                copy.setHelp("可多选，仅显示当前开放申请的权限。含高危权限时会自动多走一级超管审批。");
                fields.add(copy);
            } else {
                fields.add(field);
            }
        }
        result.setFields(fields);
        return result;
    }

    /**
     * 选项 = 权限目录顺序 ∩ 可申请集合。
     *
     * <p>目录顺序稳定（与角色页的勾选树一致），避免「同一份集合在不同页面里的排列不同」。
     * 末尾补上「可申请集合里有、但目录里没有」的码（理论上不会出现）——
     * 宁可多显示一项，也不静默丢一个可申请的权限。
     */
    private static List<FormOption> buildOptions(Set<String> allowed) {
        List<FormOption> options = new ArrayList<>();
        for (String code : PermissionCatalog.allCodeList()) {
            if (allowed.contains(code)) {
                options.add(option(code));
            }
        }
        for (String code : allowed) {
            boolean present = options.stream().anyMatch(o -> Objects.equals(o.getValue(), code));
            if (!present) {
                options.add(option(code));
            }
        }
        return options;
    }

    private static FormOption option(String code) {
        FormOption option = new FormOption();
        option.setValue(code);
        option.setLabel(PermissionCatalog.nameOf(code));
        return option;
    }
}
