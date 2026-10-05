package com.enterprise.ticket.module.export.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.References;
import com.enterprise.ticket.module.export.support.FormDataFlattenSupport.VersionSchema;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.report.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 导出装配单测（Phase 16 Wave 3 · M6 专项）
 *
 * <p>只钉三件「纯函数测不到、又最容易在改造中静默丢失」的事：
 * <ol>
 *   <li><b>「必填 applyTypeId」只有一处判定</b>——{@code count} 与 {@code build} 都必须拒绝缺参，
 *       否则会出现「预检放过、生成时才失败」（用户拿到一条莫名其妙的失败任务）；
 *       同时校验「不静默退化为导出全量」；</li>
 *   <li><b>两条上限</b>——行数上限（既有能力，新增类型必须同样受约束）与
 *       <b>列数上限</b>（M6 新增：字段并集可能病态膨胀）；</li>
 *   <li><b>两个工作表</b>——表单数据 + 附件清单，且表头是「上下文 + 动态列」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ExportSheetBuilderTest {

    @Mock
    private ExportDataLoader loader;

    @Mock
    private ExportStorage storage;

    @Mock
    private ReportService reportService;

    @InjectMocks
    private ExportSheetBuilder builder;

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ErrorCode codeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    private static ExportQuery queryWith(Long applyTypeId) {
        ExportQuery query = new ExportQuery();
        ExportQuery.CustomFormFilter filter = new ExportQuery.CustomFormFilter();
        filter.setApplyTypeId(applyTypeId);
        query.setCustomForm(filter);
        return query;
    }

    private static OrderVO order(Long id, Long applyTypeId) {
        OrderVO vo = new OrderVO();
        vo.setId(id);
        vo.setOrderNo("P16C2026092700" + id);
        vo.setApplyTypeId(applyTypeId);
        vo.setApplicantName("张三");
        vo.setStatusLabel("审批中");
        vo.setCreatedAt(LocalDateTime.of(2026, 9, 27, 10, 30, 0));
        return vo;
    }

    private static OrderFormData formData(Long orderId, Long versionId) {
        OrderFormData data = new OrderFormData();
        data.setOrderId(orderId);
        data.setFormTemplateVersionId(versionId);
        data.setFormDataJson("{\"title\":\"采购申请\"}");
        return data;
    }

    private static FormSchema schemaOf(String key, String label) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(FormFieldType.TEXT.name());
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(field)));
        return schema;
    }

    private static Attachment attachment(Long orderId) {
        Attachment attachment = new Attachment();
        attachment.setBizId(orderId);
        attachment.setFileName("报价单.pdf");
        attachment.setFileSize(2048L);
        attachment.setCreatedAt(LocalDateTime.of(2026, 9, 27, 11, 0, 0));
        return attachment;
    }

    // ------------------------------------------------------------------
    // 必填校验（单一判定点）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("count：缺 customForm → EXPORT_QUERY_INVALID（不静默导出全部自定义工单）")
    void count_withoutFilter_rejected() {
        assertEquals(ErrorCode.EXPORT_QUERY_INVALID,
                codeOf(() -> builder.count(ExportType.CUSTOM_FORM, new ExportQuery(), false)));
    }

    @Test
    @DisplayName("count：applyTypeId 为空 → 同样拒绝（列集合无法确定）")
    void count_withoutApplyTypeId_rejected() {
        assertEquals(ErrorCode.EXPORT_QUERY_INVALID,
                codeOf(() -> builder.count(ExportType.CUSTOM_FORM, queryWith(null), false)));
    }

    @Test
    @DisplayName("build：缺 applyTypeId 同样拒绝 —— 预检与生成共用同一判定，不可能两端不一致")
    void build_withoutApplyTypeId_rejected() {
        assertEquals(ErrorCode.EXPORT_QUERY_INVALID,
                codeOf(() -> builder.build(ExportType.CUSTOM_FORM, new ExportQuery(), false)));
    }

    // ------------------------------------------------------------------
    // 计数与上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("count：走「全部工单」口径计数（数据范围由列表服务收口）")
    void count_customForm_countsViaLoader() {
        when(loader.countCustomFormOrders(any())).thenReturn(3L);
        when(storage.maxRows()).thenReturn(1000);

        assertEquals(3L, builder.count(ExportType.CUSTOM_FORM, queryWith(5L), false));
    }

    @Test
    @DisplayName("count：行数超上限 → EXPORT_ROW_LIMIT_EXCEEDED（新增类型同样受既有限制约束）")
    void count_customForm_rowLimit() {
        when(loader.countCustomFormOrders(any())).thenReturn(2001L);
        when(storage.maxRows()).thenReturn(2000);

        assertEquals(ErrorCode.EXPORT_ROW_LIMIT_EXCEEDED,
                codeOf(() -> builder.count(ExportType.CUSTOM_FORM, queryWith(5L), false)));
    }

    @Test
    @DisplayName("build：字段并集列数超上限 → EXPORT_ROW_LIMIT_EXCEEDED（拒绝而不是产出不可读的宽表）")
    void build_customForm_columnLimit() {
        when(storage.maxRows()).thenReturn(1000);
        when(storage.maxColumns()).thenReturn(1);
        when(loader.loadCustomFormOrders(any(), anyLong())).thenReturn(List.of(order(1L, 5L)));
        when(loader.applyTypeNames(any())).thenReturn(Map.of(5L, "采购申请"));
        when(loader.loadFormDataRows(any())).thenReturn(List.of(formData(1L, 10L)));
        // 两个字段 → 2 列 > 上限 1
        when(loader.loadVersionSchemas(any())).thenReturn(List.of(
                new VersionSchema(10L, 1, schemaOf("title", "标题")),
                new VersionSchema(11L, 2, schemaOf("project", "项目号"))));

        assertEquals(ErrorCode.EXPORT_ROW_LIMIT_EXCEEDED,
                codeOf(() -> builder.build(ExportType.CUSTOM_FORM, queryWith(5L), false)));
    }

    // ------------------------------------------------------------------
    // 工作表装配
    // ------------------------------------------------------------------

    @Test
    @DisplayName("build：产出「表单数据 + 附件清单」两个工作表，表头 = 上下文列 + 动态列")
    void build_customForm_twoSheets() {
        when(storage.maxRows()).thenReturn(1000);
        when(storage.maxColumns()).thenReturn(120);
        when(loader.loadCustomFormOrders(any(), anyLong())).thenReturn(List.of(order(1L, 5L)));
        when(loader.applyTypeNames(any())).thenReturn(Map.of(5L, "采购申请"));
        when(loader.loadFormDataRows(any())).thenReturn(List.of(formData(1L, 10L)));
        when(loader.loadVersionSchemas(any())).thenReturn(List.of(
                new VersionSchema(10L, 1, schemaOf("title", "标题"))));
        when(loader.references(any(), any(), any())).thenReturn(References.empty());
        when(loader.loadCustomOrderAttachments(any())).thenReturn(List.of());

        List<SheetSpec> sheets = builder.build(ExportType.CUSTOM_FORM, queryWith(5L), false);

        assertEquals(2, sheets.size());
        assertEquals("表单数据", sheets.get(0).name());
        assertEquals(List.of("工单编号", "申请类型", "申请人", "工单状态", "提交时间", "标题"),
                List.of(sheets.get(0).headers()));
        assertEquals("附件清单", sheets.get(1).name());
        assertEquals(1, sheets.get(0).rows().size());
        // 上下文列已装配：申请类型名（不是裸 id）、工单号
        assertEquals("P16C20260927001", sheets.get(0).rows().get(0)[0]);
        assertEquals("采购申请", sheets.get(0).rows().get(0)[1]);
    }

    @Test
    @DisplayName("build：附件行带上工单号与申请类型（只给文件名无法归属到具体工单）")
    void build_customForm_attachmentRowsResolveOrder() {
        when(storage.maxRows()).thenReturn(1000);
        when(storage.maxColumns()).thenReturn(120);
        when(loader.loadCustomFormOrders(any(), anyLong())).thenReturn(List.of(order(1L, 5L)));
        when(loader.applyTypeNames(any())).thenReturn(Map.of(5L, "采购申请"));
        when(loader.loadFormDataRows(any())).thenReturn(List.of(formData(1L, 10L)));
        when(loader.loadVersionSchemas(any())).thenReturn(List.of(
                new VersionSchema(10L, 1, schemaOf("title", "标题"))));
        when(loader.references(any(), any(), any())).thenReturn(References.empty());
        when(loader.loadCustomOrderAttachments(any())).thenReturn(List.of(attachment(1L)));

        List<SheetSpec> sheets = builder.build(ExportType.CUSTOM_FORM, queryWith(5L), false);
        Object[] cells = sheets.get(1).rows().get(0);

        assertEquals("P16C20260927001", cells[0]);
        assertEquals("采购申请", cells[1]);
        assertEquals("张三", cells[2]);
        assertEquals("报价单.pdf", cells[3]);
        assertEquals(2048L, cells[4]);
        assertEquals("2026-09-27 11:00:00", cells[5]);
    }

    @Test
    @DisplayName("build：无工单时仍产出两个工作表（表头在，附件清单为空表）")
    void build_customForm_noOrders() {
        when(storage.maxRows()).thenReturn(1000);
        when(storage.maxColumns()).thenReturn(120);
        when(loader.loadCustomFormOrders(any(), anyLong())).thenReturn(List.of());
        when(loader.applyTypeNames(any())).thenReturn(Map.of());
        when(loader.loadFormDataRows(any())).thenReturn(List.of());
        when(loader.loadVersionSchemas(any())).thenReturn(List.of());
        when(loader.references(any(), any(), any())).thenReturn(References.empty());
        when(loader.loadCustomOrderAttachments(any())).thenReturn(List.of());

        List<SheetSpec> sheets = builder.build(ExportType.CUSTOM_FORM, queryWith(5L), false);

        assertEquals(2, sheets.size());
        assertTrue(sheets.get(0).rows().isEmpty());
        assertEquals(5, sheets.get(0).headers().length, "只有上下文列");
    }
}
