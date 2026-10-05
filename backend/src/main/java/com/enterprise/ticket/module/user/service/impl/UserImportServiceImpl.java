package com.enterprise.ticket.module.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.auth.service.PasswordPolicyService;
import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.user.dto.UserCreateRequest;
import com.enterprise.ticket.module.user.dto.UserImportExecuteRequest;
import com.enterprise.ticket.module.user.dto.UserImportFailureReportRequest;
import com.enterprise.ticket.module.user.dto.UserImportRow;
import com.enterprise.ticket.module.user.dto.vo.UserImportPreviewVO;
import com.enterprise.ticket.module.user.dto.vo.UserImportResultVO;
import com.enterprise.ticket.module.user.dto.vo.UserImportRowVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.excel.UserImportExcelSupport;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.module.user.service.UserImportService;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 员工批量导入实现（需求方 2026-09-18 小迭代 · ）
 *
 * <p><b>校验规则复用</b>：每一行的角色枚举、部门存在性、密码强度都直接调用
 * {@link UserService#assertRoleValid} / {@link UserService#requireDepartmentId} 的同一套判定
 * （预览阶段按「不抛异常、只记原因」的方式等价实现：先取分组名→ID 映射，再逐行查表），
 * 写入阶段则直接调用 {@link UserService#insertUser(UserCreateRequest)} ——
 * 与「新增员工」是<b>同一个方法</b>，不存在「Excel 侧一套规则、单条新增另一套」的漂移空间。
 *
 * <p><b>两阶段写入</b>：预览只读不写；确认导入才落库，并且<b>重新校验一遍</b>
 * （不信任客户端提交的行）—— 即便预览与确认之间他人抢占了同一姓名 / 登录名，
 * 也只会让该行变成失败，而不会写坏数据。
 *
 * <p><b>分批事务</b>：每 {@value #BATCH_SIZE} 条一个事务。正常情况整批提交；
 * 若批内某行失败导致整批回滚，则自动降级为「逐行重试」，精确定位失败行，
 * 批内其余合法行仍会成功导入 —— 避免「一行出错、整批白做」。
 */
@Slf4j
@Service
public class UserImportServiceImpl implements UserImportService {

    /** 单次导入行数上限（与设备导入一致，需求方约定） */
    private static final int MAX_ROWS = 500;

    /** 上传文件大小上限：5MB */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 分批事务的批大小 */
    private static final int BATCH_SIZE = 50;

    private static final String XLSX_SUFFIX = ".xlsx";

    private static final String DEFAULT_FILE_NAME = "员工导入";

    private final UserImportExcelSupport excelSupport;
    private final UserService userService;
    private final DepartmentMapper departmentMapper;
    /** 部门服务：导入时部门名不存在则**自动创建**（P3 修复），复用部门服务保证 path/depth 正确 */
    private final DepartmentService departmentService;
    private final UserMapper userMapper;
    private final PasswordPolicyService passwordPolicyService;
    private final TransactionTemplate transactionTemplate;
    private final OperationLogService operationLogService;

    public UserImportServiceImpl(UserImportExcelSupport excelSupport,
                                 UserService userService,
                                 DepartmentMapper departmentMapper,
                                 DepartmentService departmentService,
                                 UserMapper userMapper,
                                 PasswordPolicyService passwordPolicyService,
                                 PlatformTransactionManager transactionManager,
                                 OperationLogService operationLogService) {
        this.excelSupport = excelSupport;
        this.userService = userService;
        this.departmentMapper = departmentMapper;
        this.departmentService = departmentService;
        this.userMapper = userMapper;
        this.passwordPolicyService = passwordPolicyService;
        // 显式构造而不注入 TransactionTemplate 实例：Spring Boot 是否自动装配该 Bean 取决于版本，
        // 由 PlatformTransactionManager 自行构造可保证行为稳定（与设备导入一致）。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.operationLogService = operationLogService;
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    @Override
    public byte[] buildTemplate() {
        return excelSupport.buildTemplate();
    }

    // ------------------------------------------------------------------
    // 预览校验
    // ------------------------------------------------------------------

    @Override
    public UserImportPreviewVO preview(MultipartFile file) {
        assertUploadable(file);
        String fileName = resolveFileName(file);

        // 交给 POI 之前先做「解压体积预检」：xlsx 是 zip 容器，压缩比可轻松做到几十比一，
        // 而 POI 是整表读入堆、且 500 行的业务上限要等解析完才生效 —— 不设限就等于把 OOM
        // 的机会交给上传方。预检会读完整个流，故解析需要另开一个输入流。
        try (InputStream sizing = file.getInputStream()) {
            excelSupport.assertExpandedSize(sizing);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.USER_IMPORT_PARSE_FAILED);
        }

        List<UserImportRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = excelSupport.parse(in, MAX_ROWS);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.USER_IMPORT_PARSE_FAILED);
        }

        // 预览阶段按「部门名不存在也会在导入时自动创建」处理 ⇒ 先按「可自动创建」校验，
        // 使预览结果与最终导入结果一致（否则预览显示失败、导入却成功，管理员会困惑）。
        List<ValidatedRow> validated = validateAll(rows, loadContext());
        List<UserImportRowVO> rowVOs = validated.stream().map(ValidatedRow::vo).toList();
        int successCount = (int) rowVOs.stream().filter(UserImportRowVO::isValid).count();

        UserImportPreviewVO preview = new UserImportPreviewVO();
        preview.setFileName(fileName);
        preview.setTotalCount(rowVOs.size());
        preview.setSuccessCount(successCount);
        preview.setFailCount(rowVOs.size() - successCount);
        preview.setRows(rowVOs);
        log.info("员工导入预览：file={} 共 {} 行，通过 {} 行，失败 {} 行",
                fileName, rowVOs.size(), successCount, rowVOs.size() - successCount);
        return preview;
    }

    // ------------------------------------------------------------------
    // 确认导入
    // ------------------------------------------------------------------

    @Override
    public UserImportResultVO execute(UserImportExecuteRequest request) {
        String fileName = normalizeFileName(request.getFileName());
        List<UserImportRow> rows = request.getRows();

        // P3 修复：部门名不存在 → **自动创建**（挂到根节点）。先创建缺失部门，
        // 再把「本次新建成」的映射并入校验上下文（而不是重新查库，避免测试与并发下的不确定性）。
        Map<String, Long> createdDepartments = createMissingDepartments(rows);

        // 重新校验一遍：不信任客户端提交（预览结果可能已被篡改，或库中数据已变化）
        List<ValidatedRow> validated = validateAll(rows, loadContext(createdDepartments, false));
        List<UserImportRowVO> failures = new ArrayList<>();
        List<ValidatedRow> valid = new ArrayList<>();
        for (ValidatedRow row : validated) {
            if (row.vo().isValid()) {
                valid.add(row);
            } else {
                failures.add(row.vo());
            }
        }

        if (valid.isEmpty()) {
            recordAudit(fileName, 0, failures.size(), rows.size(), false);
            throw new BusinessException(ErrorCode.USER_IMPORT_NOTHING_TO_IMPORT,
                    "没有校验通过的数据行可导入（本次提交 " + rows.size() + " 行全部未通过，请重新选择文件）");
        }

        int imported = insertBatched(valid, failures);
        // 按原始行号排序：写入阶段失败的行是在预校验失败行之后追加的，
        // 不排序会让失败明细的行序与用户文件对不上，难以逐行核对
        failures.sort(Comparator.comparingInt(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()));

        recordAudit(fileName, imported, failures.size(), rows.size(), true);

        UserImportResultVO result = new UserImportResultVO();
        result.setImportedCount(imported);
        result.setFailedCount(failures.size());
        result.setFailures(failures);
        log.info("员工导入完成：file={} 成功 {} 人，失败 {} 行（提交 {} 行）",
                fileName, imported, failures.size(), rows.size());
        return result;
    }

    /**
     * 分批事务写入（每 {@value #BATCH_SIZE} 条一个事务）
     *
     * <p>写入统一走 {@link UserService#insertUser(UserCreateRequest)} —— 与「新增员工」
     * 完全同一个实现（校验 + 落库 + 首登强制改密），因此两条入口不可能出现规则差异。
     *
     * @return 实际导入成功人数
     */
    private int insertBatched(List<ValidatedRow> valid, List<UserImportRowVO> failures) {
        int imported = 0;
        for (int start = 0; start < valid.size(); start += BATCH_SIZE) {
            List<ValidatedRow> batch = valid.subList(start, Math.min(start + BATCH_SIZE, valid.size()));
            try {
                transactionTemplate.executeWithoutResult(status ->
                        batch.forEach(row -> userService.insertUser(row.request())));
                imported += batch.size();
            } catch (RuntimeException batchError) {
                // 批内任一行失败会整批回滚：降级为逐行重试，精确定位失败行，
                // 其余合法行仍按各自事务成功写入（不丢失已通过校验的数据）
                log.warn("员工导入第 {} 批整批回滚（{} 行），降级为逐行重试：{}",
                        start / BATCH_SIZE + 1, batch.size(), batchError.getMessage());
                imported += insertOneByOne(batch, failures);
            }
        }
        return imported;
    }

    private int insertOneByOne(List<ValidatedRow> batch, List<UserImportRowVO> failures) {
        int imported = 0;
        for (ValidatedRow row : batch) {
            try {
                transactionTemplate.executeWithoutResult(status -> userService.insertUser(row.request()));
                imported++;
            } catch (RuntimeException rowError) {
                UserImportRowVO failed = row.vo();
                failed.setValid(false);
                failed.setReason(describeInsertError(rowError, row.request().getRealName()));
                failures.add(failed);
            }
        }
        return imported;
    }

    private String describeInsertError(RuntimeException error, String realName) {
        if (error instanceof BusinessException business) {
            return business.getMessage();
        }
        if (error instanceof DuplicateKeyException) {
            // 唯一索引兜底：预检通过后仍被并发写入抢先
            return "登录名已存在（可能刚被他人创建），请更换后重新导入";
        }
        log.error("员工导入写入失败 realName={}", realName, error);
        return ErrorCode.INTERNAL_ERROR.getDefaultMessage();
    }

    // ------------------------------------------------------------------
    // 失败明细
    // ------------------------------------------------------------------

    @Override
    public byte[] buildFailureReport(UserImportFailureReportRequest request) {
        return excelSupport.buildFailureReport(request.getRows());
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private void assertUploadable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_IMPORT_FILE_REQUIRED);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(XLSX_SUFFIX)) {
            throw new BusinessException(ErrorCode.USER_IMPORT_FILE_TYPE_INVALID);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.USER_IMPORT_FILE_TOO_LARGE,
                    "文件大小 " + (file.getSize() / 1024) + "KB 超过 5MB 上限，请拆分后重新导入");
        }
    }

    private List<ValidatedRow> validateAll(List<UserImportRow> rows, ImportContext context) {
        List<ValidatedRow> result = new ArrayList<>(rows.size());
        for (UserImportRow row : rows) {
            result.add(validateRow(row, context));
        }
        return result;
    }

    /**
     * 校验单行（失败不抛异常，而是写进 {@code reason}，保证「校验不阻断」）
     *
     * <p>检查顺序即失败原因的优先级。唯一性检查刻意排在<b>最后</b>：
     * 只有「其它字段都合法」的行才占用库内 / 文件内的唯一名额，
     * 这样用户修好 A 行不会又冒出一堆 B 行的重复告警。
     */
    private ValidatedRow validateRow(UserImportRow row, ImportContext context) {
        UserImportRowVO vo = copyToVO(row);
        int rowNo = row.getRowNo() == null ? 0 : row.getRowNo();

        String realName = trimToNull(row.getRealName());
        String username = trimToNull(row.getUsername());
        String password = row.getPassword() == null ? null : row.getPassword().trim();
        String groupName = trimToNull(row.getDepartmentName());
        String role = trimToNull(row.getRole());
        String displayName = trimToNull(row.getDisplayName());

        // 1) 必填项
        if (realName == null) {
            return fail(vo, "姓名不能为空");
        }
        if (username == null) {
            return fail(vo, "登录名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return fail(vo, "初始密码不能为空");
        }
        if (groupName == null) {
            return fail(vo, "部门名称不能为空");
        }
        if (role == null) {
            return fail(vo, "角色不能为空");
        }

        // 1.5) 格式（）：姓名纯中文、登录名 5 位以上纯数字。
        //
        // 排在必填之后、唯一性之前，与单条新增的「格式 → 唯一」顺序一致 ——
        // 用户把「张伟」填进登录名时，要听到「登录名必须是5位以上纯数字」，
        // 而不是「登录名已存在」这种把格式问题伪装成冲突问题的提示。
        // 复用 userService 的同名方法而不是在这里重写正则：导入与单条新增
        // 必须共用同一份规则，否则会出现「页面上能建、导入时报格式错」。
        try {
            userService.assertRealNameFormat(realName);
        } catch (BusinessException e) {
            return fail(vo, e.getMessage());
        }
        try {
            userService.assertUsernameFormat(username);
        } catch (BusinessException e) {
            return fail(vo, e.getMessage());
        }

        // 2) 角色枚举（与新增员工同源）
        try {
            userService.assertRoleValid(role);
        } catch (BusinessException e) {
            return fail(vo, e.getMessage());
        }

        // 3) 部门按名称匹配（需求方约定「填名称按名匹配」；P3 起不存在则**自动创建**）
        Long departmentId = context.departmentIdByName().get(groupName);
        if (departmentId == null && !context.autoCreateMissingDepartment()) {
            // 执行阶段（autoCreate=false）：仍为 null 说明自动创建失败（如并发冲突），明确失败并给指引
            return fail(vo, "部门「" + groupName + "」不存在且自动创建失败，请在「部门管理」中手动创建后重试");
        }

        // 4) 密码强度（与单条新增 / 重置密码同源，）
        try {
            passwordPolicyService.validate(password, username);
        } catch (BusinessException e) {
            return fail(vo, e.getMessage());
        }

        // 4.5) 直属领导（选填，）：按姓名匹配既有在职启用员工。
        // 匹配不到时给出精确原因（不存在 / 已离职 / 已停用 / 就是本人），不静默丢弃 ——
        // 静默丢弃会让管理员以为「领导已配好」，直到工单走审批时才发现落到了超管兜底。
        Long leaderId = null;
        String leaderName = trimToNull(row.getLeaderName());
        if (leaderName != null) {
            if (leaderName.equalsIgnoreCase(realName)) {
                return fail(vo, "直属领导不能是本人");
            }
            //  起姓名允许重复，「按姓名找领导」就不再天然唯一。
            // 此时**必须报错而不是取第一个**：取第一个会把审批流挂到一个
            // 与管理员意图无关的同名同事身上 —— 审批人错了，流程照走，
            // 直到出问题才有人发现，属于最难排查的一类缺陷。
            if (context.ambiguousLeaderNames().contains(leaderName)) {
                return fail(vo, "直属领导「" + leaderName + "」对应多个同名员工，请核对后改用可唯一识别的姓名");
            }
            leaderId = context.leaderIdByName().get(leaderName);
            if (leaderId == null) {
                if (context.inactiveLeaderNames().contains(leaderName)) {
                    return fail(vo, "直属领导「" + leaderName + "」已离职或账号已停用，请选择在职员工");
                }
                return fail(vo, "直属领导「" + leaderName + "」不存在，请先导入该员工或核对姓名");
            }
        }

        // 5) 登录名：库内已有。
        //    姓名**不再判唯一**（：公司可能有多个张伟），
        //    因此这里既没有「库内姓名重复」，也没有「文件内姓名重复」。
        String usernameKey = username.toLowerCase(Locale.ROOT);
        if (context.existingUsernames().contains(usernameKey)) {
            return fail(vo, "登录名已存在：" + username);
        }
        // 6) 登录名：文件内重复（定位到具体两行，用户改起来才知道改哪一行）
        Integer firstUsernameRow = context.usernameFirstRow().get(usernameKey);
        if (firstUsernameRow != null) {
            return fail(vo, duplicateReason(firstUsernameRow, rowNo, "登录名"));
        }

        // 全部通过：占用「文件内首次出现」名额，后续同名行会被判为文件内重复
        context.usernameFirstRow().put(usernameKey, rowNo);

        UserCreateRequest request = new UserCreateRequest();
        request.setRealName(realName);
        request.setUsername(username);
        request.setPassword(password);
        request.setDepartmentId(departmentId);
        request.setRole(role);
        request.setDisplayName(displayName);
        request.setLeaderId(leaderId);

        vo.setValid(true);
        return new ValidatedRow(vo, request);
    }

    /**
     * 文件内重复的失败原因。
     *
     * <p>需求方原文要求「失败原因明确显示…『文件内第 3 行与第 7 行姓名重复』」，
     * 因此这里把<b>两行行号都写出来</b>；括号里补一句「仅第 N 行会被导入」，
     * 因为实际行为是「首次出现的行胜出」——不写清楚，用户会以为两行都会失败。
     */
    private String duplicateReason(int firstRow, int currentRow, String field) {
        return "文件内第 " + firstRow + " 行与第 " + currentRow + " 行" + field + "重复（仅第 "
                + firstRow + " 行会被导入）";
    }

    /**
     * 预览用上下文：部门名不存在时按「导入阶段会自动创建」处理（预览阶段不写库）。
     *
     * <p>这样预览的「通过/失败」与最终导入结果一致 —— 否则会出现
     * 「预览显示该行失败、确认导入却成功」这种让人困惑的偏差。
     */
    private ImportContext loadContext() {
        return loadContext(Map.of(), true);
    }

    /**
     * 加载唯一性与分组上下文（一次查询，避免逐行查库造成 N+1）。
     *
     * <p>唯一性比对用<b>大小写不敏感</b>的集合：{@code users.username} 使用
     * utf8mb4_general_ci 排序规则（不区分大小写），Java 侧若用区分大小写的容器，
     * 会出现「库能查到的登录名，本地集合却判为不冲突」→ 预检通过、写入时撞唯一索引。
     *
     * @param extraDepartments             本次运行**新创建**的「部门名 → id」映射（执行阶段传入），
     *                                     并入上下文，避免为拿到新 id 再查一次库
     * @param autoCreateMissingDepartment  部门名找不到时是否视为「可自动创建」（预览=true；执行=false）
     */
    private ImportContext loadContext(Map<String, Long> extraDepartments, boolean autoCreateMissingDepartment) {
        Map<String, Long> groupIdByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Department group : departmentMapper.selectList(
                Wrappers.<Department>lambdaQuery().select(Department::getId, Department::getDeptName))) {
            groupIdByName.put(group.getDeptName(), group.getId());
        }
        if (extraDepartments != null && !extraDepartments.isEmpty()) {
            groupIdByName.putAll(extraDepartments);
        }

        // 只取必要列：姓名 / 登录名 / 显示名 / 在职状态，其余字段（含 password_hash）不进上下文，
        // 避免把敏感数据带到一次批量校验的内存里。
        //  起多取 dimission / enabled：导入的「直属领导」列必须只认<b>在职启用</b>的领导。
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getRealName, User::getDisplayName, User::getUsername,
                        User::getDimission, User::getEnabled));
        Set<String> usernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // ：直属领导姓名 → user_id（大小写不敏感）。匹配 real_name，其次 display_name。
        // 离职 / 停用者不进映射，而是汇总到 inactiveLeaderNames —— 这样失败原因能精确到
        // 「已离职」「账号已停用」，而不是笼统的「不存在」（后者会把管理员引向错误的排查方向）。
        //
        //  起姓名允许重复，因此「一个姓名对应几个在职员工」必须单独记账：
        // 重名者进 ambiguousLeaderNames，导入时明确报错，绝不静默取第一个。
        Map<String, Long> leaderIdByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> leaderNameHits = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> inactiveLeaderNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (User user : users) {
            if (user.getUsername() != null) {
                usernames.add(user.getUsername());
            }
            boolean active = !Boolean.TRUE.equals(user.getDimission())
                    && Boolean.TRUE.equals(user.getEnabled());
            // 同一行里 real_name 与 display_name 常常是同一个值（显示名留空时兜底为姓名），
            // 因此必须先**去重**再计数：否则一个用户就会被数成两个同名者，
            // 「张三」永远被判为歧义 —— 那等于把「按姓名找领导」整条路堵死。
            for (String name : new LinkedHashSet<>(Arrays.asList(user.getRealName(), user.getDisplayName()))) {
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (active) {
                    leaderIdByName.putIfAbsent(name, user.getId());
                    leaderNameHits.merge(name, 1, Integer::sum);
                } else if (!leaderIdByName.containsKey(name)) {
                    inactiveLeaderNames.add(name);
                }
            }
        }
        Set<String> ambiguousLeaderNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Integer> entry : leaderNameHits.entrySet()) {
            if (entry.getValue() > 1) {
                ambiguousLeaderNames.add(entry.getKey());
            }
        }
        return new ImportContext(groupIdByName, usernames, leaderIdByName, inactiveLeaderNames,
                ambiguousLeaderNames, new LinkedHashMap<>(), autoCreateMissingDepartment);
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    /**
     * 记录导入审计（：批量导入属高风险，必须可靠同步留痕）
     *
     * <p>使用 {@link OperationLogService#recordCurrent} 而非 {@code @AuditLog}：本接口入参是
     * 「文件名 + 最多 500 行数据（含初始密码）」，注解型切面只能记录被截断的入参摘要，
     * 无法表达「成功 N 人 / 失败 M 行」这一核心事实；更重要的是<b>绝不能让初始密码进审计日志</b>。
     */
    private void recordAudit(String fileName, int imported, int failed, int total, boolean success) {
        try {
            String details = "file=" + fileName + " | total=" + total
                    + " | imported=" + imported + " | failed=" + failed;
            operationLogService.recordCurrent("USER", "USER_IMPORT", details, success, RiskLevel.HIGH);
        } catch (Exception e) {
            // 审计写入失败不能影响导入结果本身
            log.error("写入员工导入审计日志失败 file={}", fileName, e);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String resolveFileName(MultipartFile file) {
        return normalizeFileName(file.getOriginalFilename());
    }

    /**
     * 规范化文件名：去首尾空白 + 剥除控制字符。
     *
     * <p>文件名是客户端可控值，会写入审计详情与运行日志。含 {@code \r\n} 时足以在日志里
     * 伪造出额外的日志行（日志注入），故统一剥除（与设备导入一致）。
     */
    private String normalizeFileName(String fileName) {
        String trimmed = trimToNull(fileName);
        if (trimmed == null) {
            return DEFAULT_FILE_NAME;
        }
        String cleaned = trimmed.replaceAll("\\p{Cntrl}", "").trim();
        return cleaned.isEmpty() ? DEFAULT_FILE_NAME : cleaned;
    }

    private UserImportRowVO copyToVO(UserImportRow row) {
        UserImportRowVO vo = new UserImportRowVO();
        vo.setRowNo(row.getRowNo());
        vo.setRealName(row.getRealName());
        vo.setUsername(row.getUsername());
        vo.setPassword(row.getPassword());
        vo.setDepartmentName(row.getDepartmentName());
        vo.setRole(row.getRole());
        vo.setDisplayName(row.getDisplayName());
        vo.setLeaderName(row.getLeaderName());
        return vo;
    }

    private ValidatedRow fail(UserImportRowVO vo, String reason) {
        vo.setValid(false);
        vo.setReason(reason);
        return new ValidatedRow(vo, null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 为「文件里出现、但库里不存在」的部门名自动创建部门（P3 修复）。
     *
     * <h2>为什么挂到根节点</h2>
     * <p>Excel 只给了部门<b>名称</b>，没有层级信息 —— 无法推断它该挂在哪一级。
     * 挂到根节点（公司）是最保守的选择：不猜层级。管理员导入后可再到「部门管理」调整层级。
     *
     * <h2>为什么复用 {@link DepartmentService#create}</h2>
     * <p>部门有物化路径（{@code path} / {@code depth}）不变量，自己 insert 极易写错，
     * 导致后续「取子树」的 {@code path LIKE} 查询静默给出错误结果。复用服务层保证这条不变量。
     *
     * <h2>幂等与容错</h2>
     * <ul>
     *   <li>同名只建一次（大小写不敏感，与校验上下文口径一致）；</li>
     *   <li>并发下若已被他人创建（{@code DEPARTMENT_NAME_EXISTS}），回读一次取回 id，不判失败；</li>
     *   <li>创建失败只记 WARN 并跳过 —— 该部门仍缺失，对应行会在校验阶段明确失败，不会写坏数据。</li>
     * </ul>
     *
     * @return 本次新创建的「部门名 → id」映射（并入校验上下文用）
     */
    private Map<String, Long> createMissingDepartments(List<UserImportRow> rows) {
        Set<String> existingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Department group : departmentMapper.selectList(
                Wrappers.<Department>lambdaQuery().select(Department::getDeptName))) {
            if (group.getDeptName() != null) {
                existingNames.add(group.getDeptName());
            }
        }
        Map<String, Long> created = new LinkedHashMap<>();
        for (UserImportRow row : rows) {
            String name = trimToNull(row.getDepartmentName());
            if (name == null || existingNames.contains(name) || created.containsKey(name)) {
                continue;
            }
            try {
                DepartmentSaveRequest request = new DepartmentSaveRequest();
                request.setDeptName(name);
                // parentId 留空 → 服务层挂到根节点（公司）
                Long id = departmentService.create(request);
                created.put(name, id);
                existingNames.add(name);
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.DEPARTMENT_NAME_EXISTS) {
                    // 并发下已被他人创建：回读一次
                    Long id = findDepartmentIdByName(name);
                    if (id != null) {
                        created.put(name, id);
                        existingNames.add(name);
                    } else {
                        log.warn("员工导入：部门「{}」已被占用但回读不到 id，本次跳过", name);
                    }
                } else {
                    log.warn("员工导入：自动创建部门「{}」失败：{}", name, e.getMessage());
                }
            } catch (Exception e) {
                log.warn("员工导入：自动创建部门「{}」异常：{}", name, e.getMessage());
            }
        }
        if (!created.isEmpty()) {
            log.info("员工导入：自动创建部门 {} 个 {}", created.size(), created.keySet());
        }
        return created;
    }

    private Long findDepartmentIdByName(String name) {
        Department group = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getDeptName, name)
                .last("LIMIT 1"));
        return group == null ? null : group.getId();
    }

    /** 校验通过的行：展示用 VO + 可直接落库的请求体 */
    private record ValidatedRow(UserImportRowVO vo, UserCreateRequest request) {
    }

    /**
     * 校验上下文：分组名→ID、库内已有登录名、领导姓名映射，以及文件内登录名首次出现的行号。
     *
     * <p>{@code usernameFirstRow} 的键为<b>已归一化</b>的值（转小写），值是首次出现的
     * 文件行号 —— 这就是「文件内第 3 行与第 7 行登录名重复」这句文案的数据来源。
     *
     * <p> 起姓名允许重复，因此这里<b>没有</b> {@code existingRealNames} 与
     * {@code realNameFirstRow}：唯一性只针对登录名。而 {@code ambiguousLeaderNames}
     * 正好相反 —— 它记的是「同名在职员工超过一个」的姓名，用于把「按姓名找领导」
     * 的歧义显式暴露出来。
     *
     * <p>{@code autoCreateMissingDepartment}：部门名找不到时是否视为「可自动创建」。
     * 预览阶段为 {@code true}（预览不写库，但要与导入结果口径一致）；执行阶段为
     * {@code false}（此时部门已由 {@link #createMissingDepartments} 建好，仍缺失即判失败）。
     */
    private record ImportContext(Map<String, Long> departmentIdByName,
                                 Set<String> existingUsernames,
                                 Map<String, Long> leaderIdByName,
                                 Set<String> inactiveLeaderNames,
                                 Set<String> ambiguousLeaderNames,
                                 Map<String, Integer> usernameFirstRow,
                                 boolean autoCreateMissingDepartment) {
    }
}
