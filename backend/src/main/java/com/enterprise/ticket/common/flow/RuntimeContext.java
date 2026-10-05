package com.enterprise.ticket.common.flow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行期上下文—— {@code process.*} 字段的取值集合。
 *
 * <h2>它在数据流里的位置</h2>
 * <pre>
 *   提交时：  formData              → 条件求值（引用 process.* 的条件判定不了 → 下游 INACTIVE）
 *   运行期：  formData + RuntimeContext → 条件求值（此时 process.* 有值 → 可以下结论）
 * </pre>
 * 也正因为两侧只是「求值输入多了一块」，{@link FlowPathResolver} 的求值逻辑
 * （{@code matches} / {@code evaluate}）可以<b>一行不改</b>地复用 ——
 * 这是本波控制改动面的关键取舍：新增能力靠"喂不同的输入"，而不是靠"长出第二套求值器"。
 *
 * <h2>为什么是不可变 record</h2>
 * <p>{@code recompute} 的核心要求是<b>幂等</b>（同上下文同结果）。上下文一旦可变，
 * 幂等性就从"结构上成立"退化成"希望调用方不改它"。不可变值对象让不变式由类型系统兜住。
 *
 * @param prevNodeResult 上一审批节点结果：{@code APPROVED} / {@code REJECTED}；无（还没人审过）为 null
 * @param prevNodeHours  上一节点从「轮到它」到「处理完」的耗时（小时）；无上一节点为 null
 * @param elapsedHours   工单从提交到现在的耗时（小时），恒有值
 * @param anyRejected    是否发生过驳回
 * @param rejectCount    驳回次数
 * @param activatedCount 已激活的审批节点数（防环护栏，见 {@link ProcessFieldCatalog#ACTIVATED_COUNT}）
 */
public record RuntimeContext(String prevNodeResult,
                             Double prevNodeHours,
                             Double elapsedHours,
                             boolean anyRejected,
                             int rejectCount,
                             int activatedCount) {

    /** 空上下文：提交时刻的取值（尚无任何节点被处理） */
    public static RuntimeContext empty() {
        return new RuntimeContext(null, null, 0d, false, 0, 0);
    }

    /**
     * 转成条件求值器要的扁平 map。
     *
     * <p>与 {@link ProcessFieldCatalog} 的字段常量<b>逐字对应</b>；
     * 值为 null 的字段**不放入 map**，从而沿用求值器既有语义：
     * 取到 null 的数值比较自然不成立（与"该值尚不存在"的直觉一致）。
     * 布尔类字段显式转成字符串 {@code "true"} / {@code "false"}：
     * 前端选择器给出的也是这两个字面量，若这里放 Boolean，
     * 求值器的 {@code equalsValue} 会走 {@code String.valueOf} 兜底 —— 恰好也能相等，
     * 但依赖兜底路径不如显式对齐，避免将来求值器改类型比较时出现单边行为差异。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, ProcessFieldCatalog.PREV_NODE_RESULT, prevNodeResult);
        putIfPresent(map, ProcessFieldCatalog.PREV_NODE_HOURS, prevNodeHours);
        putIfPresent(map, ProcessFieldCatalog.ELAPSED_HOURS, elapsedHours);
        map.put(ProcessFieldCatalog.ANY_REJECTED, Boolean.toString(anyRejected));
        map.put(ProcessFieldCatalog.REJECT_COUNT, rejectCount);
        map.put(ProcessFieldCatalog.ACTIVATED_COUNT, activatedCount);
        return map;
    }

    /**
     * 把运行期字段并入求值输入。
     *
     * <p>冲突时的取舍：表单字段优先。理论上二者 key 空间不重叠
     * （运行期字段全部带 {@code process.} 前缀，且表单字段命名规范本就禁止点号），
     * 但一旦真出现同名，让"用户显式填的那个值"生效比让系统推导值生效更符合直觉。
     */
    public static Map<String, Object> merge(Map<String, Object> formData, RuntimeContext context) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (context != null) {
            merged.putAll(context.toMap());
        }
        if (formData != null) {
            merged.putAll(formData);
        }
        return merged;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
