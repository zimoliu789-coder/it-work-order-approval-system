package com.enterprise.ticket.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归还检查结果枚举（P0）
 *
 * <h2>本测试真正要钉死的是什么</h2>
 * 四值枚举里每一条「映射关系」都是业务规则，而它们坏起来都不会报错：
 * <ul>
 *   <li>把「缺配件」的目标状态写成 MAINTENANCE ⇒ <b>设备凭空少一台可借的</b>（用户明确要求它回可用）；</li>
 *   <li>把「缺配件」的建故障记录标志写成 true ⇒ 故障统计把「少一根数据线」也算成一次设备故障；</li>
 *   <li>把「丢失」的说明必填写成 false ⇒ 造出「丢失但不知道丢了什么」的记录，事后无法追溯。</li>
 * </ul>
 * 因此这里逐条断言<b>四个值的全部四个属性</b>，而不是抽查其中两个。
 *
 * <p>同时用<b>表格驱动</b>的断言把「说明必填的三态」与「说明可选的唯一态」分开 ——
 * 若只断言「损坏要填说明」，把缺配件/丢失的必填误删掉时测试仍然全绿。
 */
class ReturnConditionTest {

    @Test
    @DisplayName("四个值的完整映射：说明必填 / 设备去向 / 建故障记录 / 通知追回")
    void allFourValues_haveExactMapping() {
        // 完好：说明可选、回可用、不建档、不通知
        assertMapping(ReturnCondition.GOOD, DeviceStatus.AVAILABLE, false, false, false);
        // 损坏：说明必填、进维修、**建故障记录**、不通知追回（损坏不涉及「有东西没回来」）
        assertMapping(ReturnCondition.DAMAGED, DeviceStatus.MAINTENANCE, true, true, false);
        // 缺配件：说明必填、**回可用**（用户拍板）、**不建档**（不是设备故障）、通知追回
        assertMapping(ReturnCondition.MISSING_PARTS, DeviceStatus.AVAILABLE, true, false, true);
        // 丢失：说明必填、置为已丢失、不建档、通知查找
        assertMapping(ReturnCondition.LOST, DeviceStatus.LOST, true, false, true);
    }

    @Test
    @DisplayName("★「缺配件」必须回可用且不建故障记录（两条对照，防止被顺手改回维修中）")
    void missingParts_mustStayAvailableAndSkipFaultRecord() {
        // 与「损坏」成对断言：只断言缺配件回可用，无法证明「损坏进维修」没被一起改坏；
        // 反过来也一样。两条一起断言才能锁住这个「同族不同路」的语义分叉。
        assertEquals(DeviceStatus.AVAILABLE, ReturnCondition.MISSING_PARTS.getDeviceStatus(),
                "缺配件时设备主体是好的，必须回到「可用」—— 否则会因为少一个配件而少一台可借设备");
        assertFalse(ReturnCondition.MISSING_PARTS.isCreateFaultRecord(),
                "缺配件不是设备故障，建故障记录会污染故障统计");

        assertEquals(DeviceStatus.MAINTENANCE, ReturnCondition.DAMAGED.getDeviceStatus());
        assertTrue(ReturnCondition.DAMAGED.isCreateFaultRecord());
    }

    @Test
    @DisplayName("说明必填恰好是「损坏 / 缺配件 / 丢失」三个")
    void remarkRequiredExactlyThreeStates() {
        assertFalse(ReturnCondition.GOOD.isRemarkRequired());
        assertTrue(ReturnCondition.DAMAGED.isRemarkRequired());
        assertTrue(ReturnCondition.MISSING_PARTS.isRemarkRequired());
        assertTrue(ReturnCondition.LOST.isRemarkRequired());
    }

    @Test
    @DisplayName("通知追回恰好是「缺配件 / 丢失」两个")
    void notifyRecoveryExactlyTwoStates() {
        assertFalse(ReturnCondition.GOOD.isNotifyRecovery());
        assertFalse(ReturnCondition.DAMAGED.isNotifyRecovery());
        assertTrue(ReturnCondition.MISSING_PARTS.isNotifyRecovery());
        assertTrue(ReturnCondition.LOST.isNotifyRecovery());
    }

    @Test
    @DisplayName("旧枚举值已下线：FAULT / MINOR_DAMAGE 解析为 null")
    void legacyValuesAreGone() {
        // 这两个值由 V39 迁移掉（FAULT→DAMAGED、MINOR_DAMAGE→GOOD）。
        // 若它们「意外可用」，说明枚举被改回旧集合而迁移没跟着回滚 —— 数据与代码会不一致。
        assertNull(ReturnCondition.of("FAULT"), "FAULT 已并入 DAMAGED，不应再解析成功");
        assertNull(ReturnCondition.of("MINOR_DAMAGE"), "MINOR_DAMAGE 已并入 GOOD，不应再解析成功");
    }

    @Test
    @DisplayName("of / labelOf：非法值与 null 不得抛异常（展示层依赖这一条）")
    void ofAndLabelOfAreNullSafe() {
        assertNull(ReturnCondition.of(null));
        assertNull(ReturnCondition.of("NOT_A_CONDITION"));
        assertEquals("完好", ReturnCondition.labelOf("GOOD"));
        assertEquals("缺配件", ReturnCondition.labelOf("MISSING_PARTS"));
        // ⚠️ 这里断的是「检查结果」的中文名（丢失），不是设备状态名（已丢失）——
        // 两者刻意不同：检查时说的是「这台设备丢了」，台账上说的是「已丢失」这一状态。
        assertEquals("丢失", ReturnCondition.labelOf("LOST"));
        // 未知编码原样返回，避免详情页出现 null
        assertEquals("WHATEVER", ReturnCondition.labelOf("WHATEVER"));
        assertNull(ReturnCondition.labelOf(null));
    }

    private void assertMapping(ReturnCondition condition, DeviceStatus deviceStatus,
                               boolean remarkRequired, boolean createFaultRecord, boolean notifyRecovery) {
        assertEquals(deviceStatus, condition.getDeviceStatus(), condition + " 的设备去向");
        assertEquals(remarkRequired, condition.isRemarkRequired(), condition + " 的说明必填标志");
        assertEquals(createFaultRecord, condition.isCreateFaultRecord(), condition + " 的建故障记录标志");
        assertEquals(notifyRecovery, condition.isNotifyRecovery(), condition + " 的通知追回标志");
    }
}
