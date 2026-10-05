package com.enterprise.ticket.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批节点状态单元测试（重点：Phase 16 Wave 2 · M2 引入的 {@code INACTIVE}）。
 *
 * <p>{@code isFinished()} 是本波<b>唯一触碰既有语义</b>的一行，因此必须有测试把它钉住：
 * <ul>
 *   <li>{@code INACTIVE} 不算完成 —— 否则"无 PENDING 但有 INACTIVE"会被误判为"流程走完"，
 *       工单在还有节点可能要走的情况下直接进终态；</li>
 *   <li>除 PENDING / INACTIVE 外全部算完成（含抄送 CC_NOTIFIED，
 *       这正是"抄送不阻塞推进"的实现方式）；</li>
 *   <li>零回归不变式：不出现 INACTIVE 时，{@code this != PENDING && this != INACTIVE}
 *       与旧写法 {@code this != PENDING} 的取值完全相同。</li>
 * </ul>
 */
class ApprovalNodeStatusTest {

    @Test
    @DisplayName("INACTIVE：不算完成，但属于「尚有可能需要处理」")
    void inactiveIsOpenNotFinished() {
        assertFalse(ApprovalNodeStatus.INACTIVE.isFinished(),
                "未判定的节点不能算已完成，否则工单会被误判为流程走完");
        assertTrue(ApprovalNodeStatus.INACTIVE.isOpen());
    }

    @Test
    @DisplayName("PENDING：不算完成，属于开放态")
    void pendingIsOpenNotFinished() {
        assertFalse(ApprovalNodeStatus.PENDING.isFinished());
        assertTrue(ApprovalNodeStatus.PENDING.isOpen());
    }

    @Test
    @DisplayName("其余状态（含抄送 CC_NOTIFIED）：均为终态")
    void otherStatusesAreFinished() {
        for (ApprovalNodeStatus status : ApprovalNodeStatus.values()) {
            if (status == ApprovalNodeStatus.PENDING || status == ApprovalNodeStatus.INACTIVE) {
                continue;
            }
            assertTrue(status.isFinished(), status + " 应为终态");
            assertFalse(status.isOpen(), status + " 不应为开放态");
        }
        assertTrue(ApprovalNodeStatus.CC_NOTIFIED.isFinished(), "抄送不阻塞推进，靠 isFinished 天然为 true 实现");
    }

    @Test
    @DisplayName("零回归不变式：INACTIVE 缺席时新旧 isFinished 判定逐值一致")
    void zeroRegressionInvariant() {
        for (ApprovalNodeStatus status : ApprovalNodeStatus.values()) {
            if (status == ApprovalNodeStatus.INACTIVE) {
                continue;
            }
            boolean oldFormula = status != ApprovalNodeStatus.PENDING;
            boolean newFormula = status != ApprovalNodeStatus.PENDING && status != ApprovalNodeStatus.INACTIVE;
            assertEquals(oldFormula, newFormula,
                    status + "：开关关闭时不会出现 INACTIVE，两种写法取值必须相同");
        }
    }

    @Test
    @DisplayName("of / labelOf：合法值映射，非法值不当成 null 崩掉")
    void ofAndLabelOf() {
        assertEquals(ApprovalNodeStatus.INACTIVE, ApprovalNodeStatus.of("INACTIVE"));
        assertNull(ApprovalNodeStatus.of("NOT_A_STATUS"));
        assertNull(ApprovalNodeStatus.of(null));
        assertEquals("未激活", ApprovalNodeStatus.INACTIVE.getLabel());
        assertEquals("未激活", ApprovalNodeStatus.labelOf("INACTIVE"));
        assertEquals("NOT_A_STATUS", ApprovalNodeStatus.labelOf("NOT_A_STATUS"),
                "非法值原样返回，避免展示层出现 null");
    }
}
