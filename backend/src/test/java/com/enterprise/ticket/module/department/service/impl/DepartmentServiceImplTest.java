package com.enterprise.ticket.module.department.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.dto.vo.DepartmentDeleteResultVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentNodeVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.entity.DepartmentManager;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织与人员 —— 部门侧服务单测（Phase 19 批次 A+B，需求书 二）。
 *
 * <p>本模块此前<b>零单测</b>（旧 bizgroup / handlergroup 的测试随模块一起删除），
 * 而它恰恰是「审批上级」的事实源 —— 部门主管错了，审批就走到错的人那里，
 * 且不会报错。故这里钉死五类护栏：
 * <ol>
 *   <li><b>树组装</b>：层级归属、人数「主部门 ∪ 兼职」并集不重复计、含下级合计自底向上累加；</li>
 *   <li><b>移动防环</b>：不能移到自己 / 自己的子孙下，根节点不可移动，成功移动要整体平移子树 path/depth；</li>
 *   <li><b>主管同步</b>：整体替换 + 只同步「未手工覆盖」的成员（{@code leader_override} 保护）；</li>
 *   <li><b>删部门保护</b>：<b>部门下仍有成员一律拒绝</b>（先调走成员再删）；有子部门 / handler 部门 / 根节点同样拒绝；</li>
 *   <li><b>主部门 ∪ 兼职</b>的成员口径（IT运维组不得因为「没人把它当主部门」而空列表）。</li>
 * </ol>
 *
 * <p>注入说明：本类用方法引用构造 {@code LambdaQueryWrapper}，其求值依赖 MyBatis-Plus 的
 * Lambda 缓存（Spring 启动时建立）。纯 Mockito 测试不启 Spring，故在 {@code @BeforeAll}
 * 里显式注册用到的实体，否则会抛 {@code can not find lambda cache for this entity}。
 * 部分桩（{@code selectBatchIds}）在「无兼职 / 无主管」的分支下不会被触达，故用
 * {@code lenient()} 声明，避免严格模式下误报为多余桩。
 */
@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplTest {

    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentManagerMapper departmentManagerMapper;
    @Mock
    private UserDepartmentMapper userDepartmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserService userService;
    @Mock
    private ApprovalFlowVersionMapper approvalFlowVersionMapper;

    @InjectMocks
    private DepartmentServiceImpl service;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Department.class, DepartmentManager.class, UserDepartment.class, User.class);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static Department dept(long id, String name, Long parentId, String path, int depth) {
        Department d = new Department();
        d.setId(id);
        d.setDeptName(name);
        d.setParentId(parentId);
        d.setPath(path);
        d.setDepth(depth);
        d.setSortOrder(0);
        d.setHandlerGroup(false);
        d.setStatus(true);
        return d;
    }

    private static User user(long id, Long departmentId, boolean enabled, boolean dimission) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName("员工" + id);
        u.setRole("user");
        u.setDepartmentId(departmentId);
        u.setEnabled(enabled);
        u.setDimission(dimission);
        return u;
    }

    private static DepartmentManager manager(long departmentId, Long userId) {
        DepartmentManager m = new DepartmentManager();
        m.setId(userId);
        m.setDepartmentId(departmentId);
        m.setUserId(userId);
        return m;
    }

    private static UserDepartment membership(long userId, long departmentId) {
        UserDepartment ud = new UserDepartment();
        ud.setUserId(userId);
        ud.setDepartmentId(departmentId);
        return ud;
    }

    private static ErrorCode errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    /** 递归查节点（含子孙），找不到返回 null */
    private static DepartmentNodeVO find(List<DepartmentNodeVO> nodes, long id) {
        for (DepartmentNodeVO node : nodes) {
            if (node.getId() == id) {
                return node;
            }
            DepartmentNodeVO hit = find(node.getChildren(), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static List<Long> idsOf(List<DepartmentNodeVO> nodes) {
        List<Long> ids = new ArrayList<>();
        for (DepartmentNodeVO node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    // ==================================================================
    // 一、树组装
    // ==================================================================

    /**
     * 「公司 / 研发部 / 前端一组 / IT运维组」四节点，成员含主部门与兼职两种归属。
     *
     * <p>关键断言：
     * <ul>
     *   <li>层级归属正确（研发部挂在公司下，前端一组挂在研发部下）；</li>
     *   <li>IT运维组人数 = 主部门 2 人 + 仅兼职 1 人 = 3（并集不重复计）；</li>
     *   <li>「主部门已是本部门」的兼职行不得重复计入（u6）；</li>
     *   <li>含下级合计自底向上累加（研发部 4 = 本级 3 + 前端一组 1）。</li>
     * </ul>
     */
    @Test
    @DisplayName("树组装：层级归属 + 人数并集不重复计 + 含下级合计自底向上")
    void treeAssemblesHierarchyAndCounts() {
        Department company = dept(1, "公司", null, "/1/", 0);
        Department dev = dept(10, "研发部", 1L, "/1/10/", 1);
        Department fe = dept(11, "前端一组", 10L, "/1/10/11/", 2);
        Department itOps = dept(20, "IT运维组", 1L, "/1/20/", 1);
        itOps.setHandlerGroup(true);
        when(departmentMapper.selectList(any())).thenReturn(List.of(company, dev, fe, itOps));

        // u1/u2/u5 主部门=研发部；u3 主=前端一组；u4/u6 主=IT运维组
        List<User> users = List.of(
                user(1, 10L, true, false),
                user(2, 10L, true, false),
                user(3, 11L, true, false),
                user(4, 20L, true, false),
                user(5, 10L, true, false),
                user(6, 20L, true, false));
        when(userMapper.selectList(any())).thenReturn(users);
        lenient().when(userMapper.selectBatchIds(any())).thenReturn(users);
        // u5 兼职在 IT运维组（真实计入）；u6 兼职在自己主部门（不得重复计）；u3 同理
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(
                membership(5, 20), membership(6, 20), membership(3, 11)));
        when(departmentManagerMapper.selectList(any())).thenReturn(List.of(manager(10, 1L)));

        List<DepartmentNodeVO> roots = service.tree();

        assertEquals(1, roots.size(), "应只有一棵树（公司）");
        DepartmentNodeVO root = roots.get(0);
        assertEquals(1L, root.getId());
        assertEquals(List.of(10L, 20L), idsOf(root.getChildren()), "公司的子部门应按 depth/sort/id 顺序为 研发部、IT运维组");

        DepartmentNodeVO devNode = find(roots, 10);
        DepartmentNodeVO feNode = find(roots, 11);
        DepartmentNodeVO itNode = find(roots, 20);

        assertEquals(List.of(11L), idsOf(devNode.getChildren()), "前端一组应挂在研发部下");
        assertTrue(feNode.getChildren().isEmpty(), "前端一组应是叶子");

        assertEquals(3L, devNode.getMemberCount(), "研发部本级 = u1/u2/u5");
        assertEquals(1L, feNode.getMemberCount(), "前端一组本级 = u3");
        assertEquals(3L, itNode.getMemberCount(), "IT运维组 = 主部门 2 人 + 仅兼职 1 人（u6 兼职在主部门不得重复计）");
        assertEquals(0L, root.getMemberCount(), "公司本级无人");

        assertEquals(4L, devNode.getTotalMemberCount(), "研发部含下级 = 3 + 前端一组 1");
        assertEquals(1L, feNode.getTotalMemberCount());
        assertEquals(3L, itNode.getTotalMemberCount());
        assertEquals(7L, root.getTotalMemberCount(), "公司含下级 = 4 + 3");

        assertTrue(itNode.getHandlerGroup(), "IT运维组应带最终处理部门标记");
        assertEquals(1, devNode.getManagers().size(), "研发部主管应回填为 1 人");
        assertEquals(1L, devNode.getManagers().get(0).getId());
    }

    @Test
    @DisplayName("树组装：无部门时返回空列表")
    void treeEmpty() {
        when(departmentMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.tree().isEmpty());
    }

    @Test
    @DisplayName("部门选项：displayPath 用「 / 」拼接祖先链")
    void optionsBuildDisplayPath() {
        when(departmentMapper.selectList(any())).thenReturn(List.of(
                dept(1, "公司", null, "/1/", 0),
                dept(10, "研发部", 1L, "/1/10/", 1),
                dept(11, "前端一组", 10L, "/1/10/11/", 2)));

        List<DepartmentOptionVO> options = service.options();

        assertEquals(3, options.size());
        assertEquals("公司", byId(options, 1L).getDisplayPath());
        assertEquals("公司 / 研发部", byId(options, 10L).getDisplayPath());
        assertEquals("公司 / 研发部 / 前端一组", byId(options, 11L).getDisplayPath());
        assertEquals(2, byId(options, 11L).getDepth());
    }

    private static DepartmentOptionVO byId(List<DepartmentOptionVO> options, long id) {
        return options.stream().filter(o -> o.getId() == id).findFirst().orElseThrow();
    }

    // ==================================================================
    // 二、新增 / 编辑
    // ==================================================================

    @Test
    @DisplayName("新增：未指定上级时挂到根节点，path 先落占位再回填为 /父id/自身id/")
    void createRootFallbackAndPathBackfill() {
        when(departmentMapper.selectOne(any())).thenReturn(dept(1, "公司", null, "/1/", 0));
        when(departmentMapper.selectById(1L)).thenReturn(dept(1, "公司", null, "/1/", 0));
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        List<String> pathAtInsert = new ArrayList<>();
        when(departmentMapper.insert(any())).thenAnswer(inv -> {
            Department d = inv.getArgument(0);
            pathAtInsert.add(d.getPath());
            d.setId(30L);
            return 1;
        });
        when(departmentMapper.updateById(any())).thenReturn(1);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("测试组");
        Long id = service.create(request);

        assertEquals(30L, id);
        assertEquals("/1/PENDING/", pathAtInsert.get(0), "插入时的占位后缀应挂在父路径下");

        ArgumentCaptor<Department> saved = ArgumentCaptor.forClass(Department.class);
        verify(departmentMapper).updateById(saved.capture());
        assertEquals("/1/30/", saved.getValue().getPath(), "回填后 path = 父路径 + 自身 id");
        assertEquals(1, saved.getValue().getDepth(), "depth = 父 depth + 1");
        assertEquals(1L, saved.getValue().getParentId());
        assertFalse(saved.getValue().getHandlerGroup(), "新建部门默认不是最终处理部门");
        assertTrue(saved.getValue().getStatus());
    }

    @Test
    @DisplayName("新增：名称为空白 → 部门名称错误")
    void createBlankNameRejected() {
        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("   ");
        assertEquals(ErrorCode.DEPARTMENT_NAME_EXISTS, errorCodeOf(() -> service.create(request)));
    }

    @Test
    @DisplayName("新增：同级重名 → 部门名称错误（不落库）")
    void createDuplicateNameRejected() {
        when(departmentMapper.selectCount(any())).thenReturn(1L);
        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("研发部");
        request.setParentId(1L);

        assertEquals(ErrorCode.DEPARTMENT_NAME_EXISTS, errorCodeOf(() -> service.create(request)));
        verify(departmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("编辑：改名并绑定流程版本 / 备注（不触发移动）")
    void updateRenamesAndBindsFlow() {
        Department dev = dept(10, "研发部", 1L, "/1/10/", 1);
        when(departmentMapper.selectById(10L)).thenReturn(dev);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(approvalFlowVersionMapper.selectById(77L)).thenReturn(publishedVersion(77L));

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("研发中心");
        request.setApprovalFlowVersionId(77L);
        request.setRemark("含前端/后端");

        service.update(10L, request);

        String sqlSet = lastUpdateSqlSet();
        assertTrue(sqlSet.contains("dept_name"), "SET 子句应含 dept_name：" + sqlSet);
        assertTrue(sqlSet.contains("approval_flow_version_id"), "SET 子句应含流程版本列：" + sqlSet);
        assertTrue(sqlSet.contains("remark"), "SET 子句应含 remark：" + sqlSet);
        // 实体本身也被就地更新（供调用方/日志使用），但落库以 SET 子句为准
        assertEquals("研发中心", dev.getDeptName());
        assertEquals(1L, dev.getParentId(), "编辑不得改写父子关系");
        assertEquals("/1/10/", dev.getPath(), "编辑不得改写 path");
        assertFalse(sqlSet.contains("parent_id"), "层级只能走 /parent 端点改：" + sqlSet);
        assertFalse(sqlSet.contains("path"), "path 由 move 维护，编辑不得碰：" + sqlSet);
    }

    /**
     * 回归护栏（批次 D 全量回归发现的缺陷）：<b>解绑流程 / 清空备注必须真的落成 NULL</b>。
     *
     * <p>全局 {@code update-strategy: not_null} 会让 {@code updateById} 跳过 null 字段，
     * 于是「解绑」返回「部门已保存」却什么都没改 —— 静默无效。
     * 本用例钉死「显式 UPDATE + set」这个实现选择：谁把它改回 {@code updateById}，这里立刻翻红。
     */
    @Test
    @DisplayName("编辑：解绑流程 / 清空备注必须真的写进 SET 子句（null 不得被字段策略跳过）")
    void updateWritesNullsIntoSetClause() {
        Department dev = dept(10, "研发部", 1L, "/1/10/", 1);
        dev.setApprovalFlowVersionId(77L);
        dev.setRemark("旧备注");
        when(departmentMapper.selectById(10L)).thenReturn(dev);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("研发部");
        request.setApprovalFlowVersionId(null);
        request.setRemark("   ");

        service.update(10L, request);

        String sqlSet = lastUpdateSqlSet();
        assertTrue(sqlSet.contains("approval_flow_version_id"), "解绑必须把列写进 SET：" + sqlSet);
        assertTrue(sqlSet.contains("remark"), "清空备注必须把列写进 SET：" + sqlSet);
        verify(departmentMapper, never()).updateById(any());
        // 解绑（null）不该顺带查一次流程版本表
        verify(approvalFlowVersionMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("编辑：绑定不存在的流程版本 → 流程版本不存在（且不落库）")
    void updateRejectsUnknownFlowVersion() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(approvalFlowVersionMapper.selectById(999999999L)).thenReturn(null);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("研发部");
        request.setApprovalFlowVersionId(999999999L);

        assertEquals(ErrorCode.FLOW_VERSION_NOT_FOUND,
                errorCodeOf(() -> service.update(10L, request)));
        verify(departmentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("编辑：绑定未发布（草稿）版本 → 尚无已发布版本（且不落库）")
    void updateRejectsDraftFlowVersion() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        ApprovalFlowVersion draft = new ApprovalFlowVersion();
        draft.setId(78L);
        draft.setPublishedAt(null);
        when(approvalFlowVersionMapper.selectById(78L)).thenReturn(draft);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("研发部");
        request.setApprovalFlowVersionId(78L);

        assertEquals(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                errorCodeOf(() -> service.update(10L, request)));
        verify(departmentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("新增：绑定不存在的流程版本 → 拒绝且不落库（不留下半个部门）")
    void createRejectsUnknownFlowVersion() {
        when(departmentMapper.selectOne(any())).thenReturn(dept(1, "公司", null, "/1/", 0));
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(approvalFlowVersionMapper.selectById(999999999L)).thenReturn(null);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("新部门");
        request.setApprovalFlowVersionId(999999999L);

        assertEquals(ErrorCode.FLOW_VERSION_NOT_FOUND,
                errorCodeOf(() -> service.create(request)));
        verify(departmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("编辑：改成同级已存在的名称 → 部门名称错误")
    void updateDuplicateNameRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectCount(any())).thenReturn(1L);

        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("市场部");
        assertEquals(ErrorCode.DEPARTMENT_NAME_EXISTS, errorCodeOf(() -> service.update(10L, request)));
        verify(departmentMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 新增 / 编辑：断言辅助
    // ------------------------------------------------------------------

    /**
     * 取出「编辑部门」那次 UPDATE 的 SET 子句。
     *
     * <p>为什么抓 wrapper 而不是抓实体：编辑刻意走显式 UPDATE（见
     * {@code DepartmentServiceImpl#update} 的注释），实体参数恒为 {@code null}；
     * 要断言「解绑 / 清空真的进了 SET 子句」就只能看 wrapper 本身。
     * 这是本仓库既有惯例（{@code OrderConcurrencyContractTest} / {@code UserServiceImplTest} 同法）。
     */
    private String lastUpdateSqlSet() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Department>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departmentMapper).update(any(), captor.capture());
        return ((LambdaUpdateWrapper<Department>) captor.getValue()).getSqlSet();
    }

    /** 已发布的流程版本（{@code publishedAt} 非空 ⇒ 非草稿），用于绑定入参 */
    private static ApprovalFlowVersion publishedVersion(long id) {
        ApprovalFlowVersion version = new ApprovalFlowVersion();
        version.setId(id);
        version.setPublishedAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        return version;
    }

    @Test
    @DisplayName("编辑：部门不存在 → 部门不存在")
    void updateMissingDepartment() {
        when(departmentMapper.selectById(999L)).thenReturn(null);
        DepartmentSaveRequest request = new DepartmentSaveRequest();
        request.setDeptName("任意");
        assertEquals(ErrorCode.DEPARTMENT_NOT_FOUND, errorCodeOf(() -> service.update(999L, request)));
    }

    // ==================================================================
    // 三、移动防环
    // ==================================================================

    @Test
    @DisplayName("移动：不能移到自己下面")
    void moveToSelfRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));

        assertEquals(ErrorCode.DEPARTMENT_MOVE_INVALID, errorCodeOf(() -> service.move(10L, 10L)));
        verify(departmentMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("移动：不能移进自己的子孙（否则成环，子树 path 无限增长）")
    void moveIntoOwnDescendantRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectById(11L)).thenReturn(dept(11, "前端一组", 10L, "/1/10/11/", 2));

        assertEquals(ErrorCode.DEPARTMENT_MOVE_INVALID, errorCodeOf(() -> service.move(10L, 11L)));
        verify(departmentMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("移动：根节点不可移动")
    void moveRootRejected() {
        when(departmentMapper.selectById(1L)).thenReturn(dept(1, "公司", null, "/1/", 0));

        assertEquals(ErrorCode.DEPARTMENT_ROOT_PROTECTED, errorCodeOf(() -> service.move(1L, null)));
        verify(departmentMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("移动：成功移动整体平移子树 path / depth（子子孙孙一起走）")
    void moveRewritesSubtree() {
        Department dev = dept(10, "研发部", 1L, "/1/10/", 1);
        Department fe = dept(11, "前端一组", 10L, "/1/10/11/", 2);
        Department market = dept(20, "市场部", 1L, "/1/20/", 1);
        when(departmentMapper.selectById(10L)).thenReturn(dev);
        when(departmentMapper.selectById(20L)).thenReturn(market);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(departmentMapper.selectList(any())).thenReturn(List.of(fe));
        List<Department> updated = new ArrayList<>();
        when(departmentMapper.updateById(any())).thenAnswer(inv -> {
            updated.add(inv.getArgument(0));
            return 1;
        });

        service.move(10L, 20L);

        assertEquals(2, updated.size(), "移动的部门 + 其 1 个后代都应被重写");
        Department movedRoot = updated.get(0);
        assertEquals(20L, movedRoot.getParentId());
        assertEquals("/1/20/10/", movedRoot.getPath());
        assertEquals(2, movedRoot.getDepth(), "新 depth = 新父 depth + 1");

        Department movedChild = updated.get(1);
        assertEquals("/1/20/10/11/", movedChild.getPath(), "后代应换掉旧前缀后拼接新前缀");
        assertEquals(3, movedChild.getDepth(), "后代 depth 应整体平移（+1）");
    }

    @Test
    @DisplayName("移动：目标同级下已有同名部门 → 部门名称错误")
    void moveNameConflictAtTargetRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectById(20L)).thenReturn(dept(20, "市场部", 1L, "/1/20/", 1));
        when(departmentMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.DEPARTMENT_NAME_EXISTS, errorCodeOf(() -> service.move(10L, 20L)));
        verify(departmentMapper, never()).updateById(any());
    }

    // ==================================================================
    // 四、主管同步
    // ==================================================================

    @Test
    @DisplayName("设主管：整体替换后同步成员直属主管，且只改「未手工覆盖」的人")
    void setManagersReplacesAndSyncsOnlyUnoverridden() {
        when(departmentMapper.selectById(30L)).thenReturn(dept(30, "研发部", 1L, "/1/30/", 1));
        when(userMapper.selectById(5L)).thenReturn(user(5, 30L, true, false));
        when(userMapper.selectById(6L)).thenReturn(user(6, 30L, true, false));
        // 同步阶段重新读一次主管，应按 user_id 升序（DB 侧 ORDER BY），取第一个作为默认直属主管
        when(departmentManagerMapper.selectList(any())).thenReturn(List.of(manager(30, 5L), manager(30, 6L)));
        when(userMapper.update(any(), any())).thenReturn(4);

        service.setManagers(30L, List.of(5L, 6L));

        verify(departmentManagerMapper).delete(any());
        verify(departmentManagerMapper, times(2)).insert(any());
        ArgumentCaptor<DepartmentManager> inserted = ArgumentCaptor.forClass(DepartmentManager.class);
        verify(departmentManagerMapper, times(2)).insert(inserted.capture());
        assertEquals(30L, inserted.getAllValues().get(0).getDepartmentId());
        assertEquals(5L, inserted.getAllValues().get(0).getUserId());

        ArgumentCaptor<LambdaUpdateWrapper<User>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper).update(any(), wrapper.capture());
        LambdaUpdateWrapper<User> update = wrapper.getValue();
        String where = update.getSqlSegment().toLowerCase(Locale.ROOT);
        String set = update.getSqlSet().toLowerCase(Locale.ROOT);
        assertTrue(where.contains("department_id"), "同步应限定「本部门成员」：" + where);
        assertTrue(set.contains("leader_id"), "同步应写入 leader_id（SET 子句）：" + set);
        assertTrue(where.contains("leader_override"),
                "必须带 leader_override 保护条件 —— 少了它会把管理员手工指定的直属主管冲掉：" + where);
    }

    @Test
    @DisplayName("设主管：主管用户不存在 → 用户不存在（不静默落一条悬空主管行）")
    void setManagersUnknownUserRejected() {
        when(departmentMapper.selectById(30L)).thenReturn(dept(30, "研发部", 1L, "/1/30/", 1));
        when(userMapper.selectById(99L)).thenReturn(null);

        assertEquals(ErrorCode.USER_NOT_FOUND, errorCodeOf(() -> service.setManagers(30L, List.of(99L))));
        verify(departmentManagerMapper, never()).insert(any());
    }

    @Test
    @DisplayName("设主管：传空数组 = 清空主管，且无主管时不触发成员同步")
    void setManagersEmptyClearsAndSkipsSync() {
        when(departmentMapper.selectById(30L)).thenReturn(dept(30, "研发部", 1L, "/1/30/", 1));
        when(departmentManagerMapper.selectList(any())).thenReturn(List.of());

        service.setManagers(30L, List.of());

        verify(departmentManagerMapper).delete(any());
        verify(departmentManagerMapper, never()).insert(any());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("同步成员主管：部门无主管 → 不动任何人的直属主管")
    void syncMembersLeaderNoManagerIsNoop() {
        when(departmentManagerMapper.selectList(any())).thenReturn(List.of());

        service.syncMembersLeader(30L);

        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("主管解析：部门 id 为 null 时直接返回空 / null（不查库）")
    void managerLookupWithNullDepartment() {
        assertNull(service.primaryManagerOf(null));
        assertTrue(service.managerIdsOf(null).isEmpty());
        verify(departmentManagerMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("主管解析：过滤空 user_id、去重，且按 user_id 升序（默认主管=第一个）")
    void managerIdsOfFiltersDedupsAndOrders() {
        when(departmentManagerMapper.selectList(any())).thenReturn(List.of(
                manager(30, 5L), manager(30, null), manager(30, 5L), manager(30, 8L)));

        assertEquals(List.of(5L, 8L), service.managerIdsOf(30L));
        assertEquals(5L, service.primaryManagerOf(30L), "主主管取升序第一个");

        ArgumentCaptor<LambdaQueryWrapper<DepartmentManager>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(departmentManagerMapper, times(2)).selectList(wrapper.capture());
        String sql = wrapper.getAllValues().get(0).getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("department_id"), "应按部门过滤：" + sql);
        assertTrue(sql.contains("order by") && sql.contains("user_id"),
                "「最小 user_id 即默认主管」这条契约只能由 DB 侧 ORDER BY 保证：" + sql);
    }

    @Test
    @DisplayName("最终处理部门：未配置返回 null；已配置返回其 id")
    void handlerDepartmentIdLookup() {
        when(departmentMapper.selectOne(any())).thenReturn(null);
        assertNull(service.handlerDepartmentId());

        Department itOps = dept(20, "IT运维组", 1L, "/1/20/", 1);
        itOps.setHandlerGroup(true);
        when(departmentMapper.selectOne(any())).thenReturn(itOps);
        assertEquals(20L, service.handlerDepartmentId());
    }

    @Test
    @DisplayName("最终处理部门成员：只取「启用且未离职」的人（IT执行人候选）")
    void handlerDepartmentMemberIdsFiltersInactive() {
        Department itOps = dept(20, "IT运维组", 1L, "/1/20/", 1);
        itOps.setHandlerGroup(true);
        when(departmentMapper.selectOne(any())).thenReturn(itOps);
        when(userMapper.selectList(any())).thenReturn(List.of(user(4, 20L, true, false), user(6, 20L, false, false)));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectById(4L)).thenReturn(user(4, 20L, true, false));
        when(userMapper.selectById(6L)).thenReturn(user(6, 20L, false, false));

        assertEquals(List.of(4L), service.handlerDepartmentMemberIds());
    }

    @Test
    @DisplayName("最终处理部门成员：未配置最终处理部门 → 空列表（不查成员）")
    void handlerDepartmentMemberIdsWhenUnset() {
        when(departmentMapper.selectOne(any())).thenReturn(null);
        assertTrue(service.handlerDepartmentMemberIds().isEmpty());
        verify(userMapper, never()).selectList(any());
    }

    // ==================================================================
    // 五、删部门：仅空部门可删（有成员/子部门/handler/根节点一律拒绝）
    // ==================================================================

    @Test
    @DisplayName("删部门：有子部门 → 拒绝（先处理子部门）")
    void deleteWithChildrenRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectCount(any())).thenReturn(2L);

        assertEquals(ErrorCode.DEPARTMENT_HAS_CHILDREN, errorCodeOf(() -> service.delete(10L)));
        verify(departmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删部门：最终处理部门（IT运维组）不可删除")
    void deleteHandlerDepartmentRejected() {
        Department itOps = dept(20, "IT运维组", 1L, "/1/20/", 1);
        itOps.setHandlerGroup(true);
        when(departmentMapper.selectById(20L)).thenReturn(itOps);
        when(departmentMapper.selectCount(any())).thenReturn(0L);

        assertEquals(ErrorCode.DEPARTMENT_ROOT_PROTECTED, errorCodeOf(() -> service.delete(20L)));
        verify(departmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删部门：根节点不可删除")
    void deleteRootRejected() {
        when(departmentMapper.selectById(1L)).thenReturn(dept(1, "公司", null, "/1/", 0));

        assertEquals(ErrorCode.DEPARTMENT_ROOT_PROTECTED, errorCodeOf(() -> service.delete(1L)));
        verify(departmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删部门：部门下仍有成员 → 拒绝（先调走成员），不删任何行")
    void deleteWithMembersRejected() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        // 主部门 2 人 + 兼职 1 人 = 3 名成员
        when(userMapper.selectList(any())).thenReturn(List.of(user(1, 10L, true, false), user(2, 10L, true, false)));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(membership(9, 10)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(10L));
        assertEquals(ErrorCode.DEPARTMENT_HAS_MEMBERS, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("3 名成员"), "提示应给出成员数：" + ex.getMessage());

        verify(departmentMapper, never()).deleteById(anyLong());
        verify(departmentManagerMapper, never()).delete(any());
    }

    @Test
    @DisplayName("删部门：空部门 → 删除成功、主管关系清除、回传 0 名成员")
    void deleteEmptyDepartmentSucceeds() {
        when(departmentMapper.selectById(10L)).thenReturn(dept(10, "研发部", 1L, "/1/10/", 1));
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(departmentMapper.selectById(1L)).thenReturn(dept(1, "公司", null, "/1/", 0));
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of());

        DepartmentDeleteResultVO result = service.delete(10L);

        verify(departmentManagerMapper).delete(any());
        verify(departmentMapper).deleteById(10L);

        assertEquals("研发部", result.getDeptName());
        assertEquals(0, result.getMovedMemberCount());
        assertEquals(1L, result.getMovedToDepartmentId());
        assertEquals("公司", result.getMovedToDepartmentName());
    }

    @Test
    @DisplayName("删部门：部门不存在 → 部门不存在")
    void deleteMissingDepartment() {
        when(departmentMapper.selectById(999L)).thenReturn(null);
        assertEquals(ErrorCode.DEPARTMENT_NOT_FOUND, errorCodeOf(() -> service.delete(999L)));
    }

    // ==================================================================
    // 六、子树与成员口径
    // ==================================================================

    @Test
    @DisplayName("子树：按物化路径前缀取「本部门及全部下级」")
    void subtreeIdsByPathPrefix() {
        Department dev = dept(10, "研发部", 1L, "/1/10/", 1);
        when(departmentMapper.selectById(10L)).thenReturn(dev);
        when(departmentMapper.selectList(any())).thenReturn(List.of(dev, dept(11, "前端一组", 10L, "/1/10/11/", 2)));

        assertEquals(List.of(10L, 11L), service.subtreeIds(10L));

        ArgumentCaptor<LambdaQueryWrapper<Department>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(departmentMapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("path") && sql.contains("like"),
                "取子树必须走 path 前缀匹配（改成递归查子节点会退化为层层打库）：" + sql);
    }

    @Test
    @DisplayName("子树：null 或缺 path 时返回空（不误判为整棵树）")
    void subtreeIdsGuard() {
        assertTrue(service.subtreeIds(null).isEmpty());

        Department noPath = dept(10, "研发部", 1L, null, 1);
        when(departmentMapper.selectById(10L)).thenReturn(noPath);
        assertTrue(service.subtreeIds(10L).isEmpty());
    }

    @Test
    @DisplayName("成员列表：主部门 ∪ 兼职并集、按 id 排序、带部门名（IT运维组不为空的原因）")
    void membersOfUnionSorted() {
        when(userMapper.selectList(any())).thenReturn(List.of(user(2, 10L, true, false), user(1, 10L, true, false)));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(membership(9, 10)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(2, 10L, true, false), user(1, 10L, true, false), user(9, 11L, true, false)));
        when(departmentMapper.selectList(any())).thenReturn(List.of(
                dept(10, "研发部", 1L, "/1/10/", 1), dept(11, "前端一组", 10L, "/1/10/11/", 2)));

        List<UserOptionVO> members = service.membersOf(10L);

        assertEquals(List.of(1L, 2L, 9L), members.stream().map(UserOptionVO::getId).toList());
        assertEquals("研发部", members.get(0).getDepartmentName());
        assertEquals("前端一组", members.get(2).getDepartmentName(), "兼职成员应带其主部门名");
        assertTrue(members.get(2).getAvailable());
    }

    @Test
    @DisplayName("成员列表：部门无人时返回空（不发起批量查询）")
    void membersOfEmpty() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.membersOf(10L).isEmpty());
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("对照：本条断言有辨别力（空列表不该被当成有成员）")
    void assertionIsDiscriminating() {
        List<UserOptionVO> empty = List.of();
        assertTrue(empty.isEmpty(), "空列表必须被判为空，否则上面的用例等于恒真");
        assertEquals(0, empty.size(), "空列表长度为 0 —— 说明 isEmpty 断言不是恒真");
    }
}
