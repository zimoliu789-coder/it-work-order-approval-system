package com.enterprise.ticket.common.constant;

/**
 * 主备节点角色（，  [164]）
 *
 * <p>说明只要求两档：「主节点 / 备节点」。刻意<b>不</b>引入第三种角色
 * （如 {@code CANDIDATE} / {@code WITNESS}）—— 本系统的主备是
 * 「一主一备 + keepalived 漂移虚拟 IP」的最简形态（见 {@code deploy/DEPLOY.md} ），
 * 多一种角色就意味着多一套 keepalived {@code priority} / {@code nopreempt} 组合，
 * 而现场维护人员并不具备理解这套组合的知识储备 —— 需求 [158] 行
 * 「维护人员不懂 keepalived、数据库主从、rsync 等专业技术」正是本次要消除的现状。
 *
 * <p><b>角色的唯一事实源是 {@code ha_node} 里 {@code is_local = 1} 那一行。</b>
 * {@code ha_config} 刻意不再冗余存一份本机角色 —— 两处存同一事实必然出现
 * 「配置页显示主节点、节点表显示备节点」这类自相矛盾的展示（本项目已有前车之鉴，
 * 见 {@code PROJECT_NOTES.md} 关于「同一事实两处存储」的记录）。
 */
public enum HaRole {

    /** 主节点：持有虚拟 IP，对外提供服务，写操作落在此节点 */
    MASTER("主节点"),

    /** 备节点：待命，实时接收主节点同步，主节点故障时接管 */
    STANDBY("备节点");

    private final String label;

    HaRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isMaster() {
        return this == MASTER;
    }

    public static HaRole of(String value) {
        if (value == null) {
            return null;
        }
        for (HaRole role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 中文名，非法值原样返回。
     *
     * <p>与 {@link MessageType#labelOf} / {@link UpgradeStatus#labelOf} 同口径：
     * 展示层绝不能因为一个脏值渲染出 {@code null}。
     */
    public static String labelOf(String value) {
        HaRole role = of(value);
        return role == null ? value : role.getLabel();
    }
}
