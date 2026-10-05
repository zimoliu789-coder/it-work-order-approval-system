package com.enterprise.ticket.module.permission.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.permission.entity.PermissionApplyPolicy;
import com.enterprise.ticket.module.permission.mapper.PermissionApplyPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限申请策略—— 「哪些权限可以申请」与「申请它算不算高危」。
 *
 * <h2>代码与表的职责边界</h2>
 * <ul>
 *   <li><b>代码</b>（{@link PermissionCatalog}）说「这个码是什么」：码、中文名、**默认**等级；</li>
 *   <li><b>表</b>（{@code permission_apply_policy}）说「这个码能不能申请」：可覆盖默认等级。</li>
 * </ul>
 * 因此本类的读取一律是「先取代码默认值，再用表行覆盖」。表里没有行**不是错误** ——
 * 那正是「用默认值」的表达方式，这样新增权限码时不必同步插一行。
 *
 * <h2>⚠️ 高危判定绝不信前端</h2>
 * 表单里选中的权限码是**用户输入**。若信任前端传来的「是否高危」，
 * 攻击者改一个字段就能绕过超管那一级审批。因此
 * {@link #containsHighRisk(java.util.Collection)} 一律**服务端按码重算**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionApplyPolicyService {

    private final PermissionApplyPolicyMapper policyMapper;

    /** 全量策略视图（所有权限码 + 生效后的可申请性 / 等级），供配置界面与申请表单使用 */
    public List<PolicyView> listAll() {
        Map<String, PermissionApplyPolicy> overrides = loadOverrides();
        List<PolicyView> views = new ArrayList<>();
        for (String code : PermissionCatalog.allCodeList()) {
            views.add(viewOf(code, overrides.get(code)));
        }
        return views;
    }

    /** 当前**可申请**的权限码（配置界面与申请表单的选项来源） */
    public Set<String> applicableCodes() {
        Map<String, PermissionApplyPolicy> overrides = loadOverrides();
        Set<String> codes = new LinkedHashSet<>();
        for (String code : PermissionCatalog.allCodeList()) {
            if (isApplicable(code, overrides.get(code))) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 单个码是否可申请（生效值：表覆盖 → 代码默认） */
    public boolean isApplicable(String code) {
        return isApplicable(code, loadOverride(code));
    }

    /** 单个码的风险等级（生效值：表覆盖 → 代码默认） */
    public PermissionCatalog.RiskLevel riskOf(String code) {
        PermissionApplyPolicy override = loadOverride(code);
        if (override != null && override.getRiskLevel() != null && !override.getRiskLevel().isBlank()) {
            return parseRisk(override.getRiskLevel());
        }
        return PermissionCatalog.riskLevelOf(code);
    }

    /**
     * 一组权限码里是否含高危项 —— 决定要不要在「部门主管」之后追加一级超管。
     *
     * <p><b>必须由服务端调用</b>（不接收前端传来的布尔值）。
     */
    public boolean containsHighRisk(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return false;
        }
        for (String code : codes) {
            if (riskOf(code) == PermissionCatalog.RiskLevel.HIGH) {
                return true;
            }
        }
        return false;
    }

    /**
     * 更新一个码的策略（界面勾选「可申请」/「高危」时调用）。
     *
     * <p>写回的值等于代码默认值时**不删行**：删行会让「管理员显式确认过这项」这件事
     * 也一起消失，而配置界面无法区分「没配过」与「配成了默认值」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String code, Boolean applicable, String riskLevel) {
        if (code == null || !PermissionCatalog.exists(code)) {
            return;
        }
        PermissionApplyPolicy existing = loadOverride(code);
        PermissionApplyPolicy row = existing == null ? new PermissionApplyPolicy() : existing;
        row.setPermCode(code);
        if (applicable != null) {
            row.setApplicable(applicable);
        } else if (row.getApplicable() == null) {
            row.setApplicable(PermissionCatalog.applicableByDefault(code));
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            row.setRiskLevel(parseRisk(riskLevel).name());
        } else if (row.getRiskLevel() == null || row.getRiskLevel().isBlank()) {
            row.setRiskLevel(PermissionCatalog.riskLevelOf(code).name());
        }
        if (row.getId() == null) {
            policyMapper.insert(row);
        } else {
            policyMapper.updateById(row);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 策略视图：码 + 名称 + 生效后的可申请性 / 等级 + 是否被表覆盖 */
    public record PolicyView(String code, String name, boolean applicable,
                             String riskLevel, boolean riskLevelOverridden, boolean applicableOverridden) {
    }

    private PolicyView viewOf(String code, PermissionApplyPolicy override) {
        boolean applicable = isApplicable(code, override);
        PermissionCatalog.RiskLevel defaultRisk = PermissionCatalog.riskLevelOf(code);
        String risk = override != null && override.getRiskLevel() != null && !override.getRiskLevel().isBlank()
                ? parseRisk(override.getRiskLevel()).name()
                : defaultRisk.name();
        return new PolicyView(code, PermissionCatalog.nameOf(code), applicable, risk,
                override != null && override.getRiskLevel() != null && !override.getRiskLevel().isBlank(),
                override != null && override.getApplicable() != null);
    }

    private boolean isApplicable(String code, PermissionApplyPolicy override) {
        if (override != null && override.getApplicable() != null) {
            return Boolean.TRUE.equals(override.getApplicable());
        }
        return PermissionCatalog.applicableByDefault(code);
    }

    private PermissionCatalog.RiskLevel parseRisk(String value) {
        return "HIGH".equalsIgnoreCase(value == null ? "" : value.trim())
                ? PermissionCatalog.RiskLevel.HIGH
                : PermissionCatalog.RiskLevel.NORMAL;
    }

    private Map<String, PermissionApplyPolicy> loadOverrides() {
        List<PermissionApplyPolicy> rows = policyMapper.selectList(null);
        Map<String, PermissionApplyPolicy> map = new LinkedHashMap<>();
        for (PermissionApplyPolicy row : rows) {
            if (row.getPermCode() != null) {
                map.put(row.getPermCode(), row);
            }
        }
        return map;
    }

    private PermissionApplyPolicy loadOverride(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return policyMapper.selectOne(Wrappers.<PermissionApplyPolicy>lambdaQuery()
                .eq(PermissionApplyPolicy::getPermCode, code)
                .last("LIMIT 1"));
    }
}
