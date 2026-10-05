package com.enterprise.ticket.config;

import com.enterprise.ticket.common.constant.SetupKeys;
import com.enterprise.ticket.module.applytype.support.PresetApplyCatalog;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生产初始化清库（本次新增）—— 一次性清空演示数据。
 *
 * <h2>何时执行</h2>
 * <p>仅当 {@code app.prod-init.enabled=true}（环境变量 {@code APP_PROD_INIT=true}）
 * <b>且</b>尚未执行过时。执行成功后在 {@code system_config} 落一个完成标记
 * （{@link SetupKeys#KEY_PROD_INIT_DONE}）—— 这是**必须**的：清库是不可逆的，
 * 若每次启动都按开关重跑，部署方忘摘开关就会把上线后新产生的数据一并清掉。
 *
 * <h2>清什么 / 留什么（用户点名口径）</h2>
 * <table border="1">
 *   <tr><th>清空</th><th>保留</th></tr>
 *   <tr>
 *     <td>演示员工、演示部门（只留根节点「公司」）、演示工单及其从属数据、
 *         设备与设备分类、演示申请数据（非预置的申请类型与其表单/流程）</td>
 *     <td>表结构、系统参数、权限目录（{@code sys_role} / {@code sys_role_permission}）、
 *         <b>预置申请类型的表单与流程定义</b>、审计与运维记录</td>
 *   </tr>
 * </table>
 *
 * <h2>依赖顺序</h2>
 * <p>先删从属（节点 / 表单数据 / 附件 / 消息 / 授权），再删主体（工单 / 设备 / 用户 / 部门）；
 * 申请数据先删「非预置的类型」，再清理**不再被任何预置类型引用**的表单模板与流程。
 * 顺序错了会留下孤儿行，或者把预置类型的表单一起删掉。
 *
 * <h2>为什么放在 @Order(70)</h2>
 * <p>必须在 {@code PresetApplyInitializer}（@Order(60)）**之后**执行 ——
 * 它负责播种预置申请类型，而本类要「保留预置、清掉其余」；反过来会把刚播种的也删掉。
 */
@Slf4j
@Component
@Order(70)
@RequiredArgsConstructor
public class ProductionInitCleaner implements ApplicationRunner {

    private final AppProperties appProperties;
    private final SystemConfigService systemConfigService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        if (!appProperties.getProdInit().isEnabled()) {
            return;
        }
        if (systemConfigService.prodInitCompleted()) {
            log.warn("[生产初始化] 已执行过，本次跳过（如需再次清库请手工删除 system_config.{}）",
                    SetupKeys.KEY_PROD_INIT_DONE);
            return;
        }

        log.warn("========================================================================");
        log.warn("[生产初始化] 开始清空演示数据（表结构与预置配置保留）…");
        log.warn("========================================================================");

        // 1) 工单及其从属
        int orders = clearOrders();

        // 2) 设备与分类
        int devices = delete("device_fault", "1=1");
        int deviceRows = delete("device", "1=1");
        int categories = delete("device_category", "1=1");

        // 3) 盘点（引用设备与用户）
        int invItems = delete("inventory_task_item", "1=1");
        int invTasks = delete("inventory_task", "1=1");

        // 4) 消息（演示通知）
        int messages = delete("messages", "1=1");

        // 5) 用户级授权（来自演示的权限申请工单）
        int userPerms = delete("user_permission", "1=1");

        // 6) 组织关系 → 用户 → 部门
        int deptManagers = delete("department_manager", "1=1");
        int userDepts = delete("user_department", "1=1");
        // 「员工表只剩内置超管」：演示环境里的第二个超管（如 10002 李娜）也是演示员工，必须一并清掉。
        // 内置超管的登录名由 SuperAdminInitializer 固化在 system_config；未初始化时（全新库）
        // 没有任何超管，此时清空整张员工表，随后由初始化向导建出唯一的超管。
        String builtinAdmin = systemConfigService.superAdminUsername();
        int users = StringUtils.hasText(builtinAdmin)
                ? delete("users", "username <> ?", builtinAdmin)
                : delete("users", "1=1");
        int depts = delete("departments", "parent_id IS NOT NULL");

        // 7) 演示申请数据：先删非预置的申请类型，再清理不再被引用的表单与流程
        int applyTypes = clearDemoApplyData();

        // 8) 落完成标记
        systemConfigService.markProdInitCompleted();

        log.warn("[生产初始化] 完成。删除统计：工单 {}，工单从属 9 张表已清空，设备 {}，设备分类 {}，"
                        + "盘点项 {} / 任务 {}，消息 {}，用户授权 {}，部门主管 {}，兼职归属 {}，"
                        + "员工 {}，部门 {}，演示申请类型 {}（含其表单与流程）。"
                        + "剩余：超管 {} 个，部门 {} 个，申请类型 {} 个。",
                orders, deviceRows, categories, invItems, invTasks, messages,
                userPerms, deptManagers, userDepts, users, depts, applyTypes,
                countOf("users WHERE role = 'super_admin'"),
                countOf("departments"),
                countOf("apply_type"));
        log.warn("========================================================================");
    }

    /** 清空工单及其全部从属表（9 张从属表 + orders 本体） */
    private int clearOrders() {
        delete("order_flow_activation_log", "1=1");
        delete("order_extend_approval_nodes", "1=1");
        delete("order_extend", "1=1");
        delete("order_force_operation", "1=1");
        delete("order_handler_transfer", "1=1");
        delete("order_urge", "1=1");
        delete("order_approval_nodes", "1=1");
        delete("order_form_data", "1=1");
        delete("attachments", "1=1");
        return delete("orders", "1=1");
    }

    /**
     * 清掉「演示申请数据」：非预置的申请类型 + 不再被预置引用的表单模板 / 流程。
     *
     * <p>预置类型清单取自 {@link PresetApplyCatalog#presets()}（代码即事实源）——
     * 与播种用的是同一份，因此不会出现「播种 5 个、清理按另一份清单保留 3 个」的错配。
     *
     * @return 被删除的申请类型条数
     */
    private int clearDemoApplyData() {
        List<String> presetCodes = PresetApplyCatalog.presets().stream()
                .map(PresetApplyCatalog.Preset::typeCode)
                .toList();
        String inList = presetCodes.stream()
                .map(code -> "'" + code.replace("'", "''") + "'")
                .collect(Collectors.joining(","));

        int removedTypes = delete("apply_type", "type_code NOT IN (" + inList + ")");

        // 表单模板版本 / 模板：只留仍被「保留下来的申请类型」引用的
        delete("form_template_version",
                "id NOT IN (SELECT form_template_version_id FROM apply_type "
                        + "WHERE form_template_version_id IS NOT NULL)");
        delete("form_template",
                "id NOT IN (SELECT template_id FROM form_template_version)");

        // 审批流程版本 / 模板：同上
        delete("approval_flow_version",
                "id NOT IN (SELECT approval_flow_version_id FROM apply_type "
                        + "WHERE approval_flow_version_id IS NOT NULL)");
        delete("approval_flow",
                "id NOT IN (SELECT flow_id FROM approval_flow_version)");
        return removedTypes;
    }

    /** 执行一条 DELETE 并返回受影响行数（表名由本类常量给出，不接受外部输入） */
    private int delete(String table, String where) {
        return delete(table, where, new Object[0]);
    }

    /** 带参数的 DELETE（值走占位符，避免把配置值拼进 SQL） */
    private int delete(String table, String where, Object... args) {
        try {
            return jdbcTemplate.update("DELETE FROM `" + table + "` WHERE " + where, args);
        } catch (Exception e) {
            // 逐表容错：某一表不存在 / 结构差异不该让整个初始化中断（生产库版本可能略有差异）
            log.warn("[生产初始化] 清空表 {} 失败（跳过）：{}", table, e.getMessage());
            return 0;
        }
    }

    private long countOf(String fromWhere) {
        try {
            Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            return 0L;
        }
    }
}
