package com.enterprise.ticket.common.constant;

/**
 * 「当前所在分部」的取值来源（V54 / D5.3）。
 *
 * <h2>为什么来源要落库，而不是只写日志</h2>
 * <p>审计要求「记录取值来源三类」。如果只写操作日志，管理员要回答
 * 「这个月有多少单是员工手动改过分部的」就得去日志表里做文本检索 ——
 * 而这是一个会被反复问到的运营问题（分部被改，意味着派单责任被转移）。
 * 存成枚举列后，一句 GROUP BY 就能回答，且不会因为日志清理而丢失。
 *
 * <h2>三种来源的判定由服务端负责</h2>
 * <p>前端只回传「最终选中的分部 id」。服务端拿它和「此刻的预填值」比对：
 * 相同 = 员工接受了预填（记为预填来源），不同 = 员工手动改了。这样做的原因：
 * <ul>
 *   <li>前端可以篡改任何自报的 source 字段，服务端比对才是事实；</li>
 *   <li>预填逻辑（今天是否在出差中）只在一处实现，前端不需要重复实现一遍。</li>
 * </ul>
 */
public enum DivisionSource {

    /** 人事主部门所属的一级分部（预填兜底） */
    HR_PRIMARY("人事主部门"),

    /** 出差申请自动带入：今天落在某张已通过出差单的起止区间内 */
    TRIP_PREFILL("出差自动带入"),

    /** 员工手动改过（与预填值不同） */
    MANUAL("手动修改");

    private final String label;

    DivisionSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static DivisionSource of(String value) {
        if (value == null) {
            return null;
        }
        for (DivisionSource source : values()) {
            if (source.name().equals(value)) {
                return source;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回（避免展示层出现 null） */
    public static String labelOf(String value) {
        DivisionSource source = of(value);
        return source == null ? value : source.getLabel();
    }
}
