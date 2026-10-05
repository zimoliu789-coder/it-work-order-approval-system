package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceImportExecuteRequest;
import com.enterprise.ticket.module.device.dto.DeviceImportFailureReportRequest;
import com.enterprise.ticket.module.device.dto.DeviceImportRow;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportPreviewVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportResultVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportRowVO;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.excel.DeviceImportExcelSupport;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.service.DeviceImportService;
import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 设备批量导入实现
 *
 * <p><b>校验规则复用</b>：每一行都会构造成「单条新增设备」同样的 {@link DeviceSaveRequest}，
 * 先跑一遍 Bean Validation（必填 / 长度，与 {@code @Valid} 同一套注解），
 * 再调用 {@link DeviceService#validateNewDevice} 跑业务规则（资产编号唯一、分类存在性与归属）。
 * 因此「单条新增」与「批量导入」永远不会出现规则漂移。
 *
 * <p><b>两阶段写入</b>：预览只读不写；确认导入时才落库，并且<b>重新校验一遍</b>
 * （不信任客户端提交的行），这样即便预览与确认之间数据库发生变化（他人抢先录入了同一资产编号），
 * 也只会让该行变成失败，而不会写坏数据。
 *
 * <p><b>分批事务</b>：每 {@value #BATCH_SIZE} 条一个事务。正常情况整批提交，效率高；
 * 若批内某行失败导致整批回滚，则自动降级为「逐行重试」，精确定位失败行，
 * 批内其余合法行仍会成功导入 —— 避免「一行出错、整批白做」。
 */
@Slf4j
@Service
public class DeviceImportServiceImpl implements DeviceImportService {

    /** 单次导入行数上限（需求方约定） */
    private static final int MAX_ROWS = 500;

    /** 上传文件大小上限：5MB（需求方约定） */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 分批事务的批大小（需求方约定 50） */
    private static final int BATCH_SIZE = 50;

    private static final String XLSX_SUFFIX = ".xlsx";

    private static final String DEFAULT_FILE_NAME = "设备导入";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DeviceImportExcelSupport excelSupport;
    private final DeviceService deviceService;
    private final DeviceCategoryMapper categoryMapper;
    private final Validator validator;
    private final TransactionTemplate transactionTemplate;
    private final OperationLogService operationLogService;

    public DeviceImportServiceImpl(DeviceImportExcelSupport excelSupport,
                                   DeviceService deviceService,
                                   DeviceCategoryMapper categoryMapper,
                                   Validator validator,
                                   PlatformTransactionManager transactionManager,
                                   OperationLogService operationLogService) {
        this.excelSupport = excelSupport;
        this.deviceService = deviceService;
        this.categoryMapper = categoryMapper;
        this.validator = validator;
        // 显式构造而不注入 TransactionTemplate 实例：Spring Boot 是否自动装配该 Bean 取决于版本，
        // 由 PlatformTransactionManager 自行构造可保证行为稳定。
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
    public DeviceImportPreviewVO preview(MultipartFile file) {
        assertUploadable(file);
        String fileName = resolveFileName(file);

        // 交给 POI 之前先做「解压体积预检」：xlsx 是 zip 容器，压缩比可轻松做到几十比一，
        // 而 POI 是整表读入堆、且 500 行的业务上限要等解析完才生效 —— 不设限就等于把 OOM 的机会
        // 交给上传方。预检会读完整个流，故解析需要另开一个输入流。
        try (InputStream sizing = file.getInputStream()) {
            excelSupport.assertExpandedSize(sizing);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_PARSE_FAILED);
        }

        List<DeviceImportRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = excelSupport.parse(in, MAX_ROWS);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_PARSE_FAILED);
        }

        ImportContext context = loadContext();
        List<ValidatedRow> validated = validateAll(rows, context);

        List<DeviceImportRowVO> rowVOs = validated.stream().map(ValidatedRow::vo).toList();
        int successCount = (int) rowVOs.stream().filter(DeviceImportRowVO::isValid).count();

        DeviceImportPreviewVO preview = new DeviceImportPreviewVO();
        preview.setFileName(fileName);
        preview.setTotalCount(rowVOs.size());
        preview.setSuccessCount(successCount);
        preview.setFailCount(rowVOs.size() - successCount);
        preview.setRows(rowVOs);
        log.info("设备导入预览：file={} 共 {} 行，通过 {} 行，失败 {} 行",
                fileName, rowVOs.size(), successCount, rowVOs.size() - successCount);
        return preview;
    }

    // ------------------------------------------------------------------
    // 确认导入
    // ------------------------------------------------------------------

    @Override
    public DeviceImportResultVO execute(DeviceImportExecuteRequest request) {
        String fileName = normalizeFileName(request.getFileName());
        List<DeviceImportRow> rows = request.getRows();

        // 重新校验一遍：不信任客户端提交（预览结果可能已被篡改，或库中数据已变化）
        ImportContext context = loadContext();
        List<ValidatedRow> validated = validateAll(rows, context);
        List<DeviceImportRowVO> failures = new ArrayList<>();
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
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_NOTHING_TO_IMPORT,
                    "没有校验通过的数据行可导入（本次提交 " + rows.size() + " 行全部未通过，请重新选择文件）");
        }

        int imported = insertBatched(valid, failures);
        // 按原始行号排序：写入阶段失败的行是在预校验失败行之后追加的，
        // 不排序会让失败明细/列表顺序与用户文件的行序对不上，难以逐行核对
        failures.sort(Comparator.comparingInt(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()));

        recordAudit(fileName, imported, failures.size(), rows.size(), true);

        DeviceImportResultVO result = new DeviceImportResultVO();
        result.setImportedCount(imported);
        result.setFailedCount(failures.size());
        result.setFailures(failures);
        log.info("设备导入完成：file={} 成功 {} 台，失败 {} 行（提交 {} 行）",
                fileName, imported, failures.size(), rows.size());
        return result;
    }

    /**
     * 分批事务写入（每 {@value #BATCH_SIZE} 条一个事务）
     *
     * <p>新设备状态由 {@link DeviceService#create} 固定为「可用」。
     *
     * @return 实际导入成功条数
     */
    private int insertBatched(List<ValidatedRow> valid, List<DeviceImportRowVO> failures) {
        int imported = 0;
        for (int start = 0; start < valid.size(); start += BATCH_SIZE) {
            List<ValidatedRow> batch = valid.subList(start, Math.min(start + BATCH_SIZE, valid.size()));
            try {
                transactionTemplate.executeWithoutResult(status ->
                        batch.forEach(row -> deviceService.create(row.request())));
                imported += batch.size();
            } catch (RuntimeException batchError) {
                // 批内任一行失败会整批回滚：降级为逐行重试，精确定位失败行，
                // 其余合法行仍按各自事务成功写入（不丢失已通过校验的数据）
                log.warn("设备导入第 {} 批整批回滚（{} 行），降级为逐行重试：{}",
                        start / BATCH_SIZE + 1, batch.size(), batchError.getMessage());
                imported += insertOneByOne(batch, failures);
            }
        }
        return imported;
    }

    private int insertOneByOne(List<ValidatedRow> batch, List<DeviceImportRowVO> failures) {
        int imported = 0;
        for (ValidatedRow row : batch) {
            try {
                transactionTemplate.executeWithoutResult(status -> deviceService.create(row.request()));
                imported++;
            } catch (RuntimeException rowError) {
                DeviceImportRowVO failed = row.vo();
                failed.setValid(false);
                failed.setReason(describeInsertError(rowError, row.request().getAssetNo()));
                failures.add(failed);
            }
        }
        return imported;
    }

    private String describeInsertError(RuntimeException error, String assetNo) {
        if (error instanceof BusinessException business) {
            return business.getMessage();
        }
        if (error instanceof DuplicateKeyException) {
            // 唯一索引兜底：预检通过后仍被并发写入抢先
            return "资产编号「" + assetNo + "」已存在（含历史已删除设备），请更换";
        }
        log.error("设备导入写入失败 assetNo={}", assetNo, error);
        return ErrorCode.INTERNAL_ERROR.getDefaultMessage();
    }

    // ------------------------------------------------------------------
    // 失败明细
    // ------------------------------------------------------------------

    @Override
    public byte[] buildFailureReport(DeviceImportFailureReportRequest request) {
        return excelSupport.buildFailureReport(request.getRows());
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private void assertUploadable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_FILE_REQUIRED);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(XLSX_SUFFIX)) {
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_FILE_TYPE_INVALID);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE,
                    "文件大小 " + (file.getSize() / 1024) + "KB 超过 5MB 上限，请拆分后重新导入");
        }
    }

    private List<ValidatedRow> validateAll(List<DeviceImportRow> rows, ImportContext context) {
        List<ValidatedRow> result = new ArrayList<>(rows.size());
        for (DeviceImportRow row : rows) {
            result.add(validateRow(row, context));
        }
        return result;
    }

    /**
     * 校验单行（失败不抛异常，而是写进 {@code reason}，保证「校验不阻断」）
     */
    private ValidatedRow validateRow(DeviceImportRow row, ImportContext context) {
        DeviceImportRowVO vo = copyToVO(row);
        String assetNo = trimToNull(row.getAssetNo());
        String primaryName = trimToNull(row.getPrimaryCategoryName());
        String secondaryName = trimToNull(row.getSecondaryCategoryName());

        // 1) 一级分类：必填 + 按名称匹配（需求方约定「填名称按名匹配」）
        if (primaryName == null) {
            return fail(vo, "一级分类不能为空（请填写分类名称）");
        }
        DeviceCategory primary = context.primaryByName().get(primaryName);
        if (primary == null) {
            return fail(vo, "一级分类「" + primaryName + "」不存在，请先在「设备分类管理」中创建");
        }

        // 2) 二级分类：选填；填了必须归属所选一级分类
        Long secondaryId = null;
        if (secondaryName != null) {
            DeviceCategory secondary = context.secondaryByParent(primary.getId()).get(secondaryName);
            if (secondary == null) {
                return fail(vo, "二级分类「" + secondaryName + "」不属于一级分类「" + primaryName + "」或不存在");
            }
            secondaryId = secondary.getId();
        }

        // 3) 购置日期格式（YYYY-MM-DD）
        String purchaseDateText = trimToNull(row.getPurchaseDate());
        LocalDate purchaseDate = null;
        if (purchaseDateText != null) {
            try {
                purchaseDate = LocalDate.parse(purchaseDateText, ISO_DATE);
            } catch (DateTimeParseException e) {
                return fail(vo, "购置日期格式不正确，应为 YYYY-MM-DD（当前值：" + purchaseDateText + "）");
            }
        }

        // 4) 设备金额（选填，）：空 = 不录入，按「不超过阈值」参与审批金额分档。
        //    这里显式解析而不是交给 Bean Validation：@Digits 只拦「小数位太多」，
        //    对「abc」「1,234」「￥5000」这种非数字文本会报出 ValueFormatException 级别的
        //    原始报错，用户看不懂；统一在这里换成「单元格怎么写才对」的中文提示。
        String amountText = trimToNull(row.getAmount());
        BigDecimal amount = null;
        if (amountText != null) {
            try {
                amount = new BigDecimal(amountText.replace(",", "").replace("¥", "").replace("￥", "").trim());
            } catch (NumberFormatException e) {
                return fail(vo, "设备金额格式不正确，请填写数字（当前值：" + amountText + "）");
            }
            if (amount.signum() < 0) {
                return fail(vo, "设备金额不能为负数（当前值：" + amountText + "）");
            }
        }

        // 5) 复用「单条新增」规则：Bean Validation（必填 / 长度）+ 服务层业务规则（编号唯一 / 分类归属）
        DeviceSaveRequest request = new DeviceSaveRequest();
        request.setDeviceName(trimToNull(row.getDeviceName()));
        request.setAssetNo(assetNo);
        request.setPrimaryCategoryId(primary.getId());
        request.setSecondaryCategoryId(secondaryId);
        request.setBrand(trimToNull(row.getBrand()));
        request.setModel(trimToNull(row.getModel()));
        request.setSerialNo(trimToNull(row.getSerialNo()));
        request.setStorageLocation(trimToNull(row.getStorageLocation()));
        request.setPurchaseDate(purchaseDate);
        request.setRemark(trimToNull(row.getRemark()));
        request.setAmount(amount);

        String beanError = firstViolationMessage(request);
        if (beanError != null) {
            return fail(vo, beanError);
        }
        try {
            deviceService.validateNewDevice(request);
        } catch (BusinessException e) {
            return fail(vo, e.getMessage());
        }

        // 6) 本次导入文件内重复（与数据库唯一索引同样按「不区分大小写」比对，
        //    因为 asset_no 列使用 utf8mb4_general_ci 排序规则）
        if (assetNo != null && !context.usedAssetNos().add(assetNo.toUpperCase(Locale.ROOT))) {
            return fail(vo, "资产编号「" + assetNo + "」在本次导入文件中重复，仅第一处会被导入");
        }

        vo.setValid(true);
        return new ValidatedRow(vo, request);
    }

    /** 取第一条约束校验失败信息（与单条新增接口 @Valid 使用同一套注解与文案） */
    private String firstViolationMessage(DeviceSaveRequest request) {
        Set<ConstraintViolation<DeviceSaveRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        return violations.iterator().next().getMessage();
    }

    /**
     * 加载分类与「本次已用资产编号」上下文（一次查询，避免逐行查库造成 N+1）
     *
     * <p>用大小写不敏感的有序 Map：分类名与资产编号在库中均为 utf8mb4_general_ci
     * （不区分大小写），Java 侧若用区分大小写的容器会出现「库能查到的名，按名匹配却找不到」。
     */
    private ImportContext loadContext() {
        List<DeviceCategory> categories = categoryMapper.selectList(Wrappers.<DeviceCategory>lambdaQuery());
        Map<String, DeviceCategory> primaryByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<Long, Map<String, DeviceCategory>> secondaryByParent = new HashMap<>();
        for (DeviceCategory category : categories) {
            Integer level = category.getLevel();
            if (level != null && level == DeviceCategory.LEVEL_PRIMARY) {
                primaryByName.put(category.getCategoryName(), category);
            }
        }
        for (DeviceCategory category : categories) {
            Integer level = category.getLevel();
            if (level != null && level == DeviceCategory.LEVEL_SECONDARY && category.getParentId() != null) {
                secondaryByParent
                        .computeIfAbsent(category.getParentId(), key -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                        .put(category.getCategoryName(), category);
            }
        }
        return new ImportContext(primaryByName, secondaryByParent, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    /**
     * 记录导入审计
     *
     * <p>使用 {@link OperationLogService#recordCurrent} 而非 {@code @AuditLog}：本接口的入参是
     * 「文件名 + 最多 500 行数据」，注解型切面只能记录被截断的入参摘要，
     * 无法表达「成功 N 条 / 失败 M 条」这一核心事实。操作人（导入人）由 recordCurrent 自动填充，
     * 详情里补上文件名与成功/失败数。
     */
    private void recordAudit(String fileName, int imported, int failed, int total, boolean success) {
        try {
            String details = "file=" + fileName + " | total=" + total
                    + " | imported=" + imported + " | failed=" + failed;
            operationLogService.recordCurrent("DEVICE", "DEVICE_IMPORT", details, success, RiskLevel.NORMAL);
        } catch (Exception e) {
            // 审计写入失败不能影响导入结果本身
            log.error("写入设备导入审计日志失败 file={}", fileName, e);
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
     * 伪造出额外的日志行（日志注入），也会让「xxx-失败明细.xlsx」带上怪字符，故统一剥除。
     */
    private String normalizeFileName(String fileName) {
        String trimmed = trimToNull(fileName);
        if (trimmed == null) {
            return DEFAULT_FILE_NAME;
        }
        String cleaned = trimmed.replaceAll("\\p{Cntrl}", "").trim();
        return cleaned.isEmpty() ? DEFAULT_FILE_NAME : cleaned;
    }

    private DeviceImportRowVO copyToVO(DeviceImportRow row) {
        DeviceImportRowVO vo = new DeviceImportRowVO();
        vo.setRowNo(row.getRowNo());
        vo.setDeviceName(row.getDeviceName());
        vo.setAssetNo(row.getAssetNo());
        vo.setPrimaryCategoryName(row.getPrimaryCategoryName());
        vo.setSecondaryCategoryName(row.getSecondaryCategoryName());
        vo.setBrand(row.getBrand());
        vo.setModel(row.getModel());
        vo.setSerialNo(row.getSerialNo());
        vo.setStorageLocation(row.getStorageLocation());
        vo.setPurchaseDate(row.getPurchaseDate());
        vo.setRemark(row.getRemark());
        vo.setAmount(row.getAmount());
        return vo;
    }

    private ValidatedRow fail(DeviceImportRowVO vo, String reason) {
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

    /** 校验通过的行：展示用 VO + 可直接落库的请求体 */
    private record ValidatedRow(DeviceImportRowVO vo, DeviceSaveRequest request) {
    }

    /** 分类与资产编号上下文 */
    private record ImportContext(Map<String, DeviceCategory> primaryByName,
                                 Map<Long, Map<String, DeviceCategory>> secondaryByParent,
                                 Set<String> usedAssetNos) {

        Map<String, DeviceCategory> secondaryByParent(Long parentId) {
            return secondaryByParent.getOrDefault(parentId, Map.of());
        }
    }
}
