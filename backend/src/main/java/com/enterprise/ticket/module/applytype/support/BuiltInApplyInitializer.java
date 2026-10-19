package com.enterprise.ticket.module.applytype.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 系统内置申请类型播种（V48）
 *
 * <h2>为什么用「一次性标记」而不是「表里没有就补」</h2>
 * 与 {@link PresetApplyInitializer} 完全同一套约定（见其类注释）：
 * 直觉写法「查 type_code 不存在就建」有个幽灵缺陷 ——
 * **管理员主动停用 / 调整过某个内置类型后，重启一次配置就被覆盖回出厂值**。
 * 因此这里用一次性标记 {@code builtin_apply_seeded:<typeCode>}：
 * 标记已置位 ⇒ 无论表里有没有、是什么样，都不再触碰。
 *
 * <h2>为什么不走 ApplyTypeService</h2>
 * 服务层的创建入口（{@code create} / {@code createFull}）都强制
 * 「必须绑定一个已发布的表单版本」，而内置类型**恰好没有表单模板** ——
 * 表单是内置页面（借用表单 / 报修表单）。硬走服务入口只能靠加一串「if 内置就跳过校验」，
 * 那会把「内置」这个特殊分支渗进自定义类型的主路径。
 * 这里直接用 Mapper 插入一行结构确定的记录，服务层的校验保持对自定义类型的原样强度。
 *
 * <h2>失败不阻断启动</h2>
 * 与预置播种一致：单个失败只记 ERROR、**不置位标记**（下次启动自动重试），
 * 不抛出 —— 否则一个可选的卡片播种失败会让整个系统起不来，管理员连修它的入口都没有。
 */
@Slf4j
@Component
@Order(59)
@RequiredArgsConstructor
public class BuiltInApplyInitializer implements ApplicationRunner {

    private static final String FLAG_DONE = "1";

    private final ApplyTypeMapper applyTypeMapper;
    private final SystemConfigService systemConfigService;
    private final FormTemplateService formTemplateService;
    /** 播种表单模板需要按名字查重（幂等，见 {@link #seedFormTemplate}） */
    private final FormTemplateMapper templateMapper;
    private final FormTemplateVersionMapper templateVersionMapper;

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (BuiltInApplyCatalog.BuiltIn builtIn : BuiltInApplyCatalog.all()) {
            String flagKey = BuiltInApplyCatalog.SEED_FLAG_PREFIX + builtIn.typeCode();
            if (FLAG_DONE.equals(systemConfigService.getString(flagKey, "0"))) {
                continue;
            }
            if (seedOne(builtIn, flagKey)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("系统内置申请类型：本次新建 {} 个（共 {} 个内置）", created, BuiltInApplyCatalog.all().size());
        }
    }

    /**
     * 给内置类型准备表单模板。
     *
     * <h2>V53：内置类型从此分成两类</h2>
     * <ul>
     *   <li><b>无表单</b>（借用 / 报修）：表单是代码内置页面，
     *       {@code form_template_version_id} 保持 NULL（V48 已放开该列 NOT NULL）；</li>
     *   <li><b>有表单</b>（V53 起的出差申请）：目录给出 schema
     *       （{@link BuiltInApplyCatalog#formSchemaOf}，三个系统内置字段），
     *       这里建<b>真实的表单模板 + 首发版本</b>并绑定到类型 ——
     *       与 {@link PresetApplyInitializer} 的产出物同构，
     *       管理员后续（表单放开后）可以像改预置类一样改它，提交侧走通用动态表单链路。</li>
     * </ul>
     *
     * <h2>中断后的重试必须是幂等的</h2>
     * 先建模板再插类型行；若插类型失败（如迁移未跑、约束冲突），会留下「有模板、没类型」的残局。
     * <b>此时重试绝不能再直接调 {@code createTemplate}</b>：{@code form_template.template_name}
     * 有唯一约束，重名会抛 {@code FORM_TEMPLATE_NAME_EXISTS}，而标记又没有置位 ⇒
     * 每次启动都失败、永远重试 —— 只能人工进库删模板才能恢复。
     * 因此这里先按模板名查重：已有已发布版本就复用；只留下草稿就用内置 schema
     * 覆盖它再发布（不能直接发布，否则可能把「半途被人工改过的草稿」当成内置表单发出去）。
     */
    private void seedFormTemplate(ApplyType type, BuiltInApplyCatalog.BuiltIn builtIn) {
        FormSchema schema = BuiltInApplyCatalog.formSchemaOf(builtIn.typeCode());
        if (schema == null) {
            // 借用 / 报修：表单由代码提供，没有模板版本
            type.setFormTemplateVersionId(null);
            return;
        }

        String templateName = builtIn.typeName() + " · 表单";
        FormTemplate existing = templateMapper.selectOne(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getTemplateName, templateName)
                .last("LIMIT 1"));
        if (existing != null) {
            Long publishedVersionId = latestPublishedVersionIdOf(existing.getId());
            if (publishedVersionId != null) {
                log.warn("内置申请类型「{}」的表单模板已存在（上次播种中断残留），复用它而不新建",
                        builtIn.typeName());
                type.setFormTemplateVersionId(publishedVersionId);
                return;
            }
            // 只剩草稿：用目录给的 schema 覆盖后再发布，保证内容与代码一致
            formTemplateService.updateTemplate(existing.getId(), formRequest(builtIn, schema));
            type.setFormTemplateVersionId(formTemplateService.publish(existing.getId()));
            log.warn("内置申请类型「{}」的表单模板只有草稿（上次发布中断），已用内置 schema 覆盖并发布",
                    builtIn.typeName());
            return;
        }

        Long templateId = formTemplateService.createTemplate(formRequest(builtIn, schema));
        type.setFormTemplateVersionId(formTemplateService.publish(templateId));
    }

    /** 该模板最新的已发布版本 id；没有已发布版本返回 {@code null} */
    private Long latestPublishedVersionIdOf(Long templateId) {
        FormTemplateVersion published = templateVersionMapper.selectOne(
                Wrappers.<FormTemplateVersion>lambdaQuery()
                        .eq(FormTemplateVersion::getTemplateId, templateId)
                        .isNotNull(FormTemplateVersion::getPublishedAt)
                        .orderByDesc(FormTemplateVersion::getVersionNo)
                        .last("LIMIT 1"));
        return published == null ? null : published.getId();
    }

    private FormTemplateSaveRequest formRequest(BuiltInApplyCatalog.BuiltIn builtIn, FormSchema schema) {
        FormTemplateSaveRequest request = new FormTemplateSaveRequest();
        request.setTemplateName(builtIn.typeName() + " · 表单");
        request.setDescription("随系统内置申请类型「" + builtIn.typeName() + "」一并创建");
        request.setSchema(schema);
        return request;
    }

    /** @return 是否真的创建了 */
    private boolean seedOne(BuiltInApplyCatalog.BuiltIn builtIn, String flagKey) {
        try {
            // 极端情况：已有同编码的类型（历史数据 / 手工插入）。不覆盖、只认领 ——
            // 把 built_in 标上，避免管理页出现两张同名卡片。
            ApplyType existing = applyTypeMapper.selectOne(
                    Wrappers.<ApplyType>lambdaQuery()
                            .eq(ApplyType::getTypeCode, builtIn.typeCode())
                            .last("LIMIT 1"));
            if (existing != null) {
                if (!Boolean.TRUE.equals(existing.getBuiltIn())) {
                    ApplyType patch = new ApplyType();
                    patch.setId(existing.getId());
                    patch.setBuiltIn(true);
                    applyTypeMapper.updateById(patch);
                    log.info("系统内置申请类型：编码已存在，补标 built_in id={} code={}",
                            existing.getId(), builtIn.typeCode());
                }
                // V53：带表单的内置类型若缺绑定（历史手工建的同编码类型 / 上次播种中断），
                // 必须补上表单 —— 否则它会同时「有卡片、进不了 /enabled」（该接口排除
                // 「内置且无表单模板」），表现为管理页看得到、提交页永远点不到，且不报错。
                if (existing.getFormTemplateVersionId() == null
                        && BuiltInApplyCatalog.formSchemaOf(builtIn.typeCode()) != null) {
                    ApplyType patch = new ApplyType();
                    patch.setId(existing.getId());
                    seedFormTemplate(patch, builtIn);
                    if (patch.getFormTemplateVersionId() != null) {
                        applyTypeMapper.updateById(patch);
                        log.warn("系统内置申请类型：编码已存在但缺表单绑定，已补建并绑定 id={} code={} version={}",
                                existing.getId(), builtIn.typeCode(), patch.getFormTemplateVersionId());
                    }
                }
                systemConfigService.updateValue(flagKey, FLAG_DONE);
                return false;
            }

            ApplyType type = new ApplyType();
            type.setTypeCode(builtIn.typeCode());
            type.setTypeName(builtIn.typeName());
            type.setIcon(builtIn.icon());
            type.setDescription(builtIn.description());
            type.setSortOrder(builtIn.sortOrder());
            type.setStatus(ApplyTypeStatus.ENABLED.name());
            type.setBuiltIn(true);
            seedFormTemplate(type, builtIn);
            type.setApprovalMode(builtIn.approvalMode().name());
            type.setSubmitPermissionType(SubmitPermissionType.ALL.name());
            type.setSubmitPermissionValue("[]");
            applyTypeMapper.insert(type);

            systemConfigService.updateValue(flagKey, FLAG_DONE);
            log.info("系统内置申请类型已创建 id={} code={} name={}",
                    type.getId(), builtIn.typeCode(), builtIn.typeName());
            return true;
        } catch (Exception e) {
            // 不置位标记 ⇒ 下次启动自动重试；也不抛出 ⇒ 不阻断应用启动
            log.error("系统内置申请类型创建失败，本次跳过（下次启动会重试）code={} name={}",
                    builtIn.typeCode(), builtIn.typeName(), e);
            return false;
        }
    }
}
