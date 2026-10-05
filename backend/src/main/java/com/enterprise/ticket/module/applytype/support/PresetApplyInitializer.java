package com.enterprise.ticket.module.applytype.support;

import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.module.applytype.dto.ApplyConfigCreateRequest;
import com.enterprise.ticket.module.applytype.service.ApplyTypeService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 预置申请类型播种
 *
 * <h2>幂等性：为什么用「播种标记」而不是「表里没有就补」</h2>
 * <p>直觉写法是「查一下 type_code 存不存在，不存在就建」。这有个典型的幽灵缺陷：
 * **管理员主动删掉某个预置类型后，重启一次它就自己回来了**。
 * 本项目在权限播种上已经踩过同一个坑（见 {@code RolePermissionInitializer} 的类注释），
 * 因此沿用它的做法：用一个**一次性标记** {@code preset_apply_seeded:<typeCode>}。
 * <ul>
 *   <li>标记已置位 ⇒ 无论表里有没有，都<b>不再播种</b>（尊重管理员的删除）；</li>
 *   <li>标记未置位 ⇒ 播种，成功后置位。</li>
 * </ul>
 *
 * <h2>为什么失败不阻断启动</h2>
 * <p>某一类预置建失败（例如权限目录里选项过多导致表单 schema 校验不过）不该让整个系统起不来 ——
 * 那会让管理员连修它的入口都进不去。因此逐个 try/catch：失败只记 ERROR 且**不置位标记**，
 * 下次启动自动重试；其余预置照常。
 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class PresetApplyInitializer implements ApplicationRunner {

    /** 播种标记前缀；与 {@code rbac_role_seeded:} 同一套约定 */
    private static final String FLAG_PREFIX = "preset_apply_seeded:";
    private static final String FLAG_DONE = "1";

    private final ApplyTypeService applyTypeService;
    private final SystemConfigService systemConfigService;

    @Override
    public void run(ApplicationArguments args) {
        List<PresetApplyCatalog.Preset> presets = PresetApplyCatalog.presets();
        int created = 0;
        for (PresetApplyCatalog.Preset preset : presets) {
            String flagKey = FLAG_PREFIX + preset.typeCode();
            if (FLAG_DONE.equals(systemConfigService.getString(flagKey, "0"))) {
                continue;
            }
            if (seedOne(preset, flagKey)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("预置申请类型：本次新建 {} 个（共 {} 个预置）", created, presets.size());
        }
    }

    /** @return 是否真的创建了 */
    private boolean seedOne(PresetApplyCatalog.Preset preset, String flagKey) {
        try {
            ApplyConfigCreateRequest request = new ApplyConfigCreateRequest();
            request.setTypeCode(preset.typeCode());
            request.setTypeName(preset.typeName());
            request.setIcon(preset.icon());
            request.setDescription(preset.description());
            request.setSortOrder(preset.sortOrder());
            request.setOrderPrefix(preset.orderPrefix());
            // 预置类型默认「所有员工可提交」；管理员的类型可在页面上收窄到角色 / 部门
            request.setSubmitPermissionType(SubmitPermissionType.ALL.name());
            request.setFormSchema(preset.formSchema());
            request.setFlow(preset.flow());

            Long id = applyTypeService.createFull(request);
            systemConfigService.updateValue(flagKey, FLAG_DONE);
            log.info("预置申请类型已创建 id={} code={} name={}", id, preset.typeCode(), preset.typeName());
            return true;
        } catch (Exception e) {
            // 不置位标记 ⇒ 下次启动自动重试；也不抛出 ⇒ 不阻断应用启动
            log.error("预置申请类型创建失败，本次跳过（下次启动会重试）code={} name={}",
                    preset.typeCode(), preset.typeName(), e);
            return false;
        }
    }
}
