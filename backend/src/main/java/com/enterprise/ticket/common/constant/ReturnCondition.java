package com.enterprise.ticket.common.constant;

/**
 * 收回时的设备检查结果（ 新增 → P0 改造为四值结构化检查）
 *
 * <p>这是归还场景下唯一的「设备去向决定点」，规则内聚在本枚举，避免散落在服务层的
 * if/else 里漂移（与  {@link DeviceStatus#canManualTransfer} 的处理方式一致）。
 *
 * <h2>P0 为什么把三值改成四值</h2>
 * 原枚举是「完好 / 轻微损坏 / 故障」，它把两类<b>完全不同性质</b>的问题挤进了同一档：
 * <ul>
 *   <li><b>设备坏了</b> —— 设备自身的问题，该进维修；</li>
 *   <li><b>配件没还</b> —— 借用人的履约问题，设备本身是好的。</li>
 * </ul>
 * 旧枚举里「少一个鼠标」只能选「轻微损坏」再写进备注，后果有三：
 * 归还结果<b>无法统计</b>（分不清多少台是真坏）、管理员<b>收不到追回提醒</b>、
 * 而设备本身其实随时可以再借。<b>丢失</b>更无处可归 —— 它既不是损坏也不是报废。
 *
 * <h2>四值语义与去向（用户 2026-10-03 拍板）</h2>
 * <pre>
 * GOOD          完好     → 设备 AVAILABLE        说明可选
 * DAMAGED       损坏     → 设备 MAINTENANCE      说明必填，自动建故障记录
 * MISSING_PARTS 缺配件   → 设备 AVAILABLE        说明必填（缺了什么），通知管理员追回
 * LOST          丢失     → 设备 LOST             说明必填，通知管理员查找
 * </pre>
 *
 * <h2>⚠️「缺配件」为什么回 AVAILABLE 而不是 MAINTENANCE（本枚举最反直觉的一条）</h2>
 * 用户明确要求，且理由成立：<b>设备主体是好的</b>。因为少一个鼠标就把整台电脑锁进维修，
 * 会让<b>可用资产凭空减少</b> —— 而维修流程本来是给「设备坏了」用的。
 * 缺配件是<b>借用人的履约问题</b>，应当用「通知管理员追回」处理，而不是用设备状态处理。
 * 追回后若管理员判断确实影响使用，可在设备台账上<b>手动</b>改「维修中」
 * （见 {@link DeviceStatus#canManualTransfer} 的 AVAILABLE → MAINTENANCE 分支）。
 *
 * <h2>照片附件</h2>
 * 四种结果都可上传归还照片（{@code RETURN_PHOTO}，图片限定），由前端统一承载，
 * 不在本枚举里区分 —— 是否需要照片依现场而定，硬性要求会逼出「随手拍一张凑数」。
 */
public enum ReturnCondition {

    /** 完好 —— 设备直接回到可用 */
    GOOD("完好", DeviceStatus.AVAILABLE, false, false, false),

    /**
     * 损坏 —— 设备进维修中，并自动生成故障记录（「归还时登记故障：关联故障记录」）。
     *
     * <p>说明必填：损坏情况是维修人员的第一手线索，也是后续追责的唯一依据。
     */
    DAMAGED("损坏", DeviceStatus.MAINTENANCE, true, true, false),

    /**
     * 缺配件 —— 设备主体回可用，缺什么写进说明，并通知管理员去追回。
     *
     * <p>⚠️ <b>不建故障记录</b>：配件缺失不是设备故障，
     * 建了会让「故障统计」把「少一根数据线」也算成一次设备故障。
     * 见类注释「为什么回 AVAILABLE」。
     */
    MISSING_PARTS("缺配件", DeviceStatus.AVAILABLE, true, false, true),

    /**
     * 丢失 —— 设备置为 {@link DeviceStatus#LOST}（找回后可手动改回可用）。
     *
     * <p>丢失与报废是两件事：<b>丢失是暂时找不到，报废是确定不要了</b>。
     * 若把丢失直接记成报废，资产台账上就再也分不出「今年丢了几台」。
     */
    LOST("丢失", DeviceStatus.LOST, true, false, true);

    private final String label;
    private final DeviceStatus deviceStatus;
    private final boolean remarkRequired;
    private final boolean createFaultRecord;
    private final boolean notifyRecovery;

    ReturnCondition(String label, DeviceStatus deviceStatus,
                    boolean remarkRequired, boolean createFaultRecord, boolean notifyRecovery) {
        this.label = label;
        this.deviceStatus = deviceStatus;
        this.remarkRequired = remarkRequired;
        this.createFaultRecord = createFaultRecord;
        this.notifyRecovery = notifyRecovery;
    }

    public String getLabel() {
        return label;
    }

    /** 该检查结果对应的设备目标状态 */
    public DeviceStatus getDeviceStatus() {
        return deviceStatus;
    }

    /**
     * 说明（{@code remark}）是否必填。
     *
     * <p>⚠️ 这个判据<b>必须由服务端裁决</b>，不能只靠前端拦：绕过界面直接调接口
     * 就能造出「损坏但没说明」的记录，而它恰恰是后续追责与追回的唯一依据。
     */
    public boolean isRemarkRequired() {
        return remarkRequired;
    }

    /** 是否自动生成故障记录（仅「损坏」为真；见 MISSING_PARTS 的说明） */
    public boolean isCreateFaultRecord() {
        return createFaultRecord;
    }

    /** 是否通知管理员追回 / 查找（缺配件、丢失） */
    public boolean isNotifyRecovery() {
        return notifyRecovery;
    }

    public static ReturnCondition of(String value) {
        if (value == null) {
            return null;
        }
        for (ReturnCondition condition : values()) {
            if (condition.name().equals(value)) {
                return condition;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ReturnCondition condition = of(value);
        return condition == null ? value : condition.getLabel();
    }
}
