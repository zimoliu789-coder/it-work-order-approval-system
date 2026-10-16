package com.enterprise.ticket.common.trip;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 出差申请的业务规则（V53）
 *
 * <h2>为什么单独抽出来</h2>
 * 「同一员工不允许存在时间区间重叠的生效出差」这条规则有<b>三个消费方</b>：
 * <ol>
 *   <li><b>提交拦截</b>：新增出差单时校验，重叠则拒绝提交；</li>
 *   <li><b>「当前所在分部」预填</b>：判断某条出差单「今天是否在出差中」；</li>
 *   <li>将来的报表 / 看板（「本月在出差的人」）。</li>
 * </ol>
 * 三处各写一遍区间判断，迟早会漂移 —— 而区间边界（<b>闭合还是半开</b>）一旦不一致，
 * 表现是「提交时说不重叠、预填时说不生效」这种自相矛盾的组合。因此判定只在这里实现一次。
 *
 * <h2>字段 key 为什么写死在这里</h2>
 * 出差单的起止日期与目标分部由后端**按固定 key 读取**（见 {@code FormField#systemField}）：
 * 这三个字段在设计器里不允许删除，正是为了让本类永远读得到值。
 * key 用常量而不是各处写字符串 —— 写错一个字母就会「读不到值、静默走兜底」。
 */
public final class BusinessTripRules {

    private BusinessTripRules() {
    }

    /** 出差开始日期（表单字段 key，与播种的 schema 逐字对应） */
    public static final String FIELD_START_DATE = "trip_start_date";

    /** 出差结束日期 */
    public static final String FIELD_END_DATE = "trip_end_date";

    /** 目标一级分部（存部门 id） */
    public static final String FIELD_TARGET_DEPT = "trip_target_dept";

    /** 出差单类型编码（与 {@code OrderType.TRIP} 一致） */
    public static final String TYPE_CODE = "TRIP";

    /**
     * 两个日期区间是否**重叠**（闭区间：首日与末日都算在出差中）。
     *
     * <p><b>边界口径</b>：{@code [a1, a2]} 与 {@code [b1, b2]} 重叠 ⟺ {@code a1 <= b2 && b1 <= a2}。
     * 也就是<b>「同一天结束又开始」算重叠</b> —— 一个人不可能当天在北京结束出差、
     * 同时在上海开始下一个出差。业务前提（用户明确）：同一员工同一时间只能有一份生效出差。
     *
     * @return 任一参数为 null 时返回 false（不完整的数据不参与判定，交由必填校验去拦）
     */
    public static boolean overlaps(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    /**
     * 给定日期是否落在出差区间内（含首尾）—— 「今天是否正在出差」。
     *
     * @return 任一参数为 null 时返回 false
     */
    public static boolean covers(LocalDate start, LocalDate end, LocalDate day) {
        if (start == null || end == null || day == null) {
            return false;
        }
        return !day.isBefore(start) && !day.isAfter(end);
    }

    /**
     * 解析表单里的日期值（{@code yyyy-MM-dd}）。
     *
     * <p>宽松到「读不出来就返回 null」：表单数据来自历史工单，可能因改字段类型而变得不合格式；
     * 这里抛异常会让「打开提交页」直接 500，而它本来只是**预填的一个输入**。
     * 读不出来自然走兜底分支（回退人事主部门）即可。
     */
    public static LocalDate parseDate(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        // 兼容「2026-10-16 00:00:00」这类带时间的写法：只取日期部分
        if (text.length() > 10 && text.charAt(10) == ' ') {
            text = text.substring(0, 10);
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 目标一级分部的部门 id（读不出来返回 null） */
    public static Long parseDeptId(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
