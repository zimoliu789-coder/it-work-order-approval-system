package com.enterprise.ticket.module.export.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ExportStatus;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.export.dto.ExportDownload;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.dto.vo.ExportResultVO;
import com.enterprise.ticket.module.export.dto.vo.ExportTaskVO;
import com.enterprise.ticket.module.export.entity.ExportTask;
import com.enterprise.ticket.module.export.mapper.ExportTaskMapper;
import com.enterprise.ticket.module.export.service.ExportService;
import com.enterprise.ticket.module.export.support.ExportSheetBuilder;
import com.enterprise.ticket.module.export.support.ExportStorage;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.enterprise.ticket.module.export.service.impl.ExportTaskRunner.SCOPE_ALL;

/**
 * Excel 导出实现
 *
 * <p><b>为什么 {@link #export} 不加 {@code @Transactional}</b>：
 * 异步路径要求在「任务行已提交」之后线程池才能取到它。若把整个方法放进事务，
 * 线程池可能在提交前就开始执行，查出「任务不存在」而静默跳过（症状是
 * 「界面说已受理，却永远没有文件，也没有消息」，极难排查）。
 * 这里只做一次 INSERT（本身即一个原子语句），不需要事务包裹。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    /** 异步阈值配置键（ 可配置参数，种子值 10000） */
    private static final String KEY_ASYNC_THRESHOLD = "export_async_threshold";

    /** 导出记录列表默认返回条数上限 */
    private static final int MAX_TASK_LIST = 50;

    /** 导出记录分页大小上限（防止前端传入超大 size 拖库） */
    private static final int MAX_PAGE_SIZE = 100;

    /** 下载文件名时间戳格式（形如 20260919_1030） */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final ExportTaskMapper taskMapper;
    private final ExportSheetBuilder sheetBuilder;
    private final ExportStorage storage;
    private final ExportTaskRunner runner;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 发起导出
    // ------------------------------------------------------------------

    @Override
    public ExportResultVO export(ExportType type, ExportQuery query) {
        if (type == null) {
            throw new BusinessException(ErrorCode.EXPORT_TYPE_INVALID, "导出类型不合法");
        }
        LoginUser user = requireLogin();
        boolean admin = RoleCode.isAdminOrAbove(user.getRole());
        assertExportable(type, admin);

        ExportQuery effective = query == null ? new ExportQuery() : query;
        // 权限收口：非管理员一律按「我的工单」，忽略前端传入的 scope
        boolean allScope = admin && !type.isReport() && SCOPE_ALL.equalsIgnoreCase(effective.getScope());
        effective.setScope(allScope ? SCOPE_ALL : "MINE");

        long counted = sheetBuilder.count(type, effective, allScope);
        int threshold = systemConfigService.getInt(KEY_ASYNC_THRESHOLD, storage.asyncThreshold());
        // 报表类永不异步：行数天然很小，异步只会让用户白等一条消息
        boolean async = !type.isReport() && counted > threshold;

        ExportTask task = new ExportTask();
        task.setExportType(type.name());
        task.setQueryJson(writeQuery(effective));
        task.setRequesterId(user.getId());
        task.setStatus(async ? ExportStatus.PENDING.name() : ExportStatus.RUNNING.name());
        task.setFileName(expectedFileName(type));
        // 行数在「发起导出」这一刻就已经算出来了（上面刚 count 过），先落库：
        // 生成阶段（ExportTaskRunner）手里没有 counted，若不留存，导出记录页与完成消息
        // 会把所有记录类导出都显示成 0 行。
        task.setTotalRows((int) Math.min(counted, Integer.MAX_VALUE));
        taskMapper.insert(task);

        ExportResultVO vo = new ExportResultVO();
        vo.setTaskId(task.getId());
        vo.setExportType(type.name());
        vo.setExportTypeLabel(type.getLabel());
        vo.setTotalRows((int) Math.min(counted, Integer.MAX_VALUE));

        if (async) {
            runner.runAsync(task.getId());
            vo.setMode("ASYNC");
            vo.setFileName(task.getFileName());
            vo.setMessage("数据量较大（" + counted + " 行），已转为后台生成，完成后会在消息中心通知您下载");
            return vo;
        }

        // 同步路径：当场生成，生成结果与异步路径完全一致（共用同一份实现）
        runner.generateAndComplete(task.getId());
        ExportTask done = taskMapper.selectById(task.getId());
        if (done == null || !ExportStatus.SUCCESS.name().equals(done.getStatus())) {
            String reason = done == null ? null : done.getErrorMessage();
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    reason == null ? null : "导出生成失败：" + reason);
        }
        vo.setMode("SYNC");
        vo.setFileName(done.getFileName());
        vo.setFileSize(done.getFileSize());
        vo.setTotalRows(done.getTotalRows() == null ? vo.getTotalRows() : done.getTotalRows());
        vo.setDownloadUrl(downloadUrl(done.getId()));
        vo.setMessage("导出完成，共 " + vo.getTotalRows() + " 行，点击即可下载");
        return vo;
    }

    // ------------------------------------------------------------------
    // 导出记录
    // ------------------------------------------------------------------

    @Override
    public List<ExportTaskVO> recentTasks(ExportType type, int limit) {
        LoginUser user = requireLogin();
        boolean admin = RoleCode.isAdminOrAbove(user.getRole());
        int safeLimit = Math.min(Math.max(limit, 1), MAX_TASK_LIST);

        List<ExportTask> tasks = taskMapper.selectList(visibleWrapper(type, admin, user.getId())
                .last("LIMIT " + safeLimit));

        Map<Long, String> names = requesterNames(tasks);
        List<ExportTaskVO> result = new ArrayList<>(tasks.size());
        for (ExportTask task : tasks) {
            result.add(toVO(task, names.get(task.getRequesterId())));
        }
        return result;
    }

    @Override
    public PageResult<ExportTaskVO> pageTasks(ExportType type, int page, int size) {
        LoginUser user = requireLogin();
        boolean admin = RoleCode.isAdminOrAbove(user.getRole());
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        IPage<ExportTask> paged = taskMapper.selectPage(
                new Page<>(safePage, safeSize),
                visibleWrapper(type, admin, user.getId()));

        List<ExportTask> tasks = paged.getRecords();
        Map<Long, String> names = requesterNames(tasks);
        List<ExportTaskVO> records = new ArrayList<>(tasks.size());
        for (ExportTask task : tasks) {
            records.add(toVO(task, names.get(task.getRequesterId())));
        }

        PageResult<ExportTaskVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(paged.getTotal());
        result.setCurrent(paged.getCurrent());
        result.setSize(paged.getSize());
        result.setPages(paged.getPages());
        return result;
    }

    @Override
    public ExportTaskVO getTask(Long taskId) {
        ExportTask task = requireAccessibleTask(taskId);
        return toVO(task, nameOf(task.getRequesterId()));
    }

    @Override
    public void deleteTask(Long taskId) {
        // requireAccessibleTask 已覆盖「本人或管理员」的可见性断言；越权一律按「不存在」返回
        ExportTask task = requireAccessibleTask(taskId);
        taskMapper.deleteById(taskId);
        // 先删记录再删文件：即便删文件失败（只告警），残留文件也会被导出孤儿清理任务回收
        if (StringUtils.hasText(task.getStoredPath())) {
            storage.deleteQuietly(task.getStoredPath());
        }
    }

    /** 可见性收口：非管理员强制只查本人，条件前置而不是查完再过滤 */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExportTask> visibleWrapper(
            ExportType type, boolean admin, Long currentUserId) {
        return Wrappers.<ExportTask>lambdaQuery()
                .eq(!admin, ExportTask::getRequesterId, currentUserId)
                .eq(type != null, ExportTask::getExportType, type == null ? null : type.name())
                .orderByDesc(ExportTask::getId);
    }

    /** 批量解析发起人显示名：一次 selectBatchIds，避免逐行查库（N+1） */
    private Map<Long, String> requesterNames(List<ExportTask> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ExportTask task : tasks) {
            if (task.getRequesterId() != null) {
                ids.add(task.getRequesterId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userMapper.selectBatchIds(ids)) {
            names.put(u.getId(), displayNameOf(u));
        }
        return names;
    }

    private String nameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        User u = userMapper.selectById(userId);
        return u == null ? null : displayNameOf(u);
    }

    private String displayNameOf(User u) {
        if (StringUtils.hasText(u.getDisplayName())) {
            return u.getDisplayName();
        }
        if (StringUtils.hasText(u.getRealName())) {
            return u.getRealName();
        }
        return u.getUsername();
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    @Override
    public ExportDownload download(Long taskId) {
        ExportTask task = requireAccessibleTask(taskId);

        if (ExportStatus.FAILED.name().equals(task.getStatus())) {
            String reason = task.getErrorMessage();
            throw new BusinessException(ErrorCode.EXPORT_FAILED,
                    reason == null ? null : "导出生成失败：" + reason);
        }
        if (!ExportStatus.SUCCESS.name().equals(task.getStatus())) {
            // PENDING / RUNNING：文件还没生成，提示用户稍后从消息中心进入
            throw new BusinessException(ErrorCode.EXPORT_NOT_READY);
        }
        if (task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
        }
        if (task.getStoredPath() == null) {
            throw new BusinessException(ErrorCode.EXPORT_NOT_FOUND);
        }

        // resolve 内含「必须在存储根之内」的越界断言（路径穿越第二道防线）
        Path path = storage.resolve(task.getStoredPath());
        if (!Files.exists(path)) {
            // 记录显示成功但磁盘没有文件：可能被运维清理过，按「不存在」返回并记 warn 便于排查
            log.warn("导出文件缺失：taskId={} path={}", taskId, path);
            throw new BusinessException(ErrorCode.EXPORT_NOT_FOUND);
        }
        Resource resource = new FileSystemResource(path);
        return new ExportDownload(task, resource);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 导出权限
     *
     * <ul>
     *   <li>设备台账导出——仅 super_admin / admin；</li>
     *   <li>工单记录导出——任何登录用户，普通 user 的数据范围由 scope 收口；</li>
     *   <li>使用记录导出——任何登录用户，数据范围由 {@code UsageService#page} 按角色的
     *       {@code data_scope} 收窄（SELF / GROUP / ALL），与使用记录列表同一道防线；</li>
     *   <li>自定义表单数据导出——仅 super_admin / admin。导出的内容是<b>某个申请类型下所有人</b>
     *       提交的表单数据，与「全部工单」同一数据边界；数据过滤由
     *       {@code OrderService#pageAllOrders} 内部的管理员判定承担（不在这里重写一份，
     *       否则两处判定迟早分叉）。本方法只负责把「不属于你的功能」挡在最前面，
     *       让报错文案准确 —— 否则非管理员会收到「仅可查看全部工单」这种答非所问的提示；</li>
     *   <li>统计报表导出——「报表仅 super_admin、admin 可见」，故同样仅管理员。</li>
     * </ul>
     */
    private void assertExportable(ExportType type, boolean admin) {
        // 操作日志：比「管理员」更严 —— **仅超管**（ 的口径，理由见 ExportType.LOG 注释）。
        // 注意这里刻意不复用下面的 adminOnly：adminOnly 的语义是「超管或管理员」，
        // 而日志导出要排除 admin。把两种口径混在一个布尔里，迟早会有人为了别处方便把它放开。
        if (type == ExportType.LOG) {
            if (!RoleCode.isSuperAdmin(requireLogin().getRole())) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "仅超级管理员可导出操作日志（日志包含全系统操作明细，不支持整体带出）");
            }
            return;
        }
        boolean adminOnly = type == ExportType.DEVICE || type == ExportType.CUSTOM_FORM || type.isReport();
        if (adminOnly && !admin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, adminOnlyMessage(type));
        }
    }

    /** 拒绝文案：说清「为什么这个导出仅管理员」比一句「无权限」有用得多 */
    private String adminOnlyMessage(ExportType type) {
        if (type.isReport()) {
            return "仅管理员及以上角色可导出统计报表";
        }
        if (type == ExportType.CUSTOM_FORM) {
            return "仅管理员及以上角色可导出自定义表单数据";
        }
        return "仅管理员及以上角色可导出设备台账";
    }

    /** 取任务并校验归属：不是本人且不是管理员时，统一按「不存在」返回（不泄露他人导出记录是否存在） */
    private ExportTask requireAccessibleTask(Long taskId) {
        LoginUser user = requireLogin();
        ExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.EXPORT_NOT_FOUND);
        }
        boolean admin = RoleCode.isAdminOrAbove(user.getRole());
        if (!admin && !task.getRequesterId().equals(user.getId())) {
            log.warn("越权访问导出记录：taskId={} requesterId={} currentUserId={}",
                    taskId, task.getRequesterId(), user.getId());
            throw new BusinessException(ErrorCode.EXPORT_NOT_FOUND);
        }
        return task;
    }

    private LoginUser requireLogin() {
        LoginUser user = SecurityUtils.getCurrentUser();
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private String writeQuery(ExportQuery query) {
        try {
            return objectMapper.writeValueAsString(query);
        } catch (Exception e) {
            // 条件快照只用于复现与排查，序列化失败不该阻断导出本身
            log.warn("导出条件序列化失败，按空条件记录：{}", e.getMessage());
            return null;
        }
    }

    /** 预期文件名：类型前缀 + 时间戳 + .xlsx（同步与异步用同一个命名规则） */
    private String expectedFileName(ExportType type) {
        return type.filePrefixOf() + "_" + FILE_STAMP.format(LocalDateTime.now()) + ".xlsx";
    }

    private String downloadUrl(Long taskId) {
        return "/api/exports/" + taskId + "/download";
    }

    private ExportTaskVO toVO(ExportTask task, String requesterName) {
        ExportType type = ExportType.of(task.getExportType());
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(task.getId());
        vo.setRequesterId(task.getRequesterId());
        vo.setRequesterName(requesterName);
        vo.setExportType(task.getExportType());
        vo.setExportTypeLabel(type == null ? task.getExportType() : type.getLabel());
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(ExportStatus.labelOf(task.getStatus()));
        vo.setFileName(task.getFileName());
        vo.setFileSize(task.getFileSize());
        vo.setTotalRows(task.getTotalRows());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setExpireAt(task.getExpireAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setCreatedAt(task.getCreatedAt());
        // 只有已生成的文件才给下载地址：给出一个必然 400 的链接比不给更糟
        if (ExportStatus.SUCCESS.name().equals(task.getStatus())) {
            vo.setDownloadUrl(downloadUrl(task.getId()));
        }
        return vo;
    }
}
