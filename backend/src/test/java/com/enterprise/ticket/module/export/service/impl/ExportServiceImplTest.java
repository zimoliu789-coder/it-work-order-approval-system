package com.enterprise.ticket.module.export.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ExportStatus;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.dto.vo.ExportResultVO;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出服务单测（规范 §26.1）
 *
 * <p>主战场是三条容易出错、且出错时不会报错的规则：
 * <ol>
 *   <li><b>权限矩阵</b>——设备台账 / 报表仅管理员可导；工单导出人人可用，但普通用户一律
 *       被收口到「我的工单」（前端传 {@code scope=ALL} 也必须无效）；</li>
 *   <li><b>同步 / 异步阈值</b>——恰好等于阈值走同步（"超过"才异步），报表类永不异步
 *       （行数天然小，异步只会让用户白等一条消息）；</li>
 *   <li><b>记录归属</b>——非本人且非管理员访问他人导出任务时统一按「不存在」返回，
 *       既不泄露记录存在性，也不给出可下载地址。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceImplTest {

    private static final Long REQUESTER_ID = 2L;
    private static final Long OTHER_ID = 3L;
    private static final Long ADMIN_ID = 9L;
    private static final Long TASK_ID = 66L;
    private static final String KEY_THRESHOLD = "export_async_threshold";

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ExportTask.class);
    }

    @TempDir
    Path tempDir;

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
    /**
     * 三波补做·第三波·需求 17：导出记录页新增「发起人」列，服务据此批量解析显示名。
     * 用例若断言列表/详情中的 requesterName，需按需 stub 本 Mapper。
     */
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ExportServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

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

    /** 让 count 返回指定行数，阈值固定 10000（可被具体用例覆盖） */
    private void stubCount(ExportType type, long counted, boolean allScope, int threshold) {
        when(sheetBuilder.count(eq(type), any(), eq(allScope))).thenReturn(counted);
        when(systemConfigService.getInt(eq(KEY_THRESHOLD), anyInt())).thenReturn(threshold);
        when(storage.asyncThreshold()).thenReturn(10000);
    }

    /** 模拟 insert 回填自增主键（真实 MyBatis-Plus 会做，Mockito 不会） */
    private void stubInsert() {
        when(taskMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, ExportTask.class).setId(TASK_ID);
            return 1;
        });
    }

    private ExportTask task(String status) {
        ExportTask task = new ExportTask();
        task.setId(TASK_ID);
        task.setExportType(ExportType.ORDER.name());
        task.setRequesterId(REQUESTER_ID);
        task.setStatus(status);
        task.setFileName("工单记录_20260919_1030.xlsx");
        task.setStoredPath("2026/09/abc.xlsx");
        task.setFileSize(2048L);
        task.setTotalRows(5);
        task.setExpireAt(LocalDateTime.now().plusDays(7));
        return task;
    }

    private ErrorCode codeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 权限矩阵
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发起导出：类型为空 → EXPORT_TYPE_INVALID")
    void export_nullType() {
        assertEquals(ErrorCode.EXPORT_TYPE_INVALID, codeOf(() -> service.export(null, new ExportQuery())));
    }

    @Test
    @DisplayName("发起导出：未登录 → UNAUTHORIZED")
    void export_notLoggedIn() {
        assertEquals(ErrorCode.UNAUTHORIZED,
                codeOf(() -> service.export(ExportType.ORDER, new ExportQuery())));
    }

    @Test
    @DisplayName("发起导出：普通用户导设备台账 → 403（设备台账为管理员能力）")
    void export_deviceAsUser_forbidden() {
        login(REQUESTER_ID, RoleCode.USER);
        assertEquals(ErrorCode.FORBIDDEN,
                codeOf(() -> service.export(ExportType.DEVICE, new ExportQuery())));
    }

    @Test
    @DisplayName("发起导出：普通用户导统计报表 → 403（规范 §26.2 报表仅管理员可见）")
    void export_reportAsUser_forbidden() {
        login(REQUESTER_ID, RoleCode.USER);
        assertEquals(ErrorCode.FORBIDDEN,
                codeOf(() -> service.export(ExportType.REPORT_DEVICE_USAGE, new ExportQuery())));
    }

    @Test
    @DisplayName("发起导出：普通用户即使传 scope=ALL，也被强制收口为「我的工单」")
    void export_orderAsUser_scopeForcedToMine() {
        login(REQUESTER_ID, RoleCode.USER);
        ExportQuery query = new ExportQuery();
        query.setScope("ALL");
        stubCount(ExportType.ORDER, 5L, false, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        ExportResultVO vo = service.export(ExportType.ORDER, query);

        assertEquals("SYNC", vo.getMode());
        ArgumentCaptor<ExportQuery> captor = ArgumentCaptor.forClass(ExportQuery.class);
        verify(sheetBuilder).count(eq(ExportType.ORDER), captor.capture(), eq(false));
        assertEquals("MINE", captor.getValue().getScope(), "普通用户不得导出他人范围的工单");
    }

    @Test
    @DisplayName("发起导出：管理员传 scope=ALL → 按「全部工单」处理（allScope=true）")
    void export_orderAsAdmin_scopeAll() {
        login(ADMIN_ID, RoleCode.ADMIN);
        ExportQuery query = new ExportQuery();
        query.setScope("ALL");
        stubCount(ExportType.ORDER, 5L, true, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        ExportResultVO vo = service.export(ExportType.ORDER, query);

        assertEquals("SYNC", vo.getMode());
        ArgumentCaptor<ExportQuery> captor = ArgumentCaptor.forClass(ExportQuery.class);
        verify(sheetBuilder).count(eq(ExportType.ORDER), captor.capture(), eq(true));
        assertEquals("ALL", captor.getValue().getScope());
    }

    // ------------------------------------------------------------------
    // 同步 / 异步阈值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("行数超过阈值 → 异步受理，投递后台并返回 ASYNC（不返回下载地址）")
    void export_overThreshold_async() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.DEVICE, 20000L, false, 10000);
        stubInsert();

        ExportResultVO vo = service.export(ExportType.DEVICE, new ExportQuery());

        assertEquals("ASYNC", vo.getMode());
        assertEquals(TASK_ID, vo.getTaskId());
        assertNull(vo.getDownloadUrl(), "异步未生成完成前不应给出下载地址");
        verify(runner).runAsync(TASK_ID);
        verify(runner, never()).generateAndComplete(anyLong());
    }

    @Test
    @DisplayName("行数恰好等于阈值 → 仍走同步（「超过」才异步，边界不偏移）")
    void export_equalThreshold_sync() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.DEVICE, 10000L, false, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        ExportResultVO vo = service.export(ExportType.DEVICE, new ExportQuery());

        assertEquals("SYNC", vo.getMode());
        verify(runner).generateAndComplete(TASK_ID);
        verify(runner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("报表导出即使是超大数据量也走同步（报表行数天然小，异步只会让用户白等消息）")
    void export_report_neverAsync() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.REPORT_DEVICE_USAGE, 999999L, false, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        ExportResultVO vo = service.export(ExportType.REPORT_DEVICE_USAGE, new ExportQuery());

        assertEquals("SYNC", vo.getMode());
        verify(runner, never()).runAsync(anyLong());
        verify(runner).generateAndComplete(TASK_ID);
    }

    @Test
    @DisplayName("同步路径但生成失败（任务非 SUCCESS）→ EXPORT_FAILED，不返回半成品")
    void export_syncFailed_throws() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.DEVICE, 5L, false, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.FAILED.name()));

        assertEquals(ErrorCode.EXPORT_FAILED,
                codeOf(() -> service.export(ExportType.DEVICE, new ExportQuery())));
    }

    // ------------------------------------------------------------------
    // 记录归属
    // ------------------------------------------------------------------

    @Test
    @DisplayName("查详情：普通用户访问他人任务 → 按「不存在」返回（不泄露存在性）")
    void getTask_othersAsUser_notFound() {
        login(OTHER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));
        assertEquals(ErrorCode.EXPORT_NOT_FOUND, codeOf(() -> service.getTask(TASK_ID)));
    }

    @Test
    @DisplayName("查详情：管理员可查看他人任务")
    void getTask_othersAsAdmin_ok() {
        login(ADMIN_ID, RoleCode.ADMIN);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));
        ExportTaskVO vo = service.getTask(TASK_ID);
        assertEquals(TASK_ID, vo.getId());
        assertNotNull(vo.getStatusLabel());
        assertNotNull(vo.getDownloadUrl(), "已完成任务应给出下载地址");
    }

    @Test
    @DisplayName("下载：普通用户下载他人任务 → 按「不存在」返回")
    void download_othersAsUser_notFound() {
        login(OTHER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));
        assertEquals(ErrorCode.EXPORT_NOT_FOUND, codeOf(() -> service.download(TASK_ID)));
    }

    @Test
    @DisplayName("下载：任务不存在 → EXPORT_NOT_FOUND")
    void download_missingTask() {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);
        assertEquals(ErrorCode.EXPORT_NOT_FOUND, codeOf(() -> service.download(TASK_ID)));
    }

    // ------------------------------------------------------------------
    // 下载状态守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("下载：任务仍在生成中（PENDING）→ EXPORT_NOT_READY")
    void download_pending_notReady() {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.PENDING.name()));
        assertEquals(ErrorCode.EXPORT_NOT_READY, codeOf(() -> service.download(TASK_ID)));
    }

    @Test
    @DisplayName("下载：任务失败 → EXPORT_FAILED（把失败原因透出给用户）")
    void download_failed() {
        login(REQUESTER_ID, RoleCode.USER);
        ExportTask failed = task(ExportStatus.FAILED.name());
        failed.setErrorMessage("磁盘写入失败");
        when(taskMapper.selectById(TASK_ID)).thenReturn(failed);
        assertEquals(ErrorCode.EXPORT_FAILED, codeOf(() -> service.download(TASK_ID)));
    }

    @Test
    @DisplayName("下载：文件已过期 → EXPORT_EXPIRED")
    void download_expired() {
        login(REQUESTER_ID, RoleCode.USER);
        ExportTask expired = task(ExportStatus.SUCCESS.name());
        expired.setExpireAt(LocalDateTime.now().minusDays(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(expired);
        assertEquals(ErrorCode.EXPORT_EXPIRED, codeOf(() -> service.download(TASK_ID)));
    }

    @Test
    @DisplayName("下载：记录成功但磁盘文件缺失 → EXPORT_NOT_FOUND（而非空流/500）")
    void download_fileMissingOnDisk() {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));
        when(storage.resolve("2026/09/abc.xlsx")).thenReturn(tempDir.resolve("missing.xlsx"));
        assertEquals(ErrorCode.EXPORT_NOT_FOUND, codeOf(() -> service.download(TASK_ID)));
    }

    @Test
    @DisplayName("下载：本人且文件存在 → 返回可流式读出的资源")
    void download_success() throws Exception {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));
        Path stored = tempDir.resolve("abc.xlsx");
        Files.writeString(stored, "binary");
        when(storage.resolve("2026/09/abc.xlsx")).thenReturn(stored);

        var result = service.download(TASK_ID);

        assertNotNull(result);
        assertEquals(TASK_ID, result.task().getId());
        assertTrue(result.resource().exists());
    }

    // ------------------------------------------------------------------
    // 导出记录列表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("导出记录：仅已完成任务带下载地址，未完成的为空（不给必然 404 的链接）")
    void recentTasks_downloadUrlOnlyWhenSuccess() {
        login(REQUESTER_ID, RoleCode.USER);
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(ExportStatus.SUCCESS.name()),
                task(ExportStatus.PENDING.name())));
        // 三波补做·第三波·需求 17：列表需解析发起人显示名（批量 selectBatchIds），
        // 未 stub 时 Mockito 返回 null 会让 for-each 抛 NPE。
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        List<ExportTaskVO> list = service.recentTasks(null, 50);

        assertEquals(2, list.size());
        assertNotNull(list.get(0).getStatusLabel());
        assertNotNull(list.get(0).getDownloadUrl(), "已完成任务应有下载地址");
        assertNull(list.get(1).getDownloadUrl(), "生成中任务不应给出下载地址");
    }

    // ------------------------------------------------------------------
    // 使用记录导出（规范 §35）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发起导出：使用记录对普通用户不作管理员限制（数据范围由使用记录服务按角色收窄）")
    void export_usageAsUser_allowed() {
        login(REQUESTER_ID, RoleCode.USER);
        stubCount(ExportType.USAGE, 5L, false, 10000);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        ExportResultVO vo = service.export(ExportType.USAGE, new ExportQuery());

        assertEquals("SYNC", vo.getMode());
    }

    @Test
    @DisplayName("发起导出：使用记录超阈值 → 走异步（属记录类导出，不被报表「永不异步」规则豁免）")
    void export_usageOverThreshold_async() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.USAGE, 20000L, false, 10000);
        stubInsert();

        ExportResultVO vo = service.export(ExportType.USAGE, new ExportQuery());

        assertEquals("ASYNC", vo.getMode());
        verify(runner).runAsync(TASK_ID);
        verify(runner, never()).generateAndComplete(anyLong());
    }

    // ------------------------------------------------------------------
    // 行数落库（修复：记录类导出曾一律显示 0 行）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发起导出：行数在落库时就写入任务（生成阶段拿不到 counted，必须提前留存）")
    void export_recordsRowCountOnInsert() {
        login(ADMIN_ID, RoleCode.ADMIN);
        stubCount(ExportType.USAGE, 42L, false, 10000);
        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        stubInsert();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(ExportStatus.SUCCESS.name()));

        service.export(ExportType.USAGE, new ExportQuery());

        verify(taskMapper).insert(captor.capture());
        assertEquals(42, captor.getValue().getTotalRows(), "行数应在 INSERT 时即写入，而不是留到生成阶段补");
    }
}
