package com.enterprise.ticket.module.export.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ExportStatus;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.export.dto.vo.ExportTaskVO;
import com.enterprise.ticket.module.export.entity.ExportTask;
import com.enterprise.ticket.module.export.mapper.ExportTaskMapper;
import com.enterprise.ticket.module.export.support.ExportSheetBuilder;
import com.enterprise.ticket.module.export.support.ExportStorage;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出记录分页 + 删除单测（需求方三波·第三波·需求 17）
 *
 * <p>补做前的导出记录只能看最近 N 条、也不能删，记录会无限增长。新增能力后必须固化三类护栏：
 * <ol>
 *   <li><b>分页收敛</b>：页码下限 / 页容量上限必须收敛，否则前端一个 size=99999 就能拖库；</li>
 *   <li><b>归属可见</b>：非本人且非管理员一律按「不存在」返回（不泄露记录存在性）；</li>
 *   <li><b>删记录同时清文件</b>：先删记录再删文件（删文件失败还有孤儿清理兜底）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ExportTaskPagingDeleteTest {

    private static final Long REQUESTER_ID = 2L;
    private static final Long OTHER_ID = 3L;
    private static final Long ADMIN_ID = 9L;
    private static final Long TASK_ID = 66L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ExportTask.class);
    }

    @Mock
    private ExportTaskMapper taskMapper;
    @Mock
    private ExportSheetBuilder sheetBuilder;
    @Mock
    private ExportStorage storage;
    @Mock
    private ExportTaskRunner runner;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ExportServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void login(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(role);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private ExportTask task() {
        ExportTask task = new ExportTask();
        task.setId(TASK_ID);
        task.setExportType(ExportType.ORDER.name());
        task.setRequesterId(REQUESTER_ID);
        task.setStatus(ExportStatus.SUCCESS.name());
        task.setFileName("工单记录_20260919_1030.xlsx");
        task.setStoredPath("2026/09/abc.xlsx");
        task.setExpireAt(LocalDateTime.now().plusDays(7));
        return task;
    }

    private static ErrorCode codeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    @Test
    @DisplayName("分页：页码下限与页容量上限被收敛，并回传分页元信息")
    @SuppressWarnings("unchecked")
    void pageTasksCapsRangeAndReturnsMeta() {
        login(REQUESTER_ID, RoleCode.USER);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        ArgumentCaptor<Page<ExportTask>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        Page<ExportTask> stubbed = new Page<>(1, 10);
        stubbed.setRecords(List.of(task()));
        stubbed.setTotal(3);

        when(taskMapper.selectPage(pageCaptor.capture(), any())).thenReturn(stubbed);

        PageResult<ExportTaskVO> result = service.pageTasks(null, 0, 9999);

        assertEquals(1L, pageCaptor.getValue().getCurrent(), "页码下限应收敛为 1");
        assertEquals(100L, pageCaptor.getValue().getSize(), "页容量上限应收敛为 100");
        assertEquals(1, result.getRecords().size());
        assertEquals(3L, result.getTotal());
    }

    @Test
    @DisplayName("删除记录：本人可删，记录删除并顺带清理物理文件")
    void deleteTaskOwnerRemovesRecordAndFile() {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task());

        service.deleteTask(TASK_ID);

        verify(taskMapper).deleteById(TASK_ID);
        verify(storage).deleteQuietly("2026/09/abc.xlsx");
    }

    @Test
    @DisplayName("删除记录：无存储路径时不触碰文件系统")
    void deleteTaskWithoutStoredFileSkipsStorage() {
        login(REQUESTER_ID, RoleCode.USER);
        ExportTask task = task();
        task.setStoredPath(null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        service.deleteTask(TASK_ID);

        verify(taskMapper).deleteById(TASK_ID);
        verify(storage, never()).deleteQuietly(any());
    }

    @Test
    @DisplayName("删除记录：普通用户删他人记录 → 按「不存在」返回且无任何副作用")
    void deleteTaskForeignAsUserNotFound() {
        login(OTHER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task());

        assertEquals(ErrorCode.EXPORT_NOT_FOUND, codeOf(() -> service.deleteTask(TASK_ID)));
        verify(taskMapper, never()).deleteById(anyLong());
        verify(storage, never()).deleteQuietly(any());
    }

    @Test
    @DisplayName("删除记录：管理员可删他人记录")
    void deleteTaskForeignAsAdminOk() {
        login(ADMIN_ID, RoleCode.ADMIN);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task());

        service.deleteTask(TASK_ID);

        verify(taskMapper).deleteById(TASK_ID);
    }
}
