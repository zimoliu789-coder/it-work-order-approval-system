package com.enterprise.ticket.common.constant;

/**
 * 审批节点签署模式（ / ）
 *
 * <p>会签（ALL_SIGN）：该节点审批人全部通过才进入下一步；
 * 或签（ANY_SIGN）：该节点任意一人通过即进入下一步（并发时加乐观锁，第一个通过生效）。
 */
public final class SignType {

    /** 会签：全部通过 */
    public static final String ALL_SIGN = "ALL_SIGN";

    /** 或签：任意一人通过 */
    public static final String ANY_SIGN = "ANY_SIGN";

    private SignType() {
    }

    public static boolean isValid(String value) {
        return ALL_SIGN.equals(value) || ANY_SIGN.equals(value);
    }

    /** 非法值统一回落为或签，避免脏数据导致审批流转异常 */
    public static String normalize(String value) {
        return isValid(value) ? value : ANY_SIGN;
    }
}
