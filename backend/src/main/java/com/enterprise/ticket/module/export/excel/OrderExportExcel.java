package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.constant.ReturnCondition;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单记录导出
 *
 * <p>规范要求「按筛选条件导出工单列表、<b>审批记录</b>」，因此输出两个工作表：
 * <ol>
 *   <li>「工单记录」——一行一单，含状态、执行人、归还信息与超时/转交标记；</li>
 *   <li>「审批记录」——一行一审批节点，含步骤、审批人、会签方式、结果与意见。</li>
 * </ol>
 * 两个 sheet 共用同一批工单（由调用方传入，保证「导出的审批记录只属于导出的这批工单」）。
 *
 * <p>纯函数式构建，不依赖 Spring / 数据库，可单测列顺序与兜底文案。
 */
public final class OrderExportExcel {

    public static final String SHEET_ORDER = "工单记录";
    public static final String SHEET_APPROVAL = "审批记录";

    private static final String[] ORDER_HEADERS = {
            "工单编号", "工单类型", "申请类型", "设备名称", "申请人", "部门", "借用类型",
            "用途", "期望归还日期", "工单状态", "最终处理部门", "执行人",
            "计划结束时间", "交付时间", "实际归还时间", "收回人", "归还设备状态",
            "是否超时", "是否已转交", "提交时间"
    };

    private static final int[] ORDER_WIDTHS = {
            22, 12, 14, 28, 12, 16, 12, 30, 14, 14, 18, 12,
            20, 20, 20, 12, 14, 10, 12, 20
    };

    private static final String[] APPROVAL_HEADERS = {
            "工单编号", "审批步骤", "审批人", "会签方式", "审批结果", "操作时间", "审批意见"
    };

    private static final int[] APPROVAL_WIDTHS = {
            22, 10, 12, 12, 12, 20, 40
    };

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OrderExportExcel() {
    }

    // ------------------------------------------------------------------
    // 工单记录
    // ------------------------------------------------------------------

    public static SheetSpec buildOrders(List<OrderVO> orders) {
        List<Object[]> rows = new ArrayList<>();
        for (OrderVO order : orders == null ? List.<OrderVO>of() : orders) {
            rows.add(new Object[]{
                    order.getOrderNo(),
                    order.getOrderTypeLabel() != null ? order.getOrderTypeLabel() : OrderType.labelOf(order.getOrderType()),
                    // ：自定义申请显示其类型名；普通借用单该列为空（避免出现无意义的重复文案）
                    order.getApplyTypeName() == null ? "" : order.getApplyTypeName(),
                    order.getDeviceName(),
                    order.getApplicantName(),
                    order.getDepartmentName(),
                    order.getUseTypeLabel() != null ? order.getUseTypeLabel() : UseType.labelOf(order.getUseType()),
                    order.getReason(),
                    formatDate(order.getExpectedReturnDate()),
                    order.getStatusLabel() != null ? order.getStatusLabel() : OrderStatus.labelOf(order.getStatus()),
                    order.getHandlerGroupName(),
                    order.getActualFinalHandlerName(),
                    formatDateTime(order.getPlannedEndTime()),
                    formatDateTime(order.getDeliveredAt()),
                    formatDateTime(order.getActualEndTime()),
                    order.getReturnedByName(),
                    order.getReturnConditionLabel() != null
                            ? order.getReturnConditionLabel()
                            : ReturnCondition.labelOf(order.getReturnCondition()),
                    yesNo(order.getBorrowTimeout()),
                    yesNo(order.getTransferred()),
                    formatDateTime(order.getCreatedAt())
            });
        }
        return new SheetSpec(SHEET_ORDER, ORDER_HEADERS, ORDER_WIDTHS, rows);
    }

    // ------------------------------------------------------------------
    // 审批记录
    // ------------------------------------------------------------------

    /**
     * @param orderNoById 工单 id → 工单编号（审批记录表里只存 orderId，导出成编号才可读）
     * @param userNameById 审批人 id → 姓名
     */
    public static SheetSpec buildApprovals(List<OrderApprovalNode> nodes,
                                           Map<Long, String> orderNoById,
                                           Map<Long, String> userNameById) {
        List<Object[]> rows = new ArrayList<>();
        for (OrderApprovalNode node : nodes == null ? List.<OrderApprovalNode>of() : nodes) {
            rows.add(new Object[]{
                    mapGet(orderNoById, node.getOrderId()),
                    node.getStepOrder(),
                    mapGet(userNameById, node.getApproverId()),
                    signTypeLabel(node.getSignType()),
                    ApprovalNodeStatus.labelOf(node.getStatus()),
                    formatDateTime(node.getActionTime()),
                    node.getActionComment()
            });
        }
        return new SheetSpec(SHEET_APPROVAL, APPROVAL_HEADERS, APPROVAL_WIDTHS, rows);
    }

    /**
     * 会签方式中文化。
     *
     * <p>该列存的是 {@code ALL_SIGN / ANY_SIGN}， 的会签 / 或签语义。
     * 未登记的取值原样返回（与全局「兜底不抹掉信息」的策略一致）。
     */
    public static String signTypeLabel(String signType) {
        if (signType == null) {
            return "";
        }
        return switch (signType) {
            case "ALL_SIGN" -> "会签";
            case "ANY_SIGN" -> "或签";
            default -> signType;
        };
    }

    private static String yesNo(Boolean value) {
        if (value == null) {
            return "否";
        }
        return value ? "是" : "否";
    }

    private static String mapGet(Map<Long, String> map, Long key) {
        if (map == null || key == null) {
            return "";
        }
        String value = map.get(key);
        return value == null ? "" : value;
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATETIME.format(dateTime);
    }
}
