package com.enterprise.ticket.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备状态机：新增「已丢失」与人工变更白名单扩展（P0）
 *
 * <h2>为什么这个测试必须同时写「放行」与「拒绝」两组</h2>
 * {@code canManualTransfer} 是一道<b>安全闸门</b>：它拦的是「管理员在台账上把设备
 * 手工置成 LOCKED / IN_APPROVAL / IN_USE 从而绕过工单流程」。
 * 这类闸门的坏法有两种，且方向相反：
 * <ul>
 *   <li>扩得太少 ⇒ P0 的功能用不了（缺配件后改不回维修中、丢失后找不回可用）；</li>
 *   <li>扩得太多 ⇒ 闸门失效，设备状态与工单状态可以互相矛盾（最典型：设备在借却被标成维修中，
 *       工单<b>永远收不回</b>）。</li>
 * </ul>
 * 只测「新放行的四条能过」完全测不出第二种 —— 因此下面有一整组
 * 「必须仍被拒绝」的断言，覆盖三个工单驱动的状态与所有非法方向。
 */
class DeviceStatusManualTransferTest {

    @Test
    @DisplayName("LOST 不可被申请借用（与 BORROWED/MAINTENANCE/SCRAPPED 同一口径）")
    void lostIsNotApplicable() {
        assertFalse(DeviceStatus.LOST.isApplicable(),
                "已丢失的设备必须不可被申请 —— 否则申请人会借到一台根本不存在的设备");
        assertTrue(DeviceStatus.AVAILABLE.isApplicable(), "对照：只有 AVAILABLE 可被申请");
    }

    @Test
    @DisplayName("P0 新增的四条放行：找回 / 标记丢失 / 丢失报废 / 空闲送修")
    void newAllowedTransitions() {
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.LOST, DeviceStatus.AVAILABLE),
                "找回后可以手动改回「可用」（用户拍板）");
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.AVAILABLE, DeviceStatus.LOST),
                "台账上发现设备缺失，可以标记为「已丢失」");
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.MAINTENANCE, DeviceStatus.LOST),
                "送修期间发现设备其实是被弄丢了，也应允许标记丢失");
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.LOST, DeviceStatus.SCRAPPED),
                "确认找不回的丢失设备，可以报废（丢失≠报废，但可以走到报废）");
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.AVAILABLE, DeviceStatus.MAINTENANCE),
                "缺配件影响使用时可手动改为「维修中」（用户拍板；这是 P0 新开的手工口子）");
    }

    @Test
    @DisplayName("既有四条不受影响（回归护栏）")
    void existingTransitionsUnchanged() {
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.MAINTENANCE, DeviceStatus.AVAILABLE),
                "维修完成 → 可用");
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.AVAILABLE, DeviceStatus.SCRAPPED));
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.MAINTENANCE, DeviceStatus.SCRAPPED));
    }

    @Test
    @DisplayName("★ 工单驱动的三个状态仍禁止手工置入（这道闸门原本要防的事）")
    void workflowDrivenStatesStillForbidden() {
        // 每一个「从哪里来」都要试，否则只是证明了「某一个来源被挡住」
        for (DeviceStatus from : DeviceStatus.values()) {
            for (DeviceStatus blocked : new DeviceStatus[]{
                    DeviceStatus.LOCKED, DeviceStatus.IN_APPROVAL, DeviceStatus.IN_USE}) {
                assertFalse(DeviceStatus.canManualTransfer(from, blocked),
                        "禁止把设备手工置为 " + blocked + "（来源 " + from + "）—— 这会绕过工单流程");
            }
        }
    }

    @Test
    @DisplayName("★ 使用中的设备不能被手工送修或标记丢失（否则工单永远收不回）")
    void inUseCannotBeManuallyDiverted() {
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_USE, DeviceStatus.MAINTENANCE),
                "在借设备必须走归还流程进维修 —— 手工置入会让工单与设备状态彻底脱钩");
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_USE, DeviceStatus.LOST),
                "在借设备标记丢失应先走归还检查（那条路径会同时终结工单）");
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_USE, DeviceStatus.AVAILABLE),
                "在借设备不能手工置为可用 —— 那等于绕过了归还确认");
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_USE, DeviceStatus.SCRAPPED),
                "规范 §9 明确：BORROWED 状态设备禁止直接报废");
    }

    @Test
    @DisplayName("非法输入与自反变更一律拒绝")
    void invalidInputsRejected() {
        assertFalse(DeviceStatus.canManualTransfer(null, DeviceStatus.AVAILABLE));
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.AVAILABLE, null));
        for (DeviceStatus status : DeviceStatus.values()) {
            assertFalse(DeviceStatus.canManualTransfer(status, status),
                    "同状态互转不是变更：" + status);
        }
    }

    @Test
    @DisplayName("报废仍只能从 可用 / 维修中 / 已丢失 进入")
    void scrapOnlyFromExpectedSources() {
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.LOCKED, DeviceStatus.SCRAPPED));
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_APPROVAL, DeviceStatus.SCRAPPED));
        assertFalse(DeviceStatus.canManualTransfer(DeviceStatus.IN_USE, DeviceStatus.SCRAPPED));
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.AVAILABLE, DeviceStatus.SCRAPPED));
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.MAINTENANCE, DeviceStatus.SCRAPPED));
        assertTrue(DeviceStatus.canManualTransfer(DeviceStatus.LOST, DeviceStatus.SCRAPPED));
    }

    @Test
    @DisplayName("LOST 的中文标签为「已丢失」")
    void lostHasLabel() {
        org.junit.jupiter.api.Assertions.assertEquals("已丢失", DeviceStatus.LOST.getLabel());
        org.junit.jupiter.api.Assertions.assertEquals("已丢失", DeviceStatus.labelOf("LOST"));
    }
}
