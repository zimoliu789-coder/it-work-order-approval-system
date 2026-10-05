package com.enterprise.ticket.module.backup.controller;

import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 备份接口的权限契约测试（P0）
 *
 * <h2>为什么用反射钉注解而不是起 MockMvc</h2>
 * 权限是声明式的，坏起来极其安静：把 {@code backup:manage} 误写成 {@code backup:view}，
 * 接口照样 200、页面照样能用，只是<b>写权限被悄悄放宽</b>到「只要能看到页面就能触发全库导出」。
 * 任何功能用例都发现不了这种放宽。
 *
 * <h2>钉死的四条</h2>
 * <ol>
 *   <li><b>读 / 写分界</b>：概览与列表走 {@code backup:view}，手动触发走 {@code backup:manage}；</li>
 *   <li><b>手动触发必须审计且为 HIGH 风险</b>：全库导出是重动作，必须可追溯发起人；</li>
 *   <li><b>引用的权限码必须真实存在于目录</b>：{@code @perm.has} 对不存在的码恒为 false，
 *       拼错会让接口对所有人恒 403，而编译期毫无提示；</li>
 *   <li><b>端点数量恒为 3</b>：新增端点必须在本测试显式登记，无法悄悄带上一道没挂闸门的口子。</li>
 * </ol>
 */
class BackupControllerPermissionTest {

    private static final String READ_PERM = "backup:view";
    private static final String MANAGE_PERM = "backup:manage";

    @Test
    @DisplayName("读端点走 backup:view，写端点走 backup:manage")
    void readWriteSplit() throws Exception {
        assertPerm(overview(), READ_PERM);
        assertPerm(page(), READ_PERM);
        assertPerm(run(), MANAGE_PERM);
    }

    @Test
    @DisplayName("写端点必须挂 @AuditLog 且为 HIGH 风险")
    void manualRunIsAuditedAsHighRisk() throws Exception {
        AuditLog audit = run().getAnnotation(AuditLog.class);
        assertNotNull(audit, "手动触发备份必须留审计痕迹（谁在什么时候导了一次库）");
        assertEquals("BACKUP", audit.module());
        assertEquals(RiskLevel.HIGH, audit.risk(),
                "全库导出属重动作，误标 NORMAL 会走异步审计，出问题时查不到人");
    }

    @Test
    @DisplayName("★ 引用的权限码必须真实存在于 PermissionCatalog（拼错 = 恒 403）")
    void permissionCodesExistInCatalog() {
        List<String> codes = new ArrayList<>();
        for (Method method : BackupController.class.getDeclaredMethods()) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            if (annotation != null) {
                codes.add(extractCode(annotation.value()));
            }
        }
        assertEquals(3, codes.size(), "三个端点各有一个权限注解");
        for (String code : codes) {
            assertTrue(PermissionCatalog.exists(code),
                    "权限码不在目录中，接口将对所有人恒 403：" + code);
        }
        // 两个码都必须真的声明在目录里（而不是只靠 exists 兜住）
        assertEquals(READ_PERM, PermissionCatalog.BACKUP_VIEW);
        assertEquals(MANAGE_PERM, PermissionCatalog.BACKUP_MANAGE);
    }

    @Test
    @DisplayName("★ 两个备份权限码都不得进入任何角色的默认权限集合（只归超管）")
    void backupPermsAreNotSeededToAnyRole() {
        // 遍历内置角色码：任何一个都不该默认持有备份权限。
        // ⚠️ 这条断言的价值在于「新人加角色时它自动生效」—— 不需要有人记得回来补一行。
        for (String roleCode : RoleCode.BUILTIN) {
            var codes = PermissionCatalog.defaultPermissions(roleCode);
            assertFalse(codes.contains(READ_PERM),
                    "备份查看不应播种给角色 " + roleCode + " —— 它属基础设施级权限");
            assertFalse(codes.contains(MANAGE_PERM),
                    "备份触发不应播种给角色 " + roleCode);
        }
    }

    @Test
    @DisplayName("端点数量与 HTTP 方法恒定（3 个：GET overview / GET list / POST 触发）")
    void endpointSurfaceIsFrozen() {
        int get = 0;
        int post = 0;
        for (Method method : BackupController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) != null) {
                get++;
            }
            if (method.getAnnotation(PostMapping.class) != null) {
                post++;
            }
        }
        assertEquals(2, get, "GET 端点恒为 2（overview / page）");
        assertEquals(1, post, "POST 端点恒为 1（手动触发）");
    }

    // ------------------------------------------------------------------

    private Method overview() throws NoSuchMethodException {
        return BackupController.class.getMethod("overview");
    }

    private Method page() throws NoSuchMethodException {
        return BackupController.class.getMethod("page", long.class, long.class);
    }

    private Method run() throws NoSuchMethodException {
        return BackupController.class.getMethod("run");
    }

    private void assertPerm(Method method, String expected) {
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, method.getName() + " 缺少 @PreAuthorize");
        assertEquals(expected, extractCode(annotation.value()),
                method.getName() + " 的权限码不符");
    }

    /** 从 {@code @perm.has('x:y')} 里取出 {@code x:y} */
    private String extractCode(String expression) {
        int start = expression.indexOf('\'');
        int end = expression.lastIndexOf('\'');
        if (start < 0 || end <= start) {
            fail("无法从权限表达式解析权限码：" + expression);
        }
        return expression.substring(start + 1, end);
    }
}
