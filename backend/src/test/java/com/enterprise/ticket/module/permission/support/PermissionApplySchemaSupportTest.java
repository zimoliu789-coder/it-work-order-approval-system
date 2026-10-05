package com.enterprise.ticket.module.permission.support;

import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限申请表单选项对齐单测（P1 安全修复）。
 *
 * <p>要守的两条口径：
 * <ol>
 *   <li><b>提权类不可选</b>：{@code system:upgrade:view / execute} 等「策略标不可申请」的码
 *       不得出现在表单选项里 —— 否则申请人能选中、提交、走完审批却**静默不授权**；</li>
 *   <li><b>可申请类必须可选</b>：策略标「可申请」的码（含 P4-C3 新增的 {@code security:view}）
 *       必须出现在选项里 —— 否则管理员配好的可申请范围形同虚设。</li>
 * </ol>
 */
class PermissionApplySchemaSupportTest {

    private static FormOption option(String value, String label) {
        FormOption option = new FormOption();
        option.setValue(value);
        option.setLabel(label);
        return option;
    }

    private static FormField field(String key, List<FormOption> options) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(key);
        field.setType("MULTI_SELECT");
        field.setOptions(options == null ? null : new ArrayList<>(options));
        return field;
    }

    private static FormSchema schemaOf(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    @Test
    @DisplayName("hasPermissionField：只有带 permissionCodes 的 schema 才算权限申请表单")
    void hasPermissionField() {
        FormSchema permission = schemaOf(field("permissionCodes", List.of(option("a", "A"))));
        FormSchema other = schemaOf(field("reason", List.of()));
        assertTrue(PermissionApplySchemaSupport.hasPermissionField(permission));
        assertFalse(PermissionApplySchemaSupport.hasPermissionField(other));
        assertFalse(PermissionApplySchemaSupport.hasPermissionField(null));
    }

    @Test
    @DisplayName("选项按当前策略重建：提权类被排除、P4-C3 新权限被包含")
    void rebuildsOptionsByPolicy() {
        FormSchema schema = schemaOf(
                field("permissionCodes", List.of(
                        option(PermissionCatalog.UPGRADE_VIEW, "在线升级查看"),
                        option(PermissionCatalog.UPGRADE_EXECUTE, "在线升级执行"),
                        option(PermissionCatalog.DEVICE_LEDGER_VIEW, "设备台账查看"))),
                field("reason", List.of()));

        // 当前策略：允许 device:ledger:view 与 security:view（后者原表单里根本没有）
        Set<String> applicable = Set.of(PermissionCatalog.DEVICE_LEDGER_VIEW, PermissionCatalog.SECURITY_VIEW);
        FormSchema decorated = PermissionApplySchemaSupport.withApplicableOptions(schema, applicable);

        List<String> values = decorated.getFields().stream()
                .filter(f -> "permissionCodes".equals(f.getKey()))
                .findFirst().orElseThrow().getOptions().stream()
                .map(FormOption::getValue).toList();

        assertFalse(values.contains(PermissionCatalog.UPGRADE_VIEW), "在线升级查看必须不可选");
        assertFalse(values.contains(PermissionCatalog.UPGRADE_EXECUTE), "在线升级执行必须不可选");
        assertTrue(values.contains(PermissionCatalog.DEVICE_LEDGER_VIEW), "策略可申请的码必须可选");
        assertTrue(values.contains(PermissionCatalog.SECURITY_VIEW), "P4-C3 新增权限必须出现在选项里");
    }

    @Test
    @DisplayName("非权限申请表单原样返回（同一实例），不产生额外改动")
    void leavesOtherSchemaUntouched() {
        FormSchema other = schemaOf(field("reason", List.of()));
        assertSame(other, PermissionApplySchemaSupport.withApplicableOptions(other, Set.of("x")));
    }

    @Test
    @DisplayName("重建不修改传入对象（无副作用），且其它字段保持不变")
    void doesNotMutateInput() {
        FormField codes = field("permissionCodes", List.of(option(PermissionCatalog.UPGRADE_EXECUTE, "在线升级执行")));
        FormSchema schema = schemaOf(codes, field("reason", List.of()));

        FormSchema decorated = PermissionApplySchemaSupport.withApplicableOptions(
                schema, Set.of(PermissionCatalog.DEVICE_LEDGER_VIEW));

        assertTrue(codes.getOptions().stream()
                        .anyMatch(o -> PermissionCatalog.UPGRADE_EXECUTE.equals(o.getValue())),
                "传入的字段对象不应被就地修改");
        assertEquals(2, decorated.getFields().size());
        assertEquals("reason", decorated.getFields().get(1).getKey());
    }

    @Test
    @DisplayName("可申请集合为 null 时退化为空选项，不抛异常")
    void nullApplicableCodes() {
        FormSchema schema = schemaOf(field("permissionCodes", List.of(option("a", "A"))));
        FormSchema decorated = PermissionApplySchemaSupport.withApplicableOptions(schema, null);
        assertTrue(decorated.getFields().get(0).getOptions().isEmpty());
    }
}
