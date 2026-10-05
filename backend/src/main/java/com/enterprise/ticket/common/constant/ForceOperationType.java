package com.enterprise.ticket.common.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 超级管理员强制干预类型（；规范 V1.1 未覆盖）
 *
 * <p>仅 {@code super_admin} 可发起；每笔操作强制要求「填写原因 + 二次确认 + 高危审计 +
 * 时间线记录 + 通知相关人」。<b>刻意不提供「强制通过」</b>——审批结论必须由审批人本人负责，
 * 超管的权限边界是「结束 / 改派」，不是「代替审批人下结论」。
 *
 * <p>存库取值即 {@link #name()}，与项目其它枚举（{@code OrderStatus} / {@code TransferType}）一致。
 */
public enum ForceOperationType {

    /** 强制驳回：仅「审批中」；等价于超管代审批人驳回整单（原因必填，作为驳回意见） */
    FORCE_REJECT("强制驳回", List.of(OrderStatus.PENDING_APPROVAL)),

    /** 强制终止：所有非终态；强制结束工单并释放其占用的设备（用中的设备一并回到可用） */
    FORCE_TERMINATE("强制终止",
            List.of(OrderStatus.PENDING_APPROVAL, OrderStatus.PENDING_DELIVERY,
                    OrderStatus.BORROWED, OrderStatus.PENDING_RETURN)),

    /** 强制转交审批：仅「审批中」；把当前待办节点的审批人改派给指定员工 */
    FORCE_TRANSFER_APPROVAL("强制转交审批", List.of(OrderStatus.PENDING_APPROVAL)),

    /** 强制转交执行人：待交付 / 使用中 / 待收回；可跨小组指定新执行人 */
    FORCE_TRANSFER_HANDLER("强制转交执行人", OrderStatus.transferableNames().stream()
            .map(OrderStatus::of)
            .toList());

    private final String label;
    private final List<OrderStatus> allowedStatuses;

    ForceOperationType(String label, List<OrderStatus> allowedStatuses) {
        this.label = label;
        this.allowedStatuses = allowedStatuses;
    }

    public String getLabel() {
        return label;
    }

    /** 该操作用允许的工单状态列表 */
    public List<OrderStatus> getAllowedStatuses() {
        return allowedStatuses;
    }

    /** 是否允许在给定工单状态下执行本操作 */
    public boolean supports(OrderStatus status) {
        return status != null && allowedStatuses.contains(status);
    }

    public static ForceOperationType of(String value) {
        if (value == null) {
            return null;
        }
        for (ForceOperationType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    public static boolean isValid(String value) {
        return of(value) != null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ForceOperationType type = of(value);
        return type == null ? value : type.getLabel();
    }

    /** 全部类型名（供前端下拉与校验对齐；顺序即枚举声明顺序） */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
