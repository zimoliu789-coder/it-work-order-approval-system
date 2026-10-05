package com.enterprise.ticket.module.form.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.form.FormSchemaValidator;
import com.enterprise.ticket.common.constant.FormTemplateStatus;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateDetailVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVersionVO;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表单模板服务实现
 *
 * <h2>三个关键设计</h2>
 * <ol>
 *   <li><b>草稿用 {@code published_at IS NULL} 表达</b>，不额外建状态列 ——
 *       避免「status=草稿」与「发布时间为空」两个事实互相矛盾（见
 *       {@link FormTemplateVersion#isDraft()}）；</li>
 *   <li><b>草稿宽松、发布严格</b>：保存草稿不做结构校验（允许半成品），
 *       发布时才跑 {@link FormSchemaValidator}。若在保存时就严格校验，
 *       用户拖了一个下拉框还没配选项就存不了草稿，设计体验会非常难受；</li>
 *   <li><b>删除前查引用</b>：模板被申请类型引用时只能停用。停用**不影响**已引用它的
 *       申请类型继续工作 —— 历史工单必须能被渲染，停用只阻止「被新的申请类型引用」。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateServiceImpl implements FormTemplateService {

    private final FormTemplateMapper templateMapper;
    private final FormTemplateVersionMapper versionMapper;
    /** 删除模板前查引用（只读依赖，不反向调用 ApplyTypeService，避免服务间循环依赖） */
    private final ApplyTypeMapper applyTypeMapper;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<FormTemplateVO> listTemplates() {
        List<FormTemplate> templates = templateMapper.selectList(
                Wrappers.<FormTemplate>lambdaQuery().orderByDesc(FormTemplate::getId));
        if (templates.isEmpty()) {
            return List.of();
        }
        List<Long> ids = templates.stream().map(FormTemplate::getId).toList();
        // 一次取回全部版本，避免逐模板查库（模板数量少但版本会随发布累积）
        Map<Long, List<FormTemplateVersion>> versionsByTemplate = versionMapper.selectList(
                        Wrappers.<FormTemplateVersion>lambdaQuery()
                                .in(FormTemplateVersion::getTemplateId, ids)
                                .orderByAsc(FormTemplateVersion::getVersionNo))
                .stream()
                .collect(Collectors.groupingBy(FormTemplateVersion::getTemplateId));
        return templates.stream()
                .map(template -> toVO(template, versionsByTemplate.getOrDefault(template.getId(), List.of())))
                .toList();
    }

    @Override
    public FormTemplateDetailVO getDetail(Long templateId) {
        FormTemplate template = requireTemplate(templateId);
        List<FormTemplateVersion> versions = versionsOf(templateId);
        FormTemplateVersion published = latestPublished(versions);
        FormTemplateVersion draft = latestDraft(versions);
        // 有草稿优先给草稿：用户上次没发布完的编辑应当被继续看到
        FormTemplateVersion shown = draft != null ? draft : published;

        FormTemplateDetailVO vo = new FormTemplateDetailVO();
        vo.setId(template.getId());
        vo.setTemplateName(template.getTemplateName());
        vo.setDescription(template.getDescription());
        vo.setStatus(template.getStatus());
        vo.setStatusLabel(FormTemplateStatus.labelOf(template.getStatus()));
        vo.setLatestPublishedVersionId(published == null ? null : published.getId());
        vo.setLatestPublishedVersionNo(published == null ? null : published.getVersionNo());
        vo.setCreatedAt(template.getCreatedAt());
        vo.setUpdatedAt(template.getUpdatedAt());
        vo.setReferenced(isReferenced(versions));
        if (shown == null) {
            // 没有任何版本（理论上 createTemplate 会建 v1 草稿；容错处理人工删库的情况）
            vo.setVersionId(null);
            vo.setVersionNo(null);
            vo.setDraft(true);
            vo.setPublished(false);
            vo.setSchema(FormSchema.empty());
            return vo;
        }
        vo.setVersionId(shown.getId());
        vo.setVersionNo(shown.getVersionNo());
        vo.setDraft(shown.isDraft());
        vo.setPublished(!shown.isDraft());
        vo.setSchema(FormSchemaCodec.readSchema(shown.getSchemaJson()));
        return vo;
    }

    @Override
    public List<FormTemplateVersionVO> listVersions(Long templateId) {
        requireTemplate(templateId);
        List<FormTemplateVersion> versions = versionsOf(templateId);
        Map<Long, String> publisherNames = userNameMap(versions.stream()
                .map(FormTemplateVersion::getPublishedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        // 倒序：最新版本排在最上面，符合「版本历史」的阅读习惯
        return versions.stream()
                .sorted(Comparator.comparing(FormTemplateVersion::getVersionNo).reversed())
                .map(version -> toVersionVO(version, publisherNames, false))
                .toList();
    }

    @Override
    public FormTemplateVersionVO getVersion(Long versionId) {
        FormTemplateVersion version = requireVersion(versionId);
        Map<Long, String> names = userNameMap(version.getPublishedBy() == null
                ? Set.of() : Set.of(version.getPublishedBy()));
        return toVersionVO(version, names, true);
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTemplate(FormTemplateSaveRequest request) {
        String name = request.getTemplateName().trim();
        assertNameAvailable(name, null);

        FormTemplate template = new FormTemplate();
        template.setTemplateName(name);
        template.setDescription(trimToNull(request.getDescription()));
        template.setStatus(FormTemplateStatus.DRAFT.name());
        template.setCreatedBy(SecurityUtils.getCurrentUserId());
        templateMapper.insert(template);

        FormTemplateVersion version = new FormTemplateVersion();
        version.setTemplateId(template.getId());
        version.setVersionNo(1);
        // 允许空表单：新建模板时还没有字段是正常的，发布时才要求至少 1 个字段
        version.setSchemaJson(FormSchemaCodec.write(
                request.getSchema() == null ? FormSchema.empty() : request.getSchema()));
        versionMapper.insert(version);

        log.info("表单模板已创建 id={} name={} 初始草稿版本=1", template.getId(), name);
        return template.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(Long templateId, FormTemplateSaveRequest request) {
        FormTemplate template = requireTemplate(templateId);
        String name = request.getTemplateName().trim();
        assertNameAvailable(name, templateId);

        templateMapper.update(null, Wrappers.<FormTemplate>lambdaUpdate()
                .eq(FormTemplate::getId, templateId)
                .set(FormTemplate::getTemplateName, name)
                .set(FormTemplate::getDescription, trimToNull(request.getDescription())));

        String schemaJson = FormSchemaCodec.write(
                request.getSchema() == null ? FormSchema.empty() : request.getSchema());
        FormTemplateVersion draft = findDraft(templateId);
        if (draft == null) {
            // 「编辑已发布模板自动创建草稿版本」：不覆盖已发布版本，另开一版草稿
            int nextNo = nextVersionNo(templateId);
            FormTemplateVersion created = new FormTemplateVersion();
            created.setTemplateId(templateId);
            created.setVersionNo(nextNo);
            created.setSchemaJson(schemaJson);
            versionMapper.insert(created);
            log.info("模板 {} 已发布版本不可改，自动新建草稿版本 v{}", templateId, nextNo);
        } else {
            versionMapper.update(null, Wrappers.<FormTemplateVersion>lambdaUpdate()
                    .eq(FormTemplateVersion::getId, draft.getId())
                    .set(FormTemplateVersion::getSchemaJson, schemaJson));
        }
        log.info("表单模板 {} 草稿已保存（{}）", templateId, Objects.equals(template.getStatus(),
                FormTemplateStatus.PUBLISHED.name()) ? "另开新版本" : "v1");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long templateId) {
        requireTemplate(templateId);
        FormTemplateVersion draft = findDraft(templateId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.FORM_SCHEMA_INVALID,
                    "当前没有待发布的草稿改动，请先在设计器中保存后再发布");
        }
        // 发布是唯一的严格校验点：此后再无人能改这份 schema
        FormSchema schema = FormSchemaCodec.readSchema(draft.getSchemaJson());
        FormSchemaValidator.validateAndNormalize(schema);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        versionMapper.update(null, Wrappers.<FormTemplateVersion>lambdaUpdate()
                .eq(FormTemplateVersion::getId, draft.getId())
                .set(FormTemplateVersion::getSchemaJson, FormSchemaCodec.write(schema))
                .set(FormTemplateVersion::getPublishedAt, LocalDateTime.now())
                .set(FormTemplateVersion::getPublishedBy, currentUserId));
        templateMapper.update(null, Wrappers.<FormTemplate>lambdaUpdate()
                .eq(FormTemplate::getId, templateId)
                .set(FormTemplate::getStatus, FormTemplateStatus.PUBLISHED.name()));

        log.info("表单模板 {} 已发布版本 v{}（id={}），字段 {} 个",
                templateId, draft.getVersionNo(), draft.getId(), schema.getFields().size());
        return draft.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long templateId) {
        requireTemplate(templateId);
        List<FormTemplateVersion> versions = versionsOf(templateId);
        if (isReferenced(versions)) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_IN_USE,
                    "该表单模板已被申请类型引用，只能停用不能删除");
        }
        // 物理删除：模板与版本都是纯配置，没有「历史工单引用」的顾虑
        // （历史工单引用的是 apply_type → 版本；能走到这里的模板必然没被任何申请类型引用）
        if (!versions.isEmpty()) {
            versionMapper.delete(Wrappers.<FormTemplateVersion>lambdaQuery()
                    .eq(FormTemplateVersion::getTemplateId, templateId));
        }
        templateMapper.deleteById(templateId);
        log.info("表单模板 {} 及其 {} 个版本已删除", templateId, versions.size());
    }

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    @Override
    public FormSchema requirePublishedSchema(Long versionId) {
        FormTemplateVersion version = requireVersion(versionId);
        if (version.isDraft()) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NO_PUBLISHED_VERSION,
                    "引用的表单版本尚未发布，请先在设计器中发布该版本");
        }
        return FormSchemaCodec.readSchema(version.getSchemaJson());
    }

    @Override
    public boolean isPublishedVersion(Long versionId) {
        if (versionId == null) {
            return false;
        }
        FormTemplateVersion version = versionMapper.selectById(versionId);
        return version != null && !version.isDraft();
    }

    /** 版本 id 集合（供申请类型删除时的引用检查复用） */
    public List<Long> versionIdsOf(Long templateId) {
        return versionsOf(templateId).stream().map(FormTemplateVersion::getId).toList();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private FormTemplate requireTemplate(Long templateId) {
        if (templateId == null) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NOT_FOUND);
        }
        FormTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    private FormTemplateVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_VERSION_NOT_FOUND);
        }
        FormTemplateVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_VERSION_NOT_FOUND);
        }
        return version;
    }

    private List<FormTemplateVersion> versionsOf(Long templateId) {
        return versionMapper.selectList(Wrappers.<FormTemplateVersion>lambdaQuery()
                .eq(FormTemplateVersion::getTemplateId, templateId)
                .orderByAsc(FormTemplateVersion::getVersionNo));
    }

    private FormTemplateVersion latestPublished(List<FormTemplateVersion> versions) {
        return versions.stream()
                .filter(version -> !version.isDraft())
                .max(Comparator.comparing(FormTemplateVersion::getVersionNo))
                .orElse(null);
    }

    private FormTemplateVersion latestDraft(List<FormTemplateVersion> versions) {
        return versions.stream()
                .filter(FormTemplateVersion::isDraft)
                .max(Comparator.comparing(FormTemplateVersion::getVersionNo))
                .orElse(null);
    }

    private FormTemplateVersion findDraft(Long templateId) {
        return latestDraft(versionsOf(templateId));
    }

    private int nextVersionNo(Long templateId) {
        return versionsOf(templateId).stream()
                .map(FormTemplateVersion::getVersionNo)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private void assertNameAvailable(String name, Long excludeId) {
        long count = templateMapper.selectCount(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getTemplateName, name)
                .ne(excludeId != null, FormTemplate::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NAME_EXISTS);
        }
    }

    /** 是否被任一申请类型引用（引用的是版本，因此按版本 id 集合查） */
    private boolean isReferenced(List<FormTemplateVersion> versions) {
        if (versions.isEmpty()) {
            return false;
        }
        List<Long> versionIds = versions.stream().map(FormTemplateVersion::getId).toList();
        return applyTypeMapper.selectCount(Wrappers.<ApplyType>lambdaQuery()
                .in(ApplyType::getFormTemplateVersionId, versionIds)) > 0;
    }

    private FormTemplateVO toVO(FormTemplate template, List<FormTemplateVersion> versions) {
        FormTemplateVersion published = latestPublished(versions);
        FormTemplateVersion draft = latestDraft(versions);
        FormTemplateVersion shown = draft != null ? draft : published;

        FormTemplateVO vo = new FormTemplateVO();
        vo.setId(template.getId());
        vo.setTemplateName(template.getTemplateName());
        vo.setDescription(template.getDescription());
        vo.setStatus(template.getStatus());
        vo.setStatusLabel(FormTemplateStatus.labelOf(template.getStatus()));
        vo.setLatestVersionNo(published == null ? null : published.getVersionNo());
        vo.setLatestPublishedVersionId(published == null ? null : published.getId());
        vo.setHasDraft(draft != null);
        vo.setFieldCount(fieldCountSafe(shown));
        vo.setCreatedAt(template.getCreatedAt());
        vo.setUpdatedAt(template.getUpdatedAt());
        return vo;
    }

    private FormTemplateVersionVO toVersionVO(FormTemplateVersion version, Map<Long, String> publisherNames,
                                              boolean withSchema) {
        FormTemplateVersionVO vo = new FormTemplateVersionVO();
        vo.setId(version.getId());
        vo.setTemplateId(version.getTemplateId());
        vo.setVersionNo(version.getVersionNo());
        vo.setDraft(version.isDraft());
        vo.setFieldCount(fieldCountSafe(version));
        vo.setPublishedAt(version.getPublishedAt());
        vo.setPublishedBy(version.getPublishedBy());
        vo.setPublishedByName(version.getPublishedBy() == null ? null
                : publisherNames.get(version.getPublishedBy()));
        if (withSchema) {
            vo.setSchema(FormSchemaCodec.readSchema(version.getSchemaJson()));
        }
        return vo;
    }

    /**
     * 统计字段数。
     *
     * <p>刻意吞掉解析异常：列表接口里某一条历史数据格式异常，不应该让整个模板列表打不开 ——
     * 那会把「一条数据坏了」放大成「功能不可用」。真正的格式问题会在设计器打开或发布时报出来。
     */
    private Integer fieldCountSafe(FormTemplateVersion version) {
        if (version == null) {
            return 0;
        }
        try {
            return FormSchemaCodec.readSchema(version.getSchemaJson()).getFields().size();
        } catch (RuntimeException e) {
            log.warn("模板版本 {} 的 schema 无法解析，字段数按 0 计：{}", version.getId(), e.getMessage());
            return 0;
        }
    }

    private Map<Long, String> userNameMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(User::getId,
                        user -> StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername(),
                        (a, b) -> a));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
