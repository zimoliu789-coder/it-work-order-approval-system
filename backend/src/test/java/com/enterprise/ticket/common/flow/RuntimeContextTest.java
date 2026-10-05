package com.enterprise.ticket.common.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期上下文单元测试（Phase 16 Wave 2 · M2）。
 *
 * <p>本类是 {@code recompute} 幂等性的载体：上下文不可变，因此"同上下文同结果"是结构上成立的，
 * 而"希望调用方别改它"。测试锁定两处容易出错的细节：
 * <ul>
 *   <li><b>null 字段不进 map</b>：求值器对"取不到值"的既有语义是"比较不成立"，
 *       若把 null 塞进 map 会让数值比较与空值判断的语义分叉；</li>
 *   <li><b>布尔字段以字符串形式给出</b>：与前端选择器给的字面量对齐，
 *       不依赖求值器的 {@code String.valueOf} 兜底路径。</li>
 * </ul>
 */
class RuntimeContextTest {

    @Test
    @DisplayName("empty：全部字段为空（尚无任何节点被处理）")
    void empty() {
        RuntimeContext context = RuntimeContext.empty();
        assertNull(context.prevNodeResult());
        assertNull(context.prevNodeHours());
        assertEquals(0d, context.elapsedHours());
        assertFalse(context.anyRejected());
        assertEquals(0, context.rejectCount());
        assertEquals(0, context.activatedCount());
    }

    @Test
    @DisplayName("toMap：null 字段不放入，布尔转字符串，计数恒存在")
    void toMap() {
        Map<String, Object> map = RuntimeContext.empty().toMap();
        assertFalse(map.containsKey(ProcessFieldCatalog.PREV_NODE_RESULT),
                "上一节点结果尚不存在 → key 不应出现（沿用求值器「取不到即不命中」的语义）");
        assertFalse(map.containsKey(ProcessFieldCatalog.PREV_NODE_HOURS));
        assertEquals("false", map.get(ProcessFieldCatalog.ANY_REJECTED));
        assertEquals(0, map.get(ProcessFieldCatalog.REJECT_COUNT));
        assertEquals(0, map.get(ProcessFieldCatalog.ACTIVATED_COUNT));

        RuntimeContext full = new RuntimeContext("REJECTED", 12.5d, 30d, true, 2, 3);
        Map<String, Object> fullMap = full.toMap();
        assertEquals("REJECTED", fullMap.get(ProcessFieldCatalog.PREV_NODE_RESULT));
        assertEquals(12.5d, fullMap.get(ProcessFieldCatalog.PREV_NODE_HOURS));
        assertEquals("true", fullMap.get(ProcessFieldCatalog.ANY_REJECTED));
        assertEquals(2, fullMap.get(ProcessFieldCatalog.REJECT_COUNT));
    }

    @Test
    @DisplayName("merge：运行期字段并入表单数据；同名时表单优先")
    void merge() {
        RuntimeContext context = new RuntimeContext("APPROVED", 1d, 2d, false, 0, 1);

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("amount", 8000);
        Map<String, Object> merged = RuntimeContext.merge(form, context);
        assertEquals(8000, merged.get("amount"), "表单字段必须保留");
        assertEquals("APPROVED", merged.get(ProcessFieldCatalog.PREV_NODE_RESULT));

        // 理论 key 空间不重叠，但一旦重叠，让"用户填的值"生效比让系统推导值生效更符合直觉
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put(ProcessFieldCatalog.PREV_NODE_RESULT, "USER_VALUE");
        assertEquals("USER_VALUE",
                RuntimeContext.merge(conflict, context).get(ProcessFieldCatalog.PREV_NODE_RESULT));
    }

    @Test
    @DisplayName("merge：上下文为 null 时等于纯表单数据（提交时刻口径）")
    void merge_nullContext() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("amount", 1);
        assertEquals(form, RuntimeContext.merge(form, null));
        assertTrue(RuntimeContext.merge(null, null).isEmpty());
    }
}
