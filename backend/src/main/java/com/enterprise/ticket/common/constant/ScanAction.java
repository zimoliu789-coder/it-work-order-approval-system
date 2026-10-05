package com.enterprise.ticket.common.constant;

/**
 * 扫码借还的「建议动作」（P1 扫码借还）
 *
 * <p>「扫到一个码之后该做什么」由**服务端**判定后下发，前端只负责渲染与跳转 ——
 * 与 {@code canRequestReturn} / {@code canRequestExtend} 保持同一套「判定不下放前端」的思路。
 *
 * <h2>为什么要服务端判定而不是前端拼条件</h2>
 * <p>扫码结果要同时回答三件事：这个码是什么（设备还是工单）、我能不能操作它、
 * 接下来该跳哪个页面。这三件事依赖「当前登录人」「设备状态」「锁定超时」「我的在借工单」
 * 四个事实，任一项前端都可能拿到过期数据（列表是几分钟前拉的）。
 * 放服务端算，前端只按 {@link #BORROW} / {@link #RETURN} / {@link #VIEW} 三个分支跳转即可。
 */
public final class ScanAction {

    private ScanAction() {
    }

    /** 可借用：设备空闲（或临时锁已超时可直接接管）⇒ 跳借用申请并预选设备 */
    public static final String BORROW = "BORROW";

    /** 可归还：该设备当前有「我的在借工单」⇒ 跳归还确认 */
    public static final String RETURN = "RETURN";

    /** 可查看：扫的是工单号且我有权看 ⇒ 跳工单详情（**不**自动触发任何写操作） */
    public static final String VIEW = "VIEW";

    /** 不可操作：识别到了设备/工单，但当前不可借或我无权看 ⇒ 页面展示 {@code reason} */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** 未识别：内容为空或库里查不到 */
    public static final String NONE = "NONE";
}
