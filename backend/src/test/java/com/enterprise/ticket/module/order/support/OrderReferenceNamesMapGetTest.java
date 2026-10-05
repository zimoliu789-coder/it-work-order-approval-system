package com.enterprise.ticket.module.order.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工单引用字典的「空值安全取字典」单元测试（Phase 14 引入，W4-A2 迁至实现所在地）
 *
 * <p>纯静态方法，无需 Spring / Mockito。
 *
 * <p><b>这个用例守护的是一个真实的线上故障</b>：装配工单 VO 时，
 * 设备 / 部门 / 处理小组 / 申请类型这些字典映射在「本轮没有对应数据」时由
 * {@code Map.of()} 兜底。而自定义工单（Phase 14）没有设备，其外键为 {@code NULL}，
 * 于是 {@code Map.of().get(null)} 会抛 {@link NullPointerException} ——
 * 具体表现是「管理员按申请类型筛选、结果里全是自定义工单」时接口 500。
 *
 * <p>不可变空 Map 对 null key 的拒绝行为很容易被忽略（写成 {@code map.get(key)} 直觉上
 * 就该返回 null），因此这里把「空 Map + null key 必须安全返回 null」固化为断言。
 *
 * <p><b>W4-A2 起断言对象改为 {@link OrderReferenceNames#mapGet}</b>：实现已随名称解析
 * 搬到该类，原先留在 {@code OrderServiceImpl} 的转发入口没有任何生产调用方（只为
 * "让老测试继续绿"而存在），属于纯样板间接层，故一并删除 —— 测试直接对着实现断言，
 * 改坏实现立刻变红。
 */
class OrderReferenceNamesMapGetTest {

    @Test
    @DisplayName("Map.of() 空映射 + null key：不抛异常、返回 null（本项目踩过的 NPE 陷阱）")
    void nullKeyOnImmutableEmptyMapIsSafe() {
        assertNull(OrderReferenceNames.mapGet(Map.of(), null));
    }

    @Test
    @DisplayName("对照：直接对 Map.of() 取 null key 会抛 NPE（说明为何需要 mapGet）")
    void immutableEmptyMapItselfThrows() {
        Map<Long, String> immutableEmpty = Map.of();
        assertThrows(NullPointerException.class, () -> immutableEmpty.get(null));
    }

    @Test
    @DisplayName("单元素不可变映射同样拒绝 null key（Map.of(k,v) 不是 HashMap）")
    void nullKeyOnSingleEntryImmutableMapIsSafe() {
        assertNull(OrderReferenceNames.mapGet(Map.of(1L, "研发部"), null));
    }

    @Test
    @DisplayName("null 映射本身也安全（调用方未构造字典时不应崩）")
    void nullMapIsSafe() {
        assertNull(OrderReferenceNames.mapGet(null, 1L));
    }

    @Test
    @DisplayName("键存在取值 / 键缺失返回 null")
    void normalLookup() {
        Map<Long, String> map = new HashMap<>();
        map.put(1L, "研发部");
        assertEquals("研发部", OrderReferenceNames.mapGet(map, 1L));
        assertNull(OrderReferenceNames.mapGet(map, 999L));
    }
}
