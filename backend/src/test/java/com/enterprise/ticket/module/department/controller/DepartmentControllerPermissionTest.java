package com.enterprise.ticket.module.department.controller;

import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.department.dto.DepartmentManagerRequest;
import com.enterprise.ticket.module.department.dto.DepartmentMoveRequest;
import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 部门接口的权限契约测试（Phase 19 批次 A+B，需求书 二）。
 *
 * <h2>为什么用反射钉注解，而不是起 Spring 跑 MockMvc</h2>
 * <p>本模块的权限是「声明式」的：{@code @PreAuthorize("@perm.has('…')")} 写在方法上。
 * 它坏起来极其安静 —— 把 {@code department:manage} 误写成 {@code staff:view}，
 * 接口照样 200、功能照样跑，只是<b>权限被悄悄放宽了</b>，任何功能用例都发现不了。
 * 反射读注解能精确钉住「哪个端点要哪个码」，且不依赖上下文、毫秒级完成。
 *
 * <h2>钉死的四条</h2>
 * <ol>
 *   <li><b>读 / 写分界</b>：读（树 / 选项 / 成员）走 {@code staff:view}；
 *       写（增 / 改 / 移 / 设主管 / 删）走 {@code department:manage}；</li>
 *   <li><b>写接口一律审计且为 HIGH 风险</b>：组织结构变更必须可追溯，
 *       漏了 {@code @AuditLog} 或误标 {@code NORMAL}（异步）都会让「谁改了组织」查不到；</li>
 *   <li><b>引用的权限码必须真实存在</b>：{@code @perm.has} 对不存在的码恒为 false ——
 *       权限码拼错会让接口对所有人恒 403，而编译期毫无提示；</li>
 *   <li><b>端点数量恒为 8</b>：新增端点必然要在本测试里显式登记，无法悄悄带上一道没挂闸门的口子。</li>
 * </ol>
 *
 * <h2>P4-C 增补：admin 默认集合只有读码</h2>
 * <p>原第 5 条断言是「admin 默认集合含读码与写码」。P4-C 起写码被收回
 * （组织页把部门写操作全部按 {@code isSuperAdmin} 显隐 ⇒ 前端比后端更严，
 * admin 看不到入口却能直调接口改组织结构）。本测试随之改为
 * 「含读码、**不含**写码」并补一条反向对照（员工维护必须保留）。
 */
class DepartmentControllerPermissionTest {

    private static final String READ_PERM = "staff:view";
    private static final String WRITE_PERM = "department:manage";

    private static Method method(String name, Class<?>... params) {
        try {
            return DepartmentController.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("部门接口方法 " + name + " 不存在：端点被删或改了签名", e);
        }
    }

    private static String requiredPerm(Method m) {
        PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation,
                m.getName() + " 缺少 @PreAuthorize —— 端点将退化为「登录即可访问」，权限闸门失守");
        String expr = annotation.value();
        assertTrue(expr.startsWith("@perm.has(") && expr.endsWith(")"),
                m.getName() + " 的 @PreAuthorize 形态应为 @perm.has('…')：" + expr);
        return expr;
    }

    /** 从 @perm.has('xxx') 里抽出权限码 */
    private static String permCode(String expr) {
        int open = expr.indexOf('\'');
        int close = expr.lastIndexOf('\'');
        assertTrue(open >= 0 && close > open, "无法从表达式里解析权限码：" + expr);
        return expr.substring(open + 1, close);
    }

    private static void assertPermission(Method m, String expected) {
        assertEquals(expected, permCode(requiredPerm(m)),
                m.getName() + " 的权限码应为 " + expected);
    }

    private static AuditLog auditOf(Method m) {
        AuditLog audit = m.getAnnotation(AuditLog.class);
        assertNotNull(audit, m.getName() + " 是写接口，必须带 @AuditLog（组织结构变更不可留白）");
        return audit;
    }

    private static List<Method> endpoints() {
        List<Method> result = new ArrayList<>();
        for (Method m : DepartmentController.class.getDeclaredMethods()) {
            if (m.getAnnotation(GetMapping.class) != null || m.getAnnotation(PostMapping.class) != null
                    || m.getAnnotation(PutMapping.class) != null || m.getAnnotation(DeleteMapping.class) != null) {
                result.add(m);
            }
        }
        return result;
    }

    private static final List<Method> WRITE_ENDPOINTS = List.of(
            method("create", DepartmentSaveRequest.class),
            method("update", Long.class, DepartmentSaveRequest.class),
            method("move", Long.class, DepartmentMoveRequest.class),
            method("setManagers", Long.class, DepartmentManagerRequest.class),
            method("delete", Long.class));

    private static final List<Method> READ_ENDPOINTS = List.of(
            method("tree"), method("options"), method("members", Long.class));

    // ------------------------------------------------------------------
    // 一、读端点 → staff:view
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读端点：树 / 选项 / 成员 均需 staff:view")
    void readEndpointsRequireStaffView() {
        assertPermission(method("tree"), READ_PERM);
        assertPermission(method("options"), READ_PERM);
        assertPermission(method("members", Long.class), READ_PERM);
    }

    // ------------------------------------------------------------------
    // 二、写端点 → department:manage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写端点：增 / 改 / 移 / 设主管 / 删 均需 department:manage")
    void writeEndpointsRequireDepartmentManage() {
        assertPermission(method("create", DepartmentSaveRequest.class), WRITE_PERM);
        assertPermission(method("update", Long.class, DepartmentSaveRequest.class), WRITE_PERM);
        assertPermission(method("move", Long.class, DepartmentMoveRequest.class), WRITE_PERM);
        assertPermission(method("setManagers", Long.class, DepartmentManagerRequest.class), WRITE_PERM);
        assertPermission(method("delete", Long.class), WRITE_PERM);
    }

    @Test
    @DisplayName("读 / 写是两个不同的码（写接口复用 staff:view = 组织调整权下放给所有能看员工的人）")
    void readAndWriteCodesAreDistinct() {
        assertEquals(PermissionCatalog.STAFF_VIEW, READ_PERM,
                "本测试钉的码应与 PermissionCatalog 常量逐字一致，避免各写各的");
        assertEquals(PermissionCatalog.DEPARTMENT_MANAGE, WRITE_PERM,
                "本测试钉的码应与 PermissionCatalog 常量逐字一致，避免各写各的");
        for (Method m : WRITE_ENDPOINTS) {
            assertFalse(READ_PERM.equals(permCode(requiredPerm(m))),
                    m.getName() + " 是写接口，不得复用读权限码 " + READ_PERM);
        }
    }

    // ------------------------------------------------------------------
    // 三、写端点审计（HIGH 同步留痕）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写端点的审计：module=ORG、风险=HIGH（HIGH 才同步落库，NORMAL 是异步 = 可能丢）")
    void writeEndpointsAreAuditedAsHighRisk() {
        for (Method m : WRITE_ENDPOINTS) {
            AuditLog audit = auditOf(m);
            assertEquals("ORG", audit.module(), m.getName() + " 的审计模块应为 ORG");
            assertEquals(RiskLevel.HIGH, audit.risk(),
                    m.getName() + " 属组织结构变更，必须 HIGH（同步可靠落库）");
            assertFalse(audit.description().isBlank(), m.getName() + " 的审计描述不应为空");
            assertFalse(audit.action().isBlank(), m.getName() + " 的审计动作不应为空");
        }
    }

    @Test
    @DisplayName("读端点：走 GET，且不刷审计（读操作刷日志只会淹没有价值的记录）")
    void readEndpointsAreGetAndNotAudited() {
        for (Method m : READ_ENDPOINTS) {
            assertNotNull(m.getAnnotation(GetMapping.class), m.getName() + " 应是 GET 读接口");
            assertEquals(0, m.getAnnotationsByType(AuditLog.class).length,
                    m.getName() + " 是读接口，不应挂 @AuditLog");
        }
    }

    // ------------------------------------------------------------------
    // 四、端点清单与权限码存在性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端点数量恒为 8，且每个都挂了权限注解（新增端点漏挂闸门会被此测试拦下）")
    void endpointInventoryIsPinned() {
        List<String> names = endpoints().stream().map(Method::getName).sorted().toList();
        assertEquals(8, names.size(), "部门接口应由 8 个端点组成，实际：" + names);
        assertTrue(names.containsAll(Arrays.asList(
                        "tree", "options", "members", "create", "update", "move", "setManagers", "delete")),
                "端点清单发生了变化：" + names);
        for (Method m : endpoints()) {
            assertNotNull(m.getAnnotation(PreAuthorize.class),
                    "端点 " + m.getName() + " 没有权限注解 —— 新增端点时漏挂闸门");
        }
    }

    @Test
    @DisplayName("引用的权限码必须在 PermissionCatalog 中存在（拼错会让接口对所有人恒 403）")
    void referencedPermissionCodesExist() {
        if (!PermissionCatalog.exists(READ_PERM)) {
            fail("权限码 " + READ_PERM + " 不在 PermissionCatalog 中");
        }
        if (!PermissionCatalog.exists(WRITE_PERM)) {
            fail("权限码 " + WRITE_PERM + " 不在 PermissionCatalog 中");
        }
        assertTrue(PermissionCatalog.allCodes().contains(READ_PERM));
        assertTrue(PermissionCatalog.allCodes().contains(WRITE_PERM));
    }

    @Test
    @DisplayName("admin 默认集合：含读码、**不含**写码（P4-C：组织结构只有超管能改）")
    void adminDefaultsCoverReadButNotWrite() {
        assertTrue(PermissionCatalog.defaultPermissions("admin").contains(READ_PERM),
                "admin 默认应含 " + READ_PERM + "（否则「组织与人员」菜单点进去 403）");

        // ⚠️ P4-C 起刻意**不含**写码。
        // 本方法原先断言 admin 应含写码，理由是「需求书表 9：admin 维护员工与资产」——
        // 那是把「维护员工」顺带读成了「维护组织结构」。但组织页（views/staff/organization）
        // 把所有部门写操作（增 / 改 / 移 / 设主管 / 删 / 拖拽）都按 `isSuperAdmin` 显隐
        // ⇒ **前端比后端更严**：admin 看不到入口，却能直接调接口改组织结构
        // （实测 PUT /departments/{id}/parent 与 POST /departments 均返回 200 SUCCESS）。
        // 显隐不是安全边界（硬约定：越权分支要在「取数据之前」return），
        // 因此按「后端说了算」收回授权，让两侧一致。存量库的授权行由 V42 迁移删除。
        assertFalse(PermissionCatalog.defaultPermissions("admin").contains(WRITE_PERM),
                "P4-C 已从 admin 收回 " + WRITE_PERM + "（部门是审批上级的事实源，只有超管能改）");

        // 反向对照：**员工维护不受影响** —— 收回的是「改组织结构」，不是「维护员工」。
        // 这条断言防止后人把整块「组织与人员」权限一刀切掉（那会让 admin 连员工都改不了）。
        assertTrue(PermissionCatalog.defaultPermissions("admin").contains(PermissionCatalog.STAFF_MANAGE),
                "admin 仍应能维护员工（员工维护与组织结构是两件事）");

        assertTrue(PermissionCatalog.defaultPermissions("super_admin").isEmpty(),
                "超管不进默认集合 —— 它由 PermissionGuard 直接短路放行（授权行被误删也不会锁死自己）");
    }
}
