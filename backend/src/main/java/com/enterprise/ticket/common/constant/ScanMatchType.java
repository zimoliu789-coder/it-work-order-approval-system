package com.enterprise.ticket.common.constant;

/**
 * 扫码内容的命中类型（P1 扫码借还）
 *
 * <p>用户扫到的可能是贴在设备上的资产标签，也可能是打印在单据上的工单号 ——
 * 两种码的后续动作完全不同（前者去借/还，后者去查看），因此必须区分开。
 */
public final class ScanMatchType {

    private ScanMatchType() {
    }

    /** 命中设备（扫的是资产编号） */
    public static final String ASSET_NO = "ASSET_NO";

    /** 命中工单（扫的是工单号） */
    public static final String ORDER_NO = "ORDER_NO";

    /** 什么都没命中 */
    public static final String NONE = "NONE";
}
