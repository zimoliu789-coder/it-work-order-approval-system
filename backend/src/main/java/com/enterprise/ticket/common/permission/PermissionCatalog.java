package com.enterprise.ticket.common.permission;

import com.enterprise.ticket.common.constant.RoleCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限目录（需求方三波·第一波·）
 *
 * <h2>为什么权限目录写在代码里，而不是建一张 {@code sys_permission} 表</h2>
 * <p>权限码只有在<b>后端确实有对应实现</b>时才有意义。若把目录落库，
 * 就一定会出现「运维在库里加了一个码 → 界面勾得上 → 点下去不生效」这种最难排查的错配。
 * 目录是<b>代码资产</b>，与 {@code @PreAuthorize("@perm.has(...)")} 的字符串同生共死，
 * 因此以本类为单一事实来源，启动时按目录为内置角色补齐默认授权（见 {@code RolePermissionInitializer}）。
 *
 * <h2>权限码命名约定</h2>
 * <pre>
 *   &lt;模块&gt;:&lt;对象&gt;:&lt;动作&gt;      例：device:ledger:manage
 * </pre>
 * 动作只有三种：{@code view}（看）、{@code manage}（改）、{@code import}（批量导入）。
 * 刻意不细分 create/update/delete —— 企业内网场景里「能改台账」与「能删台账」
 * 很难存在真实差异，过细的粒度只会让授权界面变成一张没人愿意读的表格。
 *
 * <h2>super_admin 恒全量</h2>
 * <p>{@code super_admin} 不出现在 {@link #DEFAULT_PERMISSIONS} 之外，
 * 其权限由 {@code PermissionGuard} 直接短路放行 —— 保证系统永远有人能救回来
 * （需求明确：super_admin 不可改）。
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    /** 分组节点编码（仅用于树形展示，不可单独授予） */
    public static final String GROUP_ORDER = "group:order";
    public static final String GROUP_MESSAGE = "group:message";
    public static final String GROUP_ASSET = "group:asset";
    public static final String GROUP_USAGE = "group:usage";
    public static final String GROUP_SYSTEM = "group:system";
    /**
     * 组织与人员（ 新设的一级目录）。
     *
     * <p>它取代了  拆出的「员工管理」与「审批配置」两个一级目录，
     * 并把原来的三块合并成一块：
     * <ul>
     *   <li>员工管理（员工档案 / 角色调整 / 重置密码）；</li>
     *   <li>部门管理 —— <b>已退役</b>，分组整体迁移为部门树（见 V31）；</li>
     *   <li>最终处理部门管理 —— <b>已退役</b>，收拢为「IT运维组」部门 + 「IT执行人」角色。</li>
     * </ul>
     *
     * <p>「申请类型 / 审批流程模板」不再占一级目录：它们按 藏进
     * 「系统设置 → 高级设置」，因此其权限码整体挂到 {@link #GROUP_SYSTEM} 下。
     */
    public static final String GROUP_ORG = "group:org";

    // ---------------- 工单管理 ----------------
    public static final String ORDER_APPLY = "order:apply";
    public static final String ORDER_MINE = "order:mine";
    public static final String ORDER_APPROVAL = "order:approval";
    public static final String ORDER_PENDING = "order:pending";
    public static final String ORDER_ALL_VIEW = "order:all:view";
    public static final String ORDER_FORCE_MANAGE = "order:force:manage";

    // ---------------- 消息中心 ----------------
    public static final String MESSAGE_LIST = "message:list";

    // ---------------- 资产管理 ----------------
    public static final String DEVICE_LEDGER_VIEW = "device:ledger:view";
    public static final String DEVICE_LEDGER_MANAGE = "device:ledger:manage";
    public static final String DEVICE_CATEGORY_MANAGE = "device:category:manage";
    public static final String DEVICE_IMPORT = "device:import";
    public static final String DEVICE_FAULT_VIEW = "device:fault:view";
    public static final String DEVICE_FAULT_MANAGE = "device:fault:manage";

    /**
     * 设备盘点查看 / 管理（P2）。
     *
     * <p>归「资产管理」组而不是单开一级目录：盘点盘的就是设备台账本身，
     * 报告里的「盘亏」也是台账数据 —— 与「设备故障记录」同属资产本体问题域。
     *
     * <p>与 {@code device:fault:*} 一样**授予 admin**（见 DEFAULT_PERMISSIONS），
     * 且**两份都要一致**：代码默认集合只影响新库，存量库靠 V40 的幂等授予迁移补。
     */
    public static final String INVENTORY_VIEW = "inventory:view";
    public static final String INVENTORY_MANAGE = "inventory:manage";
    public static final String ASSET_REPORT_VIEW = "asset:report:view";

    // ---------------- 使用记录 ----------------
    public static final String USAGE_VIEW = "usage:view";

    // ---------------- 组织与人员 ----------------
    /**
     * 「组织与人员」页面的菜单码。
     *
     * <p><b>刻意复用 {@code staff:view} 而不是新造 {@code department:view}</b>，两个原因：
     * <ol>
     *   <li>页面只有一个入口。若菜单挂 {@code department:view}、页面内的成员列表接口仍挂
     *       {@code staff:view}，就会出现「看得到菜单、点进去 403」这类最难查的错配；</li>
     *   <li>更重要的是<b>零回归</b>：{@code staff:view} 已经在 admin 的默认集合里，
     *       复用它意味着改造后 admin 的入口一个都不会少。新造一个码而忘了补默认授权，
     *       正是本类反复强调的「admin 突然看不到某菜单」那类静默回归。</li>
     * </ol>
     */
    public static final String STAFF_VIEW = "staff:view";
    public static final String STAFF_MANAGE = "staff:manage";
    public static final String STAFF_IMPORT = "staff:import";

    /**
     * 部门维护（新增 / 编辑 / 移动 / 设撤主管 / 删除）。
     *
     * <p>与 {@link #STAFF_MANAGE} 分开：部门是**审批上级的事实源**，
     * 动部门主管等于改变一批人的审批路径，风险高于「改某个人的手机号」。
     * 分开后可以做到「业务管理员能维护员工，但只有超管能改组织结构」。
     */
    public static final String DEPARTMENT_MANAGE = "department:manage";

    // ---------------- 主备双机配置（ 预埋） ----------------
    /**
     * 主备配置查看 / 维护。
     *
     * <p>与 {@link #AD_VIEW} / {@link #AD_MANAGE} 同一取向：改主备拓扑会影响整站可用性
     * （虚拟 IP、提升备机、触发切换），属基础设施级权限，不下发给业务管理员。
     *  建码时页面尚未交付； 已把「系统设置 → 主备配置」页与
     * 心跳 / 切换事件上报一并交付，因此这里不再是「菜单占位」，而是真实入口。
     */
    public static final String HA_VIEW = "ha:view";
    public static final String HA_MANAGE = "ha:manage";

    /**
     * 数据库备份查看 / 手动触发（P0）。
     *
     * <p>与 {@link #HA_VIEW} 同一取向：备份涉及整库导出与 NAS 写入，
     * 失败排查需要的基础设施权限（改 .env、进 NAS、看容器日志）普通业务管理员并不具备，
     * 故两个码都<b>不进 {@code DEFAULT_PERMISSIONS}</b> ⇒ 只归超管，
     * 也因此<b>无需授权迁移</b>（不会误发给既有角色）。
     */
    public static final String BACKUP_VIEW = "backup:view";
    public static final String BACKUP_MANAGE = "backup:manage";

    // ---------------- 系统设置 ----------------
    public static final String CONFIG_VIEW = "config:view";
    public static final String CONFIG_MANAGE = "config:manage";
    public static final String LOG_VIEW = "log:view";

    /**
     * 异常日志查看。
     *
     * <p>与 {@link #LOG_VIEW}（操作日志）分开，理由与「操作日志 vs 异常日志」两张表分开一致：
     * 操作日志记「谁做了什么」，异常日志记「系统因为什么坏了」。
     * 前者是**审计**面（管理员需要看，用来追溯操作），后者是**运维**面
     * （异常堆栈是给能改代码的人看的，管理员看了也无从下手）。
     *
     * <p>因此本码**不进 admin 的默认集合**，只归超管 —— 与「基础设施级告警只发超管」同一取向。
     * 新码不入默认集 ⇒ 也无需授权迁移（不会误发给既有角色）。
     */
    public static final String EXCEPTION_VIEW = "exception:view";

    /**
     * 安全日志查看。
     *
     * <p>只归超管。本页的写操作（人工解封 IP）是基础设施级动作 ——
     * 解封等于把一个来源重新放进门，与「改组织结构」同属会影响一批人的安全操作。
     * 读与写共用一个码（不像组织页那样拆 view / manage）：本页只有「看」与「解封」
     * 两个动作，而解封必须建立在能看见完整事件上下文的基础上，
     * 拆开只会得到「能解封但看不到为什么被封」这种危险组合。
     *
     * <p>新码不入 {@code DEFAULT_PERMISSIONS} ⇒ 无需授权迁移。
     */
    public static final String SECURITY_VIEW = "security:view";

    public static final String JOB_RUN = "job:run";
    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_MANAGE = "role:manage";

    // ---------------- 自定义申请类型与动态表单 ----------------
    /**
     * 表单模板查看 / 设计。
     *
     * <p> 起新增。<b>刻意不纳入 admin 的默认授权</b>：
     * 表单定义决定了后续所有自定义申请长什么样，是所有自定义工单的「配置源头」。
     * 按需求约定 admin 只拿 {@code apply_type:view}（能看类型列表，看不到表单内容），
     * 设计表单与维护类型恒归超管。
     */
    public static final String FORM_TEMPLATE_VIEW = "form_template:view";
    public static final String FORM_TEMPLATE_MANAGE = "form_template:manage";

    /** 申请类型查看 / 维护；admin 默认只有 view（能看不能改） */
    public static final String APPLY_TYPE_VIEW = "apply_type:view";
    public static final String APPLY_TYPE_MANAGE = "apply_type:manage";

    // ---------------- 动态审批流程 ----------------
    /**
     * 审批流程模板查看 / 维护。
     *
     * <p>需求方明确：<b>超管才能管理流程模板，admin 只读</b>。
     * 因此 {@code manage} 与一期 {@code form_template:manage} 一样只归 super_admin，
     * {@code view} 才进 admin 的默认集合 —— 审批流决定了工单"谁来批"，
     * 与表单定义同属配置源头，写权限必须收在超管手里。
     */
    public static final String APPROVAL_FLOW_VIEW = "approval_flow:view";
    public static final String APPROVAL_FLOW_MANAGE = "approval_flow:manage";

    // ---------------- 流程监控（ · M7） ----------------
    /**
     * 流程监控查看（模板工单量 / 平均审批时长 / 瓶颈节点）。
     *
     * <p><b>只有 view，没有 manage</b>：流程监控是一个纯只读的分析视图，
     * 页面上不存在任何写操作，造一个 {@code manage} 码只会让授权界面多出一个
     * 永远勾不上也永远用不到的项（本类的命名约定要求"每个码背后确有实现"）。
     *
     * <p>归 {@code admin} 默认持有，与 {@code asset:report:view} 同级：
     * 两者都是"给业务管理员看的经营/效率数据"，公开面相同。
     */
    public static final String FLOW_MONITOR_VIEW = "flow_monitor:view";

    // ---------------- 统计仪表盘（ · M5） ----------------
    /**
     * 统计仪表盘查看（区间内工单量 / 类型 / 状态 / 时间 / 部门分布 + 平均审批时长 / 超时率）。
     *
     * <p><b>只有 view，没有 manage</b>：与 {@link #FLOW_MONITOR_VIEW} 同理 ——
     * 仪表盘是纯只读聚合视图，页面上没有任何写操作，造一个 {@code manage} 码只会
     * 让授权界面多出一个永远勾不上也永远用不到的项。
     *
     * <p>归 {@code admin} 默认持有，<b>不给 user</b>：这是「经营 / 效率数据」的查看权，
     * 部门维度对普通员工无意义；与 {@code asset:report:view} / {@code flow_monitor:view}
     * 的公开面保持一致（都归业务管理员）。
     *
     * <p><b>零回归要点</b>：工作台路由 {@code /dashboard} 本就对全角色开放、也不占菜单
     * （见 {@code config/menus.ts}），因此本码只控制「新增聚合区块是否渲染」，
     * 不影响任何既有页面与入口。
     */
    public static final String DASHBOARD_VIEW = "dashboard:view";

    // ---------------- AD 域控 ----------------
    /**
     * AD 域控配置查看 / 维护。
     *
     * <p>刻意<b>不</b>纳入 {@code admin} 角色的默认权限集（见 {@link #DEFAULT_PERMISSIONS}）：
     * AD 配置里既有服务账号的绑定密码，又决定了「谁能进系统」，
     * 属于基础设施级配置；而 {@code admin} 的定位是业务管理员。
     * 与「备份失败只告警给超管」同一取向：问题需要的基础设施权限，业务管理员并不具备。
     */
    public static final String AD_VIEW = "ad:view";
    public static final String AD_MANAGE = "ad:manage";

    // ---------------- 在线一键升级 ----------------
    /**
     * 在线升级页面查看 / 执行。
     *
     * <p><b>两个码都刻意只归超管</b>（连 {@code view} 也不给 {@code admin}）：
     * 在线升级接口能替换服务器上的可执行文件（backend.jar）与静态资源目录，
     * 等价于「拿到了这台机器的代码执行能力」—— 这远超 {@code admin} 作为
     * 「业务管理员」的职责范围，与 {@link #AD_VIEW} / {@link #AD_MANAGE}
     * 同一取向（基础设施级权限不下发给业务管理员）。
     *
     * <p>因此它们**不出现在** {@link #DEFAULT_PERMISSIONS} 里，
     * V30 迁移也**不向任何内置角色下发授权行**（超管由守卫短路放行）。
     *
     * <p>另外：升级页对外的可用性还受 {@code app.upgrade.enabled} 总开关约束
     * （生产默认关闭）—— 「有权限」与「功能已启用」是两件事，前者是授权边界，
     * 后者是运维开关，两者都要过才能真正执行升级。
     */
    public static final String UPGRADE_VIEW = "system:upgrade:view";
    public static final String UPGRADE_EXECUTE = "system:upgrade:execute";

    /**
     * 权限目录树。
     *
     * <p>结构与 的菜单结构一一对应，便于前端用同一棵树的编码渲染
     * 「菜单树 + 操作权限」勾选界面。
     */
    private static final List<PermNode> TREE = List.of(
            group(GROUP_ORDER, "审批中心", List.of(
                    menu(ORDER_APPLY, "提交申请", "/order/apply"),
                    menu(ORDER_MINE, "我发起的申请", "/order/mine"),
                    menu(ORDER_APPROVAL, "待我审批", "/order/approval"),
                    menu(ORDER_PENDING, "我的待处理", "/order/pending"),
                    menu(ORDER_ALL_VIEW, "全部工单", "/order/all"),
                    action(ORDER_FORCE_MANAGE, "超管强制干预"),
                    // 统计仪表盘（ · M5）：挂在「工单管理」组下 ——
                    // 它的四个分布维度（类型 / 状态 / 时间 / 部门）全部是工单维度，
                    // 与「资产管理 → 统计报表」报的设备 / 借用经营数据问题域不同。
                    // 注意：它虽然写成了 menu 节点（携带路径 /dashboard 便于按菜单粒度授权），
                    // 但工作台是首页、不占 config/menus.ts 的菜单项，因此不会给任何人多出菜单。
                    menu(DASHBOARD_VIEW, "统计仪表盘", "/dashboard")
            )),
            group(GROUP_MESSAGE, "消息中心", List.of(
                    menu(MESSAGE_LIST, "我的消息", "/message/list")
            )),
            group(GROUP_ASSET, "资产管理", List.of(
                    menu(DEVICE_LEDGER_VIEW, "设备台账", "/asset/ledger"),
                    action(DEVICE_LEDGER_MANAGE, "设备台账维护"),
                    action(DEVICE_CATEGORY_MANAGE, "设备分类维护"),
                    action(DEVICE_IMPORT, "设备批量导入"),
                    menu(DEVICE_FAULT_VIEW, "设备故障记录", "/asset/fault"),
                    action(DEVICE_FAULT_MANAGE, "故障处理（维修完成 / 报废）"),
                    // 设备盘点（P2）：盘点的对象就是台账本体，故与台账/故障同组
                    menu(INVENTORY_VIEW, "设备盘点", "/asset/inventory"),
                    action(INVENTORY_MANAGE, "盘点任务管理（新建 / 核对 / 完成）")
            )),
            group(GROUP_USAGE, "数据统计", List.of(
                    menu(USAGE_VIEW, "使用记录", "/usage/records"),
                    // 统计报表： 按 从「资产管理」移入本组 ——
                    // 它报的是借用频次 / 审批时效 / 故障统计，与「设备台账」这种
                    // 「资产本体」问题域不同，和「使用记录」同属「跑出来的数据」。
                    menu(ASSET_REPORT_VIEW, "统计报表", "/asset/report")
            )),
            // ---------------- 组织与人员（ 合并出的新一级目录） ----------------
            group(GROUP_ORG, "组织与人员", List.of(
                    // 菜单码复用 staff:view（见常量处注释：单一入口 + 零回归）。
                    // 页面本体是钉钉通讯录风格的「左部门树 + 右成员列表」。
                    menu(STAFF_VIEW, "组织与人员", "/staff/organization"),
                    action(STAFF_MANAGE, "员工维护（新增 / 编辑 / 重置密码 / 离职）"),
                    action(STAFF_IMPORT, "员工批量导入"),
                    action(DEPARTMENT_MANAGE, "部门维护（新增 / 编辑 / 移动 / 设主管 / 删除）")
            )),
            // ---------------- 系统设置（平台级运维；原「系统管理」更名并重排） ----------------
            // 顺序即 给出的顺序：
            // 角色与权限 / 流程监控 / 操作日志 / 在线升级 / AD域控配置 / 主备配置 / 系统参数 / 高级设置
            group(GROUP_SYSTEM, "系统设置", List.of(
                    menu(ROLE_VIEW, "角色与权限", "/system/role"),
                    action(ROLE_MANAGE, "角色维护"),
                    // 流程监控（ · M7）：从「审批配置」一级目录挪进来。
                    // 它报的是审批效率，与「在线升级 / 操作日志」同属「平台跑得怎么样」的观测面。
                    menu(FLOW_MONITOR_VIEW, "流程监控", "/system/flow-monitor"),
                    menu(LOG_VIEW, "操作日志", "/system/logs"),
                    // 异常日志：与操作日志相邻，但只归超管 ——
                    // 异常堆栈是给能改代码的人看的，管理员看了也无从下手（见常量注释）。
                    menu(EXCEPTION_VIEW, "异常日志", "/system/exception-log"),
                    // 安全日志：登录失败 / 账号锁定 / IP 封禁的检索与人工解封。
                    // 与异常日志相邻（都是「系统出了什么事」的观测面），同样只归超管。
                    menu(SECURITY_VIEW, "安全日志", "/system/security-log"),
                    action(JOB_RUN, "定时任务手动触发"),
                    // 在线一键升级：两个码都只归超管 —— 升级接口能替换
                    // 服务器上的可执行文件，等价于代码执行能力。
                    menu(UPGRADE_VIEW, "在线升级", "/system/upgrade"),
                    action(UPGRADE_EXECUTE, "在线升级执行（上传包 / 应用 / 回滚）"),
                    // AD 域控：决定「谁能进系统」+ 存服务账号绑定密码。
                    menu(AD_VIEW, "AD 域控配置", "/system/ldap"),
                    action(AD_MANAGE, "AD 域控配置维护（测试连接 / 立即同步 / 保存配置）"),
                    // 主备配置： 明确「放在系统设置下，
                    // 不要藏进高级设置，维护人员一眼能看到」。
                    menu(HA_VIEW, "主备配置", "/system/ha"),
                    action(HA_MANAGE, "主备配置维护（添加备节点 / 手动切换 / 立即同步）"),
                    // 备份记录（P0）：与「主备配置」同属基础设施级观测面 ——
                    // 一个看「故障时能不能顶上」，一个看「数据丢没丢得回来」。
                    menu(BACKUP_VIEW, "备份记录", "/system/backup"),
                    action(BACKUP_MANAGE, "手动触发数据库备份"),
                    menu(CONFIG_VIEW, "系统参数", "/system/config"),
                    action(CONFIG_MANAGE, "系统参数修改"),
                    // 审批管理三项（ ：从原「高级设置」拿出来，
                    // 直接放在「系统设置」下，不再藏一层）。
                    //
                    // ⚠️ 三层结构做不了：后端权限目录只支持**两层**（{@code buildAllCodes()}
                    // 只遍历组的直接子节点），嵌三层会让孙节点的权限码从目录里**静默**消失 ——
                    // 角色页勾选树上根本看不到它们。因此这里与其它系统设置项平铺，
                    // 顺序上紧挨在一起以表达「同属审批配置」。
                    menu(APPLY_TYPE_VIEW, "申请类型管理", "/system/apply-type"),
                    action(APPLY_TYPE_MANAGE, "申请类型维护"),
                    action(FORM_TEMPLATE_VIEW, "表单模板查看"),
                    action(FORM_TEMPLATE_MANAGE, "表单模板设计"),
                    menu(APPROVAL_FLOW_VIEW, "审批流程模板", "/system/approval-flow"),
                    action(APPROVAL_FLOW_MANAGE, "审批流程模板维护（设计 / 发布 / 删除）")
            ))
    );

    /**
     * 内置角色的默认权限集合（「初始化导入现有三角色权限」）。
     *
     * <p><b>为什么要「导入现有权限」而不是「重配一遍」</b>：本功能是在系统已运行、
     * 三个角色的行为早已被验收过的前提下补做的。默认集合必须与<h3>改造前</h3>
     * 的 {@code @PreAuthorize("hasAnyRole(...)")} 判定<b>逐条等价</b>，
     * 否则上线即出现「admin 突然看不到全部工单」这类回归。
     * 因此这里的每一行都能在改造前的注解里找到出处。
     *
     * <p>{@code super_admin} 不在此表中：它由守卫短路放行，任何情况下都是全量权限，
     * 这样即便数据库里的授权行被误删，超管也不会把自己锁在门外。
     */
    private static final Map<String, Set<String>> DEFAULT_PERMISSIONS = buildDefaults();

    private static Map<String, Set<String>> buildDefaults() {
        Map<String, Set<String>> map = new LinkedHashMap<>();

        // ---------------- 超级管理员 ----------------
        // 刻意**不登记**：它由 PermissionGuard 直接短路放行，任何情况下都是全量权限。
        // 好处是即便数据库里的授权行被误删，超管也不会把自己锁在门外。

        // ---------------- 管理员（ 重排） ----------------
        map.put(RoleCode.ADMIN, Set.of(
                ORDER_APPLY, ORDER_MINE, ORDER_APPROVAL, ORDER_PENDING, ORDER_ALL_VIEW,
                MESSAGE_LIST,
                DEVICE_LEDGER_VIEW, DEVICE_LEDGER_MANAGE, DEVICE_CATEGORY_MANAGE,
                DEVICE_IMPORT, DEVICE_FAULT_VIEW, DEVICE_FAULT_MANAGE,
                // P2：盘点与台账同属资产职责域，按「管理员=维护资产」授予
                INVENTORY_VIEW, INVENTORY_MANAGE, ASSET_REPORT_VIEW,
                USAGE_VIEW,
                // 组织与人员：三个码（页面 + 员工维护 + 批量导入）。
                //
                // ⚠️ `DEPARTMENT_MANAGE` **刻意不在 admin 的默认集里**（ 起）。
                //  给 admin 的是「维护员工、资产、查看统计」——
                // 「改组织结构」不在其中：部门是**审批上级的事实源**，
                // 动一个部门主管等于改一批人的审批路径，风险高于「改某个人的手机号」。
                // 这与本类 {@link #DEPARTMENT_MANAGE} 的 javadoc 意图一致
                // （「业务管理员能维护员工，但只有超管能改组织结构」）。
                //
                // 为什么当初给了 admin，现在要收回：
                // 组织页把部门写操作全部按 `isSuperAdmin` 显隐，**前端比后端更严** ⇒
                // admin 看不到入口、却能直接调接口改组织结构（实测 200）。
                // 显隐不是安全边界，所以按「后端说了算」收回授权，让两侧一致。
                // 存量环境的授权行由 V42 迁移删除（代码默认集合只影响新库）。
                STAFF_VIEW, STAFF_MANAGE, STAFF_IMPORT,
                // ：**回收**「申请类型管理 / 审批流程模板」的只读权限。
                //
                // 需求文档验收标准 5：「普通管理员（非超管）看不到表单设计器、流程设计器，不需要学」。
                // 改造前给 admin 的是「view 只读」，实际效果是个尴尬的中间态：
                // 列表能看、点进去「设计 / 发布」全是 403，管理员既看不懂也不敢动，
                // 拦不住任何人 —— 反倒让「这里能配」看起来像日常待办。
                // 改为整块不可见：配置入口只归超管（预设好一套直接用，见）。
                //
                // 两侧同步：前端 `config/menus.ts` 的这两项由权限码驱动，会随授权回收自动消失。
                // 存量环境的授权行由 V33 迁移清理（代码默认集合只影响新库）。
                FLOW_MONITOR_VIEW, DASHBOARD_VIEW,
                CONFIG_VIEW, ROLE_VIEW, LOG_VIEW
        ));

        // ---------------- IT主管（ 新增） ----------------
        // ：「以上全部 + 工单管理 + 资产管理」。
        // 「以上全部」= 普通员工的集合（含审批待办 —— 见 RoleCode 类注释里为什么全员都有）。
        map.put(RoleCode.IT_MANAGER, Set.of(
                ORDER_APPLY, ORDER_MINE, ORDER_APPROVAL, ORDER_PENDING, ORDER_ALL_VIEW,
                MESSAGE_LIST,
                DEVICE_LEDGER_VIEW, DEVICE_LEDGER_MANAGE, DEVICE_CATEGORY_MANAGE,
                DEVICE_FAULT_VIEW, DEVICE_FAULT_MANAGE, ASSET_REPORT_VIEW,
                USAGE_VIEW, DASHBOARD_VIEW
        ));

        // ---------------- IT执行人（ 新增） ----------------
        // ：「以上全部 + 待我处理列表」——而「待我处理」在普通员工集合里就有
        // （设备的交付确认会指派到具体执行人），因此这里与普通员工同集合。
        // 刻意不给订单全局视图：执行人只需要看到派给自己的单。
        map.put(RoleCode.IT_EXECUTOR, Set.of(
                ORDER_APPLY, ORDER_MINE, ORDER_APPROVAL, ORDER_PENDING,
                MESSAGE_LIST
        ));

        // ---------------- 部门经理/组长（ 新增） ----------------
        // ：「以上全部 + 待我审批列表」——同上，集合与普通员工一致。
        // 它与普通员工的差别不在权限码，而在**组织关系**（他是某些人的直属主管，
        // 因此他的审批待办里会真的有单）。
        map.put(RoleCode.DEPT_MANAGER, Set.of(
                ORDER_APPLY, ORDER_MINE, ORDER_APPROVAL, ORDER_PENDING,
                MESSAGE_LIST
        ));

        // ---------------- 普通员工 ----------------
        map.put(RoleCode.USER, Set.of(
                ORDER_APPLY, ORDER_MINE, ORDER_APPROVAL, ORDER_PENDING,
                MESSAGE_LIST
        ));

        return map;
    }

    private static PermNode group(String code, String name, List<PermNode> children) {
        return new PermNode(code, name, PermType.MENU, "", children);
    }

    private static PermNode menu(String code, String name, String path) {
        return new PermNode(code, name, PermType.MENU, path, List.of());
    }

    private static PermNode action(String code, String name) {
        return new PermNode(code, name, PermType.ACTION, "", List.of());
    }

    /** 目录树（只读快照，调用方不得修改） */
    public static List<PermNode> tree() {
        return TREE;
    }

    /** 展开后的全部叶子权限码（不含分组节点） */
    private static final Set<String> ALL_CODES = buildAllCodes();

    private static Set<String> buildAllCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (PermNode node : TREE) {
            for (PermNode child : node.children()) {
                codes.add(child.code());
            }
        }
        return Set.copyOf(codes);
    }

    /** 全部可授予的权限码 */
    public static Set<String> allCodes() {
        return ALL_CODES;
    }

    /** 权限码是否存在于目录（用于保存授权时过滤掉非法/已废弃的码） */
    public static boolean exists(String code) {
        return code != null && ALL_CODES.contains(code);
    }

    /** 权限码 → 名称；不存在时原样返回，避免展示层出现 null */
    public static String nameOf(String code) {
        for (PermNode root : TREE) {
            for (PermNode child : root.children()) {
                if (child.code().equals(code)) {
                    return child.name();
                }
            }
        }
        return code == null ? "" : code;
    }

    /** 内置角色默认权限；未登记的角色返回空集合（新建角色从零开始授权） */
    public static Set<String> defaultPermissions(String roleCode) {
        Set<String> codes = DEFAULT_PERMISSIONS.get(roleCode);
        return codes == null ? Set.of() : codes;
    }

    /** 全部权限码的稳定副本，供「给某角色授予全部权限」使用 */
    public static List<String> allCodeList() {
        return new ArrayList<>(ALL_CODES);
    }

    // ------------------------------------------------------------------
    // 权限申请的风险等级与可申请性
    // ------------------------------------------------------------------

    /** 权限风险等级：决定申请它要不要多走一级超管审批 */
    public enum RiskLevel {
        /** 普通：一级审批（直属主管 / 部门主管）即可开通 */
        NORMAL,
        /** 高危：部门主管 → 超管 两级 */
        HIGH
    }

    /**
     * 默认**不开放申请**的提权类权限。
     *
     * <p>判据是「拿到它能不能绕过本流程自行提权」：角色管理能给自己加角色，
     * AD 管理能改谁能进系统，系统参数能改一切开关，在线升级能替换服务器上的可执行文件
     * （等价于代码执行）。这四个都不开放申请。
     *
     * <p>⚠️ <b> 遗留的一处真实缺陷已在此修正</b>：原
     * {@code PresetApplyCatalog.NON_APPLICABLE} 写的是 {@code system:upgrade:manage}，
     * 而目录里**根本没有这个码**（真实的码是 {@code system:upgrade:view} /
     * {@code system:upgrade:execute}）。`Set.contains` 对不存在的码恒为 false，
     * 于是「在线升级」的两个码**一直是可申请的** —— 这是个静默的提权口子，
     * 因为过滤器不会报错，只是「少排除了一个」。
     * 教训：黑名单里的码必须与目录**逐字一致**，写错等于没写（故补了单测钉住）。
     *
     * <p>真正的可申请集合由 {@code permission_apply_policy} 表覆盖（管理员可配），
     * 本集合只是「没有表行时的默认值」。
     */
    private static final Set<String> DEFAULT_NON_APPLICABLE = Set.of(
            ROLE_MANAGE, AD_MANAGE, CONFIG_MANAGE, UPGRADE_VIEW, UPGRADE_EXECUTE);

    /** 该权限码默认是否开放申请（未在 {@code permission_apply_policy} 中配置时生效） */
    public static boolean applicableByDefault(String code) {
        return code != null && ALL_CODES.contains(code) && !DEFAULT_NON_APPLICABLE.contains(code);
    }

    /** 默认不开放申请的权限码集合（供迁移 / 界面展示，避免两处各写一份） */
    public static Set<String> defaultNonApplicableCodes() {
        return DEFAULT_NON_APPLICABLE;
    }

    /**
     * 权限码的风险等级。
     *
     * <p>规则（用户拍板口径）：
     * <ul>
     *   <li><b>全部 {@code *:manage} 写操作类</b> ⇒ 高危。它们都能改配置或数据；</li>
     *   <li><b>批量 / 导入 / 删除类</b>（{@code *:import}、含 {@code delete} / {@code batch} 的码）
     *       ⇒ 高危。一次操作影响面是一整批数据；</li>
     *   <li>{@code order:force:manage} ⇒ 高危（超管级强制干预）；</li>
     *   <li>其余 {@code *:view} 等只读码 ⇒ 普通。</li>
     * </ul>
     *
     * <p>未知码按**高危**处理（保守方向）：宁可让一个只读码多走一级审批，
     * 也不要因为「规则没覆盖到」而让一个写权限只经一级就开通。
     */
    public static RiskLevel riskLevelOf(String code) {
        if (code == null || code.isBlank() || !ALL_CODES.contains(code)) {
            return RiskLevel.HIGH;
        }
        if (DEFAULT_NON_APPLICABLE.contains(code)) {
            return RiskLevel.HIGH;
        }
        if (code.endsWith(":manage") || ORDER_FORCE_MANAGE.equals(code)) {
            return RiskLevel.HIGH;
        }
        if (code.endsWith(":import") || code.contains("delete") || code.contains(":batch")) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.NORMAL;
    }

    /** 一组权限码中是否含高危项（：决定是否追加超管那一级审批） */
    public static boolean containsHighRisk(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return false;
        }
        for (String code : codes) {
            if (riskLevelOf(code) == RiskLevel.HIGH) {
                return true;
            }
        }
        return false;
    }

    /**
     * 权限节点。
     *
     * @param code     权限码（分组节点为 {@code group:xxx}，不可授予）
     * @param name     中文名称
     * @param type     菜单权限 / 操作权限
     * @param menuPath 菜单路径（仅菜单权限有值，操作权限为空串；刻意不用 null，
     *                 避免后续装配响应时踩到 {@code Map.of/List.of} 不接受 null 的坑）
     * @param children 子节点
     */
    public record PermNode(String code, String name, PermType type, String menuPath, List<PermNode> children) {
    }
}
