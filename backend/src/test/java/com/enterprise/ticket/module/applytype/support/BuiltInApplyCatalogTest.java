package com.enterprise.ticket.module.applytype.support;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.trip.BusinessTripRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置申请类型目录的契约固化（V53 出差申请 → V59 借用 / 报修也有 schema）。
 *
 * <h2>这组断言守住什么</h2>
 * 内置表单的字段 key 是<b>业务逻辑的读取依据</b>：{@code BusinessTripRules} 按固定 key
 * 读重叠校验与预填的数据；借用 / 报修的系统字段则由提交页按 key 映射回专用入参
 * （{@code OrderCreateRequest} / {@code DeviceFaultRequest}）。
 * schema 由本目录播种、key 常量定义在两侧 —— 两处只要漂移一个字母，
 * 对应逻辑就会以「读不到值」的方式**静默失效**（不报错、直接放行 / 直接丢值）。
 * 因此这里逐字断言 schema 的 key 与常量一致，并固化 systemField 标记（设计器禁删的依据）。
 */
class BuiltInApplyCatalogTest {

    @Test
    @DisplayName("内置编码清单包含 TRIP：清库白名单（ProductionInitCleaner）据此自动同源")
    void codesContainTrip() {
        List<String> codes = BuiltInApplyCatalog.codes();
        assertEquals(3, codes.size());
        assertTrue(codes.contains(BuiltInApplyCatalog.CODE_TRIP));
        assertTrue(codes.contains(BuiltInApplyCatalog.CODE_BORROW));
        assertTrue(codes.contains(BuiltInApplyCatalog.CODE_FAULT_REPORT));
    }

    @Test
    @DisplayName("出差申请元数据：GROUP 审批、可换流程、无处理流、sortOrder 居内置区末位")
    void tripMetadata() {
        BuiltInApplyCatalog.BuiltIn trip = BuiltInApplyCatalog.of(BuiltInApplyCatalog.CODE_TRIP);
        assertNotNull(trip);
        assertEquals("出差申请", trip.typeName());
        assertEquals(com.enterprise.ticket.common.constant.ApprovalMode.GROUP, trip.approvalMode());
        assertTrue(trip.flowEditable());
        assertTrue(!trip.hasHandler(), "出差走审批流，没有处理流程");
        assertEquals(12, trip.sortOrder());
    }

    @Test
    @DisplayName("★ 出差表单 schema：三个字段 key 与 BusinessTripRules 常量逐字一致且全为系统内置字段")
    void tripSchemaKeysMatchRulesConstants() {
        FormSchema schema = BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_TRIP);
        assertNotNull(schema);
        assertEquals(3, schema.getFields().size());

        FormField start = schema.getFields().get(0);
        assertEquals(BusinessTripRules.FIELD_START_DATE, start.getKey());
        assertEquals(FormFieldType.DATE.name(), start.getType());
        assertSystemField(start);

        FormField end = schema.getFields().get(1);
        assertEquals(BusinessTripRules.FIELD_END_DATE, end.getKey());
        assertEquals(FormFieldType.DATE.name(), end.getType());
        assertSystemField(end);

        FormField target = schema.getFields().get(2);
        assertEquals(BusinessTripRules.FIELD_TARGET_DEPT, target.getKey());
        assertEquals(FormFieldType.ROOT_DEPT.name(), target.getType());
        assertSystemField(target);

        // 三个字段都是重叠校验 / 预填的必经输入，缺一不可
        assertTrue(Boolean.TRUE.equals(start.getRequired()));
        assertTrue(Boolean.TRUE.equals(end.getRequired()));
        assertTrue(Boolean.TRUE.equals(target.getRequired()));
    }

    @Test
    @DisplayName("非内置编码没有 schema；内置三兄弟全都有（V59 / D3「表单完全放开」）")
    void borrowAndFaultNowHaveSchema() {
        assertNotNull(BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_BORROW),
                "借用单放开表单自定义后必须有种子 schema，否则 form_template_version_id 仍为 NULL");
        assertNotNull(BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_FAULT_REPORT));
        assertNotNull(BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_TRIP));
        assertNull(BuiltInApplyCatalog.formSchemaOf("SOMETHING_ELSE"));
    }

    @Test
    @DisplayName("★ 借用表单 schema：五个系统字段 key 稳定 + 类型正确（提交页按 key 映射回入参）")
    void borrowSchemaSystemFieldsAreStable() {
        FormSchema schema = BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_BORROW);
        assertNotNull(schema);

        FormField primary = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_PRIMARY_CATEGORY);
        assertEquals(FormFieldType.DEVICE_CATEGORY.name(), primary.getType());
        assertSystemField(primary);
        assertTrue(Boolean.TRUE.equals(primary.getRequired()), "一级分类是归类依据，必填");

        FormField secondary = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_SECONDARY_CATEGORY);
        assertEquals(FormFieldType.DEVICE_SUBCATEGORY.name(), secondary.getType());
        assertSystemField(secondary);

        FormField model = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_MODEL);
        assertEquals(FormFieldType.DEVICE_MODEL.name(), model.getType());
        assertSystemField(model);

        FormField division = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_DIVISION);
        assertEquals(FormFieldType.ROOT_DEPT.name(), division.getType());
        assertSystemField(division);

        FormField returnDate = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_RETURN_DATE);
        assertEquals(FormFieldType.DATE.name(), returnDate.getType());
        assertSystemField(returnDate);
        assertTrue(Boolean.TRUE.equals(returnDate.getRequired()), "归还日期是订单必列，必填");

        // 「用途」是唯一不设保护的自由字段：删了不影响任何链路
        FormField reason = byKey(schema, BuiltInApplyCatalog.BORROW_FIELD_REASON);
        assertEquals(FormFieldType.TEXTAREA.name(), reason.getType());
        assertTrue(!Boolean.TRUE.equals(reason.getSystemField()), "用途不应标记为系统字段");
    }

    @Test
    @DisplayName("★ 报修表单 schema：四个系统字段 key 稳定；照片与「填报方式」刻意不进 schema")
    void faultSchemaSystemFieldsAreStable() {
        FormSchema schema = BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_FAULT_REPORT);
        assertNotNull(schema);
        assertEquals(4, schema.getFields().size());

        FormField device = byKey(schema, BuiltInApplyCatalog.FAULT_FIELD_DEVICE);
        assertEquals(FormFieldType.DEVICE.name(), device.getType());
        assertSystemField(device);

        FormField occurredAt = byKey(schema, BuiltInApplyCatalog.FAULT_FIELD_OCCURRED_AT);
        assertEquals(FormFieldType.DATETIME.name(), occurredAt.getType());
        assertSystemField(occurredAt);

        FormField description = byKey(schema, BuiltInApplyCatalog.FAULT_FIELD_DESCRIPTION);
        assertEquals(FormFieldType.TEXTAREA.name(), description.getType());
        assertSystemField(description);

        FormField division = byKey(schema, BuiltInApplyCatalog.FAULT_FIELD_DIVISION);
        assertEquals(FormFieldType.ROOT_DEPT.name(), division.getType());
        assertSystemField(division);

        // 照片走附件通道（FAULT_PHOTO 绑定故障记录 id），不作为表单字段 ——
        // 否则「先传图还是先建单」的顺序问题会重新出现
        assertTrue(schema.getFields().stream()
                .noneMatch(f -> FormFieldType.FILE.name().equals(f.getType())
                        || FormFieldType.IMAGE.name().equals(f.getType())));
    }

    private static FormField byKey(FormSchema schema, String key) {
        return schema.getFields().stream()
                .filter(f -> key.equals(f.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("schema 里找不到字段 key=" + key
                        + "（提交页按 key 映射回入参，改 key 会让值静默丢失）"));
    }

    private void assertSystemField(FormField field) {
        assertTrue(Boolean.TRUE.equals(field.getSystemField()),
                "字段 " + field.getKey() + " 必须标记 systemField：设计器据此禁止删除，"
                        + "后端按固定 key 读数 —— 漏标会被删掉，重叠校验静默失效");
    }
}
