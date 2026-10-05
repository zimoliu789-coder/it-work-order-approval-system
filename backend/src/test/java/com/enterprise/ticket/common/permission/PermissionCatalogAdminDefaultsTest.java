package com.enterprise.ticket.common.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置角色默认权限集的契约（Phase 16 Wave 4 · W4-D）。
 *
 * <p>这个集合的问题是「改错了不会报错，只会悄悄多给权 / 少给权」：
 * <ul>
 *   <li>把 manage 码写进默认集 → 业务管理员静默获得写权限，且没有任何编译期提示；</li>
 *   <li>写了一个**未被 PermissionCatalog 收录**的码（拼写错、或常量已删除）→
 *       授权行插进库里但永远不会生效，表现为"给了权限却进不去"，极难排查；</li>
 *   <li>默认集与 V24 迁移不一致 → 新装环境与存量环境行为分叉。</li>
 * </ul>
 * 因此这里锁的不是"有几个权限"，而是上述几条**不会自曝的错误**。
 */
class PermissionCatalogAdminDefaultsTest {

    private static final String ADMIN = "admin";
    private static final String USER = "user";

    @Test
    @DisplayName("admin 拿到 config:view（只读），但拿不到 config:manage")
    void adminGetsConfigViewButNotManage() {
        Set<String> admin = PermissionCatalog.defaultPermissions(ADMIN);

        assertTrue(admin.contains(PermissionCatalog.CONFIG_VIEW),
                "admin 必须能查看系统参数（W4-D / V24 的下发目标）");
        assertFalse(admin.contains(PermissionCatalog.CONFIG_MANAGE),
                "admin 不得获得 config:manage —— 改参数仍归超管，这是本批的权限边界");
    }

    @Test
    @DisplayName("config:view 确实在权限目录树里（否则 V24 授出去的是一条死码）")
    void configViewIsRegisteredInCatalog() {
        assertTrue(PermissionCatalog.allCodes().contains(PermissionCatalog.CONFIG_VIEW));
        // nameOf 对未收录的码会原样返回该码本身 —— 用它反向确认目录里真的有这个节点
        assertFalse(PermissionCatalog.CONFIG_VIEW.equals(PermissionCatalog.nameOf(PermissionCatalog.CONFIG_VIEW)),
                "config:view 未被目录收录，nameOf 回退成了码本身");
    }

    @Test
    @DisplayName("默认集里的每一个码都必须真实存在（防拼写错 / 常量已删）")
    void everyDefaultCodeExistsInCatalog() {
        for (String role : List.of(ADMIN, USER)) {
            for (String code : PermissionCatalog.defaultPermissions(role)) {
                assertTrue(PermissionCatalog.exists(code),
                        "角色 " + role + " 的默认集含未收录的码：" + code + "（授权行会插入但永不生效）");
            }
        }
    }

    @Test
    @DisplayName("user 拿不到 config:view —— 公开面止于业务管理员")
    void userDoesNotGetConfigView() {
        assertFalse(PermissionCatalog.defaultPermissions(USER).contains(PermissionCatalog.CONFIG_VIEW));
    }

    @Test
    @DisplayName("admin 拿不到 apply_type:view / approval_flow:view（设计器仅超管）")
    void adminDoesNotGetDesignerViewPerms() {
        Set<String> admin = PermissionCatalog.defaultPermissions(ADMIN);

        // Phase 19 批次 C：需求书验收标准 5「普通管理员（非超管）看不到表单设计器、流程设计器」。
        // 这两条断言是**防回退**用的：改造前 admin 有只读权限，很容易在下次「补权限」时
        // 顺手把它们加回来（看名字像是无害的 view 码），届时需求要被悄悄推翻且无人察觉。
        assertFalse(admin.contains(PermissionCatalog.APPLY_TYPE_VIEW),
                "批次 C 已回收申请类型管理的只读权限（admin 不得看表单设计器）");
        assertFalse(admin.contains(PermissionCatalog.APPROVAL_FLOW_VIEW),
                "批次 C 已回收审批流程模板的只读权限（admin 不得看流程设计器）");
        // 反向对照：流程监控仍归 admin —— 它报的是「审批效率」而非「怎么配流程」，
        // 这条断言防止后人把「审批相关」整体一刀切。
        assertTrue(admin.contains(PermissionCatalog.FLOW_MONITOR_VIEW),
                "流程监控是观测面，批次 C 未回收，admin 应保留");
    }

    @Test
    @DisplayName("未登记的角色返回空集（新建角色从零开始授权，不继承任何默认）")
    void unknownRoleGetsNothing() {
        assertTrue(PermissionCatalog.defaultPermissions("role_not_registered").isEmpty());
        assertTrue(PermissionCatalog.defaultPermissions(null).isEmpty());
    }

    @Test
    @DisplayName("admin 拿不到 department:manage —— 组织结构只有超管能改（P4-C 收回）")
    void adminDoesNotGetDepartmentManage() {
        Set<String> admin = PermissionCatalog.defaultPermissions(ADMIN);

        // P4-C：组织页把部门写操作（增 / 改 / 移 / 设主管 / 删 / 拖拽）全部按 `isSuperAdmin` 显隐，
        // 而 Phase 19 批次 A+B 把 `department:manage` 也给了 admin ⇒ **前端比后端更严**：
        // admin 看不到入口，却能直接调接口改组织结构（实测 PUT /parent 与 POST 均 200）。
        // 显隐不是安全边界（硬约定：越权分支要在「取数据之前」return），因此按「后端说了算」收回。
        //
        // 这条断言是**防回退**用的：`department:manage` 名字看着无害，
        // 很容易在下一次「给 admin 补权限」时被顺手加回来，届时前端显隐与后端授权又会分叉。
        assertFalse(admin.contains(PermissionCatalog.DEPARTMENT_MANAGE),
                "P4-C 已从 admin 收回 department:manage（部门是审批上级的事实源，只有超管能改）");

        // 反向对照：员工维护的三个码**保留** —— 收回的是「改组织结构」，不是「维护员工」。
        // 防止后人把整块「组织与人员」权限一刀切掉（那会让 admin 连员工都改不了）。
        assertTrue(admin.contains(PermissionCatalog.STAFF_VIEW), "admin 仍需能进「组织与人员」页");
        assertTrue(admin.contains(PermissionCatalog.STAFF_MANAGE), "admin 仍需能维护员工");
        assertTrue(admin.contains(PermissionCatalog.STAFF_IMPORT), "admin 仍需能批量导入员工");
    }
}
