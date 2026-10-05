package com.enterprise.ticket.module.form.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormTemplateStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateDetailVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVO;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表单模板服务单元测试（Phase 14）
 *
 * <p>纯 Mockito；{@code SecurityUtils} 用 {@link MockedStatic} 打桩。
 *
 * <p>为什么这些用例重要：这里承载「草稿宽松、发布严格」与「快照不可变」两条核心语义。
 * 具体地说：
 * <ul>
 *   <li><b>编辑已发布模板必须另开草稿版本</b>，不能覆盖已发布版本 —— 否则历史工单引用的
 *       那版表单会被悄悄改写，直接破坏快照语义；</li>
 *   <li><b>发布是唯一的严格校验点</b> —— 半成品 schema 一旦被放过，会变成运行期故障；</li>
 *   <li><b>被申请类型引用时禁止删除</b> —— 否则引用它的类型会指向不存在的表单。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FormTemplateServiceImplTest {

    private static final Long TEMPLATE_ID = 5L;
    private static final Long VERSION_ID = 50L;
    private static final Long USER_ID = 1L;

    private static final String VALID_SCHEMA =
            "{\"fields\":[{\"key\":\"title\",\"label\":\"标题\",\"type\":\"TEXT\",\"width\":1}]}";

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(FormTemplate.class, FormTemplateVersion.class, ApplyType.class);
    }

    @Mock
    private FormTemplateMapper templateMapper;
    @Mock
    private FormTemplateVersionMapper versionMapper;
    @Mock
    private ApplyTypeMapper applyTypeMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private FormTemplateServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void stubCurrentUser() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void closeStatic() {
        securityUtils.close();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private FormTemplate template(String status) {
        FormTemplate template = new FormTemplate();
        template.setId(TEMPLATE_ID);
        template.setTemplateName("采购申请表单");
        template.setStatus(status);
        return template;
    }

    private FormTemplateVersion version(Long id, int no, String schemaJson, LocalDateTime publishedAt) {
        FormTemplateVersion version = new FormTemplateVersion();
        version.setId(id);
        version.setTemplateId(TEMPLATE_ID);
        version.setVersionNo(no);
        version.setSchemaJson(schemaJson);
        version.setPublishedAt(publishedAt);
        return version;
    }

    private FormTemplateSaveRequest saveRequest(String name, String schemaJson) {
        FormTemplateSaveRequest request = new FormTemplateSaveRequest();
        request.setTemplateName(name);
        request.setDescription("用于采购办公用品");
        FormSchema schema = FormSchemaCodec.readSchema(schemaJson);
        request.setSchema(schema);
        return request;
    }

    private static ErrorCode codeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    // ------------------------------------------------------------------
    // createTemplate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新建模板：名称重复 → FORM_TEMPLATE_NAME_EXISTS，不写库")
    void create_rejectsDuplicateName() {
        when(templateMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.FORM_TEMPLATE_NAME_EXISTS,
                codeOf(() -> service.createTemplate(saveRequest("采购申请表单", VALID_SCHEMA))));
        verify(templateMapper, never()).insert(any(FormTemplate.class));
    }

    @Test
    @DisplayName("新建模板：正常创建模板 + v1 草稿版本，状态为 DRAFT")
    void create_createsTemplateAndDraftVersion() {
        when(templateMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.insert(any(FormTemplate.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FormTemplate.class).setId(TEMPLATE_ID);
            return 1;
        });
        when(versionMapper.insert(any(FormTemplateVersion.class))).thenReturn(1);

        Long id = service.createTemplate(saveRequest("采购申请表单", VALID_SCHEMA));

        assertEquals(TEMPLATE_ID, id);

        ArgumentCaptor<FormTemplate> templateCaptor = ArgumentCaptor.forClass(FormTemplate.class);
        verify(templateMapper).insert(templateCaptor.capture());
        assertEquals(FormTemplateStatus.DRAFT.name(), templateCaptor.getValue().getStatus());
        assertEquals(USER_ID, templateCaptor.getValue().getCreatedBy());

        ArgumentCaptor<FormTemplateVersion> versionCaptor = ArgumentCaptor.forClass(FormTemplateVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertEquals(1, versionCaptor.getValue().getVersionNo());
        assertEquals(TEMPLATE_ID, versionCaptor.getValue().getTemplateId());
        // 草稿：发布时间为空
        assertEquals(null, versionCaptor.getValue().getPublishedAt());
    }

    @Test
    @DisplayName("新建模板：允许空表单（新建时还没字段是正常的，发布时才要求）")
    void create_allowsEmptySchema() {
        when(templateMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.insert(any(FormTemplate.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FormTemplate.class).setId(TEMPLATE_ID);
            return 1;
        });
        when(versionMapper.insert(any(FormTemplateVersion.class))).thenReturn(1);

        FormTemplateSaveRequest request = new FormTemplateSaveRequest();
        request.setTemplateName("空白表单");
        request.setSchema(null);

        service.createTemplate(request);

        ArgumentCaptor<FormTemplateVersion> captor = ArgumentCaptor.forClass(FormTemplateVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(0, FormSchemaCodec.readSchema(captor.getValue().getSchemaJson()).getFields().size());
    }

    // ------------------------------------------------------------------
    // updateTemplate：已发布模板另开草稿
    // ------------------------------------------------------------------

    @Test
    @DisplayName("保存：模板已发布且无草稿 → 另开新版本草稿，不覆盖已发布版本")
    void update_createsNewDraftWhenPublished() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.PUBLISHED.name()));
        when(templateMapper.selectCount(any())).thenReturn(0L);
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(VERSION_ID, 1, VALID_SCHEMA, LocalDateTime.now())));
        when(versionMapper.insert(any(FormTemplateVersion.class))).thenReturn(1);

        service.updateTemplate(TEMPLATE_ID, saveRequest("采购申请表单", VALID_SCHEMA));

        ArgumentCaptor<FormTemplateVersion> captor = ArgumentCaptor.forClass(FormTemplateVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getVersionNo());
        assertEquals(null, captor.getValue().getPublishedAt());
        // 已发布版本不可被 update 覆盖
        verify(versionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("保存：存在草稿 → 就地更新草稿，不再新建版本")
    void update_reusesExistingDraft() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.DRAFT.name()));
        when(templateMapper.selectCount(any())).thenReturn(0L);
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(VERSION_ID, 1, null, null)));
        when(versionMapper.update(any(), any())).thenReturn(1);

        service.updateTemplate(TEMPLATE_ID, saveRequest("采购申请表单", VALID_SCHEMA));

        verify(versionMapper).update(any(), any());
        verify(versionMapper, never()).insert(any(FormTemplateVersion.class));
    }

    @Test
    @DisplayName("保存：名称与其它模板重复 → 拒绝")
    void update_rejectsDuplicateName() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.DRAFT.name()));
        when(templateMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.FORM_TEMPLATE_NAME_EXISTS,
                codeOf(() -> service.updateTemplate(TEMPLATE_ID, saveRequest("重名", VALID_SCHEMA))));
    }

    // ------------------------------------------------------------------
    // publish：唯一的严格校验点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发布：没有草稿 → FORM_SCHEMA_INVALID（提示先保存）")
    void publish_requiresDraft() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.PUBLISHED.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(VERSION_ID, 1, VALID_SCHEMA, LocalDateTime.now())));

        assertEquals(ErrorCode.FORM_SCHEMA_INVALID, codeOf(() -> service.publish(TEMPLATE_ID)));
        verify(versionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("发布：草稿是半成品（空字段）→ 拒绝发布，模板状态不变")
    void publish_rejectsInvalidSchema() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.DRAFT.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(VERSION_ID, 1, null, null)));

        assertEquals(ErrorCode.FORM_SCHEMA_INVALID, codeOf(() -> service.publish(TEMPLATE_ID)));
        verify(versionMapper, never()).update(any(), any());
        verify(templateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("发布：合法草稿 → 写入发布时间/发布人并把模板置为 PUBLISHED，返回版本 id")
    void publish_success() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.DRAFT.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(VERSION_ID, 1, VALID_SCHEMA, null)));
        when(versionMapper.update(any(), any())).thenReturn(1);
        when(templateMapper.update(any(), any())).thenReturn(1);

        Long publishedVersionId = service.publish(TEMPLATE_ID);

        assertEquals(VERSION_ID, publishedVersionId);
        verify(versionMapper).update(any(), any());
        verify(templateMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // deleteTemplate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除：模板被申请类型引用 → FORM_TEMPLATE_IN_USE，不删除任何数据")
    void delete_rejectsReferencedTemplate() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.PUBLISHED.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(VERSION_ID, 1, VALID_SCHEMA, LocalDateTime.now())));
        when(applyTypeMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.FORM_TEMPLATE_IN_USE, codeOf(() -> service.deleteTemplate(TEMPLATE_ID)));
        verify(versionMapper, never()).delete(any());
        verify(templateMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除：未被引用 → 先删版本再删模板")
    void delete_removesUnreferencedTemplate() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.DRAFT.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(VERSION_ID, 1, null, null)));
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(versionMapper.delete(any())).thenReturn(1);
        when(templateMapper.deleteById(TEMPLATE_ID)).thenReturn(1);

        service.deleteTemplate(TEMPLATE_ID);

        verify(versionMapper).delete(any());
        verify(templateMapper).deleteById(TEMPLATE_ID);
    }

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取已发布 schema：草稿版本 → FORM_TEMPLATE_NO_PUBLISHED_VERSION")
    void requirePublishedSchema_rejectsDraft() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(VERSION_ID, 1, VALID_SCHEMA, null));

        assertEquals(ErrorCode.FORM_TEMPLATE_NO_PUBLISHED_VERSION,
                codeOf(() -> service.requirePublishedSchema(VERSION_ID)));
    }

    @Test
    @DisplayName("取已发布 schema：版本不存在 → FORM_TEMPLATE_VERSION_NOT_FOUND")
    void requirePublishedSchema_rejectsMissingVersion() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(null);

        assertEquals(ErrorCode.FORM_TEMPLATE_VERSION_NOT_FOUND,
                codeOf(() -> service.requirePublishedSchema(VERSION_ID)));
    }

    @Test
    @DisplayName("取已发布 schema：已发布版本 → 返回解析后的 schema")
    void requirePublishedSchema_returnsSchema() {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, 1, VALID_SCHEMA, LocalDateTime.now()));

        FormSchema schema = service.requirePublishedSchema(VERSION_ID);

        assertEquals(1, schema.getFields().size());
        assertEquals("title", schema.getFields().get(0).getKey());
    }

    @Test
    @DisplayName("isPublishedVersion：空 / 不存在 / 草稿 → false；已发布 → true")
    void isPublishedVersion_branches() {
        assertFalse(service.isPublishedVersion(null));

        when(versionMapper.selectById(VERSION_ID)).thenReturn(null);
        assertFalse(service.isPublishedVersion(VERSION_ID));

        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(VERSION_ID, 1, VALID_SCHEMA, null));
        assertFalse(service.isPublishedVersion(VERSION_ID));

        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, 1, VALID_SCHEMA, LocalDateTime.now()));
        assertTrue(service.isPublishedVersion(VERSION_ID));
    }

    // ------------------------------------------------------------------
    // getDetail / listTemplates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("详情：有草稿优先给草稿；无草稿给最新已发布版本")
    void getDetail_prefersDraft() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(FormTemplateStatus.PUBLISHED.name()));
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        // v1 已发布、v2 草稿
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(1L, 1, VALID_SCHEMA, LocalDateTime.now()),
                version(2L, 2, "{\"fields\":[{\"key\":\"b\",\"label\":\"乙\",\"type\":\"NUMBER\",\"width\":1}]}", null)));

        FormTemplateDetailVO detail = service.getDetail(TEMPLATE_ID);

        assertEquals(2L, detail.getVersionId());
        assertTrue(Boolean.TRUE.equals(detail.getDraft()));
        assertEquals(1L, detail.getLatestPublishedVersionId());
        assertEquals("b", detail.getSchema().getFields().get(0).getKey());
    }

    @Test
    @DisplayName("详情：模板不存在 → FORM_TEMPLATE_NOT_FOUND")
    void getDetail_missingTemplate() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(null);

        assertEquals(ErrorCode.FORM_TEMPLATE_NOT_FOUND, codeOf(() -> service.getDetail(TEMPLATE_ID)));
    }

    @Test
    @DisplayName("列表：hasDraft / fieldCount / latestPublishedVersionNo 装配正确")
    void listTemplates_assemblesFlags() {
        when(templateMapper.selectList(any())).thenReturn(List.of(template(FormTemplateStatus.PUBLISHED.name())));
        when(versionMapper.selectList(any())).thenReturn(List.of(
                version(1L, 1, VALID_SCHEMA, LocalDateTime.now()),
                version(2L, 2, null, null)));

        List<FormTemplateVO> list = service.listTemplates();

        assertEquals(1, list.size());
        FormTemplateVO vo = list.get(0);
        assertTrue(Boolean.TRUE.equals(vo.getHasDraft()));
        assertEquals(1, vo.getLatestVersionNo());
        assertEquals(1L, vo.getLatestPublishedVersionId());
        // fieldCount 取草稿（更贴近用户正在编辑的内容）：草稿是空 schema → 0
        assertEquals(0, vo.getFieldCount());
    }

    @Test
    @DisplayName("列表：无模板时直接返回空列表，不再查版本")
    void listTemplates_empty() {
        when(templateMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listTemplates().isEmpty());
        verify(versionMapper, never()).selectList(any());
    }
}
