package com.enterprise.ticket.common.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 工单状态机
 *
 * <p>与设备状态机（{@link DeviceStatus}）<b>独立建模</b>（ /  反复强调）。
 *
 * <p><b>命名说明</b>：本枚举沿用 的编码 {@code BORROWED}，但对外展示文案统一为
 * 「使用中」—— 需求方在  将<b>设备</b>状态 {@code BORROWED} 改名为
 * {@code IN_USE}（长期领用场景下「已借用」不准确），若工单侧仍展示「已借用」，
 * 同一笔业务在工单列表与设备台账会出现两个互相矛盾的词。故此处保留编码以兼容规范与
 * 存量数据，仅统一显示文案。
 *
 * <p> 覆盖的流转（基础版审批 + 交付）：
 * <pre>
 * 提交成功            → PENDING_APPROVAL
 * 全部审批通过        → PENDING_DELIVERY（并随机分配 actual_final_handler_id）
 * 审批驳回 / 申请人撤回 → REJECTED / CANCELLED（释放设备回 AVAILABLE）
 * 最终处理人交付确认  → BORROWED（设备 → IN_USE）
 * </pre>
 * PENDING_RETURN / RETURNED 的流转由 「两步归还」交付。
 *
 * <p> 新增：转交可用的状态判定 {@link #isTransferable()} 及其 SQL 侧投影
 * {@link #transferableNames()} —— 两者共用同一个谓词，避免「枚举判定」与「SQL 条件」各写一遍。
 */
public enum OrderStatus {

    /** 审批中 */
    PENDING_APPROVAL("审批中", true),

    /** 待交付（审批通过，等待最终处理人交付） */
    PENDING_DELIVERY("待交付", true),

    /** 使用中（设备已交付；对应设备侧 IN_USE） */
    BORROWED("使用中", true),

    /** 待收回（申请人已发起归还，等待最终处理人确认收回，） */
    PENDING_RETURN("待收回", true),

    /** 已归还（最终处理人确认收回，流程完成，） */
    RETURNED("已归还", false),

    /** 已驳回 */
    REJECTED("已驳回", false),

    /** 已撤回 */
    CANCELLED("已撤回", false),

    /**
     * 已完成（：自定义申请「无审批」或「审批全部通过」后的终态）
     *
     * <p><b>为什么需要它，而不是复用 {@link #RETURNED}</b>：
     * {@code RETURNED}（已归还）描述的是「借出去的东西回来了」——它隐含「本工单占用过设备」。
     * 自定义申请（如采购申请）<b>根本没有设备</b>，把它标成「已归还」会让工单详情、
     * 列表与报表都出现「已归还了什么？」的荒谬语义。
     *
     * <p>状态本身不描述业务内容（那是 {@code order_type} 的职责），只描述
     * 「走到哪一步了」。对一笔无需交付的自定义申请来说，「已完成」就是终点。
     */
    COMPLETED("已完成", false),

    /**
     * 已终止（超管强制干预，；规范 V1.1  未覆盖）
     *
     * <p>与 {@link #CANCELLED}「已撤回」刻意区分：撤回是<b>申请人</b>主动收回申请，
     * 终止是 <b>super_admin</b> 强制结束一笔在办工单（并释放占用中的设备）。
     * 二者若共用同一状态，「全部工单」里会把超管强制结案显示成「申请人已撤回」，
     * 责任归属与事实都不对。该状态仅由 {@code FORCE_TERMINATE} 写入。
     */
    TERMINATED("已终止", false);

    private final String label;
    private final boolean occupiesDevice;

    OrderStatus(String label, boolean occupiesDevice) {
        this.label = label;
        this.occupiesDevice = occupiesDevice;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 该状态下工单是否仍占用设备。
     *
     * <p>用于「设备当前使用信息」查询：一笔占用中的工单即设备的当前使用记录。
     * 终态（RETURNED / REJECTED / CANCELLED）不占用设备。
     */
    public boolean isOccupiesDevice() {
        return occupiesDevice;
    }

    /** 是否终态（不可再流转、不可被转交） */
    public boolean isTerminal() {
        return this == RETURNED || this == REJECTED || this == CANCELLED
                || this == TERMINATED || this == COMPLETED;
    }

    /**
     * ：是否允许转交。
     *
     * <p>允许的状态恰好是「工单仍占用设备或仍待执行人动作」的三个状态：
     * 待交付 / 使用中 / 待收回。终态一律不可转交。
     */
    public boolean isTransferable() {
        return this == PENDING_DELIVERY || this == BORROWED || this == PENDING_RETURN;
    }

    /**
     * {@link #isTransferable()} 为真的状态名集合，供 SQL {@code IN} 条件复用。
     *
     * <p><b>为什么要有这个投影</b>：判定「能否转交」用到两处 —— 单笔校验（读枚举）与
     * 批量查询（{@code WHERE status IN (...)}）。若两处各写一份状态名，将来调整可转交
     * 状态时极易只改一处，出现「按钮可点但接口拒绝」或反过来的错位。
     * 这里由 {@link #isTransferable()} 这一个谓词统一投影，**单一时序唯一的事实来源**。
     */
    private static final List<String> TRANSFERABLE_NAMES = Arrays.stream(values())
            .filter(OrderStatus::isTransferable)
            .map(Enum::name)
            .toList();

    /** 可转交状态名（只读）；供查询侧拼 {@code IN} 条件使用 */
    public static List<String> transferableNames() {
        return TRANSFERABLE_NAMES;
    }

    public static boolean isValid(String value) {
        return of(value) != null;
    }

    public static OrderStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        OrderStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
