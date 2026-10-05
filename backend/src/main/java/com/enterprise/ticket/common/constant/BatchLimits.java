package com.enterprise.ticket.common.constant;

/**
 * 批量操作上限（P3 批量操作）
 *
 * <p>单次上限的意义不是性能，而是<b>防误操作</b>：台账「全选」很容易点到 1000+ 台，
 * 而批量改状态是不可逆的（例如误选「报废」）。
 *
 * <p>超限时一律<b>先拒绝、一条都不处理</b> —— 若改成「只处理前 200 条」，
 * 用户会以为全部完成，剩下的要等他自己发现。
 */
public final class BatchLimits {

    /** 单次批量操作的最大条目数 */
    public static final int MAX_SIZE = 200;

    private BatchLimits() {
    }
}
