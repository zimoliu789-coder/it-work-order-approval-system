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
 * 内置申请类型目录（V53 追加出差申请）的契约固化。
 *
 * <h2>这组断言守住什么</h2>
 * 出差申请的三个字段 key 是<b>业务逻辑的读取依据</b>：{@code BusinessTripRules} 按固定 key
 * 读重叠校验与预填的数据。schema 由本目录播种、key 常量定义在 {@code BusinessTripRules} ——
 * 两处只要漂移一个字母，重叠校验就会以「读不到值」的方式**静默失效**（不报错、直接放行）。
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
    @DisplayName("借用 / 报修没有表单 schema：它们的表单是代码内置页面（保持 V48 行为）")
    void borrowAndFaultHaveNoSchema() {
        assertNull(BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_BORROW));
        assertNull(BuiltInApplyCatalog.formSchemaOf(BuiltInApplyCatalog.CODE_FAULT_REPORT));
        assertNull(BuiltInApplyCatalog.formSchemaOf("SOMETHING_ELSE"));
    }

    private void assertSystemField(FormField field) {
        assertTrue(Boolean.TRUE.equals(field.getSystemField()),
                "字段 " + field.getKey() + " 必须标记 systemField：设计器据此禁止删除，"
                        + "后端按固定 key 读数 —— 漏标会被删掉，重叠校验静默失效");
    }
}
