package com.enterprise.ticket.common.constant;

/**
 * 设备状态机（，含需求方  的命名调整与 P0 的「丢失」状态）
 *
 * <p>设备状态与工单状态<b>独立建模</b>（ 明确要求），本枚举只描述设备自身状态。
 * 完整流转：
 * <pre>
 * AVAILABLE →（用户打开申请表选设备）→ LOCKED
 * LOCKED    →（提交成功）→ IN_APPROVAL ；→（超时未提交/取消）→ AVAILABLE
 * IN_APPROVAL →（驳回/撤回）→ AVAILABLE ；→（最终处理人交付确认）→ IN_USE
 * IN_USE    →（归还检查，完好）→ AVAILABLE
 *           →（归还检查，损坏）→ MAINTENANCE
 *           →（归还检查，缺配件）→ AVAILABLE（设备主体是好的，配件另走追回）
 *           →（归还检查，丢失）→ LOST
 * MAINTENANCE →（维修完成，管理员操作）→ AVAILABLE
 * LOST      →（找回，管理员操作）→ AVAILABLE ；→（确认找不回）→ SCRAPPED
 * AVAILABLE / MAINTENANCE / LOST →（管理员报废）→ SCRAPPED
 * </pre>
 *
 * <p><b>命名调整（需求方 ）</b>：原 {@code BORROWED}（已借用）改名为 {@code IN_USE}（使用中）。
 * 原因：企业内部大量设备是<b>长期领用</b>而非短期借用，「已借用」语义不准确。
 * 流转逻辑不变，仅枚举名与展示文案变化；存量数据由 Flyway
 * {@code V5__phase4_borrow_order.sql} 同步改写。
 *
 * <p>注意区分：<b>工单</b>状态仍沿用 的 {@code BORROWED} 编码
 * （见 {@link OrderStatus}），仅对外展示文案统一为「使用中」。
 *
 * <p><b>P0 新增 {@link #LOST}</b>：丢失与报废是两件事 ——
 * <b>丢失是暂时找不到，报废是确定不要了</b>。 原状态机没有这一档，
 * 归还时发现设备丢失只能记成报废，资产台账从此分不出「丢了几台」。
 * 新增后：丢失设备<b>不可被申请</b>（{@code applicable = false}），
 * 找回可手动改回 AVAILABLE，确认找不回再报废。
 *
 * <p>{@link #canManualTransfer} 限定管理员可手工执行的变更，避免管理界面把设备手工置为
 * LOCKED / IN_APPROVAL / IN_USE 而绕过工单流程。
 */
public enum DeviceStatus {

    /** 可用，空闲中 —— 唯一可被新建借用申请的状态 */
    AVAILABLE("可用，空闲中", true),

    /** 临时锁定（用户正在填表未提交），默认 5 分钟超时自动释放 */
    LOCKED("临时锁定", false),

    /** 审批中（已提交，等待审批流程完成） */
    IN_APPROVAL("审批中", false),

    /**
     * 使用中（已交付，正在使用；含短期借用与长期领用两种工单类型）
     *
     * <p> 起由「最终处理人确认交付」驱动进入。
     */
    IN_USE("使用中", false),

    /** 维修中（故障报修，或归还检查登记为「损坏」） */
    MAINTENANCE("维修中", false),

    /**
     * 已丢失（P0 新增，归还检查登记为「丢失」，或管理员在台账上标记）
     *
     * <p>与 {@link #SCRAPPED} 的区别是本状态存在的全部理由：
     * 丢失是<b>暂时找不到</b>（可能被找回，故保留改回 AVAILABLE 的通路），
     * 报废是<b>确定不要了</b>（资产生命周期终结，不可逆）。
     * 二者混用会让「资产丢失率」这项指标永远算不出来。
     */
    LOST("已丢失", false),

    /** 已报废（资产生命周期终结，不可再被申请，但历史工单可查） */
    SCRAPPED("已报废", false);

    private final String label;
    private final boolean applicable;

    DeviceStatus(String label, boolean applicable) {
        this.label = label;
        this.applicable = applicable;
    }

    /** 中文名称，用于列表展示与日志 */
    public String getLabel() {
        return label;
    }

    /** 中文名称（静态安全版）：非法/空值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        DeviceStatus status = of(value);
        return status == null ? value : status.getLabel();
    }

    /** 是否可被新建借用申请（ 表格「可否被申请」列） */
    public boolean isApplicable() {
        return applicable;
    }

    public static boolean isValid(String value) {
        return of(value) != null;
    }

    /** 按名称解析，非法值返回 {@code null}（由调用方决定报错还是回落） */
    public static DeviceStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (DeviceStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 允许的<b>管理员手动</b>状态变更（ + P0 扩展）。
     *
     * <p><b>这道白名单真正要防的是什么</b>：把设备手工置为
     * {@link #LOCKED} / {@link #IN_APPROVAL} / {@link #IN_USE} 从而<b>绕过工单流程</b>
     * （设备明明没被借走，台账上却写「使用中」；或者反过来把在借设备「解锁」了）
     * —— 这四种状态各有唯一入口（申请表、提交、交付确认），手工介入必然造成状态与工单不一致。
     *
     * <p>因此放行的是「<b>不可借但可逆</b>」的维护类动作，它们不绕过任何审批链路：
     * <ul>
     *   <li>{@code AVAILABLE / MAINTENANCE / LOST → SCRAPPED}（报废，终态）</li>
     *   <li>{@code MAINTENANCE → AVAILABLE}（维修完成）</li>
     *   <li><b>{@code LOST → AVAILABLE}（重见天日 —— P0 新增，用户明确要求「找回来后可以手动改回可用」）</b></li>
     *   <li><b>{@code AVAILABLE / MAINTENANCE → LOST}（台账上发现缺失 —— P0 新增）</b></li>
     *   <li><b>{@code AVAILABLE → MAINTENANCE}（P0 新增）</b>：归还检查判定「缺配件」后设备回的是
     *       AVAILABLE，若管理员看记录后认为缺配件影响使用，需要一条把它转维修中的通路
     *       （用户原话「可以手动把设备改成维修中」）。⚠️ 注意方向是单向的：
     *       只允许从 AVAILABLE 进 MAINTENANCE，<b>不允许</b> IN_USE → MAINTENANCE，
     *       后者必须走归还流程（否则设备在借却被标成维修，工单永远收不回）。</li>
     * </ul>
     */
    public static boolean canManualTransfer(DeviceStatus from, DeviceStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        if (to == SCRAPPED) {
            return from == AVAILABLE || from == MAINTENANCE || from == LOST;
        }
        if (to == AVAILABLE) {
            return from == MAINTENANCE || from == LOST;
        }
        if (to == LOST) {
            return from == AVAILABLE || from == MAINTENANCE;
        }
        if (to == MAINTENANCE) {
            // 只放行「空闲设备送修」；IN_USE → MAINTENANCE 必须走归还流程
            return from == AVAILABLE;
        }
        return false;
    }
}
