package com.enterprise.ticket.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.ApprovalFlowStatus;
import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.FormTemplateStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.ConditionRule;
import com.enterprise.ticket.common.flow.FlowBranch;
import com.enterprise.ticket.common.flow.FlowCondition;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowDefinitionValidator;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowOperator;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormOption;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.form.FormSchemaValidator;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlow;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.entity.DepartmentManager;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 演示数据初始化（仅 dev profile，幂等）
 *
 * <p>目的：让各阶段界面开箱即可走查。
 * <ul>
 *   <li><b></b>：预置部门、分组成员、多级审批节点（含会签/或签）、
 *       最终处理部门及其成员，并刻意保留一个「未绑定最终处理部门、未配置审批节点」的分组，
 *       用于验证  的提示与  的兜底/拒绝逻辑；</li>
 *   <li><b></b>（ / ）：预置两级设备分类与多状态设备台账；</li>
 *   <li><b></b>（ /  / ）：预置三笔处于不同阶段的工单 ——
 *       使用中（短期借用）、待交付（长期领用）、审批中，其中「待交付」那笔刻意包含一个
 *       被「自审批跳过」的节点，便于直接查看快照效果与设备使用信息。</li>
 * </ul>
 *
 * <p><b>超级管理员保护（需求方  明确要求）</b>：本类<b>绝不创建、绝不修改</b>超管账号。
 * {@link #ensureDemoUsers} 对已存在的账号会显式判断角色，命中 super_admin 立即跳过 ——
 * 即使未来有人把演示员工的姓名改成超管姓名，也不会覆盖超管。
 *
 * <p>幂等策略：四类数据各自独立判断，互不牵连 ——
 * 设备演示数据按 {@code device_category} 是否为空、
 * 工单演示数据按 {@code orders} 是否为空，
 * 避免前序阶段已产生数据时后续阶段的演示数据永远不被创建。
 *
 * <p>触发条件：{@code app.demo.enabled=true}。生产 profile 下本类不被加载（{@link Profile}），
 * 因此不会污染正式环境。
 */
@Slf4j
@Component
@Profile("dev")
// 用全限定名而非 import：本类同时使用了工单实体 com.enterprise.ticket.module.order.entity.Order，
// 与 Spring 的同名注解无法同时 import
@org.springframework.core.annotation.Order(2)
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    /** 演示员工：登录名 / 姓名 / 角色 / 主部门名 */
    private record DemoUser(String username, String displayName, String role, String groupName) {
    }

    /**
     * 演示部门：部门名 / 是否最终处理部门 / 部门主管姓名 / 成员姓名。
     *
     * <p>：原先的「最终处理小组」与「业务分组」合并为部门树，
     * 因此这里从两个种子列表（HANDLER_GROUPS + BIZ_GROUPS）合成一个 {@link #DEPARTMENTS}。
     */
    private record DeptSeed(String name, boolean handlerGroup, List<String> managerNames, List<String> memberNames) {
    }

    /** 设备分类种子：分类名 / 父分类名（null 表示一级分类）/ 同级顺序 */
    private record CategorySeed(String name, String parentName, int sortOrder) {
    }

    /** 设备台账种子 */
    private record DeviceSeed(String deviceName, String assetNo, String primaryCategory, String secondaryCategory,
                              String brand, String model, String serialNo, String location,
                              LocalDate purchaseDate, String status, String remark) {
    }

    /** 工单快照节点种子 */
    private record OrderNodeSeed(int stepOrder, String approverName, String signType, String status) {
    }

    /** 工单种子 */
    private record OrderSeed(String noSuffix, String assetNo, String applicantName, String useType,
                             String reason, Integer returnDays,
                             String status, String handlerName, Integer deliveredDaysAgo,
                             List<OrderNodeSeed> nodes) {
    }

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("10001", "张伟", RoleCode.USER, "研发部"),
            new DemoUser("10002", "李娜", RoleCode.USER, "研发部"),
            new DemoUser("10003", "刘洋", RoleCode.ADMIN, "研发部"),
            new DemoUser("10004", "王强", RoleCode.USER, "市场部"),
            new DemoUser("10005", "陈晨", RoleCode.USER, "行政部"),
            new DemoUser("10006", "赵敏", RoleCode.ADMIN, "行政部"));

    /**
     * 演示部门。全部挂在根节点「公司」之下。
     *
     * <p>每一项都刻意指向一条要验证的分支：
     * <ul>
     *   <li><b>研发部</b>：设部门主管（刘洋）—— 「提交后自动报给部门主管」的主路径；</li>
     *   <li><b>市场部</b>：设部门主管（陈晨）；</li>
     *   <li><b>行政部</b>：<b>刻意不设主管</b> —— 验证「未配主管 → 超管兜底」；</li>
     *   <li><b>IT运维组</b>：{@code handlerGroup = true}，全系统唯一的最终处理部门，
     *       成员即「IT执行人」候选（负责发设备）。注意它的成员<b>不</b>改写主部门 ——
     *       同一个人可以既属于研发部、又挂在 IT运维组下（多部门归属）。</li>
     * </ul>
     */
    private static final List<DeptSeed> DEPARTMENTS = List.of(
            new DeptSeed("研发部", false, List.of("刘洋"), List.of("张伟", "李娜", "刘洋")),
            new DeptSeed("市场部", false, List.of("陈晨"), List.of("王强", "陈晨")),
            new DeptSeed("行政部", false, List.of(), List.of("赵敏")),
            new DeptSeed("IT运维组", true, List.of(), List.of("陈晨", "刘洋", "赵敏")));

    /** 设备分类：3 个一级分类 + 4 个二级分类 */
    private static final List<CategorySeed> DEVICE_CATEGORIES = List.of(
            new CategorySeed("电脑", null, 1),
            new CategorySeed("显示器", null, 2),
            new CategorySeed("网络设备", null, 3),
            new CategorySeed("笔记本", "电脑", 1),
            new CategorySeed("台式机", "电脑", 2),
            new CategorySeed("液晶显示器", "显示器", 1),
            new CategorySeed("路由器", "网络设备", 1));

    /**
     * 设备台账（ / ）：覆盖多种状态，便于验证状态机分支。
     *
     * <p> 起，IT-2026-0002 / 0003 / 0007 分别被  演示工单占用，
     * 状态由「审批中 / 使用中」体现（与工单状态保持一致，见 {@link #ensureOrderDemoData}）。
     */
    private static final List<DeviceSeed> DEVICES = List.of(
            new DeviceSeed("ThinkPad X1 Carbon", "IT-2026-0001", "电脑", "笔记本",
                    "联想", "X1 Carbon Gen11", "SN-X1C-0001", "A座3F研发区", LocalDate.of(2026, 1, 15),
                    DeviceStatus.AVAILABLE.name(), "张伟专用开发机"),
            new DeviceSeed("MacBook Pro 14", "IT-2026-0002", "电脑", "笔记本",
                    "Apple", "MacBook Pro 14 M3", "SN-MBP-0002", "A座3F研发区", LocalDate.of(2026, 2, 20),
                    DeviceStatus.IN_APPROVAL.name(), "李娜长期领用（演示工单）"),
            new DeviceSeed("Dell OptiPlex 7010", "IT-2026-0003", "电脑", "台式机",
                    "Dell", "OptiPlex 7010", "SN-OPT-0003", "B座2F行政区", LocalDate.of(2025, 11, 1),
                    DeviceStatus.IN_APPROVAL.name(), "张伟申请中（演示工单）"),
            new DeviceSeed("DELL U2723QE", "IT-2026-0004", "显示器", "液晶显示器",
                    "Dell", "U2723QE 27寸", "SN-U27-0004", "A座3F研发区", LocalDate.of(2026, 1, 15),
                    DeviceStatus.AVAILABLE.name(), null),
            new DeviceSeed("华为 MateView", "IT-2026-0005", "显示器", "液晶显示器",
                    "华为", "MateView 28.2寸", "SN-MV-0005", "B座2F行政区", LocalDate.of(2025, 9, 10),
                    DeviceStatus.MAINTENANCE.name(), "屏幕闪烁，送修中"),
            new DeviceSeed("华三 MSR830 路由器", "IT-2026-0006", "网络设备", "路由器",
                    "H3C", "MSR830-6EI", "SN-MSR-0006", "A座1F机房", LocalDate.of(2025, 6, 1),
                    DeviceStatus.AVAILABLE.name(), "备用出口路由"),
            new DeviceSeed("TP-Link 交换机", "IT-2026-0007", "网络设备", null,
                    "TP-Link", "TL-SG1024D", "SN-TLS-0007", "A座1F机房", LocalDate.of(2024, 3, 18),
                    DeviceStatus.IN_USE.name(), "会议室改造期间临时接入网络（演示工单）"),
            new DeviceSeed("ThinkCentre M720", "IT-2024-0018", "电脑", "台式机",
                    "联想", "ThinkCentre M720", "SN-TC-0018", "已下架", LocalDate.of(2023, 5, 6),
                    DeviceStatus.SCRAPPED.name(), "使用年限到期，已报废"));

    /**
     *  演示工单（ /  / ）
     *
     * <p>三笔刻意覆盖三个不同阶段，且快照节点状态各不相同：
     * <ol>
     *   <li><b>使用中（短期借用）</b> —— TP-Link 交换机：单级或签已通过、已交付，
     *       用于验证「设备详情显示当前使用人 / 使用类型 / 关联工单号 / 到期日」；</li>
     *   <li><b>待交付（长期领用）</b> —— MacBook Pro：两级审批已走完，其中 step2 因
     *       「审批人 = 申请人」被<b>自动跳过</b>，可直接验证交付确认入口；</li>
     *   <li><b>审批中</b> —— Dell 台式机：两级节点均待办，用 刘洋 登录即可看到
     *       「审批待办」，并验证会签/或签的逐级流转。</li>
     * </ol>
     * 期望归还日期用「相对今天的天数」表达，保证演示数据不会随时间推移变成「早已过期」。
     */
    private static final List<OrderSeed> ORDERS = List.of(
            new OrderSeed("001", "IT-2026-0007", "王强", UseType.SHORT_TERM.name(),
                    "会议室改造期间临时接入网络", 7,
                    OrderStatus.BORROWED.name(), "赵敏", 1,
                    List.of(new OrderNodeSeed(1, "陈晨", SignType.ANY_SIGN, ApprovalNodeStatus.APPROVED.name()))),
            new OrderSeed("002", "IT-2026-0002", "李娜", UseType.LONG_TERM.name(),
                    "长期领用，作为日常开发机", null,
                    OrderStatus.PENDING_DELIVERY.name(), "刘洋", null,
                    List.of(new OrderNodeSeed(1, "刘洋", SignType.ANY_SIGN, ApprovalNodeStatus.APPROVED.name()),
                            new OrderNodeSeed(2, "李娜", SignType.ALL_SIGN, ApprovalNodeStatus.SKIPPED.name()))),
            new OrderSeed("003", "IT-2026-0003", "张伟", UseType.SHORT_TERM.name(),
                    "替换送修的台式机，临时使用", 3,
                    OrderStatus.PENDING_APPROVAL.name(), null, null,
                    List.of(new OrderNodeSeed(1, "刘洋", SignType.ANY_SIGN, ApprovalNodeStatus.PENDING.name()),
                            new OrderNodeSeed(2, "李娜", SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name()))));

    /** 计划结束时间统一取当日 23:59:59，与工单服务保持一致 */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final DepartmentMapper departmentMapper;
    private final DepartmentManagerMapper departmentManagerMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final DeviceCategoryMapper deviceCategoryMapper;
    private final DeviceMapper deviceMapper;
    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper orderApprovalNodeMapper;
    // ：自定义申请类型演示数据直插（不走 Service，避免启动期依赖安全上下文取当前用户）
    private final FormTemplateMapper formTemplateMapper;
    private final FormTemplateVersionMapper formTemplateVersionMapper;
    private final ApplyTypeMapper applyTypeMapper;
    private final ApprovalFlowMapper approvalFlowMapper;
    private final ApprovalFlowVersionMapper approvalFlowVersionMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = appProperties.getDemo();
        if (!demo.isEnabled()) {
            log.info("[Demo] 演示数据初始化已关闭（app.demo.enabled=false）");
            return;
        }

        // 设备演示数据独立幂等，不依赖部门是否存在（详见类注释）
        ensureDeviceDemoData();

        // 口令对齐独立幂等，必须与「分组是否已存在」解耦：若嵌在下面的「分组为空」分支内，
        // 任何已初始化过的环境（分组已存在）都会整段跳过，导致库里仍是旧口令、
        // 文档写着新口令而登录报「账号或密码错误」——排查成本极高（需求方 2026-09-18 约定）。
        if (StringUtils.hasText(demo.getUserInitPassword())) {
            alignDemoPasswords(demo.getUserInitPassword());
            //  ：预置联系方式，让「找回密码」在开发环境开箱即可走查
            ensureDemoContacts();
        }

        // 判据从「departments 表是否为空」改成「演示部门是否已存在」：
        // V31 迁移已经幂等地建好了根节点「公司」与「IT运维组」，表永远非空 ——
        // 沿用旧的空表判据会让**全新环境**的演示部门永远不被创建。
        boolean demoDepartmentsReady = departmentMapper.selectCount(
                Wrappers.<Department>lambdaQuery().eq(Department::getDeptName, DEPARTMENTS.get(0).name())) > 0;
        if (demoDepartmentsReady) {
            log.info("[Demo] 演示部门已存在，跳过组织演示数据初始化");
        } else if (!StringUtils.hasText(demo.getUserInitPassword())) {
            log.warn("[Demo] 未配置 app.demo.user-init-password，跳过演示员工/部门初始化"
                    + "（开发环境可在 application-dev.yml 或 DEMO_USER_INIT_PASSWORD 中配置）");
        } else {
            Map<String, Long> userIds = ensureDemoUsers(demo.getUserInitPassword());
            createDepartments(userIds);
            log.info("[Demo] 组织演示数据初始化完成：{} 个演示员工、{} 个演示部门"
                            + "（含 1 个不设主管的「行政部」用于验证超管兜底）",
                    userIds.size(), DEPARTMENTS.size());
        }

        //  演示工单：依赖「员工 + 分组 + 设备」，独立幂等
        ensureOrderDemoData();

        // ：演示员工的直属领导（LEADER 规则的数据来源），独立幂等。
        // 这一项**不受** applyConfigEnabled 影响：它是「组织结构」而非「演示申请配置」。
        ensureDemoLeaders();

        //  自定义申请类型（表单模板 + 申请类型）+  演示审批流程。
        //
        // ⚠️  起默认关闭（app.demo.apply-config-enabled=false）：
        //    这两处**每次启动**都会重建演示类型与演示流程，与需求「删掉所有演示数据」冲突 ——
        //    只删库不关这里，下次启动就会还原（V41 删除后启动即被重建，
        //    预置的「采购申请」因此撞唯一键而创建失败）。
        //    申请类型改由 PresetApplyInitializer 播种。
        if (demo.isApplyConfigEnabled()) {
            ensureCustomApplyDemoData();
            ensureFlowDemoData();
        } else {
            log.info("[Demo] 演示申请配置（采购申请 / 演示审批流程）未播种"
                    + "（app.demo.apply-config-enabled=false， 起默认关闭）");
        }
    }

    /**
     * 创建演示员工；已存在同名员工则复用其 user_id（幂等）
     *
     * <p><b>超管保护</b>：命中 super_admin 角色时立即跳过，绝不修改其登录名、密码或角色
     * （需求方约定：超管登录名固定为 administrator，不可修改、不可被重置）。
     *
     * <p><b>口令对齐</b>：已存在的演示账号会走 {@link #alignDemoPassword}，
     * 保证「文档里写的口令」与「库里实际的口令」始终一致。
     */
    private Map<String, Long> ensureDemoUsers(String rawPassword) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (DemoUser seed : DEMO_USERS) {
            //  ：必须按「数字登录名」查。V27 之后 username 已是 10001 起，
            // 仍按中文姓名查会恒为 null —— 表现为口令对齐静默空转，且重启也修不回。
            User existing = userService.getByUsername(seed.username());
            if (existing != null) {
                if (RoleCode.isSuperAdmin(existing.getRole())) {
                    log.warn("[Demo] 账号 [{}] 是超级管理员，演示数据初始化跳过该账号（不修改超管）",
                            seed.displayName());
                    continue;
                }
                alignDemoPassword(existing, rawPassword);
                result.put(seed.displayName(), existing.getId());
                continue;
            }
            User user = new User();
            // ：登录名即员工姓名，重名自动加 _2 后缀
            user.setUsername(seed.username());
            user.setDisplayName(seed.displayName());
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRole(seed.role());
            user.setAuthType("LOCAL");
            // 演示账号不强制改密，便于直接登录走查各角色界面
            user.setForceChangePassword(false);
            user.setEnabled(true);
            user.setDimission(false);
            userService.save(user);
            result.put(seed.displayName(), user.getId());
        }
        return result;
    }

    /**
     * 演示账号口令对齐（需求方 2026-09-18 约定：演示 / 测试员工统一为演示口令）
     *
     * <p>为什么需要它：{@link #ensureDemoUsers} 原本「账号已存在即跳过」，于是早先创建过的演示
     * 账号库里仍是旧口令 —— 文档写着新口令、实际登录却报「账号或密码错误」，排查成本极高。
     * 本方法把演示账号的口令对齐到 {@code app.demo.user-init-password}，让文档与界面永远一致。
     *
     * <p>安全边界（三条，缺一不可）：
     * <ol>
     *   <li>仅 {@code dev} profile 加载本类，生产环境根本不会执行；</li>
     *   <li>只处理 {@link #DEMO_USERS} 白名单内的账号，不遍历、不批量更新真实员工；</li>
     *   <li>调用方已先排除 super_admin，超管口令绝不被覆盖。</li>
     * </ol>
     * 先用 {@link UserService#matchesPassword} 比对，口令已正确时不写库，避免每次启动都产生写操作。
     */
    private void alignDemoPassword(User existing, String rawPassword) {
        if (userService.matchesPassword(existing, rawPassword)) {
            return;
        }
        userService.updatePassword(existing.getId(), rawPassword, true);
        log.info("[Demo] 演示账号 [{}] 的口令与配置不一致，已对齐为演示初始口令",
                existing.getDisplayName());
    }

    /**
     * 批量口令对齐（独立于分组初始化，见 {@link #run}）
     *
     * <p>遍历 {@link #DEMO_USERS} 白名单，把「已存在且非 super_admin」的演示账号口令对齐到
     * 配置值；口令已正确的账号不写库（{@link UserService#matchesPassword} 预检），
     * 因此每次启动都能安全执行，不产生无谓写操作。
     *
     * <p>与 {@link #alignDemoPassword} 的关键区别：后者只在「首次创建分组」分支内被调用，
     * 而本方法在任何已初始化的环境都会执行，是「文档口令 = 库内口令」的真正保证。
     */
    private void alignDemoPasswords(String rawPassword) {
        int aligned = 0;
        for (DemoUser seed : DEMO_USERS) {
            //  ：按数字登录名查（原因见 ensureDemoUsers）
            User existing = userService.getByUsername(seed.username());
            if (existing == null || RoleCode.isSuperAdmin(existing.getRole())) {
                continue;
            }
            if (userService.matchesPassword(existing, rawPassword)) {
                continue;
            }
            userService.updatePassword(existing.getId(), rawPassword, true);
            aligned++;
        }
        if (aligned > 0) {
            log.info("[Demo] 演示账号口令对齐完成：{} 个账号已对齐为演示初始口令", aligned);
        }
    }

    /**
     * 为演示账号预置手机号与邮箱（ ）。
     *
     * <h2>为什么需要它</h2>
     * <p>V26/V27 之后全库账号的 {@code phone} / {@code email} 都是空的，于是人工走查
     * 「找回密码」时<b>没有任何可用的样例账号</b> —— 得先去个人中心绑一次才能试，
     * 而绑定本身正是待验证的功能，陷入自证循环。这里按演示账号白名单预置，
     * 让开发环境开箱即可演示。
     *
     * <h2>三条边界（与口令对齐同口径）</h2>
     * <ol>
     *   <li>只在 {@code app.demo.enabled} 且配置了演示口令时执行（生产不加载本类）；</li>
     *   <li>只处理 {@link #DEMO_USERS} 白名单，不遍历、不批量更新真实员工；</li>
     *   <li><b>两个字段都为空时才写</b> —— 手工绑过 / 换绑过的联系方式绝不被启动覆盖，
     *       否则「换绑后重启又变回原值」会成为一个无从解释的缺陷。</li>
     * </ol>
     *
     * <p>编号规则：手机号 {@code 139 + 序号 8 位}（10001 → 13900000001），
     * 邮箱 {@code user<登录名>@example.com}。两者都满足统一的格式校验正则是前提，
     * 否则写入的就是一批「自己都通不过校验」的样例数据。
     */
    private void ensureDemoContacts() {
        int updated = 0;
        for (DemoUser seed : DEMO_USERS) {
            User existing = userService.getByUsername(seed.username());
            if (existing == null
                    || StringUtils.hasText(existing.getPhone())
                    || StringUtils.hasText(existing.getEmail())) {
                continue;
            }
            String phone = String.format("139%08d", Long.parseLong(seed.username()) - 10000);
            String email = "user" + seed.username() + "@example.com";
            // 条件更新（isNull 双保险 + 受影响行数）：即便两个实例同时启动，
            // 也只有一个能把空值填上，不会互相覆盖。
            boolean changed = userService.update(Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, existing.getId())
                    .isNull(User::getPhone)
                    .isNull(User::getEmail)
                    .set(User::getPhone, phone)
                    .set(User::getEmail, email));
            if (changed) {
                updated++;
            }
        }
        if (updated > 0) {
            log.info("[Demo] 演示账号联系方式预置完成：{} 个账号（仅填补空值，不覆盖手工绑定）", updated);
        }
    }

    /**
     * 创建演示部门、部门主管与最终处理部门成员。
     *
     * <p>三件事各自独立地幂等：部门按「名 + 父节点」判重；主管与成员关系<b>只插不查重</b> ——
     * 但要走到这里的前提是「演示部门不存在」，因此整段只会执行一次。
     */
    private void createDepartments(Map<String, Long> userIds) {
        Long rootId = ensureRootDepartment();
        Department root = departmentMapper.selectById(rootId);
        String rootPath = root == null || !StringUtils.hasText(root.getPath())
                ? "/" + rootId + "/"
                : root.getPath();

        int sortOrder = 1;
        for (DeptSeed seed : DEPARTMENTS) {
            Department dept = new Department();
            dept.setDeptName(seed.name());
            dept.setParentId(rootId);
            dept.setDepth(1);
            dept.setSortOrder(sortOrder++);
            dept.setHandlerGroup(seed.handlerGroup());
            dept.setStatus(true);
            dept.setRemark("演示数据");
            // path 依赖自身 id，先落一行占位再回填 —— 与 DepartmentServiceImpl#create 同一手法。
            // 不能等插入后才第一次写 path：那样中途失败会留下 path 为 NULL 的脏行，
            // 而所有「取子树」的查询都靠 path 前缀匹配，NULL 会让整棵子树静默消失。
            dept.setPath(rootPath + "PENDING/");
            departmentMapper.insert(dept);
            dept.setPath(rootPath + dept.getId() + "/");
            departmentMapper.updateById(dept);

            for (String managerName : seed.managerNames()) {
                Long managerId = userIds.get(managerName);
                if (managerId == null) {
                    continue;
                }
                DepartmentManager manager = new DepartmentManager();
                manager.setDepartmentId(dept.getId());
                manager.setUserId(managerId);
                departmentManagerMapper.insert(manager);
            }

            List<Long> memberIds = seed.memberNames().stream()
                    .map(userIds::get).filter(Objects::nonNull).toList();
            for (Long memberId : memberIds) {
                UserDepartment membership = new UserDepartment();
                membership.setUserId(memberId);
                membership.setDepartmentId(dept.getId());
                userDepartmentMapper.insert(membership);
            }
            // 「主部门」只在非最终处理部门上写：IT运维组的成员大多主职在别的部门，
            // 若在这里统一 assign，会把所有人的主部门改写成 IT运维组。
            if (!seed.handlerGroup() && !memberIds.isEmpty()) {
                userService.assignDepartment(memberIds, dept.getId());
            }
        }
    }

    /** 根节点「公司」：V31 已建则复用；全新库（未跑迁移）时补建 */
    private Long ensureRootDepartment() {
        Department root = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .isNull(Department::getParentId)
                .orderByAsc(Department::getId)
                .last("LIMIT 1"));
        if (root != null) {
            return root.getId();
        }
        Department created = new Department();
        created.setDeptName("公司");
        created.setParentId(null);
        created.setDepth(0);
        created.setSortOrder(0);
        created.setHandlerGroup(false);
        created.setStatus(true);
        created.setRemark("演示数据（组织根节点）");
        created.setPath("/PENDING/");
        departmentMapper.insert(created);
        created.setPath("/" + created.getId() + "/");
        departmentMapper.updateById(created);
        return created.getId();
    }

    /** 全局唯一的最终处理部门 id（IT运维组）；未配置返回 null */
    private Long handlerDepartmentId() {
        Department handler = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerGroup, true)
                .orderByAsc(Department::getId)
                .last("LIMIT 1"));
        return handler == null ? null : handler.getId();
    }

    // ------------------------------------------------------------------
    //  设备分类与设备台账演示数据（ / ）
    // ------------------------------------------------------------------

    private void ensureDeviceDemoData() {
        Long existing = deviceCategoryMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("[Demo] 已存在 {} 个设备分类，跳过设备分类与台账演示数据初始化", existing);
            return;
        }

        Map<String, Long> categoryIds = new LinkedHashMap<>();
        for (CategorySeed seed : DEVICE_CATEGORIES) {
            DeviceCategory category = new DeviceCategory();
            category.setCategoryName(seed.name());
            category.setSortOrder(seed.sortOrder());
            if (seed.parentName() == null) {
                category.setParentId(DeviceCategory.ROOT_PARENT_ID);
                category.setLevel(DeviceCategory.LEVEL_PRIMARY);
            } else {
                Long parentId = categoryIds.get(seed.parentName());
                if (parentId == null) {
                    log.warn("[Demo] 设备分类「{}」的父分类「{}」尚未创建，已跳过", seed.name(), seed.parentName());
                    continue;
                }
                category.setParentId(parentId);
                category.setLevel(DeviceCategory.LEVEL_SECONDARY);
            }
            deviceCategoryMapper.insert(category);
            categoryIds.put(seed.name(), category.getId());
        }

        for (DeviceSeed seed : DEVICES) {
            Long primaryId = categoryIds.get(seed.primaryCategory());
            if (primaryId == null) {
                log.warn("[Demo] 设备「{}」的一级分类「{}」不存在，已跳过", seed.deviceName(), seed.primaryCategory());
                continue;
            }
            Device device = new Device();
            device.setDeviceName(seed.deviceName());
            device.setAssetNo(seed.assetNo());
            device.setPrimaryCategoryId(primaryId);
            device.setSecondaryCategoryId(seed.secondaryCategory() == null
                    ? null : categoryIds.get(seed.secondaryCategory()));
            device.setBrand(seed.brand());
            device.setModel(seed.model());
            device.setSerialNo(seed.serialNo());
            device.setStorageLocation(seed.location());
            device.setPurchaseDate(seed.purchaseDate());
            device.setStatus(seed.status());
            device.setRemark(seed.remark());
            device.setDeleted(false);
            deviceMapper.insert(device);
        }

        log.info("[Demo] 设备演示数据初始化完成：{} 个分类（3 一级 + 4 二级）、{} 台设备",
                categoryIds.size(), DEVICES.size());
    }

    // ------------------------------------------------------------------
    //  借用工单演示数据（ /  / ）
    // ------------------------------------------------------------------

    private void ensureOrderDemoData() {
        Long existing = orderMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("[Demo] 已存在 {} 笔工单，跳过工单演示数据初始化", existing);
            return;
        }

        Map<String, Device> devicesByAssetNo = new LinkedHashMap<>();
        for (Device device : deviceMapper.selectList(null)) {
            devicesByAssetNo.put(device.getAssetNo(), device);
        }
        if (devicesByAssetNo.isEmpty()) {
            log.warn("[Demo] 无设备数据，跳过工单演示数据初始化");
            return;
        }

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (OrderSeed seed : ORDERS) {
            Device device = devicesByAssetNo.get(seed.assetNo());
            Long applicantId = userId(seed.applicantName());
            if (device == null || applicantId == null) {
                log.warn("[Demo] 工单「{}」依赖的设备/员工缺失（device={} applicant={}），已跳过",
                        seed.noSuffix(), seed.assetNo(), seed.applicantName());
                continue;
            }
            User applicant = userService.getById(applicantId);
            Long departmentId = applicant == null ? null : applicant.getDepartmentId();
            if (departmentId == null) {
                log.warn("[Demo] 员工「{}」未分配部门，跳过其演示工单", seed.applicantName());
                continue;
            }
            Long handlerGroupId = handlerDepartmentId();
            if (handlerGroupId == null) {
                log.warn("[Demo] 系统未配置最终处理部门（IT运维组），跳过「{}」的演示工单",
                        seed.applicantName());
                continue;
            }
            Long handlerId = userId(seed.handlerName());

            Order order = new Order();
            order.setOrderNo("BO" + datePart + "-" + seed.noSuffix());
            // ：显式写入工单类型（数据库默认值也是 BORROW，但显式赋值能让演示数据自解释）
            order.setOrderType(OrderType.BORROW.name());
            order.setDeviceId(device.getId());
            order.setApplicantId(applicantId);
            order.setDepartmentId(departmentId);
            order.setUseType(seed.useType());
            order.setReason(seed.reason());
            // 期望归还日期用相对天数，避免演示数据随时间推移变成「早已过期」
            order.setExpectedReturnDate(seed.returnDays() == null ? null : LocalDate.now().plusDays(seed.returnDays()));
            order.setStatus(seed.status());
            order.setHandlerDepartmentId(handlerGroupId);
            order.setActualFinalHandlerId(handlerId);
            order.setBorrowTimeout(false);
            order.setAutoExtendCount(0);
            if (seed.returnDays() != null) {
                order.setPlannedEndTime(LocalDateTime.of(LocalDate.now().plusDays(seed.returnDays()), END_OF_DAY));
            }
            if (seed.deliveredDaysAgo() != null) {
                order.setDeliveredAt(now.minusDays(seed.deliveredDaysAgo()));
                order.setDeliveredBy(handlerId);
            }
            // 演示数据时间线必须自洽：工单创建时间要<b>早于</b>其审批 / 交付动作时间。
            // 原实现只回拨了审批动作时间（now-6h）却没回拨创建时间（依赖 DB 默认 CURRENT_TIMESTAMP=now），
            // 于是「审批时间 < 创建时间」，报表算出「平均审批耗时 -6 小时」的负数（ 评审发现）。
            order.setCreatedAt(now.minusDays(2));
            orderMapper.insert(order);

            for (OrderNodeSeed nodeSeed : seed.nodes()) {
                Long approverId = userId(nodeSeed.approverName());
                if (approverId == null) {
                    continue;
                }
                OrderApprovalNode node = new OrderApprovalNode();
                node.setOrderId(order.getId());
                node.setStepOrder(nodeSeed.stepOrder());
                node.setApproverId(approverId);
                node.setSignType(nodeSeed.signType());
                node.setStatus(nodeSeed.status());
                node.setSuperBackup(false);
                node.setFallback(false);
                if (ApprovalNodeStatus.APPROVED.name().equals(nodeSeed.status())
                        || ApprovalNodeStatus.REJECTED.name().equals(nodeSeed.status())) {
                    // 审批动作时间 = 提交后 2 小时（必须晚于上面写入的 created_at，见其注释）
                    node.setActionTime(now.minusDays(2).plusHours(2));
                    node.setActionComment("演示数据：审批通过");
                }
                orderApprovalNodeMapper.insert(node);
            }

            // 设备状态与工单状态保持一致（占用中的工单决定设备所处阶段）
            OrderStatus orderStatus = OrderStatus.of(seed.status());
            String deviceStatus = orderStatus == OrderStatus.BORROWED
                    ? DeviceStatus.IN_USE.name() : DeviceStatus.IN_APPROVAL.name();
            deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                    .eq(Device::getId, device.getId())
                    .set(Device::getStatus, deviceStatus));
            created++;
        }

        log.info("[Demo] 工单演示数据初始化完成：{} 笔（含使用中 / 待交付 / 审批中各一笔，"
                        + "其中待交付工单含一个被「自审批跳过」的节点）", created);
    }

    // ------------------------------------------------------------------
    //  自定义申请类型演示数据（表单模板 + 申请类型）
    // ------------------------------------------------------------------

    /**
     *  演示数据（）：1 个示例表单模板「采购申请」（v1 已发布）+ 1 个<b>启用</b>的申请类型。
     *
     * <p>为什么直插 Mapper 而不复用 Service：Service 的写入路径要 {@code SecurityUtils.getCurrentUserId()}
     * （记录 created_by / published_by），而本初始化器在容器启动、尚无 HTTP 安全上下文时执行，
     * 走 Service 会因取不到当前用户而抛异常、导致启动失败。演示数据本质是「以系统身份写入」，
     * 直插更贴切，也避免为一个演示数据去伪造安全上下文。
     *
     * <p>幂等：仅当「表单模板」与「申请类型」两张表都为空时才写入，
     * 避免污染任何已手工配置过表单 / 类型的存量环境。
     *
     * <p>schema 先过一遍 {@link FormSchemaValidator#validateAndNormalize} —— 让演示数据与
     * 「真实发布」产出的存档<b>逐字一致</b>（例如布局类字段会被归一化掉 key），
     * 同时也顺带在启动期校验这份手写 schema 确实合法。
     */
    private void ensureCustomApplyDemoData() {
        long existingTemplates = countOf(formTemplateMapper.selectCount(null));
        long existingTypes = countOf(applyTypeMapper.selectCount(null));
        if (existingTemplates > 0 || existingTypes > 0) {
            log.info("[Demo] 已存在 {} 个表单模板 / {} 个申请类型，跳过自定义申请演示数据初始化",
                    existingTemplates, existingTypes);
            return;
        }

        // 创建人取演示管理员；取不到则留空（form_template.created_by 可空）
        Long creatorId = userId("刘洋");

        // 1) 表单模板 + 已发布版本 v1（发布后冻结，历史工单引用它）
        FormTemplate template = new FormTemplate();
        template.setTemplateName("采购申请");
        template.setDescription("办公用品、软件许可等采购申请（演示数据，覆盖文本 / 选择 / 数字 / 日期 / 附件 / 人员等字段）");
        template.setStatus(FormTemplateStatus.PUBLISHED.name());
        template.setCreatedBy(creatorId);
        formTemplateMapper.insert(template);

        FormSchema schema = buildPurchaseSchema();
        FormSchemaValidator.validateAndNormalize(schema);

        FormTemplateVersion version = new FormTemplateVersion();
        version.setTemplateId(template.getId());
        version.setVersionNo(1);
        version.setSchemaJson(FormSchemaCodec.write(schema));
        version.setPublishedAt(LocalDateTime.now());
        version.setPublishedBy(creatorId);
        formTemplateVersionMapper.insert(version);

        // 2) 申请类型：指向上面的已发布版本，走分组审批，编号前缀 CG
        ApplyType applyType = new ApplyType();
        applyType.setTypeCode("PURCHASE");
        applyType.setTypeName("采购申请");
        applyType.setIcon("ShoppingCart");
        applyType.setDescription("办公用品、软件许可等采购申请");
        applyType.setSortOrder(1);
        applyType.setStatus(ApplyTypeStatus.ENABLED.name());
        applyType.setFormTemplateVersionId(version.getId());
        applyType.setOrderPrefix("CG");
        applyType.setApprovalMode(ApprovalMode.GROUP.name());
        applyType.setSubmitPermissionType(SubmitPermissionType.ROLE.name());
        // 演示：普通员工与管理员均可提交（提交权限值存「角色码」的 JSON 数组）
        applyType.setSubmitPermissionValue(FormSchemaCodec.write(List.of(RoleCode.USER, RoleCode.ADMIN)));
        applyType.setCreatedBy(creatorId);
        applyTypeMapper.insert(applyType);

        log.info("[Demo] 自定义申请演示数据初始化完成：表单模板「{}」(v1 已发布) + 申请类型「{}」({})，"
                        + "提交权限=角色[{}, {}]，审批方式={}",
                template.getTemplateName(), applyType.getTypeName(), applyType.getTypeCode(),
                RoleCode.USER, RoleCode.ADMIN, applyType.getApprovalMode());
    }

    /**
     * 「采购申请」表单定义（演示）。
     *
     * <p>刻意覆盖多种字段类型，便于一次走查「设计器渲染 / 动态表单渲染 / 服务端数据校验」三条链路：
     * 说明文字 → 单行文本 → 单选下拉 → 数字 → 日期 → 多行文本 → 附件 → 人员选择。
     */
    private FormSchema buildPurchaseSchema() {
        FormSchema schema = new FormSchema();

        schema.getFields().add(description("请如实填写采购需求，提交后由所在部门负责人审批；"
                + "金额较大的申请请附上报价单。"));

        FormField title = new FormField();
        title.setKey("purchaseTitle");
        title.setLabel("申请标题");
        title.setType("TEXT");
        title.setRequired(true);
        title.setPlaceholder("如：研发部 3 月办公用品采购");
        title.setWidth(1);
        title.setMaxLength(60);
        schema.getFields().add(title);

        FormField category = new FormField();
        category.setKey("purchaseCategory");
        category.setLabel("采购类别");
        category.setType("SELECT");
        category.setRequired(true);
        category.setWidth(2);
        category.setOptions(List.of(
                option("OFFICE", "办公用品"),
                option("DEVICE", "电子设备"),
                option("SOFTWARE", "软件许可"),
                option("OTHER", "其他")));
        schema.getFields().add(category);

        FormField amount = new FormField();
        amount.setKey("amount");
        amount.setLabel("预计金额");
        amount.setType("NUMBER");
        amount.setRequired(true);
        amount.setWidth(2);
        amount.setMin(0.0);
        amount.setPrecision(2);
        amount.setUnit("元");
        schema.getFields().add(amount);

        FormField expectedDate = new FormField();
        expectedDate.setKey("expectedDate");
        expectedDate.setLabel("期望到货日期");
        expectedDate.setType("DATE");
        expectedDate.setRequired(true);
        expectedDate.setWidth(2);
        expectedDate.setDateLimit("NOT_BEFORE_TODAY");
        schema.getFields().add(expectedDate);

        FormField reason = new FormField();
        reason.setKey("reason");
        reason.setLabel("采购事由");
        reason.setType("TEXTAREA");
        reason.setRequired(true);
        reason.setWidth(1);
        reason.setMaxLength(500);
        reason.setPlaceholder("请说明采购背景与用途");
        schema.getFields().add(reason);

        FormField attachments = new FormField();
        attachments.setKey("attachments");
        attachments.setLabel("附件（报价单 / 清单）");
        attachments.setType("FILE");
        attachments.setRequired(false);
        attachments.setWidth(1);
        attachments.setMaxCount(5);
        attachments.setMaxSizeMb(10);
        schema.getFields().add(attachments);

        FormField receiver = new FormField();
        receiver.setKey("receiver");
        receiver.setLabel("指定接收人");
        receiver.setType("USER");
        receiver.setRequired(false);
        receiver.setWidth(2);
        receiver.setHelp("物品到货后的签收人，可留空由行政统一分配");
        schema.getFields().add(receiver);

        return schema;
    }

    /** 布局类「说明文字」字段（key 由发布校验归一化清空） */
    private FormField description(String content) {
        FormField field = new FormField();
        field.setLabel("说明");
        field.setType("DESCRIPTION");
        field.setRequired(false);
        field.setWidth(1);
        field.setContent(content);
        return field;
    }

    private FormOption option(String value, String label) {
        FormOption option = new FormOption();
        option.setValue(value);
        option.setLabel(label);
        return option;
    }

    // ------------------------------------------------------------------
    //  演示数据（直属领导 + 含四类新节点的审批流程）
    // ------------------------------------------------------------------

    /** 演示员工直属领导种子：员工姓名 → 直属领导姓名 */
    private record LeaderSeed(String employeeName, String leaderName) {
    }

    private static final List<LeaderSeed> DEMO_LEADERS = List.of(
            new LeaderSeed("张伟", "刘洋"),
            new LeaderSeed("李娜", "刘洋"),
            new LeaderSeed("王强", "赵敏"),
            new LeaderSeed("陈晨", "赵敏"));

    /**
     * 演示员工的直属领导。
     *
     * <p><b>幂等且不覆盖手工配置</b>：仅当 {@code leader_id} 仍为空时才写入。
     * 演示数据不该每次启动都把管理员在界面上配好的组织关系冲掉 ——
     * 这正是 {@code isNull(leader_id)} 这个条件存在的意义，而不是多余的防御。
     */
    private void ensureDemoLeaders() {
        int updated = 0;
        for (LeaderSeed seed : DEMO_LEADERS) {
            Long employeeId = userId(seed.employeeName());
            Long leaderId = userId(seed.leaderName());
            if (employeeId == null || leaderId == null) {
                continue;
            }
            boolean changed = userService.update(Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, employeeId)
                    .isNull(User::getLeaderId)
                    .set(User::getLeaderId, leaderId));
            if (changed) {
                updated++;
            }
        }
        if (updated > 0) {
            log.info("[Demo] 演示员工直属领导初始化完成：{} 条（仅填补未配置的，不覆盖手工配置）", updated);
        }
    }

    /** 取某表单模板最新已发布版本（不存在时返回 null，让调用方决定跳过而不是启动失败） */
    private FormTemplateVersion latestVersionOf(String templateName) {
        FormTemplate template = formTemplateMapper.selectOne(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getTemplateName, templateName)
                .orderByAsc(FormTemplate::getId)
                .last("LIMIT 1"));
        if (template == null) {
            return null;
        }
        return formTemplateVersionMapper.selectOne(Wrappers.<FormTemplateVersion>lambdaQuery()
                .eq(FormTemplateVersion::getTemplateId, template.getId())
                .orderByDesc(FormTemplateVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     *  演示审批流程 + 绑定申请类型（用户 2026-09-21 验收要求）。
     *
     * <p>一个流程里同时出现<b>四类新节点</b>，便于一次验收覆盖四个功能：
     * <pre>
     *   n1  部门负责人审批（指定人员 刘洋，或签，限时 24h）
     *    ↓
     *   cc1 抄送管理员（角色 admin）           ← 提交即发消息
     *    ↓
     *   c1  金额判断：amount &gt; 5000 → n2 ；else → n3
     *    ↓(n2)
     *   n2  直属领导审批（LEADER，或签，限时 48h）
     *    ↓
     *   n3  上一节点指定的审批人（PREV_ASSIGN，2 人，或签）
     *    ↓
     *   end
     * </pre>
     *
     * <p>刻意保留<b>分支汇合</b>（n2 与 c1 的 else 分支都指向 n3）：与  的
     * 「汇合渲染成引用块」互为验证 —— 汇合如果实现有误，这里的 stepOrder 会重复或漏节点。
     *
     * <p>幂等：仅当 {@code approval_flow} 为空时写入，绝不覆盖已配置过的流程。
     * 定义在写库前先过一遍 {@link FlowDefinitionValidator} —— 演示数据也必须是一份
     * 真正能通过发布校验的定义，否则会留下「看得见、提交就报错」的坏样例。
     */
    private void ensureFlowDemoData() {
        Long existingFlows = approvalFlowMapper.selectCount(null);
        if (existingFlows != null && existingFlows > 0) {
            log.info("[Demo] 已存在 {} 个审批流程，跳过  流程演示数据初始化", existingFlows);
            return;
        }
        // 条件分支必须引用真实存在的表单字段，因此依赖  的「采购申请」表单
        FormTemplateVersion version = latestVersionOf("采购申请");
        if (version == null) {
            log.warn("[Demo] 未找到「采购申请」表单模板，跳过  流程演示数据初始化"
                    + "（条件分支需要引用表单字段）");
            return;
        }
        FormSchema schema = FormSchemaCodec.readSchema(version.getSchemaJson());
        Long creatorId = userId("刘洋");
        Long approverId = creatorId;

        FlowDefinition definition = buildDemoFlow(approverId);
        FlowDefinitionValidator.validate(definition, schema);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setFlowCode("DEMO_PURCHASE_FLOW");
        flow.setFlowName("采购流程（演示：嵌套条件 / 抄送 / 时限 / 直属领导 / 上一节点指定）");
        flow.setDescription("部门负责人审批(限时24h) → 抄送管理员 → 按「金额 / 类别」嵌套条件分支 → "
                + "直属领导审批(限时48h) → 上一节点指定2人审批 → 结束");
        flow.setStatus(ApprovalFlowStatus.PUBLISHED.name());
        flow.setCreatedBy(creatorId);
        approvalFlowMapper.insert(flow);

        ApprovalFlowVersion flowVersion = new ApprovalFlowVersion();
        flowVersion.setFlowId(flow.getId());
        flowVersion.setVersionNo(1);
        flowVersion.setDefinitionJson(FlowDefinitionCodec.write(definition));
        flowVersion.setNodeCount(FlowDefinitionValidator.countApprovalNodes(definition));
        flowVersion.setPublishedAt(LocalDateTime.now());
        flowVersion.setPublishedBy(creatorId);
        approvalFlowVersionMapper.insert(flowVersion);

        ApplyType applyType = new ApplyType();
        applyType.setTypeCode("PURCHASE_FLOW");
        applyType.setTypeName("采购申请（自定义流程）");
        applyType.setIcon("Share");
        applyType.setDescription("演示自定义审批流程：抄送、审批时限、直属领导审批、上一节点指定审批人");
        applyType.setSortOrder(2);
        applyType.setStatus(ApplyTypeStatus.ENABLED.name());
        applyType.setFormTemplateVersionId(version.getId());
        applyType.setOrderPrefix("CGLC");
        applyType.setApprovalMode(ApprovalMode.FLOW.name());
        applyType.setApprovalFlowVersionId(flowVersion.getId());
        applyType.setSubmitPermissionType(SubmitPermissionType.ROLE.name());
        applyType.setSubmitPermissionValue(FormSchemaCodec.write(List.of(RoleCode.USER, RoleCode.ADMIN)));
        applyType.setCreatedBy(creatorId);
        applyTypeMapper.insert(applyType);

        log.info("[Demo]  流程演示数据初始化完成：流程「{}」v1（已发布，{} 个审批节点 + 1 个抄送节点）"
                        + " + 申请类型「{}」({})，审批方式={}",
                flow.getFlowName(), flowVersion.getNodeCount(), applyType.getTypeName(),
                applyType.getTypeCode(), applyType.getApprovalMode());
    }

    /**
     *  演示流程定义（结构见 {@link #ensureFlowDemoData} 的注释图）。
     *
     * @param approverId n1「指定人员」规则指向的 user_id；为 null 时不加该规则
     *                   （宁可少一条规则也不要写一个指向 null 的 id —— 那会让提交时解析出空集，
     *                   工单静默落到超管兜底，看起来像功能坏了）
     */
    private FlowDefinition buildDemoFlow(Long approverId) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");

        FlowNode n1 = approvalNode("n1", "部门负责人审批", SignType.ANY_SIGN, 24, "cc1");
        if (approverId != null) {
            ApproverRule specific = new ApproverRule();
            specific.setType(ApproverRuleType.SPECIFIC_USER.name());
            specific.setUserIds(List.of(approverId));
            n1.getApproverRules().add(specific);
        }
        definition.getNodes().add(n1);

        // 抄送节点：不配 signType / 分支 / 时限（发布校验会拒），只需规则 + next
        FlowNode cc1 = new FlowNode();
        cc1.setKey("cc1");
        cc1.setType(FlowNodeType.CC.name());
        cc1.setName("抄送管理员");
        cc1.setNext("c1");
        ApproverRule ccRule = new ApproverRule();
        ccRule.setType(ApproverRuleType.ROLE.name());
        ccRule.setRoleCode(RoleCode.ADMIN);
        cc1.getApproverRules().add(ccRule);
        definition.getNodes().add(cc1);

        FlowNode c1 = new FlowNode();
        c1.setKey("c1");
        c1.setType(FlowNodeType.CONDITION.name());
        c1.setName("金额判断");
        c1.getBranches().add(flowBranch("b1", "金额大于 5000", false, "amount",
                FlowOperator.GT, 5000, "n2"));
        // M3-A：嵌套条件分支（组中组，恰好用满 3 层上限）。
        //
        // <p>刻意**排在第一支之后**：金额 > 5000 的输入仍然走 b1，既有回归数据
        // （8000 / 办公用品 / 「W2回归采购」）的命中分支与路径逐字节不变 ——
        // 演示数据可以变，回归口径不能被动摇。
        //
        // <p>这一支自身也是**可达**的，不是摆设：金额不超过 5000、但类别是电子设备
        // 或标题带「紧急」时同样需要复核。
        FlowBranch nested = new FlowBranch();
        nested.setKey("b2");
        nested.setName("金额不超过 5000 且（电子设备 或 标题含「紧急」）");
        nested.setElseBranch(false);
        nested.setNext("n2");
        nested.setCondition(demoCondition("AND",
                demoRule("amount", FlowOperator.LTE, 5000),
                demoGroup("OR",
                        demoRule("purchaseCategory", FlowOperator.EQ, "DEVICE"),
                        demoGroup("AND", demoRule("purchaseTitle", FlowOperator.CONTAINS, "紧急")))));
        c1.getBranches().add(nested);
        c1.getBranches().add(flowBranch("b3", "其它情况", true, null, null, null, "n3"));
        definition.getNodes().add(c1);

        FlowNode n2 = approvalNode("n2", "直属领导审批", SignType.ANY_SIGN, 48, "n3");
        ApproverRule leader = new ApproverRule();
        leader.setType(ApproverRuleType.LEADER.name());
        n2.getApproverRules().add(leader);
        definition.getNodes().add(n2);

        FlowNode n3 = approvalNode("n3", "上一节点指定的审批人", SignType.ANY_SIGN, null, "end");
        ApproverRule prevAssign = new ApproverRule();
        prevAssign.setType(ApproverRuleType.PREV_ASSIGN.name());
        prevAssign.setAssignCount(2);
        n3.getApproverRules().add(prevAssign);
        definition.getNodes().add(n3);

        FlowNode end = new FlowNode();
        end.setKey("end");
        end.setType(FlowNodeType.END.name());
        end.setName("结束");
        definition.getNodes().add(end);

        return definition;
    }

    /** 审批节点（或签 / 会签 + 可选时限 + 必填 next） */
    private FlowNode approvalNode(String key, String name, String signType, Integer timeLimitHours, String next) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName(name);
        node.setSignType(signType);
        node.setTimeLimitHours(timeLimitHours);
        node.setNext(next);
        return node;
    }

    /**
     * 条件分支。{@code elseBranch=true} 时条件与运算符传 null（默认出口不写条件）。
     *
     * <p>非默认分支的运算符必须可比较（{@code GT} 等），否则发布校验会拒 ——
     * 演示数据同样要过校验，不能靠「反正是 demo」绕过。
     */
    private FlowBranch flowBranch(String key, String name, boolean elseBranch, String field,
                                  FlowOperator operator, Object value, String next) {
        FlowBranch branch = new FlowBranch();
        branch.setKey(key);
        branch.setName(name);
        branch.setElseBranch(elseBranch);
        branch.setNext(next);
        if (!elseBranch) {
            ConditionRule rule = new ConditionRule();
            rule.setField(field);
            rule.setOp(operator == null ? null : operator.name());
            rule.setValue(value);
            FlowCondition condition = new FlowCondition();
            condition.setLogic(FlowCondition.LOGIC_AND);
            condition.setRules(new ArrayList<>(List.of(rule)));
            branch.setCondition(condition);
        }
        return branch;
    }

    private long countOf(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 单条条件规则（ · M3-A）。
     *
     * <p>与 {@link #flowBranch} 拆开是因为规则现在也能直接放进嵌套组里 ——
     * 复用同一个构造点比在组里再手写一遍 {@code new ConditionRule()} 更不容易写歪。
     */
    private ConditionRule demoRule(String field, FlowOperator operator, Object value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOp(operator == null ? null : operator.name());
        rule.setValue(value);
        return rule;
    }

    /**
     * 嵌套条件组信封（ · M3-A）。
     *
     * <p>只写 {@code kind} 与 {@code condition}：组信封上留 field/op/value 会被发布校验
     * 以「不应携带字段 / 运算符 / 比较值」拒掉 —— 演示数据也必须是一份真能发布的定义。
     */
    private ConditionRule demoGroup(String logic, ConditionRule... rules) {
        return ConditionRule.group(demoCondition(logic, rules));
    }

    /** 条件组（逻辑 + 子节点） */
    private FlowCondition demoCondition(String logic, ConditionRule... rules) {
        FlowCondition condition = new FlowCondition();
        condition.setLogic(logic);
        condition.setRules(new ArrayList<>(List.of(rules)));
        return condition;
    }

    /**
     * 按「登录名 → 姓名」两级查 user_id；不存在返回 null
     * （演示数据容错，不抛异常中断启动）。
     *
     * <p> ：种子数据里既写数字登录名（{@link #DEMO_USERS}），
     * 也写中文姓名（工单申请人、审批人、直属领导）—— 两处都通过本方法解析，
     * 于是「按 login 查」与「按姓名查」不再共用一条 {@code getByUsername} 路径，
     * 避免姓名恰好等于某个登录名时静默取到错误的人。
     *
     * <p>同名（重名）时取 id 最小的一条：演示数据里 6 个姓名在库中唯一，
     * 这只是「万一有人重名也不至于随机挑一个」的确定性兜底。
     */
    private Long userId(String account) {
        if (!StringUtils.hasText(account)) {
            return null;
        }
        User byUsername = userService.getByUsername(account);
        if (byUsername != null) {
            return byUsername.getId();
        }
        return userService.listByRealName(account).stream()
                .map(User::getId)
                .min(Long::compareTo)
                .orElse(null);
    }
}
