package com.enterprise.ticket.module.device.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceImportExecuteRequest;
import com.enterprise.ticket.module.device.dto.DeviceImportRow;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportPreviewVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportResultVO;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.excel.DeviceImportExcelSupport;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备批量导入规则单元测试（规范 §8）
 *
 * <p>集中覆盖「最容易出错、且出错了不会报错只会写坏数据」的几条规则：
 * <ul>
 *   <li><b>两阶段写入</b>：确认导入会<b>重新校验</b>一遍，而不是相信预览结果（防 TOCTOU）；</li>
 *   <li><b>分批事务降级</b>：批内一行失败整批回滚后，合法行仍要按各自事务落库；</li>
 *   <li><b>结果统计</b>：成功数 + 失败数必须等于提交行数，失败原因要能定位到行；</li>
 *   <li><b>校验同源</b>：导入复用「单条新增」的 Bean Validation 注解与
 *       {@link DeviceService#validateNewDevice} 业务规则，不另写一套；</li>
 *   <li><b>体积预检</b>：必须发生在解析之前（否则 xlsx 的高压缩比会先把内存吃满）。</li>
 * </ul>
 *
 * <p>此处用<b>真实的</b> Hibernate Validator，而不是 mock —— 「导入与单条新增共用同一套注解」
 * 这一结论只有真跑一遍约束校验才算被验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceImportServiceImplTest {

    private static final String PRIMARY = "电脑";
    private static final String SECONDARY = "笔记本";

    /** MyBatis-Plus 的方法引用 Wrapper 依赖实体元数据缓存，无 Spring 上下文时需手动注册 */
    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(DeviceCategory.class);
    }

    @Mock
    private DeviceImportExcelSupport excelSupport;
    @Mock
    private DeviceService deviceService;
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private OperationLogService operationLogService;

    /** 真实校验器：用于验证「导入复用单条新增的 Bean Validation 注解」 */
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private DeviceImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeviceImportServiceImpl(excelSupport, deviceService, categoryMapper,
                validator, transactionManager, operationLogService);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(categoryMapper.selectList(any())).thenReturn(categories());
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private DeviceCategory category(Long id, String name, Long parentId, int level) {
        DeviceCategory category = new DeviceCategory();
        category.setId(id);
        category.setCategoryName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        return category;
    }

    /** 与演示基线一致的两级分类：电脑(10) → 笔记本(13)；显示器(11) → 液晶显示器(15) */
    private List<DeviceCategory> categories() {
        return List.of(
                category(10L, PRIMARY, DeviceCategory.ROOT_PARENT_ID, DeviceCategory.LEVEL_PRIMARY),
                category(11L, "显示器", DeviceCategory.ROOT_PARENT_ID, DeviceCategory.LEVEL_PRIMARY),
                category(13L, SECONDARY, 10L, DeviceCategory.LEVEL_SECONDARY),
                category(15L, "液晶显示器", 11L, DeviceCategory.LEVEL_SECONDARY));
    }

    private DeviceImportRow row(int rowNo, String deviceName, String assetNo) {
        DeviceImportRow row = new DeviceImportRow();
        row.setRowNo(rowNo);
        row.setDeviceName(deviceName);
        row.setAssetNo(assetNo);
        row.setPrimaryCategoryName(PRIMARY);
        row.setSecondaryCategoryName(SECONDARY);
        return row;
    }

    private DeviceImportExecuteRequest executeRequest(List<DeviceImportRow> rows) {
        DeviceImportExecuteRequest request = new DeviceImportExecuteRequest();
        request.setFileName("设备清单.xlsx");
        request.setRows(rows);
        return request;
    }

    private MockMultipartFile upload(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private static ErrorCode errorCodeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    // ------------------------------------------------------------------
    // 上传前后的形态校验（文件类型 / 大小）—— 后端必须自己再判一遍
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上传校验：非 .xlsx 后缀直接拒绝，且不触碰解析器")
    void upload_rejectsNonXlsxBeforeParsing() {
        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_TYPE_INVALID,
                errorCodeOf(() -> service.preview(upload("设备清单.xls", "x".getBytes(StandardCharsets.UTF_8)))));
        verify(excelSupport, never()).parse(any(InputStream.class), anyInt());
    }

    @Test
    @DisplayName("上传校验：超过 5MB 后端二次拦截（不依赖前端）")
    void upload_rejectsOversizeFileOnServer() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE,
                errorCodeOf(() -> service.preview(upload("设备清单.xlsx", big))));
        verify(excelSupport, never()).parse(any(InputStream.class), anyInt());
    }

    @Test
    @DisplayName("上传校验：空文件被拒绝")
    void upload_rejectsEmptyFile() {
        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_REQUIRED,
                errorCodeOf(() -> service.preview(upload("设备清单.xlsx", new byte[0]))));
    }

    // ------------------------------------------------------------------
    // 体积预检的先后顺序（资源防护）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预览：解压体积预检必须先于解析执行")
    void preview_checksExpandedSizeBeforeParsing() {
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row(2, "设备A", "IMP-A")));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        InOrder order = inOrder(excelSupport);
        order.verify(excelSupport).assertExpandedSize(any(InputStream.class));
        order.verify(excelSupport).parse(any(InputStream.class), anyInt());
        assertEquals(1, preview.getSuccessCount());
    }

    @Test
    @DisplayName("预览：体积预检失败时直接返回 FILE_TOO_LARGE，不再解析")
    void preview_propagatesExpandedSizeRejection() {
        doThrow(new BusinessException(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE, "文件解压后的内容体积异常"))
                .when(excelSupport).assertExpandedSize(any(InputStream.class));

        assertEquals(ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE,
                errorCodeOf(() -> service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)))));
        verify(excelSupport, never()).parse(any(InputStream.class), anyInt());
    }

    // ------------------------------------------------------------------
    // 逐行校验规则
    // ------------------------------------------------------------------

    @Test
    @DisplayName("校验：一级分类按名称匹配不到 → 该行失败并提示去分类管理创建")
    void validate_rejectsUnknownPrimaryCategory() {
        DeviceImportRow row = row(2, "设备A", "IMP-A");
        row.setPrimaryCategoryName("不存在的分类");
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "不存在");
    }

    @Test
    @DisplayName("校验：二级分类必须归属所选一级分类（显示器下的分类配到电脑上 → 失败）")
    void validate_rejectsSecondaryCategoryOfAnotherPrimary() {
        DeviceImportRow row = row(2, "设备A", "IMP-A");
        row.setSecondaryCategoryName("液晶显示器");
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "不属于一级分类");
    }

    @Test
    @DisplayName("校验：购置日期必须为 YYYY-MM-DD")
    void validate_rejectsNonIsoDate() {
        DeviceImportRow row = row(2, "设备A", "IMP-A");
        row.setPurchaseDate("2026/01/15");
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "YYYY-MM-DD");
    }

    @Test
    @DisplayName("校验：字段超长按「报错」处理而非静默截断（复用单条新增的 @Size）")
    void validate_rejectsOverlongDeviceName() {
        DeviceImportRow row = row(2, "设".repeat(129), "IMP-A");
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "128");
    }

    @Test
    @DisplayName("校验：设备名称为空（Bean Validation 与单条新增同一套注解）")
    void validate_rejectsBlankDeviceName() {
        DeviceImportRow row = row(2, "   ", "IMP-A");
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "设备名称不能为空");
    }

    @Test
    @DisplayName("校验：资产编号在库中已存在（复用 validateNewDevice 的业务规则）")
    void validate_rejectsAssetNoAlreadyInDatabase() {
        doThrow(new BusinessException(ErrorCode.DEVICE_ASSET_NO_EXISTS, "资产编号「IMP-A」已存在，请更换"))
                .when(deviceService).validateNewDevice(argThat((DeviceSaveRequest r) -> "IMP-A".equals(r.getAssetNo())));
        when(excelSupport.parse(any(InputStream.class), anyInt())).thenReturn(List.of(row(2, "设备A", "IMP-A")));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertRowFailure(preview, 0, "已存在");
    }

    @Test
    @DisplayName("校验：本次导入文件内重复（不区分大小写）时，只有第一处被导入")
    void validate_rejectsInFileDuplicateIgnoringCase() {
        when(excelSupport.parse(any(InputStream.class), anyInt()))
                .thenReturn(List.of(row(2, "设备A", "IMP-DUP"), row(3, "设备B", "imp-dup")));

        DeviceImportPreviewVO preview = service.preview(upload("设备清单.xlsx", "x".getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, preview.getTotalCount());
        assertEquals(1, preview.getSuccessCount());
        assertEquals(1, preview.getFailCount());
        assertRowFailure(preview, 1, "重复");
    }

    // ------------------------------------------------------------------
    // 确认导入：TOCTOU / 事务降级 / 统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确认导入：确认前库中已被并发写入同编号 → 重新校验把该行判失败，不产生重复数据")
    void execute_revalidatesRowsAgainstLatestDatabaseState() {
        // 预览时通过，确认时库中已存在（模拟 TOCTOU 窗口内他人抢先录入）
        doThrow(new BusinessException(ErrorCode.DEVICE_ASSET_NO_EXISTS, "资产编号「IMP-RACE」已存在，请更换"))
                .when(deviceService).validateNewDevice(argThat((DeviceSaveRequest r) -> "IMP-RACE".equals(r.getAssetNo())));

        DeviceImportResultVO result = service.execute(executeRequest(
                List.of(row(2, "竞态设备", "IMP-RACE"), row(3, "正常设备", "IMP-OK"))));

        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals("IMP-RACE", result.getFailures().get(0).getAssetNo());
        assertTrue(result.getFailures().get(0).getReason().contains("已存在"));
        // 只有通过二次校验的那一行被写入
        verify(deviceService, times(1)).create(any(DeviceSaveRequest.class));
    }

    @Test
    @DisplayName("确认导入：批内一行写入失败 → 整批回滚后逐行降级，其余合法行仍落库")
    void execute_fallsBackToRowByRowWhenBatchInsertFails() {
        // 只在「写入」阶段失败：模拟唯一索引在预检之后仍被并发抢先（DuplicateKeyException 的最终兜底）
        doThrow(new BusinessException(ErrorCode.DEVICE_ASSET_NO_EXISTS, "资产编号「IMP-B」已存在，请更换"))
                .when(deviceService).create(argThat((DeviceSaveRequest r) -> "IMP-B".equals(r.getAssetNo())));

        DeviceImportResultVO result = service.execute(executeRequest(
                List.of(row(2, "设备A", "IMP-A"), row(3, "设备B", "IMP-B"), row(4, "设备C", "IMP-C"))));

        assertEquals(2, result.getImportedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals("IMP-B", result.getFailures().get(0).getAssetNo());
        assertTrue(result.getFailures().get(0).getReason().contains("已存在"));
        // A 被尝试两次（随整批回滚一次 + 逐行降级成功一次）；B 也是两次（整批内失败 + 逐行复现失败）；
        // C 只有一次 —— 整批的 forEach 在 B 处就中断了，它从未参与整批尝试
        verify(deviceService, times(2)).create(argThat((DeviceSaveRequest r) -> "IMP-A".equals(r.getAssetNo())));
        verify(deviceService, times(2)).create(argThat((DeviceSaveRequest r) -> "IMP-B".equals(r.getAssetNo())));
        verify(deviceService, times(1)).create(argThat((DeviceSaveRequest r) -> "IMP-C".equals(r.getAssetNo())));
    }

    @Test
    @DisplayName("确认导入：失败明细按原始行号升序，便于逐行核对原文件")
    void execute_sortsFailuresByRowNo() {
        doThrow(new BusinessException(ErrorCode.DEVICE_ASSET_NO_EXISTS, "写入失败"))
                .when(deviceService).create(any(DeviceSaveRequest.class));

        DeviceImportResultVO result = service.execute(executeRequest(
                List.of(row(9, "设备A", "IMP-A"), row(3, "设备B", "IMP-B"))));

        assertEquals(0, result.getImportedCount());
        assertEquals(2, result.getFailedCount());
        assertEquals(3, result.getFailures().get(0).getRowNo());
        assertEquals(9, result.getFailures().get(1).getRowNo());
    }

    @Test
    @DisplayName("确认导入：全部行未通过 → NOTHING_TO_IMPORT，且审计记为失败、不写库")
    void execute_throwsWhenNothingToImport() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.execute(executeRequest(List.of(row(2, null, "IMP-X")))));

        assertEquals(ErrorCode.DEVICE_IMPORT_NOTHING_TO_IMPORT, e.getErrorCode());
        verify(deviceService, never()).create(any(DeviceSaveRequest.class));
        verify(operationLogService).recordCurrent(eq("DEVICE"), eq("DEVICE_IMPORT"), anyString(),
                eq(false), eq(RiskLevel.NORMAL));
    }

    @Test
    @DisplayName("确认导入：成功/失败计数之和等于提交行数，并写一条含文件名的成功审计")
    void execute_recordsAuditWithAccurateCounts() {
        DeviceImportResultVO result = service.execute(executeRequest(
                List.of(row(2, "设备A", "IMP-A"), row(3, null, "IMP-B"))));

        assertEquals(2, result.getImportedCount() + result.getFailedCount());
        verify(operationLogService).recordCurrent(eq("DEVICE"), eq("DEVICE_IMPORT"),
                argThat(details -> details != null && details.contains("设备清单.xlsx")), eq(true), eq(RiskLevel.NORMAL));
    }

    @Test
    @DisplayName("确认导入：文件名中的控制字符被剥除（防日志行伪造）")
    void execute_sanitizesFileName() {
        DeviceImportExecuteRequest request = executeRequest(List.of(row(2, "设备A", "IMP-A")));
        request.setFileName("evil\r\nX-Injected: 1.xlsx");

        service.execute(request);

        verify(operationLogService).recordCurrent(eq("DEVICE"), eq("DEVICE_IMPORT"),
                argThat(details -> details != null && !details.contains("\n") && !details.contains("\r")
                        && details.contains("X-Injected: 1.xlsx")), eq(true), eq(RiskLevel.NORMAL));
    }

    // ------------------------------------------------------------------

    private void assertRowFailure(DeviceImportPreviewVO preview, int rowIndex, String expectedReasonFragment) {
        assertEquals(1, preview.getFailCount());
        assertFalse(preview.getRows().get(rowIndex).isValid());
        String reason = preview.getRows().get(rowIndex).getReason();
        assertTrue(reason != null && reason.contains(expectedReasonFragment),
                "失败原因应包含「" + expectedReasonFragment + "」，实际：" + reason);
    }
}
