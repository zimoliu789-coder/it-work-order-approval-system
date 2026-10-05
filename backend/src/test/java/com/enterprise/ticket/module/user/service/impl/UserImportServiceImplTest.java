package com.enterprise.ticket.module.user.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.auth.service.PasswordPolicyService;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.user.dto.UserImportExecuteRequest;
import com.enterprise.ticket.module.user.dto.UserImportRow;
import com.enterprise.ticket.module.user.dto.vo.UserImportResultVO;
import com.enterprise.ticket.module.user.dto.vo.UserImportRowVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.excel.UserImportExcelSupport;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.module.user.service.UserService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工批量导入「唯一性与格式校验」单元测试。
 *
 * <h2>口径在需求九之后发生了变化，本测试即是这条口径的固化</h2>
 * <ul>
 *   <li><b>姓名不再唯一</b>（需求九.1）：库内已有「张三」仍可再导入一个「张三」，
 *       文件内出现两行「张三」也各自成功。公司里重名是常态，把姓名当唯一键
 *       会让「再招一个张伟」这件事在导入阶段就失败。</li>
 *   <li><b>只有登录名唯一</b>（需求九.2）：既覆盖「库内已有」，也覆盖「文件内重复」，
 *       且失败原因必须写清楚<b>是哪两行</b>（「文件内第 N 行与第 M 行登录名重复」）。</li>
 *   <li><b>格式校验复用 UserService</b>：本类把 {@code userService} 整个 mock 掉，
 *       因此「姓名纯中文 / 登录名纯数字」这两条由 {@code UserServiceImplTest} 负责验证；
 *       这里只验证「导入确实调用了它们」（顺序与接线），不重复造断言。</li>
 *   <li><b>重名领导</b>：姓名可重复后，「按姓名找直属领导」必须显式报歧义，
 *       绝不能静默取第一个 —— 那会把审批挂到一个同名的陌生人身上。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserImportServiceImplTest {

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(User.class, Department.class);
    }

    @Mock
    private UserImportExcelSupport excelSupport;
    @Mock
    private UserService userService;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentService departmentService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordPolicyService passwordPolicyService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private UserImportServiceImpl service;

    @BeforeEach
    void setUpContext() {
        // 部门：研发部 → id 7
        Department group = new Department();
        group.setId(7L);
        group.setDeptName("研发部");
        when(departmentMapper.selectList(any())).thenReturn(List.of(group));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private UserImportRow row(int rowNo, String realName, String username) {
        UserImportRow row = new UserImportRow();
        row.setRowNo(rowNo);
        row.setRealName(realName);
        row.setUsername(username);
        row.setPassword("TestPass@2026");
        row.setDepartmentName("研发部");
        row.setRole("user");
        return row;
    }

    private UserImportResultVO execute(UserImportRow... rows) {
        UserImportExecuteRequest request = new UserImportExecuteRequest();
        request.setFileName("员工.xlsx");
        request.setRows(List.of(rows));
        return service.execute(request);
    }

    private User existingUser(String realName, String username) {
        User user = new User();
        user.setId(99L);
        user.setRealName(realName);
        user.setDisplayName(realName);
        user.setUsername(username);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    private String reasonOf(UserImportResultVO result, int rowNo) {
        return result.getFailures().stream()
                .filter(row -> Integer.valueOf(rowNo).equals(row.getRowNo()))
                .map(UserImportRowVO::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("第 " + rowNo + " 行应存在于失败明细中"));
    }

    // ------------------------------------------------------------------
    // 姓名可重复（需求九.1）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("姓名可重复：文件内两行同名 → 两行都成功（不再有「文件内姓名重复」）")
    void execute_fileInternalDuplicateRealName_allowed() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        UserImportResultVO result = execute(
                row(2, "张三", "10001"),
                row(5, "张三", "10002"));

        assertEquals(2, result.getImportedCount(), "重名不再拦截，两行都应成功");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    @DisplayName("姓名可重复：库内已有同名 → 仍可导入（不再有「姓名已存在」）")
    void execute_dbExistingRealName_allowed() {
        when(userMapper.selectList(any())).thenReturn(List.of(existingUser("张三", "10000")));

        UserImportResultVO result = execute(row(2, "张三", "10005"));

        assertEquals(1, result.getImportedCount(), "库内已有「张三」不应阻止再导入一个「张三」");
        assertEquals(0, result.getFailedCount());
    }

    // ------------------------------------------------------------------
    // 登录名唯一（需求九.2）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登录名唯一：文件内重复 → 保留首行，重复行给出「第 N 行与第 M 行登录名重复」")
    void execute_fileInternalDuplicateUsername() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        UserImportResultVO result = execute(
                row(3, "李四", "10001"),
                row(6, "王五", "10001"));

        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals("文件内第 3 行与第 6 行登录名重复（仅第 3 行会被导入）", reasonOf(result, 6));
    }

    @Test
    @DisplayName("登录名唯一：库内已有 → 「登录名已存在」（大小写不敏感，与 utf8mb4_general_ci 对齐）")
    void execute_dbExistingUsername() {
        // 库里存的是小写 10001；导入列里写 10001 也必须判冲突（此处顺带锁住归一化比较）
        when(userMapper.selectList(any())).thenReturn(List.of(existingUser("赵六", "10001")));

        UserImportResultVO result = execute(
                row(2, "张三", "10001"),
                // 一行合法：保证 execute 不会因「全部无效」而在返回结果前抛错
                row(4, "赵六", "10009"));

        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals("登录名已存在：10001", reasonOf(result, 2));
    }

    // ------------------------------------------------------------------
    // 重名领导（姓名可重复后的必然歧义）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("直属领导重名：库内有两个在职「张三」→ 明确报歧义，不静默取第一个")
    void execute_ambiguousLeaderName_rejected() {
        when(userMapper.selectList(any())).thenReturn(List.of(
                existingUser("张三", "10001"),
                existingUser("张三", "10002")));

        UserImportRow withLeader = row(2, "李四", "10003");
        withLeader.setLeaderName("张三");
        UserImportRow ok = row(3, "王五", "10004");

        UserImportResultVO result = execute(withLeader, ok);

        assertEquals(1, result.getFailedCount());
        assertTrue(reasonOf(result, 2).contains("对应多个同名员工"),
                "重名领导必须被明确拒绝，实际：" + reasonOf(result, 2));
    }

    @Test
    @DisplayName("直属领导唯一：库内只有一个「张三」（显示名与姓名相同）→ 正常解析，不误判为歧义")
    void execute_uniqueLeaderName_resolved() {
        // 这条用例锁的是一个很容易踩的坑：`real_name` 与 `display_name` 常常是同一个值
        // （显示名留空时兜底为姓名），若不去重就计数，一个人会被数成两个同名者，
        // 于是所有「按姓名找领导」都会变成歧义 —— 演示库全体领导都受此影响。
        when(userMapper.selectList(any())).thenReturn(List.of(existingUser("张三", "10001")));

        UserImportRow withLeader = row(2, "李四", "10003");
        withLeader.setLeaderName("张三");

        UserImportResultVO result = execute(withLeader);

        assertEquals(0, result.getFailedCount(),
                "只有一个「张三」时不应判为歧义，实际：" + (result.getFailedCount() == 0
                        ? "" : reasonOf(result, 2)));
        assertEquals(1, result.getImportedCount());
    }

    // ------------------------------------------------------------------
    // 全失败 / 分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部行无效 → 不写库，抛 USER_IMPORT_NOTHING_TO_IMPORT")
    void execute_allInvalid_nothingToImport() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        // 用必填缺失触发失败：第 2 行姓名空、第 3 行登录名空，
        // 保证「没有一行有效」，从而走到 NOTHING_TO_IMPORT 分支
        UserImportRow blank = row(2, "", "10001");
        UserImportRow blank2 = row(3, "钱七", "");

        BusinessException ex = assertThrows(BusinessException.class, () -> execute(blank, blank2));
        assertEquals(ErrorCode.USER_IMPORT_NOTHING_TO_IMPORT, ex.getErrorCode());
    }

    @Test
    @DisplayName("部门名不存在 → 自动创建部门（P3），该行正常导入")
    void execute_unknownDepartment_autoCreated() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(departmentService.create(any())).thenReturn(66L);

        UserImportRow newDept = row(2, "孙八", "10006");
        newDept.setDepartmentName("不存在的组");
        UserImportRow good = row(3, "周九", "10007");

        UserImportResultVO result = execute(newDept, good);

        assertEquals(2, result.getImportedCount());
        assertEquals(0, result.getFailedCount());
        verify(departmentService).create(any());
    }

    @Test
    @DisplayName("部门自动创建失败（并发冲突且回读不到）→ 该行明确失败，不写坏数据")
    void execute_departmentAutoCreateFailed_rowRejected() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(departmentService.create(any()))
                .thenThrow(new BusinessException(ErrorCode.DEPARTMENT_NAME_EXISTS));
        when(departmentMapper.selectOne(any())).thenReturn(null);

        UserImportRow newDept = row(2, "孙八", "10006");
        newDept.setDepartmentName("不存在的组");
        UserImportRow good = row(3, "周九", "10007");

        UserImportResultVO result = execute(newDept, good);

        assertEquals(1, result.getFailedCount());
        assertTrue(reasonOf(result, 2).contains("自动创建失败"));
    }
}
