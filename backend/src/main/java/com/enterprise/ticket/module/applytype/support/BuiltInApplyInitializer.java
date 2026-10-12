package com.enterprise.ticket.module.applytype.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
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
            // 表单由代码提供 ⇒ 没有表单模板版本（V48 已放开该列 NOT NULL）
            type.setFormTemplateVersionId(null);
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
