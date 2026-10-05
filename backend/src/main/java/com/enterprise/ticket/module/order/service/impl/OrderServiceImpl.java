package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.constant.ReturnCondition;
import com.enterprise.ticket.common.constant.ReturnTrigger;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.ScanAction;
import com.enterprise.ticket.common.constant.ScanMatchType;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormDataValidator;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.BorrowFieldCatalog;
import com.enterprise.ticket.common.flow.BorrowFlowCatalog;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.service.ApplyTypeService;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.service.DeviceFaultService;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVersionVO;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.CustomOrderRequest;
import com.enterprise.ticket.module.order.dto.OrderAllQuery;
import com.enterprise.ticket.module.order.dto.OrderApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderConfirmReturnRequest;
import com.enterprise.ticket.module.order.dto.OrderCreateRequest;
import com.enterprise.ticket.module.order.dto.OrderReturnRequest;
import com.enterprise.ticket.module.order.dto.vo.AssignCandidateVO;
import com.enterprise.ticket.module.order.dto.vo.BorrowFlowPreviewVO;
import com.enterprise.ticket.module.order.dto.vo.DeviceOptionVO;
import com.enterprise.ticket.module.order.dto.vo.OrderDetailVO;
import com.enterprise.ticket.module.order.dto.vo.OrderFormDataVO;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.dto.vo.ScanLookupVO;
import com.enterprise.ticket.module.order.dto.vo.ScanOrderBriefVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.support.BorrowFlowPreviewer;
import com.enterprise.ticket.module.order.support.FlowInputResolver;
import com.enterprise.ticket.module.order.support.OrderApprovalNodeSupport;
import com.enterprise.ticket.module.order.support.OrderConcurrencyGuard;
import com.enterprise.ticket.module.order.support.OrderReferenceNames;
import com.enterprise.ticket.module.order.support.OrderViewAssembler;
import com.enterprise.ticket.module.order.support.PendingAssignSupport;
import com.enterprise.ticket.module.order.support.ScanCodeParser;
import com.enterprise.ticket.module.order.support.FlowNodeMaterializer;
import com.enterprise.ticket.module.order.support.FlowNodeMaterializer.LeaderNotice;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import com.enterprise.ticket.module.order.service.OrderExtendService;
import com.enterprise.ticket.module.order.service.OrderForceOperationService;
import com.enterprise.ticket.module.order.service.OrderService;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import com.enterprise.ticket.module.order.service.OrderUrgeService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 借用工单实现（ /  /  /  /  /  / ）
 *
 * <p><b>事务与并发</b>：提交、审批、交付、撤回均具备明确事务边界；
 * 对设备的每一次状态推进都带<b>状态前置条件</b>的条件 UPDATE，靠数据库行锁保证
 * 「两个用户同时抢同一台设备」时只有一个成功，绝不依赖前端按钮禁用。
 *
 * <p><b>快照</b>：提交瞬间把分组审批配置复制为 {@link OrderApprovalNode}，
 * 此后历史工单只读快照，后续改配置不影响在途与历史工单。
 *
 * <p><b>跨模块约定</b>：直接注入各模块 Mapper 而非 Service，避免「工单 ↔ 设备」形成循环依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 单页上限 */
    private static final long MAX_PAGE_SIZE = 200L;

    /** 可选设备返回上限 */
    private static final int MAX_DEVICE_OPTION_SIZE = 200;

    /** 工单编号时间部分格式 */
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 计划结束时间统一取当日 23:59:59（期望归还日期是「日」精度，避免当天即被判超时） */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    /** 站内消息里展示实际归还时间用的格式 */
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 审批待办子查询：我有一笔「位于当前步骤」的待审批节点。
     *
     * <p>必须限定 {@code step_order = MIN(PENDING 的 step_order)}，否则后续步骤的审批人
     * 会在前序步骤尚未通过时就提前看到待办（违反「按 step_order 从小到大依次执行」）。
     */
    private static final String PENDING_APPROVAL_IN_SQL =
            "SELECT n.order_id FROM order_approval_nodes n "
                    + "WHERE n.approver_id = %d AND n.status = 'PENDING' "
                    + "AND n.step_order = (SELECT MIN(n2.step_order) FROM order_approval_nodes n2 "
                    + "WHERE n2.order_id = n.order_id AND n2.status = 'PENDING')";

    /**
     * 「抄送我的」子查询：我被抄送的工单。
     *
     * <p>只认 {@code node_type = 'CC'} 的行，因此不会与「我是审批人」混淆
     * （审批行的 {@code node_type} 为 {@code APPROVAL} 或 NULL）。
     */
    private static final String CC_IN_SQL =
            "SELECT n.order_id FROM order_approval_nodes n "
                    + "WHERE n.node_type = 'CC' AND n.approver_id = %d";

    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper nodeMapper;
    private final DeviceMapper deviceMapper;
    private final DepartmentMapper departmentMapper;
    private final DepartmentManagerMapper departmentManagerMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 自定义申请出口
     *
     * <p>方向同样是单向：{@code ApplyTypeServiceImpl} 只依赖 apply_type / form / order 的
     * Mapper 与 {@code FormTemplateService}，不反向依赖本服务，因此不会形成循环依赖。
     * 本服务用它做「类型存在 + 已启用 + 我有提交权限」三项校验。
     */
    private final ApplyTypeService applyTypeService;

    /**
     * 表单模板出口：仅用于取「该申请类型绑定的已发布版本」的字段定义。
     *
     * <p>取 schema 而不是直接查库，是为了让「必须引用已发布版本」这条规则只有一处实现 ——
     * 若这里自己 selectById 再判断 publishedAt，规则就被复制到了第二处，
     * 将来放宽/收紧口径时极易漏改。
     */
    private final FormTemplateService formTemplateService;

    /** 动态表单数据校验器：按 schema 校验用户提交的值 */
    private final FormDataValidator formDataValidator;

    /** 工单自定义表单数据 */
    private final OrderFormDataMapper orderFormDataMapper;
    /**
     * 「权限申请审批通过 ⇒ 自动开通」。
     *
     * <p>刻意注入成独立服务而不是在本类里写授权逻辑：授权要读策略表、要写用户级授权表、
     * 要发消息，塞进本类会让这个已经很大的类再胖一圈，而它的职责是「工单流程」。
     */
    private final com.enterprise.ticket.module.permission.service.PermissionGrantOnApprovalService
            permissionGrantOnApprovalService;
    /**
     * 权限申请策略：判「申请里有没有高危项」。
     *
     * <p>⚠️ 高危判定**必须服务端按码重算**，绝不读前端传来的「是否高危」——
     * 表单值是用户输入，信任它等于让攻击者改一个字段就绕过超管那一级。
     */
    private final com.enterprise.ticket.module.permission.service.PermissionApplyPolicyService
            permissionApplyPolicyService;

    /**
     * 用户结果通知出口（P2 修复）：站内消息 + 邮件。
     *
     * <p>审批结果（通过 / 驳回）走它而不是裸的 {@code messageService}，是因为需求要求
     * 「申请结果通知加邮件」—— 只有站内消息时，用户不打开系统就永远不知道结果。
     * 邮件在事务提交后投递（见该服务），不影响本类的事务边界。
     */
    private final com.enterprise.ticket.module.message.service.UserNotificationService
            userNotificationService;

    /** 申请类型 Mapper：仅供列表批量取「申请类型名称」，不做业务判定 */

    /**
     * 审批流程出口：仅用于取「该版本已冻结的流程定义」。
     *
     * <p>与 {@code formTemplateService} 同样的理由：走服务契约而不是自己查
     * {@code approval_flow_version} 再判 {@code published_at}，让「必须是已发布版本」
     * 这条规则只有一处实现。方向单向（{@code ApprovalFlowServiceImpl} 只依赖
     * {@code ApplyTypeMapper}），不构成循环依赖。
     */
    private final ApprovalFlowService approvalFlowService;

    /**
     * 审批人规则解析：把流程节点上的「审批人来源」解析成真实 user_id。
     *
     * <p>与  的 {@code common/flow} 内核同源 —— 预览接口用的是同一个 resolver，
     * 因此「预览里能看到的人」与「提交后真正成为审批人的人」必然一致。
     */
    private final ApproverRuleResolver approverRuleResolver;

    /**
     * 站内消息出口
     *
     * <p>方向是「工单模块 → 消息模块」的单向依赖：消息模块只依赖 OrderMapper 而不依赖本服务，
     * 因此不会形成循环依赖。
     */
    private final MessageService messageService;

    /**
     * 延期子工单出口
     *
     * <p>方向同样是单向：{@code OrderExtendServiceImpl} 只依赖 order/user/message 的
     * Mapper 与 Service，不反向依赖本服务，因此不会形成循环依赖。
     * 本服务仅用它批量统计延期次数，供列表/详情判断「能否申请延期」。
     */
    private final OrderExtendService orderExtendService;

    /**
     * 设备故障出口
     *
     * <p>归还登记「故障」时由本服务调用它自动建档；故障模块不依赖本服务，无循环依赖。
     */
    private final DeviceFaultService deviceFaultService;

    /**
     * 工单转交出口
     *
     * <p>只用于「批量统计转交次数」与详情页装配转交历史；转交写操作在
     * {@code OrderTransferServiceImpl} 内闭环，本服务不参与。
     */
    private final OrderTransferService orderTransferService;

    /**
     * 催办出口
     *
     * <p>只用于「批量取催办状态」与详情页装配催办记录；催办写操作在
     * {@code OrderUrgeServiceImpl} 内闭环。冷却倒计时由本服务按审批节点比对后算出。
     */
    private final OrderUrgeService orderUrgeService;

    /**
     * 超管强制干预出口（）
     *
     * <p>只用于详情页装配「强制操作时间线」；写操作在
     * {@code OrderForceOperationServiceImpl} 内闭环，本服务不参与。
     */
    private final OrderForceOperationService orderForceOperationService;

    /**
     * 部门服务。
     *
     * <p>本服务只读它两件事：{@link DepartmentService#handlerDepartmentId()}（最终处理部门）
     * 与 {@link DepartmentService#managerIdsOf(Long)}（部门主管 = 一级审批人）。
     * 两者都是**组织关系的唯一事实源** —— 组织怎么建、主管怎么设全在「组织与人员」页里，
     * 工单侧不复制一份判定，否则「改了主管但工单还报给旧人」这类漂移迟早发生。
     */
    private final DepartmentService departmentService;

    /**
     * 借用路径预览器。
     *
     * <p>改造前这件事挂在 bizgroup 模块的 {@code BizGroupService} 上；业务分组退役后
     * 搬到 order 侧 —— 因为「这笔借用会走哪几级」本质是工单问题，不是组织问题。
     */
    private final BorrowFlowPreviewer borrowFlowPreviewer;

    /**
     * 流程节点激活服务。
     *
     * <p>它是「运行期条件」的唯一入口：重算激活态、作废未完成节点、超时加签、
     * 节点改派都收敛在它内部。本服务只负责在正确的时机调用它，不自行改写节点状态 ——
     * 否则会出现"第二个改节点的代码路径"，与重算逻辑基于不同状态做决策。
     */
    private final FlowActivationService flowActivationService;

    /**
     * 流程节点物化器。
     *
     * <p>在 M2 之前"把求值结果变成节点行"只有提交一个调用点；引入运行期激活后变成两个
     * （提交 + 运行期激活）。两处必须产出完全一致的行，因此把这段逻辑抽成一个组件共用，
     * 而不是在激活路径里复制一份。
     */
    private final FlowNodeMaterializer flowNodeMaterializer;

    /**
     * 条件求值输入重建器。
     *
     * <p>运行期重算必须用「与提交时逐字一致」的条件输入，否则同一条件在提交时与重算时
     * 可能得到不同结论。本服务在提交时用请求入参构造输入，重算时则由该组件从已落库的
     * 业务字段反向重建 —— 保证"唯一的真相"仍在业务表里。
     */
    private final FlowInputResolver flowInputResolver;
    /** W4-A2：工单引用数据的名称 / 字典解析（原先散在本类的若干私有方法） */
    private final OrderReferenceNames referenceNames;
    /** W4-A2：列表 / 详情的视图装配（原先同为散在本类的私有方法群） */
    private final OrderViewAssembler viewAssembler;
    /**
     * 员工选项：「上一节点指定审批人」的候选名单。
     *
     * <p>注入 {@code UserService} 而不是直接查 {@code userMapper}：候选名单需要
     * 「在职启用」+「部门名」两项派生，两处各写一遍必然漂移。
     */
    private final UserService userService;

    // ------------------------------------------------------------------
    // 申请页辅助
    // ------------------------------------------------------------------

    @Override
    public List<DeviceOptionVO> listSelectableDevices(String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_DEVICE_OPTION_SIZE);
        String trimmed = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LocalDateTime threshold = LocalDateTime.now()
                .minusMinutes(systemConfigService.lockTimeoutMinutes());

        List<Device> devices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                // 可申请 = 当前可用，或「临时锁已超时」可直接接管（：超时视为已释放）
                .and(w -> w.eq(Device::getStatus, DeviceStatus.AVAILABLE.name())
                        .or(o -> o.eq(Device::getStatus, DeviceStatus.LOCKED.name())
                                .lt(Device::getLockedAt, threshold)))
                .and(trimmed != null, w -> w.like(Device::getDeviceName, trimmed)
                        .or().like(Device::getAssetNo, trimmed))
                .orderByAsc(Device::getId)
                .last("LIMIT " + safeLimit));

        Map<Long, String> categoryNames = categoryNameMap();
        return devices.stream().map(device -> {
            DeviceOptionVO vo = new DeviceOptionVO();
            vo.setId(device.getId());
            vo.setDeviceName(device.getDeviceName());
            vo.setAssetNo(device.getAssetNo());
            vo.setPrimaryCategoryName(categoryNames.get(device.getPrimaryCategoryId()));
            vo.setSecondaryCategoryName(device.getSecondaryCategoryId() == null
                    ? null : categoryNames.get(device.getSecondaryCategoryId()));
            vo.setBrand(device.getBrand());
            vo.setModel(device.getModel());
            vo.setStorageLocation(device.getStorageLocation());
            vo.setStatus(device.getStatus());
            vo.setStatusLabel(DeviceStatus.labelOf(device.getStatus()));
            return vo;
        }).toList();
    }

    @Override
    public ScanLookupVO scanLookup(String code) {
        Long currentUserId = requireCurrentUserId();
        ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse(code);

        ScanLookupVO vo = new ScanLookupVO();
        vo.setCode(code == null ? null : code.trim());

        if (!parsed.hasCode()) {
            return unidentified(vo, "没有读到内容，请重新扫描，或手动输入资产编号 / 工单号。");
        }

        String target = parsed.code();
        String hint = parsed.hint();
        boolean hintAsset = ScanMatchType.ASSET_NO.equals(hint);
        boolean hintOrder = ScanMatchType.ORDER_NO.equals(hint);
        // 只有带明确前缀时才单查一张表；纯编号或通用外壳（如 CODE:xxx）两张表都试 ——
        // 资产编号与工单号都是自由文本，除前缀外没有可靠形状可以区分。
        boolean tryOrder = hintOrder || (!hintAsset && !hintOrder);
        boolean tryAsset = hintAsset || (!hintAsset && !hintOrder);

        if (tryOrder) {
            Order byNo = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getOrderNo, target)
                    .last("LIMIT 1"));
            if (byNo != null) {
                return describeOrder(vo, byNo, currentUserId);
            }
        }

        if (!tryAsset) {
            return unidentified(vo, "没有找到与「" + target + "」匹配的设备或工单。");
        }

        Device device = deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                .eq(Device::getAssetNo, target)
                .last("LIMIT 1"));
        if (device == null) {
            return unidentified(vo, "没有找到资产编号为「" + target + "」的设备。");
        }
        return describeDevice(vo, device, currentUserId);
    }

    /**
     * 扫到的是一张工单（工单号）。
     *
     * <p>刻意<b>不自动触发任何写操作</b>：扫单据大概率只是想看进度；
     * 若顺手把归还做了，用户会在没打算归还的时候把设备交出去。
     */
    private ScanLookupVO describeOrder(ScanLookupVO vo, Order order, Long currentUserId) {
        vo.setMatched(true);
        vo.setMatchType(ScanMatchType.ORDER_NO);

        if (!canViewOrder(order, currentUserId, currentRole())) {
            // 无权时**连摘要都不返回**：工单号是用户自己扫出来的，但由它反查到的
            // 设备名 / 资产编号 / 申请人姓名不是 —— 先塞进 VO 再改 action，等于把
            // 无权信息随响应一起发了出去（前端不展示 ≠ 客户端拿不到）。
            vo.setAction(ScanAction.UNAVAILABLE);
            vo.setActionLabel("无权查看该工单");
            vo.setReason("该工单不是你发起或经办的，无法查看。");
            return vo;
        }

        Device device = order.getDeviceId() == null ? null : deviceMapper.selectById(order.getDeviceId());
        vo.setDevice(deviceOptionOf(device));
        vo.setOrder(briefOf(order, device, currentUserId));
        vo.setAction(ScanAction.VIEW);
        vo.setActionLabel("已找到工单 " + order.getOrderNo() + "，可查看详情。");
        return vo;
    }

    /**
     * 扫到的是一台设备（资产编号）—— 这里才是「借还」的真正分流点。
     *
     * <p>判定顺序<b>不可颠倒</b>：先看「我是否正在借这台」，再看「这台好不好借」。
     * 反过来会出错 —— 正在被自己借用的设备状态是 {@code IN_USE}（{@code isApplicable=false}），
     * 先判可借性会把「该还了」误报成「不可借用」，用户站在设备前却被告知借不了。
     */
    private ScanLookupVO describeDevice(ScanLookupVO vo, Device device, Long currentUserId) {
        vo.setMatched(true);
        vo.setMatchType(ScanMatchType.ASSET_NO);
        vo.setDevice(deviceOptionOf(device));

        Order mine = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplicantId, currentUserId)
                .eq(Order::getDeviceId, device.getId())
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .orderByDesc(Order::getId)
                .last("LIMIT 1"));
        if (mine != null) {
            vo.setOrder(briefOf(mine, device, currentUserId));
            vo.setAction(ScanAction.RETURN);
            vo.setActionLabel("你正在借用「" + device.getDeviceName() + "」，可发起归还。");
            return vo;
        }

        if (isBorrowable(device)) {
            vo.setAction(ScanAction.BORROW);
            vo.setActionLabel("「" + device.getDeviceName() + "」当前可用，可发起借用申请。");
            return vo;
        }

        vo.setAction(ScanAction.UNAVAILABLE);
        vo.setActionLabel("「" + device.getDeviceName() + "」当前不可借用");
        vo.setReason(unavailableReason(device));
        return vo;
    }

    /** 没识别到目标：统一把三个动作字段置成「无」，并给出可操作的原因 */
    private ScanLookupVO unidentified(ScanLookupVO vo, String reason) {
        vo.setMatched(false);
        vo.setMatchType(ScanMatchType.NONE);
        vo.setAction(ScanAction.NONE);
        vo.setActionLabel("未识别到设备或工单");
        vo.setReason(reason);
        return vo;
    }

    /**
     * 设备是否可被新建借用申请。
     *
     * <p>与 {@code listSelectableDevices} 共用同一口径：{@code DeviceStatus.isApplicable()}
     * 表达「 表格可否被申请」，再对 {@code LOCKED} 加一条超时可接管的特例
     * （：锁超时视为已释放）。<b>不要</b>在这里另写一份状态名白名单，
     * 否则新增设备状态时两条口径必然漂移。
     */
    private boolean isBorrowable(Device device) {
        DeviceStatus status = DeviceStatus.of(device.getStatus());
        if (status == null) {
            return false;
        }
        if (status.isApplicable()) {
            return true;
        }
        return status == DeviceStatus.LOCKED
                && device.getLockedAt() != null
                && device.getLockedAt().isBefore(LocalDateTime.now()
                        .minusMinutes(systemConfigService.lockTimeoutMinutes()));
    }

    /**
     * 把「为什么不能借」翻译成用户看得懂、且知道下一步做什么的一句话。
     *
     * <p>用穷尽 switch 而非 if 链：新增设备状态时编译器会直接报错，
     * 逼着人回来补文案，而不是默默落到 default 上给出一句含糊的提示。
     */
    private String unavailableReason(Device device) {
        DeviceStatus status = DeviceStatus.of(device.getStatus());
        if (status == null) {
            return "该设备状态异常（" + device.getStatus() + "），请联系管理员确认。";
        }
        return switch (status) {
            case IN_USE -> "该设备正在被他人使用中，需等其归还后才能申请。";
            case IN_APPROVAL -> "该设备正处于借用审批中，暂不可申请。";
            case LOCKED -> "该设备刚被他人选中（正在填写申请），请稍后再试。";
            case MAINTENANCE -> "该设备维修中，暂不可借用。";
            case LOST -> "该设备已标记为丢失，不可借用。请联系管理员确认。";
            case SCRAPPED -> "该设备已报废，不可借用。";
            case AVAILABLE -> "该设备当前可用，但锁定信息异常，请稍后重试。";
        };
    }

    /** 设备 → 申请页同款选项 VO（与 listSelectableDevices 的字段映射一致，便于前端复用类型） */
    private DeviceOptionVO deviceOptionOf(Device device) {
        if (device == null) {
            return null;
        }
        Map<Long, String> categoryNames = categoryNameMap();
        DeviceOptionVO vo = new DeviceOptionVO();
        vo.setId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setAssetNo(device.getAssetNo());
        vo.setPrimaryCategoryName(categoryNames.get(device.getPrimaryCategoryId()));
        vo.setSecondaryCategoryName(device.getSecondaryCategoryId() == null
                ? null : categoryNames.get(device.getSecondaryCategoryId()));
        vo.setBrand(device.getBrand());
        vo.setModel(device.getModel());
        vo.setStorageLocation(device.getStorageLocation());
        vo.setStatus(device.getStatus());
        vo.setStatusLabel(DeviceStatus.labelOf(device.getStatus()));
        return vo;
    }

    /** 工单 → 扫码用最小摘要（归还判定与列表页同口径：本人申请 且 状态=使用中） */
    private ScanOrderBriefVO briefOf(Order order, Device device, Long currentUserId) {
        ScanOrderBriefVO vo = new ScanOrderBriefVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(OrderStatus.labelOf(order.getStatus()));
        vo.setDeviceId(order.getDeviceId());
        vo.setDeviceName(device == null ? null : device.getDeviceName());
        vo.setAssetNo(device == null ? null : device.getAssetNo());
        vo.setApplicantName(referenceNames.userNameOf(order.getApplicantId()));
        vo.setPlannedEndTime(order.getPlannedEndTime());
        vo.setCanRequestReturn(Objects.equals(order.getApplicantId(), currentUserId)
                && OrderStatus.BORROWED.name().equals(order.getStatus()));
        return vo;
    }

    @Override
    public BorrowFlowPreviewVO previewBorrowFlow(String useType, LocalDate expectedReturnDate, Long deviceId) {
        // 委托 BorrowFlowPreviewer：部门反查、流程版本解冻、路径求值、审批人解析都在那边，
        // 且与提交侧共用同一套流程内核 —— 这里再实现一遍必然与真实路径漂移。
        return borrowFlowPreviewer.preview(useType, expectedReturnDate, deviceId);
    }

    @Override
    public AssignCandidateVO assignCandidates(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        String role = currentRole();
        Order order = requireOrder(orderId);
        // 与详情同口径：能看这笔单的人才能看它的指派候选。
        //
        // 为什么不再收紧到「仅当前步骤审批人」：候选名单的内容上限就是流程定义里限定的池子
        // （预置流程 = IT执行人角色 ∪ IT运维组成员），对能看详情的人不构成额外信息；
        // 而收紧会引入一个新的失败模式 —— 一旦判定口径有偏差，审批人拉不到候选，
        // 「必须指定 1 人」就变成永远无法满足的前置条件，工单静默卡死。
        // 真正的写入校验在 applyNextStepAssignment（服务端权威），本方法只负责"提前告知"。
        assertCanView(order, currentUserId, role);

        AssignCandidateVO vo = new AssignCandidateVO();
        vo.setRequiredCount(0);
        vo.setCandidates(List.of());

        if (!OrderStatus.PENDING_APPROVAL.name().equals(order.getStatus())) {
            return vo;
        }
        List<OrderApprovalNode> nodes = listNodes(orderId);
        // 本接口在**审批之前**调用，因此不能直接套 applyNextStepAssignment 的"当前步骤"口径
        // （那是审批之后的状态）。用 placeholdersAfterMyApproval 模拟"我这一票通过后"再看，
        // 两处描述的才是同一个时刻；否则前端拿不到待指派提示、服务端却拒绝，工单静默卡死。
        List<OrderApprovalNode> placeholders =
                PendingAssignSupport.placeholdersAfterMyApproval(nodes, currentUserId);
        if (placeholders.isEmpty()) {
            return vo;
        }
        OrderApprovalNode placeholder = placeholders.get(0);
        ApproverRule rule = PendingAssignSupport.prevAssignRuleOf(order.getApprovalFlowJson(), placeholder);
        boolean restricted = rule != null && approverRuleResolver.assignScopeRestricted(rule);
        ApproverRuleType.AssignScope scope = PendingAssignSupport.assignScopeOf(rule);

        vo.setRequiredCount(placeholders.size());
        vo.setNodeKey(placeholder.getNodeKey());
        vo.setNodeName(placeholder.getNodeName());
        vo.setStepOrder(placeholder.getStepOrder());
        vo.setAssignScope(scope.name());
        vo.setAssignScopeLabel(scope.getLabel());
        vo.setRestricted(restricted);

        // 不受限传 null（"不限定范围"），受限传池子（可能为空 ⇒ "范围内无人"，与"不限定"语义不同）
        List<UserOptionVO> candidates = userService.listActiveOptions(
                restricted ? approverRuleResolver.assignablePool(rule) : null);
        Long applicantId = order.getApplicantId();
        vo.setCandidates(candidates.stream()
                .filter(user -> !Objects.equals(user.getId(), applicantId))
                .toList());
        return vo;
    }

    // ------------------------------------------------------------------
    // 借用申请
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(OrderCreateRequest request) {
        Long applicantId = requireCurrentUserId();
        User applicant = requireUser(applicantId);

        // 1) 借用类型与归还日期。
        //    ：申请表单不再让员工选借用类型，**留空即按「短期借用」处理**；
        //    但显式传了非法值时仍然拒绝 —— 保住既有调用方与回归脚本的错误语义（USE_TYPE_INVALID）。
        String rawUseType = trimToNull(request.getUseType());
        UseType useType;
        if (rawUseType == null) {
            useType = UseType.SHORT_TERM;
        } else {
            useType = UseType.of(rawUseType);
            if (useType == null) {
                throw new BusinessException(ErrorCode.USE_TYPE_INVALID, "借用类型取值不合法：" + rawUseType);
            }
        }
        LocalDate expectedReturnDate = request.getExpectedReturnDate();
        if (useType.isExpectedReturnDateRequired()) {
            if (expectedReturnDate == null) {
                throw new BusinessException(ErrorCode.EXPECTED_RETURN_DATE_REQUIRED);
            }
            if (expectedReturnDate.isBefore(LocalDate.now())) {
                throw new BusinessException(ErrorCode.EXPECTED_RETURN_DATE_INVALID,
                        "期望归还日期不能早于今天（" + LocalDate.now() + "）");
            }
        } else {
            // 长期领用无固定归还日期：忽略前端可能残留的值，避免脏数据污染后续到期统计
            expectedReturnDate = null;
        }

        // 2) 部门（ USER_WITHOUT_DEPARTMENT）
        if (applicant.getDepartmentId() == null) {
            throw new BusinessException(ErrorCode.USER_WITHOUT_DEPARTMENT,
                    "你尚未被分配部门，请联系管理员配置后再提交申请");
        }
        Department department = departmentMapper.selectById(applicant.getDepartmentId());
        if (department == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND, "所属部门不存在，请联系管理员");
        }

        // 3) 最终处理部门（：全局唯一的「IT运维组」，departments.handler_group = 1）。
        //    改造前是「每个业务分组各自绑定一个最终处理小组」，改造后全系统只有一个 ——
        //    因此这里不再从申请人部门上取，而是问部门服务要那唯一的一个。
        Long handlerGroupId = departmentService.handlerDepartmentId();
        if (handlerGroupId == null) {
            throw new BusinessException(ErrorCode.HANDLER_DEPARTMENT_NOT_CONFIGURED,
                    "系统尚未配置最终处理部门（IT运维组），请联系管理员");
        }
        Department handlerDepartment = departmentMapper.selectById(handlerGroupId);
        if (handlerDepartment == null) {
            throw new BusinessException(ErrorCode.HANDLER_DEPARTMENT_NOT_CONFIGURED, "绑定的最终处理部门不存在，请联系管理员");
        }
        List<Long> handlerPool = activeHandlerIds(handlerGroupId);
        if (handlerPool.isEmpty()) {
            throw new BusinessException(ErrorCode.HANDLER_DEPARTMENT_EMPTY,
                    "最终处理部门「" + handlerDepartment.getDeptName() + "」暂无在职可用成员，请联系管理员");
        }

        // 4) 设备必须由本人持有有效临时锁
        Device device = requireDevice(request.getDeviceId());
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.LOCKED
                || !Objects.equals(device.getLockedBy(), applicantId)
                || !Objects.equals(device.getLockToken(), request.getLockToken())) {
            throw new BusinessException(ErrorCode.DEVICE_LOCK_TOKEN_INVALID);
        }

        // 5) 生成审批快照（ /  /  / ）
        //    两种来源，二选一：
        //      ① 该**部门**绑定了自己的审批流程 → 走那份流程（存量部门可能还绑着老流程，
        //         尊重既有配置：改造不应悄悄改掉某部门已经谈好的审批链）；
        //      ② 没绑定 → 走**系统预置三级流程**（ · ）。
        //         改造前的 ② 是「部门主管单级」硬编码路径（buildSnapshot），
        //         现在它只服务于自定义申请的 GROUP 模式。
        List<LeaderNotice> leaderNotices = new ArrayList<>();
        FlowDefinition frozenFlow = null;
        List<OrderApprovalNode> nodes;
        boolean skipApproval;
        if (department.getApprovalFlowVersionId() != null) {
            frozenFlow = approvalFlowService.requirePublishedDefinition(department.getApprovalFlowVersionId());
            Map<String, Object> borrowData = buildBorrowFormData(useType, expectedReturnDate, device, request);
            // M2：改用 resolveForSubmit —— 开关开启时把「提交时判不了的分支下游」落 INACTIVE；
            // 开关关闭时它等价于原来的三参 resolve（内部传"已知但为空"的上下文，不做延迟判定）。
            FlowPathResolver.Result path = flowActivationService.resolveForSubmit(
                    frozenFlow, BorrowFieldCatalog.schema(), borrowData);
            nodes = buildFlowNodes(path, applicant, borrowData, Map.of(), leaderNotices);
            // 流程模式：判据必须是「有没有 PENDING 节点」，不能用 !nodes.isEmpty()。
            // 因为流程会把未命中的节点以 SKIPPED 落库（为了事后可解释），此时节点行非空却无人待审 ——
            // 若按「非空」判，就会造出一笔「状态是待审批、却没有任何人能审」的死单。
            // 这与自定义申请 FLOW 分支的判据完全一致。
            skipApproval = nodes.stream()
                    .noneMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
        } else {
            // 系统预置流程：结构与级别写在 BorrowFlowCatalog（代码即事实源），
            // **金额阈值现读系统参数** —— 这正是「管理员改一个数字即可，不用画流程图」的落点。
            // 产出的定义整份写进 orders.approval_flow_json（见下方落库），
            // 因此之后无论阈值怎么改，这笔单的审批口径都不会变：已提交工单走提交时快照。
            frozenFlow = BorrowFlowCatalog.preset(systemConfigService.approvalDeviceAmountThreshold());
            Map<String, Object> borrowData = buildBorrowFormData(useType, expectedReturnDate, device, request);
            FlowPathResolver.Result path = flowActivationService.resolveForSubmit(
                    frozenFlow, BorrowFieldCatalog.schema(), borrowData);
            nodes = buildFlowNodes(path, applicant, borrowData, Map.of(), leaderNotices);
            // 判据与 ① 一致：流程会把未命中的 SKIPPED 节点一起落库，
            // 因此「需不需要审批」只能看有没有 PENDING，不能看节点行是否非空。
            skipApproval = nodes.stream()
                    .noneMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
        }
        OrderStatus initialStatus = skipApproval ? OrderStatus.PENDING_DELIVERY : OrderStatus.PENDING_APPROVAL;

        // 6) 落库工单（编号唯一索引兜底，遇极端撞号重试）
        Order order = new Order();
        order.setOrderType(OrderType.BORROW.name());
        order.setDeviceId(device.getId());
        order.setApplicantId(applicantId);
        order.setDepartmentId(department.getId());
        order.setUseType(useType.name());
        // 用途（原「借用原因」）选填：空值写 null（V32 已放开 reason 的 NOT NULL）。
        // 「使用地点 / 备注」在 已从表单砍掉 —— 不再采集（列保留，历史数据不受影响）。
        order.setReason(trimToNull(request.getReason()));
        order.setExpectedReturnDate(expectedReturnDate);
        order.setStatus(initialStatus.name());
        order.setHandlerDepartmentId(handlerGroupId);
        order.setBorrowTimeout(false);
        order.setAutoExtendCount(0);
        // 流程定义快照（M1）：与节点行互补 —— 节点行是「执行结果」，这份 JSON 是「当时的规则」。
        // 未绑定流程时为 null（与自定义申请非 FLOW 模式一致）。
        order.setApprovalFlowJson(frozenFlow == null ? null : FlowDefinitionCodec.write(frozenFlow));
        // 归属快照（M7）：快照 JSON 里没有模板/版本 id，流程监控按模板聚合必须靠这两个列。
        // 预置流程**不在 approval_flow 表里**（见 BorrowFlowCatalog 类注释），因此没有版本指针，
        // 只有名字快照 —— 监控页会把这类单归入「未归属」分组，名字是那一组里唯一的辨识依据。
        if (department.getApprovalFlowVersionId() != null) {
            applyFlowAttribution(order, department.getApprovalFlowVersionId());
        } else {
            order.setApprovalFlowName(BorrowFlowCatalog.PRESET_FLOW_NAME);
        }
        Order saved = insertOrderWithGeneratedNo(order);
        Long orderId = saved.getId();

        // 7) 保存快照节点
        for (OrderApprovalNode node : nodes) {
            node.setOrderId(orderId);
            nodeMapper.insert(node);
        }

        // 8) 设备推进：LOCKED → IN_APPROVAL，并原子清空临时锁三件套
        int deviceUpdated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, device.getId())
                .eq(Device::getStatus, DeviceStatus.LOCKED.name())
                .eq(Device::getLockToken, request.getLockToken())
                .set(Device::getStatus, DeviceStatus.IN_APPROVAL.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
        if (deviceUpdated == 0) {
            // 事务回滚：设备已被并发提交抢占或被超时释放，工单不会残留
            throw new BusinessException(ErrorCode.DEVICE_LOCK_TOKEN_INVALID,
                    "设备临时锁已失效（可能已超时或被他人占用），请重新选择设备后提交");
        }

        // 9) 无需审批的特殊路由（ 最后一行）：直接进入待交付并分配执行人
        if (skipApproval) {
            assignFinalHandler(saved, handlerPool, nodes);
        }

        // 10) 流程模式的随单通知（M1）——与自定义申请 FLOW 分支同一套能力，口径也一致：
        //     抄送「提交即解析即发送」；直属领导解析失败的三方兜底通知。
        //     未绑定流程时 frozenFlow 为 null，两处都不产生任何消息（零回归）。
        if (frozenFlow != null) {
            notifyCcRecipients(nodes, orderId, saved.getOrderNo(), BORROW_TYPE_NAME);
            notifyLeaderFallbacks(leaderNotices, saved, null);
        }

        // 10.1) 首个审批环节的「审批待办」通知 —— 与自定义申请（submitCustomOrder 中
        //       notifyCurrentApprovers）口径对齐。
        //       修复前借用单只通知抄送人、**从不通知审批人**：审批人必须自己打开
        //       「审批待办」才发现有新单，属真实功能缺口（上线验收 F6 抓出）。
        //       占位节点（approver_id 为空，待上一节点指定）会被 notifyCurrentApprovers
        //       内部过滤并静默返回，因此分组模式与「上一节点指定」模式同样安全。
        if (!skipApproval) {
            notifyCurrentApprovers(orderId, nodes, saved.getOrderNo(), BORROW_TYPE_NAME);
        }

        log.info("工单已提交 id={} no={} device={} applicant={} useType={} 初始状态={} 快照节点={} 流程={}",
                orderId, saved.getOrderNo(), device.getId(), applicantId, useType.name(),
                initialStatus.name(), nodes.size(),
                department.getApprovalFlowVersionId() != null ? "部门自定义流程" : "系统预置流程");
        return orderId;
    }

    /** 借用单在消息文案里的类型名（无 ApplyType，故用常量而非查库） */
    private static final String BORROW_TYPE_NAME = "设备借用申请";

    // ------------------------------------------------------------------
    // 自定义申请
    // ------------------------------------------------------------------

    /**
     * 提交自定义申请
     *
     * <p>流程：类型可提交校验 → 取已发布版本的 schema → 逐字段校验表单数据 →
     * 建单（编号可用类型前缀）→ 存表单数据 → 按审批方式决定直接完成还是生成审批快照。
     *
     * <h2>与借用申请的三处关键差异</h2>
     * <ol>
     *   <li><b>没有设备</b>：不锁设备、不校验 lockToken、不推进设备状态。
     *       自定义申请（采购、外出登记……）与设备无关；</li>
     *   <li><b>不要求处理小组</b>：借用单必须有最终处理部门（因为要有人交付设备），
     *       自定义申请没有交付动作，处理小组只是「审批通过后 best-effort 记一位办理人」
     *       的来源，缺失不阻断；</li>
     *   <li><b>可以没有部门</b>：仅「走分组审批」时才需要（快照由分组配置决定）。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitCustomOrder(CustomOrderRequest request) {
        Long applicantId = requireCurrentUserId();
        User applicant = requireUser(applicantId);

        // 1) 类型必须存在、启用，且当前用户在提交权限范围内 —— 直调接口绕不过这一层
        ApplyType applyType = applyTypeService.requireSubmittable(request.getApplyTypeId());
        // 2) 表单定义取「该类型绑定的已发布版本」，而不是类型当前指向的最新草稿
        FormSchema schema = formTemplateService.requirePublishedSchema(applyType.getFormTemplateVersionId());
        // 2.5) 权限申请表单：选项按当前策略动态生成（与角色页「可申请权限配置」同源），
        //      保证「策略标不可申请的提权类」在表单里直接选不到。
        //      仅当表单确实含 permissionCodes 字段时才查策略（普通申请无需这次查询）。
        if (com.enterprise.ticket.module.permission.support.PermissionApplySchemaSupport.hasPermissionField(schema)) {
            schema = com.enterprise.ticket.module.permission.support.PermissionApplySchemaSupport
                    .withApplicableOptions(schema, permissionApplyPolicyService.applicableCodes());
        }
        // 3) 按 schema 逐字段校验（失败抛 FORM_DATA_INVALID，message 里带字段级明细）
        Map<String, Object> formData = request.getFormData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getFormData());
        // 3.5) 服务端二次校验：申请人勾选的权限码必须仍「开放申请」。
        //      表单选项是提交那一刻生成/缓存下来的，可能已过期；更可能的是直接调接口绕过前端。
        //      在此明确拒绝（可读原因），而不是让它走到审批终点再静默不授权。
        assertPermissionCodesApplicable(formData);
        formDataValidator.validate(schema, formData);

        ApprovalMode mode = ApprovalMode.of(applyType.getApprovalMode());
        if (mode == null) {
            // 配置脏数据兜底：审批方式解析不出来时按「无审批」处理而不是报错。
            // 报错会让这个类型彻底不可提交，而管理员从提交页根本看不出原因；
            // 按无审批处理至少能让流程走通，且工单里留有完整表单数据可追溯。
            mode = ApprovalMode.NONE;
        }

        Long departmentId = null;
        Long handlerGroupId = null;
        List<OrderApprovalNode> nodes = List.of();
        // FLOW 模式冻结下来的流程定义（非 FLOW 恒为 null），随工单一起落库作为快照
        FlowDefinition frozenFlow = null;
        // M7：与 frozenFlow 同步落库的「归属版本指针」。刻意提到分支外声明 ——
        // 工单对象的装配发生在 if/else 之后，若它只活在 FLOW 分支里，装配处就取不到，
        // 只能退化成"重新读一次 applyType.getApprovalFlowVersionId()"，
        // 那会出现两个取值的来源（物化用 A、归属用 B），是将来最容易被改错的地方。
        Long frozenFlowVersionId = null;
        // ：记录「直属领导解析失败」的兜底事件，待工单落库后再发消息（消息正文需要工单号）
        List<LeaderNotice> leaderNotices = new ArrayList<>();
        if (mode == ApprovalMode.GROUP) {
            // 分组审批需要审批快照，快照由部门配置决定（与借用申请共用同一套生成逻辑）
            if (applicant.getDepartmentId() == null) {
                throw new BusinessException(ErrorCode.USER_WITHOUT_DEPARTMENT,
                        "你尚未被分配部门，请联系管理员配置后再提交申请");
            }
            Department department = departmentMapper.selectById(applicant.getDepartmentId());
            if (department == null) {
                throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND, "所属部门不存在，请联系管理员");
            }
            departmentId = department.getId();
            handlerGroupId = departmentService.handlerDepartmentId();
            nodes = buildSnapshot(applicant, department.getId());
        } else if (mode == ApprovalMode.FLOW) {
            // 独立审批流程
            // 1) 取「该类型绑定的那一版」流程定义 —— 取版本而非模板，与表单同一套快照语义：
            //    流程模板之后再怎么改，都不会改写这笔工单的审批口径。
            Long flowVersionId = applyType.getApprovalFlowVersionId();
            if (flowVersionId == null) {
                // ApplyType 侧已保证「FLOW ⟺ 有流程版本」，这里是纵深防御：
                // 若配置被人工改脏，宁可明确报错也不要静默降级成「无需审批」——
                // 后者会让一笔本该审批的申请直接完成，是真正的事故。
                throw new BusinessException(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                        "「" + applyType.getTypeName() + "」的审批流程配置缺失，请联系管理员重新绑定");
            }
            frozenFlow = approvalFlowService.requirePublishedDefinition(flowVersionId);
            // M7：归属指针与物化用的定义同源同版（见上方 frozenFlowVersionId 的声明注释）
            frozenFlowVersionId = flowVersionId;
            // 2) 按表单数据求值条件分支，一次算出完整路径（含被跳过 / 待判定的节点）
            //    M2：含运行期条件的定义会把「提交时判不了」的分支下游落为 INACTIVE（待运行期激活）
            FlowPathResolver.Result path = flowActivationService.resolveForSubmit(frozenFlow, schema, formData);
            // 3) 物化节点：命中 → 解析审批人（抄送节点即解析抄送对象）；
            //    未命中 → 落 SKIPPED 并记下「为什么没走」；待人指定 → 落占位行
            nodes = buildFlowNodes(path, applicant, formData, request.getApproverSelections(), leaderNotices);
        }

        // ：高危权限申请 ⇒ 在审批链末尾追加一级超管。
        // 放在这里（节点已物化、needApproval 未计算）是唯一正确的位置：
        //   · 早于 needApproval ⇒ 追加的节点会参与「有没有人待审」的判定；
        //   · 晚于物化 ⇒ 能拿到 max(stepOrder) 作为新节点的步序（步序是拓扑距离，不能拍脑袋）。
        nodes = appendSuperAdminForHighRiskPermissions(nodes, formData);

        // 「无需审批」有三个来源：① 类型配置为 NONE；② 配置为 GROUP 但快照为空
        //   （：申请人本身是超管且分组未配置审批节点 → 直接跳过审批）；
        //   ③ FLOW 且条件分支把路径上所有审批节点都绕开了。
        // 判据从「有没有节点行」改成「有没有 PENDING 节点」：FLOW 会把未命中的节点
        // 以 SKIPPED 落库（为了事后可解释），此时节点行非空却无人待审 ——
        // 若仍按 !nodes.isEmpty() 判，就会造出一笔「状态是待审批、却没有任何人能审」的死单。
        boolean needApproval = nodes.stream()
                .anyMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
        OrderStatus initialStatus = needApproval ? OrderStatus.PENDING_APPROVAL : OrderStatus.COMPLETED;

        Order order = new Order();
        order.setOrderType(OrderType.CUSTOM.name());
        order.setApplyTypeId(applyType.getId());
        // 流程定义快照：与节点行互补 —— 节点行是「执行结果」，这份 JSON 是「当时的规则」
        order.setApprovalFlowJson(frozenFlow == null ? null : FlowDefinitionCodec.write(frozenFlow));
        // 归属快照（M7）：与上一行的快照同生共死。取的是**物化时实际用的那一版**
        // （frozenFlowVersionId，在 FLOW 分支内随定义一起赋值），
        // 而不是在装配处重新读一次 applyType —— 保证"写进归属的那一版"
        // 与"物化节点用的那一版"在代码上是同一个值，不存在配置被并发改动导致错位的窗口。
        applyFlowAttribution(order, frozenFlow == null ? null : frozenFlowVersionId);
        order.setApplicantId(applicantId);
        order.setDepartmentId(departmentId);
        order.setStatus(initialStatus.name());
        order.setHandlerDepartmentId(handlerGroupId);
        order.setBorrowTimeout(false);
        order.setAutoExtendCount(0);
        // 设备 / 借用类型 / 使用地点 / 借用原因 对自定义申请不适用，一律留空（见 V16 迁移说明）
        Order saved = insertOrderWithGeneratedNo(order, applyType.getOrderPrefix());
        Long orderId = saved.getId();

        // 审批快照落库（仅分组审批模式有节点）
        for (OrderApprovalNode node : nodes) {
            node.setOrderId(orderId);
            nodeMapper.insert(node);
        }

        // 表单数据与工单同时落库：同一事务内，不存在「有工单没表单」的中间态
        OrderFormData form = new OrderFormData();
        form.setOrderId(orderId);
        form.setFormTemplateVersionId(applyType.getFormTemplateVersionId());
        form.setFormDataJson(FormSchemaCodec.write(formData));
        orderFormDataMapper.insert(form);

        // ：抄送节点「提交即解析、即发送」——抄送是知会而非审批步骤，
        // 早一秒告知就早一秒知情，不等流程走到该节点。
        notifyCcRecipients(nodes, orderId, saved.getOrderNo(), applyType.getTypeName());
        // ：直属领导解析失败的兜底三方通知（申请人知情 / 超管接件 / 管理员去维护）
        notifyLeaderFallbacks(leaderNotices, saved, applyType);
        if (needApproval) {
            notifyCurrentApprovers(orderId, nodes, saved.getOrderNo(), applyType.getTypeName());
        }
        log.info("自定义申请已提交 id={} no={} 类型={}({}) 初始状态={} 快照节点={}",
                orderId, saved.getOrderNo(), applyType.getTypeName(), applyType.getId(),
                initialStatus.name(), nodes.size());
        return orderId;
    }

    /**
     * 自定义申请「全部审批通过 → 已完成」
     *
     * <p>与借用单的区别在于<b>没有交付环节</b>：借用单审批通过后进入「待交付」，
     * 由实际执行人交付设备后才进入「使用中」；自定义申请没有实物交付动作，
     * 审批通过即流程终点（状态落 {@link OrderStatus#COMPLETED}）。
     *
     * <p>仍然登记一位<b>办理人</b>（best-effort）：让「谁受理了这笔申请」有据可查。
     * 但办理人缺失<b>不阻断</b>完成 —— 这与借用单「处理小组无人则整单回滚」的强约束刻意不同，
     * 因为那里缺人等于业务无法继续（设备交付不出去），这里只影响责任归属的留痕。
     */
    private void completeCustomOrder(Order order) {
        Long handlerId = null;
        List<Long> pool = activeHandlerIds(order.getHandlerDepartmentId());
        if (!pool.isEmpty()) {
            handlerId = pickHandler(pool);
        }
        OrderConcurrencyGuard.requireOrderTransitionClaimed(
                orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                        .eq(Order::getId, order.getId())
                        .eq(Order::getStatus, OrderStatus.PENDING_APPROVAL.name())
                        .set(Order::getStatus, OrderStatus.COMPLETED.name())
                        .set(Order::getActualFinalHandlerId, handlerId)));

        String typeName = applyTypeNameOf(order.getApplyTypeId());
        userNotificationService.notify(order.getApplicantId(), MessageType.APPROVAL_PASSED, "申请已通过",
                "你提交的「" + (typeName == null ? "自定义申请" : typeName) + "」（"
                        + order.getOrderNo() + "）已全部审批通过，流程完成。", order.getId());

        // ：权限申请审批通过 ⇒ 同事务自动开通。
        // 放在**同一个事务**里是刻意的 —— 「工单完成」与「权限开通」要么都成、要么都不成；
        // 放异步任务会出现「工单已完成、权限没开」的中断态（申请人只会看到「批了但用不了」）。
        // 驳回路径不会走到这里（本方法只在全部审批通过时调用）⇒「拒绝就不开通」是结构保证。
        permissionGrantOnApprovalService.grantIfPermissionApply(order);

        log.info("自定义申请 {} 全部审批通过，进入已完成（办理人={}）", order.getOrderNo(), handlerId);
    }

    /**
     * 取工单的自定义表单数据
     *
     * <p>返回的 schema 是<b>这笔工单当初用的那一版</b>（由
     * {@code order_form_data.form_template_version_id} 决定），不是申请类型当前绑定的版本 ——
     * 否则申请类型换绑表单后，所有历史工单的回显都会跟着变（违背快照语义，）。
     */
    @Override
    public OrderFormDataVO getFormData(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        String role = currentRole();
        Order order = requireOrder(orderId);
        // 可见性与工单详情完全同一套判定：申请人 / 审批人 / 实际执行人 / admin 以上
        assertCanView(order, currentUserId, role);
        if (!OrderType.CUSTOM.name().equals(order.getOrderType())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CUSTOM);
        }
        OrderFormData row = orderFormDataMapper.selectOne(Wrappers.<OrderFormData>lambdaQuery()
                .eq(OrderFormData::getOrderId, orderId));
        if (row == null) {
            throw new BusinessException(ErrorCode.ORDER_FORM_DATA_NOT_FOUND);
        }
        FormTemplateVersionVO version = formTemplateService.getVersion(row.getFormTemplateVersionId());

        OrderFormDataVO vo = new OrderFormDataVO();
        vo.setOrderId(orderId);
        vo.setApplyTypeId(order.getApplyTypeId());
        vo.setApplyTypeName(applyTypeNameOf(order.getApplyTypeId()));
        vo.setFormTemplateVersionId(row.getFormTemplateVersionId());
        vo.setFormTemplateVersionNo(version.getVersionNo());
        vo.setSchema(version.getSchema());
        vo.setData(FormSchemaCodec.readData(row.getFormDataJson()));
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    /** 通知「当前步骤」的审批人有新的自定义申请待审批 */
    private void notifyCurrentApprovers(Long orderId, List<OrderApprovalNode> nodes,
                                        String orderNo, String typeName) {
        Integer step = currentStepOrder(nodes);
        if (step == null) {
            return;
        }
        List<Long> approverIds = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), step))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getApproverId)
                .filter(Objects::nonNull)
                .toList();
        if (approverIds.isEmpty()) {
            return;
        }
        messageService.send(approverIds, MessageType.APPROVAL_TODO, "新的审批待办",
                "有一笔「" + typeName + "」申请（" + orderNo + "）等待你审批。", orderId);
    }

    /** 单个工单的申请类型名称（详情 / 完成通知用） */
    private String applyTypeNameOf(Long applyTypeId) {
        // W4-A2：实现已迁至 OrderReferenceNames（纯搬迁，行为不变）
        return referenceNames.applyTypeNameOf(applyTypeId);
    }

    // ------------------------------------------------------------------
    // M1：借用单接入自定义流程
    // ------------------------------------------------------------------

    /**
     * 把借用单的提交上下文转成条件求值器要的扁平 map（M1）。
     *
     * <p>借用单没有动态表单，条件字段来自 {@link BorrowFieldCatalog} 定义的内置域。
     * 这里负责把这些内置字段从「已校验过的入参 + 设备 + 借用类型」取出来，
     * key 与 {@code BorrowFieldCatalog.schema()} 逐字对应 —— 二者靠
     * {@link BorrowFieldCatalog} 里的常量保证一致，避免静默失配。
     *
     * <p>刻意复用 `buildFlowNodes`/`FlowPathResolver` 这条既有链路，而不是为借用单另写一套
     * 节点构造：条件分支、抄送、时限、直属领导、上一节点指定这些能力因此**自动**在借用单上可用，
     * 且与自定义申请共用同一份实现（一份规则，不会漂移）。
     */
    private Map<String, Object> buildBorrowFormData(UseType useType, LocalDate expectedReturnDate,
                                                    Device device, OrderCreateRequest request) {
        Integer expectedDays = BorrowFieldCatalog.expectedDays(LocalDate.now(), expectedReturnDate);
        return BorrowFieldCatalog.formData(
                useType == null ? null : useType.name(),
                expectedDays,
                device == null ? null : device.getPrimaryCategoryId(),
                request.getReason(),
                // 设备金额：取自资产台账，是金额分档条件的唯一依据。
                // 未录入时为 null，formData 会**不放入**该 key，于是数值比较不成立 → 走三级分支
                // （需求口径：未录入金额按 ≤ 阈值处理）。
                device == null ? null : device.getAmount());
    }

    /**
     * 生成审批快照（ /  /  / ）
     *
     * @return 快照节点；<b>返回空列表</b>表示该工单无需审批直接进入待交付
     *         （唯一场景：「申请人是 super_admin 且审批节点为空」）
     */
    /**
     * 高危权限申请 ⇒ 追加一级超管审批。
     *
     * <h2>为什么是「追加节点」而不是「换一套流程引擎」</h2>
     * 审批引擎的推进逻辑只认 {@code step_order} 与节点状态，追加一个 step_order 最大的
     * PENDING 节点天然就是「最后一级」—— 不需要新建任何流程机制，
     * 也不会影响既有类型的审批路径。
     *
     * <h2>为什么要服务端重算</h2>
     * 表单里的 {@code permissionCodes} 是用户输入。若信任前端传来的「是否高危」，
     * 攻击者改一个字段就能绕过超管那一级 —— 这正是需求里「高危要多走一级」的全部意义。
     *
     * @return 原列表（不需要追加时）或追加后的新列表；不改动入参
     */
    private List<OrderApprovalNode> appendSuperAdminForHighRiskPermissions(
            List<OrderApprovalNode> nodes, Map<String, Object> formData) {
        List<String> codes = permissionGrantOnApprovalService.readAppliedCodes(formData);
        if (codes.isEmpty() || !permissionApplyPolicyService.containsHighRisk(codes)) {
            return nodes;
        }
        List<OrderApprovalNode> result = new ArrayList<>(nodes);
        int nextStep = result.stream()
                .mapToInt(node -> node.getStepOrder() == null ? 0 : node.getStepOrder())
                .max()
                .orElse(0) + 1;
        OrderApprovalNode node = newNode(nextStep, requireSuperAdminId(), SignType.ANY_SIGN);
        node.setNodeName("超管审批（高危权限）");
        result.add(node);
        log.info("权限申请含高危项 {}，已在审批链末尾追加一级超管审批（step={}）", codes, nextStep);
        return result;
    }

    /**
     * 服务端二次校验：权限申请里勾选的码必须仍「开放申请」（P1 安全修复）。
     *
     * <p>与 {@link #appendSuperAdminForHighRiskPermissions} 同源读取 {@code permissionCodes}，
     * 但目的相反：前者决定「要不要多走一级超管」，本方法决定「这笔申请能不能提交」。
     *
     * <p>被拒的是**提权类**权限（角色管理 / AD 管理 / 系统参数 / 在线升级）—— 它们在
     * {@code permission_apply_policy} 里被标为不可申请。若不在提交时拦住，申请人会照常走完
     * 两级审批、工单 COMPLETED，而权限**不会开通**（静默失败），审批人白审一场。
     * 因此这里给出可读的拒绝原因，让申请人在提交那一刻就知道该取消勾选。
     *
     * <p>只对含 {@code permissionCodes} 字段的工单生效（读不到即为空列表，普通自定义申请直接返回）。
     */
    private void assertPermissionCodesApplicable(Map<String, Object> formData) {
        List<String> codes = permissionGrantOnApprovalService.readAppliedCodes(formData);
        if (codes.isEmpty()) {
            return;
        }
        List<String> notApplicable = codes.stream()
                .filter(code -> !permissionApplyPolicyService.isApplicable(code))
                .distinct()
                .toList();
        if (notApplicable.isEmpty()) {
            return;
        }
        String names = notApplicable.stream()
                .map(code -> com.enterprise.ticket.common.permission.PermissionCatalog.nameOf(code)
                        + "（" + code + "）")
                .collect(java.util.stream.Collectors.joining("、"));
        throw new BusinessException(ErrorCode.FORM_DATA_INVALID,
                "以下权限不开放申请，请取消勾选后重新提交：" + names);
    }

    private List<OrderApprovalNode> buildSnapshot(User applicant, Long departmentId) {
        // ：审批人来源从「分组审批人表」换成「部门主管」。
        // 顺序由 DepartmentService 保证（user_id 升序），不在这里再排一次 —— 排两次迟早不一致。
        List<Long> configured = departmentService.managerIdsOf(departmentId);

        // ：审批节点为空 → super_admin 兜底；但申请人本身是 super_admin 时直接跳过审批
        if (configured.isEmpty()) {
            if (RoleCode.isSuperAdmin(applicant.getRole())) {
                log.info("申请人 {} 为超级管理员且分组未配置审批节点，按 直接跳过审批", applicant.getId());
                return List.of();
            }
            OrderApprovalNode node = newNode(1, requireSuperAdminId(), SignType.ANY_SIGN);
            node.setSuperBackup(true);
            return List.of(node);
        }

        // 先解析出最终审批人（含离职兜底替换），再判断「申请人是否为唯一审批人」
        Map<Long, ResolvedApprover> resolved = new LinkedHashMap<>();
        Set<Long> resolvedApproverIds = new LinkedHashSet<>();
        for (Long managerId : configured) {
            User approver = userMapper.selectById(managerId);
            boolean unavailable = approver == null
                    || !Boolean.TRUE.equals(approver.getEnabled())
                    || Boolean.TRUE.equals(approver.getDimission());
            Long approverId = unavailable ? requireSuperAdminId() : managerId;
            resolved.put(managerId, new ResolvedApprover(approverId, unavailable));
            resolvedApproverIds.add(approverId);
        }
        boolean applicantIsOnlyApprover = resolvedApproverIds.size() == 1
                && resolvedApproverIds.contains(applicant.getId());

        List<OrderApprovalNode> nodes = new ArrayList<>();
        int stepOrder = 1;
        for (Map.Entry<Long, ResolvedApprover> entry : resolved.entrySet()) {
            ResolvedApprover target = entry.getValue();
            // 部门主管之间是**串行**的（一人一步）：与改造前「分组审批人按 step_order 逐个批」一致，
            // 因此既有的会签/或签推进逻辑一行都不用改。
            OrderApprovalNode node = newNode(stepOrder++, target.approverId(), SignType.ANY_SIGN);
            // 只置 is_fallback：语义是「原审批人离职/禁用，快照生成时替换为 super_admin」。
            // 刻意不再置 is_super_backup —— 该列专指「分组未配置审批节点时的兜底节点」，
            // 两者混用会让界面同时打出「超管兜底」与「原审批人离职」两个标签，语义重复且误导。
            node.setFallback(target.fallback());
            if (!target.fallback() && !applicantIsOnlyApprover
                    && Objects.equals(target.approverId(), applicant.getId())) {
                // ：审批人 = 申请人且还有其他审批人时，跳过该节点
                node.setStatus(ApprovalNodeStatus.SKIPPED.name());
            }
            nodes.add(node);
        }
        return nodes;
    }

    // ------------------------------------------------------------------
    // ：独立审批流程的快照物化
    // ------------------------------------------------------------------

    /**
     * 把「条件求值后的流程路径」物化成审批节点行。
     *
     * <h2>为什么未命中的节点也要落库</h2>
     * <p>它们在界面上是「这条分支没走」的证据。若只落命中节点，详情页就只剩半张图，
     * 事后没人能回答「为什么这笔单没经过财务审批」—— 而这恰恰是条件分支最需要被解释的地方。
     * 落库的代价为零：状态置 {@code SKIPPED}（非 PENDING），
     * {@code currentStepOrder}（最小含 PENDING 的步骤）自然把它们排除在外，
     * 因此既有审批引擎<b>一行都不用改</b>。
     *
     * <h2>多审批人为什么共享 stepOrder</h2>
     * <p>一个节点解析出 N 个人 → N 行、同一个 {@code step_order}。这正是既有
     * ANY_SIGN / ALL_SIGN 的语义载体（或签任一通过即推进，会签全部通过才推进），
     * 所以「同一步骤多人」这件事不需要新概念。
     */
    private List<OrderApprovalNode> buildFlowNodes(FlowPathResolver.Result path, User applicant,
                                                   Map<String, Object> formData,
                                                   Map<String, List<Long>> selections,
                                                   List<LeaderNotice> leaderNotices) {
        // 物化逻辑已抽到 FlowNodeMaterializer：M2 之后「提交时的初始物化」与
        // 「运行期把 INACTIVE 激活为 PENDING」是同一个动作，必须共用同一份实现，
        // 否则会出现"提交时走的节点规则"与"运行期激活出来的节点规则"悄悄分叉。
        List<OrderApprovalNode> nodes = new ArrayList<>();
        for (FlowPathResolver.ResolvedNode resolved : path.nodes()) {
            nodes.addAll(flowNodeMaterializer.materialize(resolved, applicant, formData, selections, leaderNotices));
        }
        return nodes;
    }


    // ------------------------------------------------------------------
    // ：抄送消息 / 直属领导兜底通知 / 待指派
    // ------------------------------------------------------------------

    /**
     * 抄送消息：提交时即向抄送对象发送。
     *
     * <p>同一个抄送节点解析出多人时只发<b>一条</b>消息给全部对象（同一件事不必多发几条）；
     * 不同抄送节点各发一条，便于正文区分是「哪个环节的抄送」。
     */
    private void notifyCcRecipients(List<OrderApprovalNode> nodes, Long orderId, String orderNo,
                                    String typeName) {
        Map<String, List<Long>> byNode = nodes.stream()
                .filter(node -> FlowNodeType.CC.name().equals(node.getNodeType()))
                .filter(node -> node.getApproverId() != null)
                .collect(Collectors.groupingBy(
                        node -> node.getNodeKey() == null ? "-" : node.getNodeKey(),
                        LinkedHashMap::new,
                        Collectors.mapping(OrderApprovalNode::getApproverId, Collectors.toList())));
        if (byNode.isEmpty()) {
            return;
        }
        String type = typeName == null ? "申请" : typeName;
        for (List<Long> ids : byNode.values()) {
            List<Long> recipients = ids.stream().distinct().toList();
            if (recipients.isEmpty()) {
                continue;
            }
            messageService.send(recipients, MessageType.ORDER_CC, "工单抄送：" + orderNo,
                    "有一笔「" + type + "」（" + orderNo + "）已提交，抄送给你知悉，可点击查看详情。",
                    orderId);
        }
    }

    /**
     * 直属领导解析失败的兜底三方通知。
     *
     * <p>三种收件人、三条不同文案：
     * <ul>
     *   <li><b>申请人</b>：告知将由超管兜底，并请其联系管理员维护直属领导；</li>
     *   <li><b>超级管理员</b>：告知有一笔工单需要他兜底审批；</li>
     *   <li><b>管理员（admin）</b>：告知某员工的直属领导配置有问题，请去维护。</li>
     * </ul>
     * 只在解析失败时发一次（提交时一次），不做重复提醒，避免消息噪音。
     */
    private void notifyLeaderFallbacks(List<LeaderNotice> notices, Order order, ApplyType applyType) {
        if (notices == null || notices.isEmpty()) {
            return;
        }
        String typeName = applyType == null || applyType.getTypeName() == null
                ? "申请" : applyType.getTypeName();
        String applicantName = userNameOf(order.getApplicantId());
        String nodeNames = notices.stream().map(LeaderNotice::nodeName).distinct()
                .collect(Collectors.joining("、"));
        String reasons = notices.stream().map(LeaderNotice::reason).distinct()
                .collect(Collectors.joining("、"));

        // 1) 申请人：知情 + 引导去维护
        messageService.send(order.getApplicantId(), MessageType.APPROVAL_LEADER_FALLBACK,
                "直属领导审批已由超管兜底",
                "你提交的「" + typeName + "」（" + order.getOrderNo() + "）中，节点「" + nodeNames
                        + "」因" + reasons + "，将由超级管理员兜底审批。"
                        + "请联系管理员维护你的直属领导信息，以免影响后续审批。",
                order.getId());

        // 2) 超管：接件
        List<Long> superAdmins = activeUserIdsOfRole(RoleCode.SUPER_ADMIN);
        if (!superAdmins.isEmpty()) {
            messageService.send(superAdmins, MessageType.APPROVAL_LEADER_FALLBACK,
                    "有工单需你兜底审批",
                    "「" + typeName + "」（" + order.getOrderNo() + "）由 " + applicantName
                            + " 提交，节点「" + nodeNames + "」因" + reasons + "，由你兜底审批，请及时处理。",
                    order.getId());
        }

        // 3) 管理员：去维护员工资料
        List<Long> admins = activeUserIdsOfRole(RoleCode.ADMIN);
        if (!admins.isEmpty()) {
            messageService.send(admins, MessageType.APPROVAL_LEADER_FALLBACK,
                    "员工直属领导配置待维护",
                    "员工 " + applicantName + " 的直属领导" + reasons + "；其提交的「" + typeName
                            + "」（" + order.getOrderNo() + "）该节点已由超管兜底。"
                            + "请在「员工管理」中维护其直属领导。",
                    order.getId());
        }
    }

    /** 某角色下「在职且启用」的用户 id（通知收件人用） */
    private List<Long> activeUserIdsOfRole(String role) {
        return userMapper.selectList(Wrappers.<User>lambdaQuery().eq(User::getRole, role)).stream()
                .filter(ApproverRuleResolver::isActive)
                .map(User::getId)
                .toList();
    }

    /**
     * 当前步骤成为「当前」时，对它的 PENDING 审批人做一次在职校验。
     *
     * <p>解决的是这个现实问题：某节点在提交时审批人尚在职，等轮到它时该审批人已离职 ——
     * 若不做替换，这步就永远没人能审，工单静默卡死。处置与一期一致：
     * 替换为超级管理员 + 打 {@code is_fallback} + 通知新审批人。
     *
     * <p>跳过 {@code approver_id} 为空的占位行（那是「待上一节点指定」，由
     * {@link #applyNextStepAssignment} 负责）。
     *
     * <h2>W4-A4：为什么改为接收调用方已持有的 {@code order} 与 {@code nodes}（C6）</h2>
     * <p>原先自行 {@code listNodes(orderId)}，而调用方 {@code approve()} 在同一条路径上已经读过，
     * 属纯冗余查询。改为传入后与 {@link #applyNextStepAssignment} 共用同一份快照。
     *
     * <p><b>传入的 {@code nodes} 必须是 {@code recompute} 之后重读的那一份</b>：运行期重算会把
     * {@code INACTIVE} 行激活为 {@code PENDING}，重算前的快照不含这些行，用旧快照会
     * <b>漏掉刚被激活的节点</b>——表现为「节点已激活但其审批人已离职」的窗口被放过，
     * 该步骤随后会静默卡死。由 {@code approve()} 的调用顺序保证。
     *
     * <p>本方法只改 {@code approver_id != null} 且审批人失效的行，与
     * {@link #applyNextStepAssignment} 关心的 {@code approver_id == null} 占位行
     * <b>作用域不相交</b>，因此两者共享同一份快照不会互相干扰（顺序仍是：先校验、后指派）。
     */
    private void revalidateCurrentStep(Order order, List<OrderApprovalNode> nodes) {
        Long orderId = order.getId();
        Integer step = currentStepOrder(nodes);
        if (step == null) {
            return;
        }
        List<OrderApprovalNode> pending = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), step))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .filter(node -> node.getApproverId() != null)
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        Long backupId = null;
        boolean changed = false;
        String typeName = applyTypeNameOf(order.getApplyTypeId());
        for (OrderApprovalNode node : pending) {
            User approver = userMapper.selectById(node.getApproverId());
            if (ApproverRuleResolver.isActive(approver)) {
                continue;
            }
            if (backupId == null) {
                backupId = requireSuperAdminId();
            }
            if (Objects.equals(backupId, node.getApproverId())) {
                continue;
            }
            nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                    .eq(OrderApprovalNode::getId, node.getId())
                    .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                    .set(OrderApprovalNode::getApproverId, backupId)
                    .set(OrderApprovalNode::getFallback, true));
            changed = true;
            messageService.send(backupId, MessageType.APPROVAL_TODO, "新的审批待办",
                    "「" + (typeName == null ? "申请" : typeName) + "」（" + order.getOrderNo()
                            + "）原审批人已离职/停用，改由你兜底审批，请及时处理。", orderId);
        }
        if (changed) {
            log.info("工单 {} 当前步骤存在已离职/停用的审批人，已替换为超级管理员兜底", order.getOrderNo());
        }
    }

    /**
     * 「上一节点审批人指定」：本次通过后，若下一步骤存在待指派占位节点，
     * 则要求本次请求携带 {@code nextApproverIds} 并完成指派（与审批同一事务）。
     *
     * <h2>三条判定</h2>
     * <ol>
     *   <li>下一步骤有待指派节点 → 必须带参数，且数量与占位行数一致、
     *       被指定人在职启用且不是申请人本人（自审回避）；</li>
     *   <li>没有待指派节点却传了参数 → <b>400 拒绝</b>（静默忽略会让上游以为指派成功，
     *       而工单实际会卡在「无人可审」）；</li>
     *   <li>都不满足 → 什么都不做。</li>
     * </ol>
     *
     * <p>指派完成后立即通知被指定人（否则他们要等超时提醒才知道轮到自己了）。
     */
    /**
     * 「待上一节点指定」的指派。
     *
     * <h2>W4-A4：为什么改为接收 {@code order} 与 {@code nodes}（C6 冗余查询消除）</h2>
     * <p>原先本方法自行 {@code listNodes(orderId)} + {@code requireOrder(orderId)}，
     * 而唯一调用方 {@code approve()} 在同一路径上刚读过这两份数据 —— 纯冗余，
     * 审批主路径每次白白多 2 条 SELECT。改为由调用方传入后，这两条查询在
     * {@code approve()} 里被「重算后统一重读一次」覆盖，净省 2 条。
     *
     * <p><b>为什么不是「解析快照判断有无 PREV_ASSIGN 再早退」</b>：那条路同样能省 2 条，
     * 但要为此多解析一次 {@code approval_flow_json}（{@code recompute} 内部已解析过一遍），
     * 净收益更差；而「共享 recompute 之后的那一份快照」除了零额外解析，还顺手消除了
     * 「两个方法各自读、读到不同版本」的可能性。
     *
     * <p><b>传入的 {@code nodes} 必须是 {@code recompute} 之后重读的那一份</b>：重算会把
     * {@code INACTIVE} 激活为 {@code PENDING} 占位行，重算前的快照里没有它们，
     * 用旧快照会漏掉本轮刚激活、正等着被指定的节点。由 {@code approve()} 的调用顺序保证。
     *
     * <p>另外 {@code nodes} 由「在职校验」{@code revalidateCurrentStep} 与本方法共享：
     * 前者只改 {@code approver_id != null} 的行（离职替换），而本方法只关心
     * {@code approver_id == null} 的占位行，两者作用域不相交，故共享同一份快照不会互相干扰。
     */
    private void applyNextStepAssignment(Order order, OrderApproveRequest request, Long currentUserId,
                                         List<OrderApprovalNode> nodes) {
        Long orderId = order.getId();
        Integer step = currentStepOrder(nodes);
        List<OrderApprovalNode> placeholders = PendingAssignSupport.placeholders(nodes, step);
        List<Long> requested = request.getNextApproverIds() == null ? List.of()
                : request.getNextApproverIds().stream().filter(Objects::nonNull).distinct().toList();
        if (placeholders.isEmpty()) {
            if (!requested.isEmpty()) {
                throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID,
                        "本步骤没有需要指定的审批人，请勿提交「指定下一节点审批人」");
            }
            return;
        }
        if (requested.isEmpty()) {
            throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID,
                    "下一步骤的审批人需由你指定，请选择 " + placeholders.size() + " 位审批人后再通过");
        }
        if (requested.size() != placeholders.size()) {
            throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID,
                    "下一步骤需指定 " + placeholders.size() + " 位审批人（当前提交 " + requested.size() + " 位）");
        }
        // W4-A4：`order` 由调用方传入 —— 原先在此 `requireOrder(orderId)` 是同一路径上的重复读，
        // 且读到的还是同一行（审批过程中 orders 的关键字段不会被本链路修改）。
        // ：范围校验的规则取自**同一节点的定义**（同一 step 的占位行共享一个流程节点），
        // 因此取第一条占位行即可；取不到规则时不限制范围（与存量定义的行为一致）。
        ApproverRule prevAssignRule = PendingAssignSupport.prevAssignRuleOf(
                order.getApprovalFlowJson(), placeholders.get(0));
        for (Long id : requested) {
            if (Objects.equals(id, order.getApplicantId())) {
                throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID, "不能指定申请人本人为审批人");
            }
            User user = userMapper.selectById(id);
            if (!ApproverRuleResolver.isActive(user)) {
                throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID,
                        "指定的审批人不存在或已离职/停用，请重新选择");
            }
            // 规则配了 assignScope（如预置流程第 3 级的「IT执行人」）时，被指定人必须落在范围内。
            // 前端选择器只是提示：请求体由客户端完全可控，不在这里拦，
            //「从 IT执行人里选」就只是一句界面文案 —— 随便什么人都指得进来。
            if (prevAssignRule != null && !approverRuleResolver.isAssignable(prevAssignRule, id)) {
                throw new BusinessException(ErrorCode.FLOW_NEXT_ASSIGN_INVALID,
                        "指定的审批人不在此节点允许的范围内，请从候选列表中选择");
            }
        }
        // 同事务逐行指派；带 status/approver_id 前置条件，避免并发下重复指派
        for (int i = 0; i < placeholders.size(); i++) {
            nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                    .eq(OrderApprovalNode::getId, placeholders.get(i).getId())
                    .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                    .isNull(OrderApprovalNode::getApproverId)
                    .set(OrderApprovalNode::getApproverId, requested.get(i)));
        }
        log.info("工单 {} 的下一节点（step={}）审批人已由 {} 指定为 {}",
                order.getOrderNo(), step, currentUserId, requested);
        String typeName = applyTypeNameOf(order.getApplyTypeId());
        messageService.send(requested, MessageType.APPROVAL_TODO, "新的审批待办",
                "「" + (typeName == null ? "申请" : typeName) + "」（" + order.getOrderNo()
                        + "）已由上一节点审批人指定由你审批，请及时处理。", orderId);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<OrderVO> pageMyOrders(long page, long size, String status, String keyword) {
        Long currentUserId = requireCurrentUserId();
        String normalizedStatus = validateStatusFilter(status);
        String trimmed = StringUtils.hasText(keyword) ? keyword.trim() : null;

        IPage<Order> resultPage = orderMapper.selectPage(pageOf(page, size), Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplicantId, currentUserId)
                .eq(normalizedStatus != null, Order::getStatus, normalizedStatus)
                .like(trimmed != null, Order::getOrderNo, trimmed)
                .orderByDesc(Order::getId));
        return viewAssembler.toPageResult(resultPage, currentUserId, currentRole());
    }

    @Override
    public PageResult<OrderVO> pagePendingApproval(long page, long size) {
        Long currentUserId = requireCurrentUserId();
        // 只返回「当前步骤轮到我」的工单：后续步骤的待办不应提前出现（ 按 step_order 依次执行）
        IPage<Order> resultPage = orderMapper.selectPage(pageOf(page, size), Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.PENDING_APPROVAL.name())
                .inSql(Order::getId, PENDING_APPROVAL_IN_SQL.formatted(currentUserId))
                .orderByAsc(Order::getId));
        return viewAssembler.toPageResult(resultPage, currentUserId, currentRole());
    }

    @Override
    public PageResult<OrderVO> pageMyHandling(long page, long size) {
        Long currentUserId = requireCurrentUserId();
        //  起本列表还包含「待收回」：归还流程的第二步（确认收回）同样由实际执行人处理，
        // 而超时工单可以直接收回，因此必须出现在「我的待处理」里。
        // 排序：超时置顶 + 红色标签（ / 「我的待处理超时工单置顶」），同组内按 id 升序保持稳定翻页。
        IPage<Order> resultPage = orderMapper.selectPage(pageOf(page, size), Wrappers.<Order>lambdaQuery()
                .eq(Order::getActualFinalHandlerId, currentUserId)
                .in(Order::getStatus, List.of(OrderStatus.PENDING_DELIVERY.name(),
                        OrderStatus.BORROWED.name(), OrderStatus.PENDING_RETURN.name()))
                .orderByDesc(Order::getBorrowTimeout)
                .orderByAsc(Order::getId));
        return viewAssembler.toPageResult(resultPage, currentUserId, currentRole());
    }

    /**
     * 「抄送我的」：我被抄送过的工单。
     *
     * <p>与其余列表刻意不同，这里<b>不限定工单状态</b>：抄送是「知会」，被抄送人理应
     * 看到该工单的最终结果（哪怕已通过 / 已驳回 / 已撤回），否则「抄送」这件事只发生了一半。
     *
     * <p>只按 {@code node_type='CC'} 匹配抄送行，因此不会把「我是审批人」的工单混进来
     * （审批行的 {@code node_type} 为 {@code APPROVAL} 或 NULL）。
     */
    @Override
    public PageResult<OrderVO> pageCcOrders(long page, long size) {
        Long currentUserId = requireCurrentUserId();
        IPage<Order> resultPage = orderMapper.selectPage(pageOf(page, size), Wrappers.<Order>lambdaQuery()
                .inSql(Order::getId, CC_IN_SQL.formatted(currentUserId))
                .orderByDesc(Order::getId));
        return viewAssembler.toPageResult(resultPage, currentUserId, currentRole());
    }

    // ------------------------------------------------------------------
    // 全部工单（全局视图，仅 super_admin / admin）
    // ------------------------------------------------------------------

    @Override
    public PageResult<OrderVO> pageAllOrders(OrderAllQuery query) {
        // ：前端菜单隐藏只是体验，服务层必须再判一次角色，否则直调接口就能越权读到全量工单
        if (!RoleCode.isAdminOrAbove(currentRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员及以上角色可查看全部工单");
        }
        long safePage = Math.max(query.getPage(), 1L);
        long safeSize = Math.min(Math.max(query.getSize(), 1L), MAX_PAGE_SIZE);

        String status = validateStatusFilter(query.getStatus());
        String useType = validateUseTypeFilter(query.getUseType());

        // 申请人 / 设备为「名称模糊搜索」：先查出匹配的 id 集合再过滤，
        // 而不是拼字符串子查询 —— 保留 MyBatis 的参数绑定，不引入 SQL 注入面
        String applicantKeyword = trimToNull(query.getApplicantKeyword());
        Set<Long> applicantIds = matchUserIds(applicantKeyword);
        if (applicantKeyword != null && applicantIds.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }
        String deviceKeyword = trimToNull(query.getDeviceKeyword());
        Set<Long> deviceIds = matchDeviceIds(deviceKeyword);
        if (deviceKeyword != null && deviceIds.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }

        LocalDateTime submittedFrom = query.getSubmitTimeFrom() == null
                ? null : query.getSubmitTimeFrom().atStartOfDay();
        LocalDateTime submittedTo = query.getSubmitTimeTo() == null
                ? null : LocalDateTime.of(query.getSubmitTimeTo(), END_OF_DAY);
        // 区间颠倒时直接报错：否则 SQL 条件互斥、静默返回空列表，用户会误以为「没有工单」
        if (submittedFrom != null && submittedTo != null && submittedFrom.isAfter(submittedTo)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "提交时间的起始日期不能晚于截止日期");
        }

        var wrapper = Wrappers.<Order>lambdaQuery()
                .eq(status != null, Order::getStatus, status)
                .eq(useType != null, Order::getUseType, useType)
                .eq(query.getDepartmentId() != null, Order::getDepartmentId, query.getDepartmentId())
                // ：按自定义申请类型筛选（NULL 值表示自定义类型筛选，普通借用单不会被误命中）
                .eq(query.getApplyTypeId() != null, Order::getApplyTypeId, query.getApplyTypeId())
                // 「已超时」是标记位而非状态，故独立成一个筛选条件
                .eq(query.getBorrowTimeout() != null, Order::getBorrowTimeout, query.getBorrowTimeout())
                // ：「已转交」同样是事实标记而非状态，用固定子查询表达（不拼接用户输入，无注入面）
                .inSql(Boolean.TRUE.equals(query.getTransferred()), Order::getId,
                        "SELECT DISTINCT order_id FROM order_handler_transfer")
                .notInSql(Boolean.FALSE.equals(query.getTransferred()), Order::getId,
                        "SELECT DISTINCT order_id FROM order_handler_transfer")
                .ge(submittedFrom != null, Order::getCreatedAt, submittedFrom)
                .le(submittedTo != null, Order::getCreatedAt, submittedTo)
                .in(!applicantIds.isEmpty(), Order::getApplicantId, applicantIds)
                .in(!deviceIds.isEmpty(), Order::getDeviceId, deviceIds);

        // 排序白名单：前端值绝不直接拼进 SQL。这里是**精确匹配**（大小写敏感）——
        // 唯一调用方是本项目前端的固定字段名，对影响 SQL 的取值保持严格白名单更稳妥
        String sortBy = trimToNull(query.getSortBy());
        boolean asc = "asc".equalsIgnoreCase(trimToNull(query.getSortOrder()));
        switch (sortBy == null ? "createdAt" : sortBy) {
            case "expectedReturnDate" -> wrapper.orderBy(true, asc, Order::getExpectedReturnDate);
            case "id" -> wrapper.orderBy(true, asc, Order::getId);
            case "createdAt" -> wrapper.orderBy(true, asc, Order::getCreatedAt);
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + sortBy);
        }
        if (!"id".equals(sortBy)) {
            // 同值兜底：保证翻页稳定，不出现记录重复或漏出
            wrapper.orderByDesc(Order::getId);
        }

        IPage<Order> resultPage = orderMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return viewAssembler.toPageResult(resultPage, SecurityUtils.getCurrentUserId(), currentRole());
    }

    /** 借用类型筛选值校验（白名单） */
    private String validateUseTypeFilter(String useType) {
        String trimmed = trimToNull(useType);
        if (trimmed == null) {
            return null;
        }
        if (UseType.of(trimmed) == null) {
            throw new BusinessException(ErrorCode.USE_TYPE_INVALID, "借用类型筛选值不合法：" + trimmed);
        }
        return trimmed;
    }

    /** 按姓名（登录名 / 显示名）模糊匹配用户，返回匹配到的 user_id 集合 */
    private Set<Long> matchUserIds(String keyword) {
        if (keyword == null) {
            return Set.of();
        }
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .like(User::getUsername, keyword)
                        .or().like(User::getDisplayName, keyword))
                .stream()
                .map(User::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 按设备名称 / 资产编号模糊匹配设备，返回匹配到的 device_id 集合 */
    private Set<Long> matchDeviceIds(String keyword) {
        if (keyword == null) {
            return Set.of();
        }
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .like(Device::getDeviceName, keyword)
                        .or().like(Device::getAssetNo, keyword))
                .stream()
                .map(Device::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public OrderDetailVO getDetail(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        String role = currentRole();
        Order order = requireOrder(orderId);
        assertCanView(order, currentUserId, role);

        List<OrderApprovalNode> nodes = listNodes(orderId);
        // W4-A2：详情装配（字典回填 / canXxx / 节点视图 / 时间线）已迁至 OrderViewAssembler。
        // assertCanView 有意留在服务层 —— 可见性判定属于入口职责，不随装配搬走。
        return viewAssembler.toDetail(order, currentUserId, role, nodes);
    }

    // ------------------------------------------------------------------
    // 审批
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long orderId, OrderApproveRequest request) {
        Long currentUserId = requireCurrentUserId();
        // W4-F：先取本单行锁、再读。顺序不可颠倒 ——
        // 会签并发下两个事务若各自读到「对方那行仍是 PENDING」，双方都会认为还有待办
        // 而不做终态转移，工单就卡在「审批中且已无待办节点」的死单上（任何审批人都推不动）。
        Order order = requireOrderForUpdate(orderId);
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "当前工单状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不可审批");
        }

        List<OrderApprovalNode> nodes = listNodes(orderId);
        Integer currentStep = currentStepOrder(nodes);
        if (currentStep == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "该工单没有待处理的审批节点");
        }

        //  守卫：当前步骤全部是「待上一节点指定」的占位节点（approver_id 为空）时，
        // 明确告知原因，而不是含糊的「当前审批节点不需要你处理」——
        // 后者会让上一节点审批人以为系统出故障，实际只是他上次通过时漏了指派。
        List<OrderApprovalNode> currentPending = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .toList();
        if (!currentPending.isEmpty()
                && currentPending.stream().allMatch(node -> node.getApproverId() == null)) {
            throw new BusinessException(ErrorCode.FLOW_APPROVER_PENDING_ASSIGN,
                    "该步骤的审批人需由上一节点审批人指定，当前尚未指定，无法审批");
        }

        OrderApprovalNode myNode = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> Objects.equals(node.getApproverId(), currentUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVER_NOT_CURRENT_NODE));
        if (!ApprovalNodeStatus.PENDING.name().equals(myNode.getStatus())) {
            // ：或签并发下第二个提交者应看到「节点已处理」
            throw new BusinessException(ErrorCode.APPROVAL_NODE_HANDLED);
        }

        LocalDateTime now = LocalDateTime.now();
        String comment = trimToNull(request.getComment());

        if (Boolean.FALSE.equals(request.getApproved())) {
            if (comment == null) {
                throw new BusinessException(ErrorCode.REJECT_COMMENT_REQUIRED);
            }
            OrderConcurrencyGuard.requireNodeActionApplied(
                    actionNode(myNode.getId(), ApprovalNodeStatus.REJECTED, comment, now));
            // M2 · onReject：节点配了"驳回改道"时不整单终止 —— 本节点 REJECTED，
            // 转而激活目标分支；工单保持审批中、不释放设备（Q3 已确认）。
            // 未配置（存量流程全部如此）→ 走下面的默认口径，与第二期逐行一致。
            if (flowActivationService.applyRejectGoto(order, myNode, comment)) {
                return;
            }
            // ：任一节点驳回 → 其余未完成节点自动作废，API 拦截后续操作
            cancelPendingNodes(orderId, myNode.getId());
            // W4-F：终态转移必须带状态前置条件并断言命中 —— 无条件写会在并发下覆盖别人的结果，
            // 而且「什么都没改」也会被当成成功提交上去。
            OrderConcurrencyGuard.requireOrderTransitionClaimed(
                    orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                            .eq(Order::getId, orderId)
                            .eq(Order::getStatus, OrderStatus.PENDING_APPROVAL.name())
                            .set(Order::getStatus, OrderStatus.REJECTED.name())
                            .set(Order::getActualFinalHandlerId, null)));
            // ：驳回释放设备（需求方确认「拒绝后设备释放临时锁，回到可用」）。
            // 自定义申请没有设备（device_id 为空），跳过释放 —— 不加这层判断的话，
            // 会拿 null 拼出 `id = null` 的设备更新条件（命中 0 行），
            // 日志里还会留下「设备 null 已释放」这种误导性记录，排查时白白浪费一轮。
            if (order.getDeviceId() == null) {
                log.info("工单 {} 被 {} 驳回（自定义申请，无设备需释放）。原因：{}",
                        order.getOrderNo(), currentUserId, comment);
            } else {
                releaseDevice(order.getDeviceId());
                log.info("工单 {} 被 {} 驳回，设备 {} 已释放回可用。原因：{}",
                        order.getOrderNo(), currentUserId, order.getDeviceId(), comment);
            }
            // 驳回通知申请人（含驳回原因）。 只要求「拦截后续操作」，
            // 但「被驳回」是申请人必须第一时间知道的结果 —— 不发消息就只能靠他自己猜。
            // P2 修复：走站内 + 邮件双通道。
            userNotificationService.notify(order.getApplicantId(), MessageType.APPROVAL_REJECTED, "申请被驳回",
                    "你提交的工单 " + order.getOrderNo() + " 已被驳回。原因：" + comment, order.getId());
            return;
        }

        OrderConcurrencyGuard.requireNodeActionApplied(
                actionNode(myNode.getId(), ApprovalNodeStatus.APPROVED, comment, now));
        boolean anySign = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .anyMatch(node -> SignType.ANY_SIGN.equals(node.getSignType()));
        if (anySign) {
            //  或签：任意一人通过即进入下一步，同步骤其余待办节点标记 SKIPPED
            skipPendingNodesOfStep(orderId, currentStep);
        }

        // M2：运行期重算必须放在<b>最前</b> —— 它把 INACTIVE 节点激活为 PENDING（并同步解析审批人），
        // 紧随其后的「在职校验」与「待上一节点指派」正好覆盖刚激活的这一批。
        // 若放在两者之后，新激活的节点要等<b>下一次</b>审批动作才被校验，
        // 中间会短暂出现「已激活但审批人失效 / 未指派」的窗口 ——
        // 审批人此刻点进来会拿到误导性报错。
        flowActivationService.recompute(order);

        // W4-A4（C6）：重新读取一次节点 —— 这一步是**必须**的，不是优化：
        // recompute 会把 INACTIVE 激活为 PENDING，上面第 1338 行读到的快照已过期，
        // 拿它做「在职校验 / 待上一节点指派」会漏掉本轮刚激活的节点。
        // 这一份快照随后被下面两个步骤共享，替掉它们各自内部的 listNodes，
        // 使审批主路径相比改造前净省 2 条 SELECT（原先 revalidate 1 条 + apply 2 条 → 现在 1 条）。
        List<OrderApprovalNode> currentNodes = listNodes(orderId);

        // ：新步骤成为「当前步骤」时，先做一次审批人在职校验（失效者替换为超管兜底），
        // 再处理「待上一节点指定」的指派 —— 顺序不可颠倒：指派必须先于「还有没有待办」的判定，
        // 否则刚被指派的节点在本轮会被误判成「无人处理」。
        revalidateCurrentStep(order, currentNodes);
        applyNextStepAssignment(order, request, currentUserId, currentNodes);

        // 以库中最新状态判断是否还有未完成节点（或签批量跳过 / 会签全部通过）
        boolean hasPending = hasOpenApproval(orderId, order);
        if (!hasPending) {
            if (OrderType.CUSTOM.name().equals(order.getOrderType())) {
                // 自定义申请没有设备交付环节：审批通过即流程终点（已完成）。
                // 借用单在这里是「进入待交付并分配执行人」，两者刻意不同 ——
                // 硬套借用单流程会造出一笔「待交付但无设备可交付」的死单。
                completeCustomOrder(order);
                return;
            }
            List<Long> handlerPool = activeHandlerIds(order.getHandlerDepartmentId());
            if (handlerPool.isEmpty()) {
                // 提交时已校验；若期间组员全部离职/禁用，则整单回滚保持原状态，由管理员补齐人员
                throw new BusinessException(ErrorCode.HANDLER_DEPARTMENT_EMPTY,
                        "最终处理部门暂无在职可用成员，请联系管理员补充后再次审批");
            }
            assignFinalHandler(order, handlerPool, currentNodes);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, String reason) {
        Long currentUserId = requireCurrentUserId();
        // W4-F：本顺序是「先改节点、再改工单」，正好与 approve 的 orders→nodes 相反。
        // 不统一的话两者并发会成环（approve 持 orders 想拿 nodes，cancel 持 nodes 想拿 orders）
        // ⇒ 死锁。统一到 orders→nodes 后，全库的加锁顺序回到一条线上。
        Order order = requireOrderForUpdate(orderId);
        if (!Objects.equals(order.getApplicantId(), currentUserId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_APPLICANT);
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.PENDING_APPROVAL && status != OrderStatus.PENDING_DELIVERY) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "当前工单状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不允许撤回");
        }
        cancelPendingNodes(orderId, null);
        OrderConcurrencyGuard.requireOrderTransitionClaimed(
                orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                        .eq(Order::getId, orderId)
                        .in(Order::getStatus, List.of(OrderStatus.PENDING_APPROVAL.name(),
                                OrderStatus.PENDING_DELIVERY.name()))
                        .set(Order::getStatus, OrderStatus.CANCELLED.name())
                        .set(Order::getActualFinalHandlerId, null)));
        if (order.getDeviceId() == null) {
            // 自定义申请没有设备，撤回时无需释放（同 approve 的驳回分支）
            log.info("工单 {} 已被申请人 {} 撤回（自定义申请，无设备需释放）。原因：{}",
                    order.getOrderNo(), currentUserId, trimToNull(reason));
        } else {
            releaseDevice(order.getDeviceId());
            log.info("工单 {} 已被申请人 {} 撤回，设备 {} 已释放回可用。原因：{}",
                    order.getOrderNo(), currentUserId, order.getDeviceId(), trimToNull(reason));
        }
        // 撤回通知原实际执行人（MessageType.ORDER_CANCELLED 的 Javadoc 接收人）。
        // 只有已经分配到执行人的单（待交付 / 使用中撤回）才需要通知 ——
        // 审批尚未走完时并没有「有人在等这笔活」，发过去只是噪音。
        Long cancelledHandler = order.getActualFinalHandlerId();
        if (cancelledHandler != null && !Objects.equals(cancelledHandler, currentUserId)) {
            String cancelReason = trimToNull(reason);
            messageService.send(cancelledHandler, MessageType.ORDER_CANCELLED, "工单已撤回",
                    "申请人撤回了工单 " + order.getOrderNo() + "，无需再交付/处理。"
                            + (cancelReason == null ? "" : "原因：" + cancelReason),
                    order.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deliver(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.PENDING_DELIVERY) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "当前工单状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不可执行交付确认");
        }
        if (!Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            throw new BusinessException(ErrorCode.OPERATOR_NOT_ACTUAL_FINAL_HANDLER,
                    "仅本工单实际执行人可确认交付");
        }
        Device device = requireDevice(order.getDeviceId());
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.IN_APPROVAL) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus()) + "」，不可交付");
        }

        LocalDateTime now = LocalDateTime.now();
        // 工单 → 使用中（ / ），并记录交付时间与交付人
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING_DELIVERY.name())
                .set(Order::getStatus, OrderStatus.BORROWED.name())
                .set(Order::getDeliveredAt, now)
                .set(Order::getDeliveredBy, currentUserId)
                .set(Order::getPlannedEndTime, plannedEndTime(order)));
        // 设备 → 使用中（：IN_APPROVAL →（最终处理人交付确认）→ IN_USE）
        int deviceUpdated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, device.getId())
                .eq(Device::getStatus, DeviceStatus.IN_APPROVAL.name())
                .set(Device::getStatus, DeviceStatus.IN_USE.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
        if (deviceUpdated == 0) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID, "设备状态已变化，请刷新后重试");
        }
        log.info("工单 {} 已由 {} 确认交付，设备 {} 进入使用中", order.getOrderNo(), currentUserId, device.getId());
    }

    // ------------------------------------------------------------------
    // 两步归还（；需求方   / 4）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestReturn(Long orderId, OrderReturnRequest request) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        // ：申请人本人操作；API 层同样拦截，此处是最终防线
        if (!Objects.equals(order.getApplicantId(), currentUserId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_APPLICANT, "只有申请人本人可以发起归还");
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.BORROWED) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "当前工单状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，只有使用中的工单可以发起归还");
        }

        // 关键：**设备状态保持不变**。若此处把设备放回 AVAILABLE，
        // 归还途中设备就会被他人重新申请，同一台设备出现两笔在办工单。
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .set(Order::getStatus, OrderStatus.PENDING_RETURN.name())
                .set(Order::getReturnTrigger, ReturnTrigger.USER_INITIATED.name())
                .set(Order::getReturnNote, trimToNull(request == null ? null : request.getReturnNote())));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "工单状态已变化，请刷新后重试");
        }

        messageService.send(order.getActualFinalHandlerId(), MessageType.RETURN_REQUESTED, "设备归还申请",
                "%s 申请归还设备「%s」，请确认收回（工单 %s）。"
                        .formatted(userNameOf(order.getApplicantId()), deviceLabelOf(order.getDeviceId()),
                                order.getOrderNo()),
                orderId);
        log.info("工单 {} 已由申请人 {} 发起归还 → 待收回；设备 {} 保持使用中",
                order.getOrderNo(), currentUserId, order.getDeviceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReturn(Long orderId, OrderConfirmReturnRequest request) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        OrderStatus status = OrderStatus.of(order.getStatus());

        // ：仅 actual_final_handler_id 有权限操作，申请人本人不能确认收回。
        // 额外放行 super_admin：执行人本人已离职/禁用时无人可操作， /  明确要求超管兜底。
        boolean handlerSelf = Objects.equals(order.getActualFinalHandlerId(), currentUserId);
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());
        if (!handlerSelf && !superAdmin) {
            throw new BusinessException(ErrorCode.OPERATOR_NOT_ACTUAL_FINAL_HANDLER);
        }

        boolean normalReturn = status == OrderStatus.PENDING_RETURN;
        boolean timeoutDirectReturn = status == OrderStatus.BORROWED && Boolean.TRUE.equals(order.getBorrowTimeout());
        if (!normalReturn && !timeoutDirectReturn) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "当前工单状态为「" + OrderStatus.labelOf(order.getStatus())
                            + "」，不可确认收回（仅「待收回」，或已超时的「使用中」工单可直接收回）");
        }

        ReturnCondition condition = ReturnCondition.of(request == null ? null : request.getCondition());
        if (condition == null) {
            throw new BusinessException(ErrorCode.RETURN_CONDITION_INVALID, "请选择收回时的设备状态");
        }
        // P0：说明必填的裁决必须在**服务端**。只靠前端拦不住绕过界面直接调接口的调用，
        // 而「损坏 / 缺配件 / 丢失」的说明恰恰是后续追责与追回的唯一依据 ——
        // 造出一条「损坏但没说明」的记录，等于把一条查不下去的账留在库里。
        String returnRemark = request == null ? null : trimToNull(request.getRemark());
        if (condition.isRemarkRequired() && returnRemark == null) {
            throw new BusinessException(ErrorCode.RETURN_CONDITION_INVALID,
                    "检查结果为「" + condition.getLabel() + "」时必须填写说明（"
                            + (condition == ReturnCondition.MISSING_PARTS
                                    ? "写明缺了哪些配件" : "写明具体情况") + "）");
        }
        Device device = requireDevice(order.getDeviceId());
        if (DeviceStatus.of(device.getStatus()) != DeviceStatus.IN_USE) {
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "设备当前状态为「" + DeviceStatus.labelOf(device.getStatus()) + "」，不可收回");
        }

        // 归还触发来源（需求方确认的三值枚举，不再自造第四值）：
        //   · 正常两步归还 —— 沿用第一步写入的值（USER_INITIATED / DIMISSION）；
        //   · super_admin 代为收回 —— ADMIN_FORCE；
        //   · 执行人因超时直接收回 —— 不改写（保持原值，通常为空）；
        //     该情形由 borrow_timeout = true 独立标识，无需挤占 USER_INITIATED 的语义。
        String trigger = (superAdmin && !handlerSelf) ? ReturnTrigger.ADMIN_FORCE.name() : order.getReturnTrigger();

        LocalDateTime now = LocalDateTime.now();
        // borrow_timeout 是**实时标记位**（语义＝「当前是否处于超时状态」），随工单终结一并清除：
        // 归还完成后若仍置 true，「我的工单」会在「已归还」旁继续挂「已超时」红标，
        // 且「全部工单 → 仅看已超时」会把已归还的工单筛出来（与筛选语义不符）。
        // 历史「曾超时归还」的事实不丢失：`auto_extend_count`（是否用满顺延）与
        // `actual_end_time` 对比 `expected_return_date` 仍可完整还原，供  统计使用。
        var orderUpdate = Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .set(Order::getStatus, OrderStatus.RETURNED.name())
                .set(Order::getBorrowTimeout, false)
                .set(Order::getActualEndTime, now)
                .set(Order::getReturnedBy, currentUserId)
                .set(Order::getReturnCondition, condition.name())
                .set(Order::getReturnRemark, returnRemark)
                .set(Order::getReturnTrigger, trigger);
        // 状态前置条件与上面的分支一一对应，保证并发下不会重复结算同一笔工单
        if (normalReturn) {
            orderUpdate.eq(Order::getStatus, OrderStatus.PENDING_RETURN.name());
        } else {
            orderUpdate.eq(Order::getStatus, OrderStatus.BORROWED.name())
                    .eq(Order::getBorrowTimeout, true);
        }
        if (orderMapper.update(null, orderUpdate) == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "工单状态已变化，请刷新后重试");
        }

        // 设备去向由登记结果决定（ +  设备状态机）：
        // 完好 / 轻微损坏 → AVAILABLE；故障 → MAINTENANCE（维修完成后由管理员恢复可用）
        DeviceStatus deviceTarget = condition.getDeviceStatus();
        int deviceUpdated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, device.getId())
                .eq(Device::getStatus, DeviceStatus.IN_USE.name())
                .set(Device::getStatus, deviceTarget.name()));
        if (deviceUpdated == 0) {
            // 事务回滚：工单不会停在「已归还但设备仍在使用中」的不一致状态
            throw new BusinessException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "设备状态已变化（可能已被其他流程处理），请刷新后重试");
        }

        // 「归还时登记故障：设备 → MAINTENANCE，关联故障记录」：
        // 登记为「故障」时自动生成故障记录（若借用人此前已上报过待维修记录，则不重复建档）。
        // 放在设备状态推进之后、消息通知之前：建档失败会随事务一起回滚，不留半截数据。
        // ⚠️ 判据取枚举自述（isCreateFaultRecord）而不是写死 `== DAMAGED`：
        // 「缺配件」刻意**不建**故障记录 —— 配件缺失不是设备故障，
        // 建了会让「故障统计」把「少一根数据线」也算成一次设备故障。
        if (condition.isCreateFaultRecord()) {
            deviceFaultService.recordReturnFault(device.getId(), orderId, currentUserId,
                    returnRemark == null ? "归还时登记设备损坏" : returnRemark, now);
        }

        // 缺配件 / 丢失：设备本身可能没事（缺配件还回了 AVAILABLE），
        // 但**必须让人知道「有东西没回来」** —— 工单此刻已变「已归还」，
        // 不会再出现在任何人的待办里，不通知就彻底沉底了。
        if (condition.isNotifyRecovery()) {
            notifyRecoveryNeeded(order, device, condition, returnRemark, currentUserId, now);
        }

        messageService.send(order.getApplicantId(), MessageType.RETURN_CONFIRMED, "设备已收回",
                "您借用的设备「%s」已确认收回，设备状态：%s，实际归还时间 %s（工单 %s）。"
                        .formatted(deviceLabelOf(order.getDeviceId()), condition.getLabel(),
                                now.format(DATETIME_FMT), order.getOrderNo()),
                orderId);
        log.info("工单 {} 已由 {} 检查并收回 → 已归还；设备 {} → {}（检查结果 {}，触发来源 {}）",
                order.getOrderNo(), currentUserId, device.getId(), deviceTarget.name(),
                condition.name(), trigger);
    }

    /**
     * 归还检查结果为「缺配件 / 丢失」时通知管理员（P0）。
     *
     * <p><b>为什么必须单独发一条消息</b>：这两类结果发生时工单已经终结（RETURNED），
     * 不会再出现在「我的待处理」里 —— 若只把结果记在工单字段上，
     * 「有东西没回来」这件事就彻底沉底了，而它恰恰需要有人去<b>追回或查找</b>。
     *
     * <p>正文里刻意同时给出<b>资产编号</b>与<b>工单号</b>：追回时管理员要按资产编号去台账
     * 定位设备、按工单号回溯是谁借的；缺任何一个都要再点几次页面才能拼出来。
     *
     * <p>收件人取 {@code selectAdminIds()}（含 admin 与 super_admin）：追回配件是管理员的
     * 日常动作，只发超管会把超管变成转发中间人。
     */
    private void notifyRecoveryNeeded(Order order, Device device, ReturnCondition condition,
                                      String remark, Long checkedBy, LocalDateTime checkedAt) {
        List<Long> adminIds = userMapper.selectAdminIds();
        if (adminIds.isEmpty()) {
            // 与既有告警一致：没有收件人时留 warn 而不是抛错 ——
            // 归还本身已经成功落库，不能因为「没人可通知」把整个事务回滚掉。
            log.warn("工单 {} 的归还检查结果为「{}」，但系统中没有可用的管理员接收人，未发出通知",
                    order.getOrderNo(), condition.getLabel());
            return;
        }
        boolean lost = condition == ReturnCondition.LOST;
        String title = lost ? "设备已登记丢失，请查找" : "归还发现缺配件，请追回";
        String consequence = lost
                ? "该设备已置为「已丢失」且不可再被申请；找回后可在设备台账上手动改回「可用」，"
                        + "确认找不回则按报废处理。"
                : "设备主体已回到「可用」（配件缺失不影响设备本身），请按说明追回缺失配件；"
                        + "若确认影响使用，可在设备台账上手动改为「维修中」。";
        messageService.send(adminIds, MessageType.RETURN_RECOVERY_ALERT, title,
                ("设备「%s」（资产编号 %s）在工单 %s 的归还检查中被登记为「%s」。%s检查人：%s，检查时间：%s。%s")
                        .formatted(deviceLabelOf(order.getDeviceId()), device.getAssetNo(),
                                order.getOrderNo(), condition.getLabel(),
                                remark == null ? "" : "说明：" + remark + "；",
                                userNameOf(checkedBy), checkedAt.format(DATETIME_FMT),
                                consequence),
                order.getId());
    }

    // ------------------------------------------------------------------
    // 分配实际执行人（：加权随机，优先分配当前待处理最少的组员）
    // ------------------------------------------------------------------

    /**
     * 分配实际执行人（：加权随机，优先分配当前待处理最少的组员）。
     *
     * <h2>：「已被上一节点指定的执行人」优先</h2>
     * <p>预置借用流程的最后一级是「IT执行人处理」（{@code PREV_ASSIGN}）——
     * IT主管通过时必须指定一位执行人，而那位执行人**就是发设备的人**。
     * 若这里仍按加权随机另选一人，IT主管的选择会被静默覆盖，
     * 「IT主管决定谁发设备」（）这条就等于没落地 —— 且不会报任何错。
     *
     * <p>判据取自定义本身（{@link BorrowFlowCatalog#prevAssignNodeKeys}）而不是硬编码节点 key：
     * 部门自定义流程若也配了「上一节点指定」，同样按「被指定者即执行人」处理 ——
     * 那是同一条语义，没有理由只在预置流程上生效。
     *
     * @param nodes 该工单的审批节点快照；调用方已持有，避免在这里再查一次库
     */
    private void assignFinalHandler(Order order, List<Long> handlerPool, List<OrderApprovalNode> nodes) {
        Long orderId = order.getId();
        Long designated = designatedHandlerOf(order, nodes);
        if (designated != null && !handlerPool.contains(designated)) {
            // 不因此推翻指定：指定是 IT主管 的人工决定，组织挂靠差异（例如角色是「IT执行人」
            // 但没挂进 IT运维组）不该让这个决定失效。留一条 warn 供管理员发现配置不一致。
            log.warn("工单 {} 的执行人由上一节点指定为 user={}，但该用户不在最终处理部门 {} 的成员列表中；"
                            + "仍按指定结果分配",
                    order.getOrderNo(), designated, order.getHandlerDepartmentId());
        }
        Long chosen = designated != null ? designated : pickHandler(handlerPool);
        // W4-F：这里的入状态有两个来源 —— approve 到达时是 PENDING_APPROVAL，
        // 而 create 的「免审批直送待交付」路由是**先以 PENDING_DELIVERY 插入再调本方法**
        // （见 create 里的 initialStatus），所以前置条件必须同时容纳两者。
        // 再加「执行人尚未指派」这一条：它才是真正挡住并发重复分配的判据 ——
        // 若只判状态，第二次写照样能命中已经变成 PENDING_DELIVERY 的行，
        // 把加权随机抽出来的执行人**静默覆盖**掉（且不留任何痕迹）。
        OrderConcurrencyGuard.requireOrderTransitionClaimed(
                orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                        .eq(Order::getId, orderId)
                        .in(Order::getStatus, List.of(OrderStatus.PENDING_APPROVAL.name(),
                                OrderStatus.PENDING_DELIVERY.name()))
                        .isNull(Order::getActualFinalHandlerId)
                        .set(Order::getStatus, OrderStatus.PENDING_DELIVERY.name())
                        .set(Order::getActualFinalHandlerId, chosen)));
        // 待交付通知「实际执行人」（MessageType.DELIVERY_TODO
        // 的 Javadoc 早就写明接收人是他，但此前从未发送）：执行人只会在「我的待处理」
        // 里看到一笔新工单，没有任何提示 —— 审批通过后没人交付，工单就静静停在那里。
        // 注意两种情况刻意不通知：
        //   ① 自定义申请没有交付环节（走 completeCustomOrder，不进本方法）；
        //   ② 执行人就是申请人本人时（申请人已完成提交动作，再发一条「待你交付」是噪音）。
        if (!Objects.equals(chosen, order.getApplicantId())) {
            messageService.send(chosen, MessageType.DELIVERY_TODO, "设备待交付",
                    "工单 " + order.getOrderNo() + " 已审批通过，设备待你确认交付，请及时处理。", orderId);
        }
        log.info("工单 {} 全部审批通过/免审批，已分配实际执行人 user={}（来源={}），进入待交付",
                order.getOrderNo(), chosen, designated != null ? "上一节点指定" : "加权随机");
    }

    /**
     * 本笔工单的「已被指定的执行人」（无则返回 null）。
     *
     * <p>见 {@link #assignFinalHandler} 的注释：预置借用流程第 3 级（IT执行人处理）
     * 由 IT主管 通过时指定，被指定者即实际执行人。
     *
     * <p>取「step_order 最大的那个被指定节点」而不是"第一个"：一笔单里可能存在多个
     * 指定节点（大额分支下更靠后的那一级才是最终执行人）。取第一个会把中间某一级
     * 被指定的人误当成发设备的人。
     */
    private Long designatedHandlerOf(Order order, List<OrderApprovalNode> nodes) {
        if (nodes == null || nodes.isEmpty() || !StringUtils.hasText(order.getApprovalFlowJson())) {
            return null;
        }
        Set<String> prevAssignKeys;
        try {
            prevAssignKeys = BorrowFlowCatalog.prevAssignNodeKeys(
                    FlowDefinitionCodec.read(order.getApprovalFlowJson()));
        } catch (RuntimeException e) {
            // 快照损坏不该让一笔已经审完的单一辈子停在待交付之外：退回加权随机（与改造前一致）。
            log.warn("工单 {} 的流程快照无法解析，执行人退回加权随机分配：{}",
                    order.getOrderNo(), e.getMessage());
            return null;
        }
        if (prevAssignKeys.isEmpty()) {
            return null;
        }
        Long designated = null;
        int maxStep = Integer.MIN_VALUE;
        for (OrderApprovalNode node : nodes) {
            if (node == null || node.getApproverId() == null || node.getStepOrder() == null) {
                continue;
            }
            if (!prevAssignKeys.contains(node.getNodeKey())) {
                continue;
            }
            if (node.getStepOrder() > maxStep) {
                maxStep = node.getStepOrder();
                designated = node.getApproverId();
            }
        }
        return designated;
    }

    /** 加权随机：先取「进行中工单数」最少的组员，再在并列者中随机 */
    private Long pickHandler(List<Long> handlerPool) {
        if (handlerPool.size() == 1) {
            return handlerPool.get(0);
        }
        List<Order> ongoing = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getActualFinalHandlerId)
                .in(Order::getActualFinalHandlerId, handlerPool)
                .in(Order::getStatus, List.of(OrderStatus.PENDING_DELIVERY.name(),
                        OrderStatus.BORROWED.name(), OrderStatus.PENDING_RETURN.name())));
        Map<Long, Long> load = ongoing.stream()
                .collect(Collectors.groupingBy(Order::getActualFinalHandlerId, Collectors.counting()));
        long minLoad = handlerPool.stream().mapToLong(id -> load.getOrDefault(id, 0L)).min().orElse(0L);
        List<Long> leastLoaded = handlerPool.stream()
                .filter(id -> load.getOrDefault(id, 0L) == minLoad)
                .toList();
        return leastLoaded.get(ThreadLocalRandom.current().nextInt(leastLoaded.size()));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Page<Order> pageOf(long page, long size) {
        return new Page<>(Math.max(page, 1L), Math.min(Math.max(size, 1L), MAX_PAGE_SIZE));
    }

    private String validateStatusFilter(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim();
        if (!OrderStatus.isValid(normalized)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "工单状态筛选值不合法：" + normalized);
        }
        return normalized;
    }

    private Order insertOrderWithGeneratedNo(Order order) {
        return insertOrderWithGeneratedNo(order, "BO");
    }

    /**
     * 带前缀的落库（：自定义申请类型可配置 {@code order_prefix}，如 {@code CG}）。
     *
     * <p>前缀由申请类型给出，已通过 {@code FormSchemaValidator.validateOrderPrefix} 约束为字母开头 2–10 位；
     * 此处再兜底一次（空则回落 {@code BO}）。编号 = 前缀 + 秒级时间戳 + 4 位随机数，唯一索引兜底撞号。
     */
    private Order insertOrderWithGeneratedNo(Order order, String prefix) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            order.setOrderNo(generateOrderNo(prefix));
            try {
                orderMapper.insert(order);
                return order;
            } catch (DuplicateKeyException e) {
                log.warn("工单编号冲突（第 {} 次）：{}", attempt, order.getOrderNo());
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工单编号生成失败，请稍后重试");
    }

    /**
     * 落「流程模板归属」快照（ · M7）。
     *
     * <p>把「这笔工单走的是哪一版流程定义」记成一个稳定指针，供流程监控按模板聚合。
     * 背景见 V22 迁移与 {@code Order#approvalFlowVersionId} 的注释：
     * 快照 JSON 里不含模板/版本 id，仅靠「快照 ≡ 已发布版本定义」反查在真实数据上命中率为 0。
     *
     * <p><b>名字快照拿不到时不写、也不编</b>：{@code flowNameOfVersion} 在版本行或流程行
     * 已被删除时返回 null。此时仍然写版本指针（它指向哪一步是事实），只是名字留空，
     * 监控页会回落到「已删除流程 #id」这类占位文案。反过来若在这里编一个「未知流程」，
     * 就会把"当时确实有名字"这件事永久抹掉，且和真正的空值无法区分。
     */
    private void applyFlowAttribution(Order order, Long flowVersionId) {
        if (flowVersionId == null) {
            return;
        }
        order.setApprovalFlowVersionId(flowVersionId);
        order.setApprovalFlowName(approvalFlowService.flowNameOfVersion(flowVersionId));
    }

    private String generateOrderNo() {
        return generateOrderNo("BO");
    }
    private String generateOrderNo(String prefix) {
        String p = (prefix == null || prefix.isBlank()) ? "BO" : prefix.trim().toUpperCase();
        // 秒级时间戳 + 4 位随机数：100 人规模下撞号概率可忽略，且唯一索引兜底
        return p + LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }

    private OrderApprovalNode newNode(Integer stepOrder, Long approverId, String signType) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setStepOrder(stepOrder);
        node.setApproverId(approverId);
        node.setSignType(signType);
        node.setStatus(ApprovalNodeStatus.PENDING.name());
        node.setSuperBackup(false);
        node.setFallback(false);
        return node;
    }

    /**
     * 写入节点审批结果，<b>返回受影响行数</b>。
     *
     * <p>带 {@code status = PENDING} 前置条件：并发或签下第二个请求会更新 0 行。
     * W4-F 之前这里返回 {@code void}，受影响行数被丢掉，于是「一行都没改」的请求
     * 照样往下走并回 {@code SUCCESS} —— 这道自称「最后一道数据库级防线」的条件写形同虚设。
     * 现在把行数交给调用点，由 {@link OrderConcurrencyGuard#requireNodeActionApplied(int)} 显式断言。
     */
    private int actionNode(Long nodeId, ApprovalNodeStatus status, String comment, LocalDateTime actionTime) {
        return nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getId, nodeId)
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .set(OrderApprovalNode::getStatus, status.name())
                .set(OrderApprovalNode::getActionComment, comment)
                .set(OrderApprovalNode::getActionTime, actionTime));
    }

    /**
     * 整单驳回/撤回：其余未完成节点标记 CANCELLED。
     *
     * <p>M2 起委托给 {@link FlowActivationService#cancelOpenNodes} —— 它会把
     * {@code INACTIVE}（尚未判定）也一并作废。这不是顺手加的：{@code INACTIVE} 的
     * {@code isFinished()} 为 false，若只作废 PENDING，那些 INACTIVE 行会残留，
     * 之后任何一次运行期重算都可能把它们激活 —— 表现为<b>一笔已终止的工单突然又冒出待审节点</b>。
     * 开关关闭时不存在 INACTIVE 行，两条 SQL 完全等价。
     */
    private void cancelPendingNodes(Long orderId, Long exceptNodeId) {
        flowActivationService.cancelOpenNodes(orderId, exceptNodeId);
    }

    /**
     * M2 三段式：这笔工单是否还有「需要处理」的节点？
     *
     * <pre>
     *   有 PENDING              → 有（不动）
     *   无 PENDING 但有 INACTIVE → 先重算一次再判（这是本波最关键的一处判定）
     *   两者皆无                → 没有，可以进终态
     * </pre>
     *
     * <h2>为什么不能只判 PENDING</h2>
     * <p>运行期流程在提交时会把"判不了"的下游落成 {@code INACTIVE}。若只看 PENDING，
     * 一笔"当前没有待办、但后面还有节点可能要走"的工单会被直接判成流程走完 ——
     * 申请人拿到"已完成"，而流程其实还有环节没走。
     *
     * <h2>第三段兜底为什么必须存在</h2>
     * <p>第二次重算后若仍无 PENDING 却还有 INACTIVE，说明出现了配置层面的病态
     * （例如两个条件互相依赖）。此时宁可<b>强制激活</b>让它继续往前走，
     * 也绝不让工单静默卡死 —— 卡死的工单没有任何人能救活，而多走一步最多是多一次审批。
     *
     * <h2>调用前提：本单行锁必须已被持有（W4-F）</h2>
     * <p>本方法是「读节点 → 判定无待办 → 转移终态」的 write skew 现场，
     * 它<b>只在</b>调用方先取得 {@code orders} 行锁时才是正确的：会签并发下，
     * 两个事务各自读到「对方那行仍待办」，就会双双判 true 而谁都不转移，
     * 工单卡在「审批中且无待办节点」。所以 {@code approve} 与 {@code cancel} 都以
     * {@code requireOrderForUpdate} 开头，而不是直接 {@code requireOrder}。
     */
    private boolean hasOpenApproval(Long orderId, Order order) {
        List<OrderApprovalNode> nodes = listNodes(orderId);
        if (nodes.stream().anyMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))) {
            return true;
        }
        boolean hasInactive = nodes.stream()
                .anyMatch(node -> ApprovalNodeStatus.INACTIVE.name().equals(node.getStatus()));
        if (!hasInactive) {
            return false;
        }
        // 用「上一节点已完成」这个新事实再算一次
        flowActivationService.recompute(order);
        nodes = listNodes(orderId);
        if (nodes.stream().anyMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))) {
            return true;
        }
        Set<String> remaining = nodes.stream()
                .filter(node -> ApprovalNodeStatus.INACTIVE.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getNodeKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (remaining.isEmpty()) {
            return false;
        }
        log.warn("工单 {} 重算后仍无待审节点却残留 {} 个未激活节点，按兜底策略强制激活以避免卡死：{}",
                order.getOrderNo(), remaining.size(), remaining);
        flowActivationService.recompute(order, remaining);
        return listNodes(orderId).stream()
                .anyMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
    }

    /** 或签：同一步骤其余待处理节点标记 SKIPPED（「第一个通过生效」） */
    private void skipPendingNodesOfStep(Long orderId, Integer stepOrder) {
        nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .eq(OrderApprovalNode::getStepOrder, stepOrder)
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.SKIPPED.name()));
    }

    private List<OrderApprovalNode> listNodes(Long orderId) {
        return nodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .orderByAsc(OrderApprovalNode::getStepOrder)
                .orderByAsc(OrderApprovalNode::getId));
    }

    /** 当前待办步骤 = 最小的「仍存在 PENDING 节点」的 stepOrder；无待办返回 null */
    private Integer currentStepOrder(List<OrderApprovalNode> nodes) {
        // W4-A2：实现在 OrderApprovalNodeSupport（三处重复口径的收敛起点）
        return OrderApprovalNodeSupport.currentStepOrder(nodes);
    }

    /** 计划结束时间：短期借用取期望归还日当日 23:59:59；长期领用无计划结束时间 */
    private LocalDateTime plannedEndTime(Order order) {
        if (UseType.SHORT_TERM.name().equals(order.getUseType()) && order.getExpectedReturnDate() != null) {
            return LocalDateTime.of(order.getExpectedReturnDate(), END_OF_DAY);
        }
        return null;
    }

    /** 释放设备（驳回 / 撤回）：LOCKED 或 IN_APPROVAL 一律回到 AVAILABLE，并清空临时锁 */
    private void releaseDevice(Long deviceId) {
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, deviceId)
                .in(Device::getStatus, List.of(DeviceStatus.LOCKED.name(), DeviceStatus.IN_APPROVAL.name()))
                .set(Device::getStatus, DeviceStatus.AVAILABLE.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
    }

    /** 最终处理部门内「启用且在职」的成员 user_id；无可用成员返回空列表 */
    private List<Long> activeHandlerIds(Long handlerGroupId) {
        if (handlerGroupId == null) {
            return List.of();
        }
        List<UserDepartment> members = userDepartmentMapper.selectList(
                Wrappers.<UserDepartment>lambdaQuery().eq(UserDepartment::getDepartmentId, handlerGroupId));
        if (members.isEmpty()) {
            return List.of();
        }
        Set<Long> memberIds = members.stream().map(UserDepartment::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(memberIds).stream()
                .filter(user -> Boolean.TRUE.equals(user.getEnabled()) && !Boolean.TRUE.equals(user.getDimission()))
                .map(User::getId)
                .toList();
    }

    /** super_admin 兜底账号；系统必须至少存在一个超管，否则审批无法兜底 */
    private Long requireSuperAdminId() {
        List<User> superAdmins = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, RoleCode.SUPER_ADMIN)
                .orderByAsc(User::getId)
                .last("LIMIT 1"));
        if (superAdmins.isEmpty()) {
            throw new BusinessException(ErrorCode.APPROVAL_FLOW_NOT_CONFIGURED,
                    "系统尚未创建超级管理员账号，无法进行兜底审批");
        }
        return superAdmins.get(0).getId();
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 先取本单 orders 行锁，再读取工单（W4-F：同单并发写串行化）。
     *
     * <p>锁必须在<b>本事务第一条读之前</b>拿到 —— 否则快照已经定在加锁之前，后面怎么读都是旧数据，
     * 这正是 W4-F 抓到的会签死单的成因。因此这是「会修改本单节点状态」的入口专用读取：
     * {@code approve} 与 {@code cancel}；普通查询继续用不加锁的 {@link #requireOrder(Long)}。
     *
     * <p>加锁顺序约定见 {@code OrderMapper#lockById}。
     */
    private Order requireOrderForUpdate(Long orderId) {
        orderMapper.lockById(orderId);
        return requireOrder(orderId);
    }

    private Device requireDevice(Long deviceId) {
        Device device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(ErrorCode.DEVICE_NOT_FOUND);
        }
        return device;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    /**
     * 工单可见性：申请人 / 审批人 / <b>抄送人</b> / 实际执行人 / super_admin / admin
     *
     * <p> 起「抄送人」自动纳入：抄送行与审批行落在同一张 {@code order_approval_nodes}，
     * 且 {@code approver_id} 就是抄送对象，因此下面这条「我是本单任一节点的相关人」判定
     * 天然覆盖了抄送人 —— 不需要为此再加一条分支。
     *
     * <p>可见 ≠ 可写：全部写接口（审批 / 驳回 / 撤回 / 交付 / 归还 / 转交 / 催办 / 延期 /
     * 强制干预）各自校验自己的角色前提，抄送人不在那些前提里，因此会被自然拒绝。
     */
    private void assertCanView(Order order, Long currentUserId, String role) {
        if (!canViewOrder(order, currentUserId, role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单");
        }
    }

    /**
     * 工单可见性的<b>布尔版</b>（与 {@link #assertCanView} 同一口径）。
     *
     * <p>拆出布尔版是因为扫码场景需要「能看就跳详情、不能看就给一句解释」，
     * 而不是抛异常。两处若各写一份判定，迟早会在某次收紧权限时只改一边 ——
     * 那时「详情能打开、扫码说无权」这种矛盾就会出现。
     */
    private boolean canViewOrder(Order order, Long currentUserId, String role) {
        if (RoleCode.isAdminOrAbove(role)
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return true;
        }
        return nodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getApproverId, currentUserId)) > 0;
    }

    // ------------------------------------------------------------------
    // 名称 / 字典入口（W4-A2：实现已迁至 OrderReferenceNames；
    // 此处保留少量服务层入口，供归还、建单等非装配路径使用）
    // ------------------------------------------------------------------

    /** 设备分类 id → 名称（建单校验用，全量字典） */
    private Map<Long, String> categoryNameMap() {
        return referenceNames.categoryNameMap();
    }

    /**
     * 单个用户的展示名（显示名 → 登录名 → 「用户#id」）。
     *
     * <p>供归还流程拼装站内消息正文使用：批量列表走
     * {@link OrderReferenceNames#userNameMap(java.util.Set)} 避免 N+1，
     * 这里只用于单笔操作，一次查询开销可忽略。
     */
    private String userNameOf(Long userId) {
        return referenceNames.userNameOf(userId);
    }

    /** 单个设备的展示名「设备名（资产编号）」；设备缺失时退化为 id，避免消息正文出现 null */
    private String deviceLabelOf(Long deviceId) {
        return referenceNames.deviceLabelOf(deviceId);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 审批人解析结果：最终审批人（可能被兜底替换）+ 是否发生过兜底替换 */
    private record ResolvedApprover(Long approverId, boolean fallback) {
    }
}
