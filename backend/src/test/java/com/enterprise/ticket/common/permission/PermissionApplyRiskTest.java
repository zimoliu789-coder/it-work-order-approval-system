package com.enterprise.ticket.common.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限申请的风险等级与可申请性（P4-C4）—— 纯函数契约。
 *
 * <h2>为什么这些断言必须存在</h2>
 * 这两件事错了都**不会报错**，只会在安全上悄悄开一个口子：
 * <ul>
 *   <li><b>可申请集合</b>多放一个提权类权限 ⇒ 申请人可以自己给自己加权限，流程形同虚设；</li>
 *   <li><b>风险等级</b>判低了 ⇒ 高危权限只经一级审批就开通，超管那一级被静默跳过。</li>
 * </ul>
 *
 * <h2>本测试还钉住一处**已修复的真实缺陷**</h2>
 * P4-B 的 {@code PresetApplyCatalog.NON_APPLICABLE} 里写的是 {@code system:upgrade:manage}，
 * 而目录中**没有这个码**（真实码是 {@code system:upgrade:view} / {@code system:upgrade:execute}）。
 * {@code Set.contains} 对不存在的码恒为 false ⇒ 「在线升级」一直是可申请的。
 * 下面 {@link #defaultNonApplicableCodesAllExist()} 专门防这一类「黑名单写错等于没写」。
 */
@DisplayName("权限申请：风险等级 / 可申请性（P4-C4）")
class PermissionApplyRiskTest {

    @Test
    @DisplayName("★ 提权类默认不可申请：角色 / AD / 系统参数 / 在线升级")
    void privilegedCodesAreNotApplicable() {
        assertFalse(PermissionCatalog.applicableByDefault(PermissionCatalog.ROLE_MANAGE),
                "role:manage 拿到就能自我提权");
        assertFalse(PermissionCatalog.applicableByDefault(PermissionCatalog.AD_MANAGE),
                "ad:manage 决定谁能进系统");
        assertFalse(PermissionCatalog.applicableByDefault(PermissionCatalog.CONFIG_MANAGE),
                "config:manage 能改一切开关");
        assertFalse(PermissionCatalog.applicableByDefault(PermissionCatalog.UPGRADE_EXECUTE),
                "system:upgrade:execute 能替换服务器可执行文件（等价代码执行）");
        assertFalse(PermissionCatalog.applicableByDefault(PermissionCatalog.UPGRADE_VIEW),
                "在线升级整块只归超管");
    }

    @Test
    @DisplayName("★ 黑名单里的每一个码都必须真实存在于目录（写错等于没写）")
    void defaultNonApplicableCodesAllExist() {
        for (String code : PermissionCatalog.defaultNonApplicableCodes()) {
            assertTrue(PermissionCatalog.exists(code),
                    "不可申请集合里含目录中不存在的码：" + code
                            + "（Set.contains 对它恒为 false ⇒ 过滤静默失效，这正是 P4-B 踩过的坑）");
        }
    }

    @Test
    @DisplayName("普通只读码可申请，且为普通等级（只经一级审批）")
    void readonlyCodesAreApplicableAndNormal() {
        assertTrue(PermissionCatalog.applicableByDefault(PermissionCatalog.ORDER_ALL_VIEW));
        assertEquals(PermissionCatalog.RiskLevel.NORMAL,
                PermissionCatalog.riskLevelOf(PermissionCatalog.ORDER_ALL_VIEW));
        assertEquals(PermissionCatalog.RiskLevel.NORMAL,
                PermissionCatalog.riskLevelOf(PermissionCatalog.DEVICE_LEDGER_VIEW));
    }

    @Test
    @DisplayName("★ 全部 *:manage 写操作类都是高危（要超管多走一级）")
    void allManageCodesAreHighRisk() {
        String[] manages = {
                PermissionCatalog.DEVICE_LEDGER_MANAGE,
                PermissionCatalog.DEVICE_CATEGORY_MANAGE,
                PermissionCatalog.DEVICE_FAULT_MANAGE,
                PermissionCatalog.INVENTORY_MANAGE,
                PermissionCatalog.STAFF_MANAGE,
                PermissionCatalog.DEPARTMENT_MANAGE,
                PermissionCatalog.APPLY_TYPE_MANAGE,
                PermissionCatalog.APPROVAL_FLOW_MANAGE,
                PermissionCatalog.ORDER_FORCE_MANAGE};
        for (String code : manages) {
            assertEquals(PermissionCatalog.RiskLevel.HIGH, PermissionCatalog.riskLevelOf(code),
                    code + " 是写操作类，必须走两级审批");
        }
    }

    @Test
    @DisplayName("★ 批量 / 导入类也是高危（一次操作影响一整批数据）")
    void importCodesAreHighRisk() {
        assertEquals(PermissionCatalog.RiskLevel.HIGH,
                PermissionCatalog.riskLevelOf(PermissionCatalog.DEVICE_IMPORT));
        assertEquals(PermissionCatalog.RiskLevel.HIGH,
                PermissionCatalog.riskLevelOf(PermissionCatalog.STAFF_IMPORT));
    }

    @Test
    @DisplayName("★ 未知 / 空码按高危处理（保守方向：宁可多走一级，不可漏走）")
    void unknownCodeIsHighRisk() {
        assertEquals(PermissionCatalog.RiskLevel.HIGH, PermissionCatalog.riskLevelOf("nonsense:code"));
        assertEquals(PermissionCatalog.RiskLevel.HIGH, PermissionCatalog.riskLevelOf(null));
        assertEquals(PermissionCatalog.RiskLevel.HIGH, PermissionCatalog.riskLevelOf("  "));
    }

    @Test
    @DisplayName("不可申请的码不可申请，且默认等级为高危")
    void nonApplicableCodesAreHighRisk() {
        for (String code : PermissionCatalog.defaultNonApplicableCodes()) {
            assertFalse(PermissionCatalog.applicableByDefault(code));
            assertEquals(PermissionCatalog.RiskLevel.HIGH, PermissionCatalog.riskLevelOf(code));
        }
    }

    @Test
    @DisplayName("containsHighRisk：一组码里只要有一个高危就算（用于决定是否追加超管那一级）")
    void containsHighRiskDetectsAny() {
        assertTrue(PermissionCatalog.containsHighRisk(java.util.List.of(
                PermissionCatalog.ORDER_ALL_VIEW, PermissionCatalog.DEVICE_LEDGER_MANAGE)));
        assertFalse(PermissionCatalog.containsHighRisk(java.util.List.of(
                PermissionCatalog.ORDER_ALL_VIEW, PermissionCatalog.DEVICE_LEDGER_VIEW)));
        assertFalse(PermissionCatalog.containsHighRisk(java.util.List.of()));
        assertFalse(PermissionCatalog.containsHighRisk(null));
    }

    @Test
    @DisplayName("目录里不存在「可申请 + 提权类」的组合（防止把提权类改回可申请）")
    void noPrivilegedCodeIsApplicable() {
        for (String code : PermissionCatalog.allCodeList()) {
            if (PermissionCatalog.defaultNonApplicableCodes().contains(code)) {
                assertFalse(PermissionCatalog.applicableByDefault(code), code + " 不应可申请");
            }
        }
    }
}
