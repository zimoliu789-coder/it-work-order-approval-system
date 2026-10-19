package com.enterprise.ticket.common.constant;

/**
 * 转单范围（V55 / D5.7）。
 *
 * <h2>为什么同组与跨组必须分成两种，而不是"看目标在哪就自动判定"</h2>
 * <p>两者的**发起权限、审批人、生效方式**完全不同：
 * <ul>
 *   <li>同组：当前执行人自己就能发起，但要<b>本组管理员审批</b>；生效时只换人、不换小组；</li>
 *   <li>跨组：<b>只有本组管理员</b>能发起，目标只能是<b>对方小组的管理员</b>，
 *       由对方接收后<b>在自己组内分配</b>；生效时人 + 小组一起换。</li>
 * </ul>
 * 若让系统按「目标是否同组」自动推断，就会出现「组员随手选了个外组的人 ⇒ 系统判定为跨组
 * ⇒ 但他没有跨组权限」这种先选后拒的错位，还不如让他先明确说清楚要哪一种。
 */
public enum TransferScope {

    /** 同组转单：本组内换执行人，本组管理员审批 */
    SAME_GROUP("同组转单"),

    /** 跨组转单：整笔单移交到另一个处理小组，由对方管理员接收并分配 */
    CROSS_GROUP("跨组转单");

    private final String label;

    TransferScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static TransferScope of(String value) {
        if (value == null) {
            return null;
        }
        for (TransferScope scope : values()) {
            if (scope.name().equals(value)) {
                return scope;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回（避免展示层出现 null） */
    public static String labelOf(String value) {
        TransferScope scope = of(value);
        return scope == null ? value : scope.getLabel();
    }
}
