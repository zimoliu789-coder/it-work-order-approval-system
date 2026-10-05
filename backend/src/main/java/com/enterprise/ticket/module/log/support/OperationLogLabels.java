package com.enterprise.ticket.module.log.support;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 操作日志「中文化」标签与摘要生成（； 只要求留痕，未规定展示形态）
 *
 * <p><b>为什么要独立成一个纯函数类</b>：模块 / 动作的中文映射、以及「技术详情 → 人类可读摘要」
 * 的转换，是<b>展示口径</b>而非业务规则。把它从 Controller / 实体里抽出来，好处有三：
 * <ul>
 *   <li>可单元测试（不依赖 Spring、数据库）；</li>
 *   <li>映射缺失时只影响展示，不会影响审计落库（原始编码始终保留在 {@code module} / {@code action} 列）；</li>
 *   <li>后续新增 {@code @AuditLog} 动作时，只在此处补一行。</li>
 * </ul>
 *
 * <p><b>兜底策略</b>：未登记的编码原样返回（而不是「未知」），这样即便漏配也不会把信息抹掉，
 * 只会以英文编码的形式露出，便于发现并补录。
 */
public final class OperationLogLabels {

    private OperationLogLabels() {
    }

    // ------------------------------------------------------------------
    // 模块编码 → 中文
    // ------------------------------------------------------------------

    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    static {
        MODULES.put("AUTH", "认证登录");
        MODULES.put("USER", "员工管理");
        MODULES.put("DEVICE", "设备管理");
        MODULES.put("ORDER", "工单管理");
        MODULES.put("SYSTEM", "系统配置");
        MODULES.put("JOB", "定时任务");
        MODULES.put("LOG", "操作日志");
        MODULES.put("ATTACHMENT", "附件管理");
        MODULES.put("EXPORT", "导入导出");
        MODULES.put("REPORT", "统计报表");
        MODULES.put("ROLE", "角色权限");
        MODULES.put("USAGE", "使用记录");
        MODULES.put("OPS", "运维作业");
        MODULES.put("AD", "AD 域控");
    }

    // ------------------------------------------------------------------
    // 动作编码 → 中文（全量映射；新增 @AuditLog 动作时在此补一行）
    // ------------------------------------------------------------------

    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

    static {
        // 认证登录
        ACTIONS.put("LOGIN", "登录");
        ACTIONS.put("LOGOUT", "退出登录");
        ACTIONS.put("LOGIN_FAILED", "登录失败");
        ACTIONS.put("LOGIN_RATE_LIMITED", "登录限流拦截");
        ACTIONS.put("LOGIN_LOCKED", "账号锁定拦截");
        ACTIONS.put("LOGIN_DISABLED", "禁用/离职账号登录拦截");
        ACTIONS.put("LOGIN_LDAP_UNAVAILABLE", "域账号登录拦截");
        ACTIONS.put("CHANGE_PASSWORD", "修改密码");
        ACTIONS.put("RESET_PASSWORD", "重置密码");

        // 员工管理
        ACTIONS.put("USER_CREATE", "新增员工");
        ACTIONS.put("USER_UPDATE", "编辑员工");
        ACTIONS.put("USER_RESET_PASSWORD", "重置员工密码");
        ACTIONS.put("USER_ENABLE", "启用员工账号");
        ACTIONS.put("USER_DISABLE", "禁用员工账号");
        ACTIONS.put("USER_DIMISSION", "标记员工离职");
        ACTIONS.put("USER_REINSTATE", "恢复员工在职");
        ACTIONS.put("USER_IMPORT", "批量导入员工");

        // 设备管理
        ACTIONS.put("DEVICE_CREATE", "新增设备");
        ACTIONS.put("DEVICE_UPDATE", "修改设备");
        ACTIONS.put("DEVICE_STATUS_CHANGE", "变更设备状态");
        ACTIONS.put("DEVICE_DELETE", "删除设备");
        ACTIONS.put("DEVICE_FORCE_UNLOCK", "强制释放设备锁");
        ACTIONS.put("DEVICE_CATEGORY_CREATE", "新增设备分类");
        ACTIONS.put("DEVICE_CATEGORY_UPDATE", "修改设备分类");
        ACTIONS.put("DEVICE_CATEGORY_SORT", "调整设备分类顺序");
        ACTIONS.put("DEVICE_CATEGORY_DELETE", "删除设备分类");
        ACTIONS.put("DEVICE_FAULT_REPORT", "上报设备故障");
        ACTIONS.put("DEVICE_FAULT_REPAIR", "登记设备维修完成");
        ACTIONS.put("DEVICE_FAULT_SCRAP", "故障设备报废");
        ACTIONS.put("DEVICE_IMPORT", "批量导入设备");

        // 工单管理
        ACTIONS.put("ORDER_CREATE", "提交借用申请");
        ACTIONS.put("ORDER_APPROVE", "审批工单");
        ACTIONS.put("ORDER_CANCEL", "撤回工单");
        ACTIONS.put("ORDER_DELIVER", "确认交付设备");
        ACTIONS.put("ORDER_RETURN_REQUEST", "发起归还设备");
        ACTIONS.put("ORDER_RETURN_CONFIRM", "确认收回设备");
        ACTIONS.put("ORDER_EXTEND_REQUEST", "发起借用延期");
        ACTIONS.put("ORDER_EXTEND_APPROVE", "审批借用延期");
        ACTIONS.put("ORDER_TRANSFER", "工单转交");
        ACTIONS.put("ORDER_URGE_APPROVAL", "催办审批");
        ACTIONS.put("ORDER_URGE_RETURN", "催办归还");
        ACTIONS.put("ORDER_FORCE_OPERATION", "超管强制干预");

        // 附件（：下载必记操作日志）
        ACTIONS.put("ATTACHMENT_UPLOAD", "上传附件");
        ACTIONS.put("ATTACHMENT_DOWNLOAD", "下载附件");
        ACTIONS.put("ATTACHMENT_DELETE", "删除附件");

        // 导入导出与统计报表（「所有导入导出操作记入审计日志」）
        ACTIONS.put("EXPORT_CREATE", "发起导出");
        ACTIONS.put("EXPORT_DOWNLOAD", "下载导出文件");
        ACTIONS.put("EXPORT_DELETE", "删除导出记录");
        ACTIONS.put("REPORT_VIEW", "查看统计报表");

        // 系统配置（部门 / 最终处理部门 / 审批配置）
        ACTIONS.put("BIZ_GROUP_CREATE", "新增部门");
        ACTIONS.put("BIZ_GROUP_UPDATE", "修改部门");
        ACTIONS.put("BIZ_GROUP_CONFIG_SAVE", "保存分组审批配置");
        ACTIONS.put("BIZ_GROUP_DELETE", "删除部门");
        ACTIONS.put("BIZ_GROUP_SORT", "部门排序");
        ACTIONS.put("BIZ_GROUP_MEMBER_ADD", "添加分组成员");
        ACTIONS.put("BIZ_GROUP_MEMBER_REMOVE", "移除分组成员");
        ACTIONS.put("HANDLER_GROUP_CREATE", "新增最终处理部门");
        ACTIONS.put("HANDLER_GROUP_UPDATE", "修改最终处理部门");
        ACTIONS.put("HANDLER_GROUP_DELETE", "删除最终处理部门");
        ACTIONS.put("HANDLER_GROUP_MEMBER_SET", "设置小组成员");

        // 定时任务
        ACTIONS.put("JOB_MANUAL_RUN", "手动触发定时任务");

        // 角色与权限（需求方三波·第一波·）
        ACTIONS.put("ROLE_CREATE", "新建角色");
        ACTIONS.put("ROLE_UPDATE", "编辑角色");
        ACTIONS.put("ROLE_PERMISSION_SET", "调整角色权限");
        ACTIONS.put("ROLE_DELETE", "删除角色");

        // 系统参数写入（需求方三波·第一波·）
        ACTIONS.put("CONFIG_UPDATE", "修改系统参数");

        // 使用记录查询（需求方三波·第一波·）
        ACTIONS.put("USAGE_QUERY", "查询使用记录");

        // 限流拦截（Docker 部署 + 限流加固）。
        // 这三条都属于「系统主动拒绝服务」，与业务动作性质不同：
        // 排查时最先要看的就是「到底是谁被挡了、挡在哪一层」，因此分层命名、前缀统一。
        ACTIONS.put("API_RATE_LIMITED", "接口限流拦截");
        ACTIONS.put("ORDER_SUBMIT_RATE_LIMITED", "提交限流拦截");

        // 运维作业（备份脚本上报，）
        ACTIONS.put("BACKUP_ALERT_REPORT", "备份作业告警上报");

        // AD 域控（；）
        ACTIONS.put("AD_CONFIG_UPDATE", "修改 AD 配置");
        ACTIONS.put("AD_TEST_CONNECTION", "测试 AD 连接");
        ACTIONS.put("AD_SYNC", "同步 AD 域用户");
        ACTIONS.put("AD_AUTH_UNAVAILABLE", "AD 不可用降级本地认证");
        ACTIONS.put("AD_AUTO_PROVISION", "AD 首次登录自动建号");
        ACTIONS.put("AUTH_TYPE_CONVERT", "账号来源转换（本地 ↔ AD）");
    }

    // ------------------------------------------------------------------
    // 公开方法
    // ------------------------------------------------------------------

    public static String moduleLabel(String module) {
        return label(MODULES, module);
    }

    public static String actionLabel(String action) {
        return label(ACTIONS, action);
    }

    /** 结果标签：SUCCESS → 成功，FAILED → 失败 */
    public static String resultLabel(String result) {
        if ("SUCCESS".equals(result)) {
            return "成功";
        }
        if ("FAILED".equals(result)) {
            return "失败";
        }
        return result;
    }

    /** 风险标签：HIGH → 高风险，NORMAL → 普通；未知原样返回 */
    public static String riskLabel(String riskLevel) {
        if ("HIGH".equals(riskLevel)) {
            return "高风险";
        }
        if ("NORMAL".equals(riskLevel)) {
            return "普通";
        }
        return riskLevel;
    }

    /** 已登记的全部模块编码（供筛选下拉；顺序即声明顺序） */
    public static Set<String> moduleCodes() {
        return new LinkedHashSet<>(MODULES.keySet());
    }

    /** 模块编码 → 中文（只读副本） */
    public static Map<String, String> modules() {
        return new LinkedHashMap<>(MODULES);
    }

    /** 动作编码 → 中文（只读副本） */
    public static Map<String, String> actions() {
        return new LinkedHashMap<>(ACTIONS);
    }

    /**
     * 由「动作 + 技术详情 + 结果」生成人类可读的一行摘要（列表「详情」列默认展示）。
     *
     * <p>取数优先级：
     * <ol>
     *   <li>{@code desc=}（{@code @AuditLog(description=...)}）—— 最贴合业务动作；</li>
     *   <li>批量导入的 {@code file=/imported=/failed=}；</li>
     *   <li>认证模块的纯文本详情（去掉 {@code ip=} / {@code role=} 等技术片段）；</li>
     *   <li>兜底：动作中文名。</li>
     * </ol>
     * 失败时追加失败原因（取自 {@code error=}），<b>不包含任何堆栈</b>。
     */
    public static String summary(String action, String details, String result) {
        String base = extractDesc(details);
        if (base == null) {
            base = importSummary(details);
        }
        if (base == null) {
            base = plainTextDetail(details);
        }
        if (base == null || base.isBlank()) {
            base = actionLabel(action);
        }
        if ("FAILED".equals(result)) {
            String error = fieldValue(details, "error");
            if (error != null && !base.contains(error)) {
                return base + "（失败：" + error + "）";
            }
            if (!base.contains("失败")) {
                return base + "（失败）";
            }
        }
        return base;
    }

    // ------------------------------------------------------------------
    // 内部解析
    // ------------------------------------------------------------------

    private static String label(Map<String, String> mapping, String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return mapping.getOrDefault(code, code);
    }

    /** 提取 {@code desc=...} 段（到下一个 " | " 为止） */
    private static String extractDesc(String details) {
        return fieldValue(details, "desc");
    }

    /** 批量导入摘要 */
    private static String importSummary(String details) {
        if (details == null || !details.contains("file=")) {
            return null;
        }
        String imported = fieldValue(details, "imported");
        if (imported == null) {
            return null;
        }
        String file = fieldValue(details, "file");
        String failed = fieldValue(details, "failed");
        return "导入文件「" + (file == null ? "" : file) + "」：成功 " + imported
                + " 条，失败 " + (failed == null ? "0" : failed) + " 条";
    }

    /**
     * 认证模块的纯文本详情：形如「登录成功，role=user，ip=x」→ 去掉 {@code ip=} / {@code role=} 片段。
     * 仅当详情不是「键值对流水」（不含 {@code desc=} 且不含 " | "）时才处理。
     */
    private static String plainTextDetail(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        if (details.contains("desc=") || details.contains(" | ")) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("，");
        for (String part : details.split("[，,]")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("ip=") || trimmed.startsWith("role=")) {
                continue;
            }
            joiner.add(trimmed);
        }
        String text = joiner.toString();
        return text.isBlank() ? null : text;
    }

    /** 从「key=value | key=value」流水里取某个键的值；取不到返回 null */
    private static String fieldValue(String details, String key) {
        if (details == null || details.isBlank()) {
            return null;
        }
        String prefix = key + "=";
        for (String segment : details.split("\\s*\\|\\s*")) {
            if (segment.startsWith(prefix)) {
                String value = segment.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
