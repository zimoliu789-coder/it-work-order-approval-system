package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期字段域单元测试（Phase 16 Wave 2 · M2）。
 *
 * <p>为什么要给一个"常量表 + 几个静态判断"写单测：本类是整个运行期条件能力的<b>字段契约</b>。
 * 求值器以字符串 key 取值，字段名写错<b>不报错</b>、只是条件永远不命中 ——
 * 属于最难排查的一类静默失效。这里锁定三件事：
 * <ol>
 *   <li>前缀判定与白名单（{@code process.foo} 必须被识别为"运行期但未知"）；</li>
 *   <li>{@code isRuntimeDependent} 是<b>静态按字段名</b>判定，与"当前取值是否已知"无关；</li>
 *   <li>暴露的 schema 类型正确（数值类给 NUMBER、枚举类给 SELECT），
 *       因为它直接决定前端选择器能列出的可选值与校验器允许的运算符。</li>
 * </ol>
 */
class ProcessFieldCatalogTest {

    @Test
    @DisplayName("前缀识别：只有 process. 开头的才算运行期字段")
    void prefixRecognition() {
        assertTrue(ProcessFieldCatalog.isProcessField(ProcessFieldCatalog.PREV_NODE_RESULT));
        assertTrue(ProcessFieldCatalog.isProcessField("process.anything"));
        assertFalse(ProcessFieldCatalog.isProcessField("amount"));
        assertFalse(ProcessFieldCatalog.isProcessField("borrow.useType"));
        assertFalse(ProcessFieldCatalog.isProcessField(null));
        assertFalse(ProcessFieldCatalog.isProcessField("processx.foo"), "前缀必须带点号，避免误伤同前缀的业务字段");
    }

    @Test
    @DisplayName("白名单：六个已知字段全部 isKnown，process.foo 不得被当作表单字段")
    void knownWhitelist() {
        for (String key : List.of(ProcessFieldCatalog.PREV_NODE_RESULT, ProcessFieldCatalog.PREV_NODE_HOURS,
                ProcessFieldCatalog.ELAPSED_HOURS, ProcessFieldCatalog.ANY_REJECTED,
                ProcessFieldCatalog.REJECT_COUNT, ProcessFieldCatalog.ACTIVATED_COUNT)) {
            assertTrue(ProcessFieldCatalog.isKnown(key), key + " 应在白名单内");
        }
        assertFalse(ProcessFieldCatalog.isKnown("process.foo"),
                "未知的运行期字段必须被拒，而不是回落到表单 schema 查找（那会报出误导性的「字段不存在」）");
        assertFalse(ProcessFieldCatalog.isKnown("amount"));
        assertFalse(ProcessFieldCatalog.isKnown(null));
    }

    @Test
    @DisplayName("运行期依赖判定：按字段名静态判定，不看在提交时的取值")
    void runtimeDependent_isStaticByFieldName() {
        FlowCondition withProcess = cond("AND", rule(ProcessFieldCatalog.REJECT_COUNT, "GT", "0"));
        assertTrue(ProcessFieldCatalog.isRuntimeDependent(withProcess),
                "rejectCount 在提交时是 0（当下可知），但它将来会变，必须判为运行期依赖");

        FlowCondition withFormField = cond("AND", rule("amount", "GT", "5000"));
        assertFalse(ProcessFieldCatalog.isRuntimeDependent(withFormField));

        assertFalse(ProcessFieldCatalog.isRuntimeDependent(null));
        assertFalse(ProcessFieldCatalog.isRuntimeDependent(new FlowCondition()),
                "无条件规则 → 不构成运行期依赖");
    }

    @Test
    @DisplayName("混合条件：只要有一条规则引用 process.* 即为运行期依赖")
    void runtimeDependent_anyRuleSuffices() {
        FlowCondition mixed = cond("AND",
                rule("amount", "GT", "5000"),
                rule(ProcessFieldCatalog.PREV_NODE_RESULT, "EQ", "APPROVED"));
        assertTrue(ProcessFieldCatalog.isRuntimeDependent(mixed));
    }

    @Test
    @DisplayName("schema：数值字段给 NUMBER、枚举字段给 SELECT 且带可选值")
    void schemaTypesDriveOperatorsAndPicker() {
        List<FormField> fields = ProcessFieldCatalog.schema().getFields();
        assertEquals(6, fields.size());

        assertEquals(FormFieldType.NUMBER.name(), fieldOf(fields, ProcessFieldCatalog.PREV_NODE_HOURS).getType(),
                "耗时类字段必须是数值型，否则无法配 > / < 运算符");
        assertEquals(FormFieldType.NUMBER.name(), fieldOf(fields, ProcessFieldCatalog.REJECT_COUNT).getType());
        assertEquals(FormFieldType.NUMBER.name(), fieldOf(fields, ProcessFieldCatalog.ACTIVATED_COUNT).getType());

        FormField result = fieldOf(fields, ProcessFieldCatalog.PREV_NODE_RESULT);
        assertEquals(FormFieldType.SELECT.name(), result.getType());
        assertEquals(2, result.getOptions().size(), "上一节点结果的可选值只有 APPROVED / REJECTED");
        assertEquals("APPROVED", result.getOptions().get(0).getValue());

        FormField rejected = fieldOf(fields, ProcessFieldCatalog.ANY_REJECTED);
        assertEquals(FormFieldType.SELECT.name(), rejected.getType());
        assertEquals("true", rejected.getOptions().get(0).getValue(),
                "布尔字段的可选值必须是字符串 true/false —— 与 RuntimeContext.toMap 的序列化口径一致");
    }

    @Test
    @DisplayName("标签：每个字段都有中文名，labelOf 对未知字段原样返回")
    void labels() {
        assertEquals(6, ProcessFieldCatalog.labels().size());
        assertEquals("上一节点结果", ProcessFieldCatalog.labelOf(ProcessFieldCatalog.PREV_NODE_RESULT));
        assertEquals("驳回次数", ProcessFieldCatalog.labelOf(ProcessFieldCatalog.REJECT_COUNT));
        assertEquals("custom.field", ProcessFieldCatalog.labelOf("custom.field"),
                "未知字段原样返回，避免文案层出现 null");
        assertEquals("", ProcessFieldCatalog.labelOf(null));
    }

    @Test
    @DisplayName("护栏常量：动态插入上限为 5（与设计稿一致）")
    void maxDynamicInsert() {
        assertEquals(5, ProcessFieldCatalog.MAX_DYNAMIC_INSERT);
    }

    private static FormField fieldOf(List<FormField> fields, String key) {
        return fields.stream().filter(field -> key.equals(field.getKey())).findFirst().orElseThrow();
    }
}
