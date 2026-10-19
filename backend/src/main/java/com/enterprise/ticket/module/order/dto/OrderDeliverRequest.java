package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 交付确认请求（V56 / 第 9 项）。
 *
 * <h2>为什么交付要带资产编号</h2>
 * <p>改造后申请人提交时只选「分类 + 型号」，<b>没有具体设备</b>。
 * 设备绑定被推迟到 IT 交付这一刻：IT 拿着实机扫一下资产编号（或手输），
 * 系统做三层校验（分类 → 型号 → 空闲）后把设备与工单绑在一起，并直接置为「使用中」。
 *
 * <p><b>可以不传</b>：V56 之前提交的历史工单在提交时就绑定了设备（当时走临时锁，
 * 设备处于「审批中」），那批单交付时只需要确认，无需再扫码。服务端按
 * {@code borrow_order.device_id} 是否为空自动分支，前端不必判断。
 */
@Data
public class OrderDeliverRequest {

    /**
     * 要交付的设备资产编号（扫码枪读到的值或手输）。
     *
     * <p>只对新单（{@code device_id} 为空）必填；历史单忽略此字段。
     * 传工单号、带前缀的扫码内容都不需要 —— 交付场景只认资产编号，
     * 这一点与「扫码查询」（{@code /orders/scan}）不同：那里是问「这是什么」，
     * 这里已经明确要交付哪一笔单，只需要确认「发哪一台」。
     */
    @Size(max = 64, message = "资产编号长度不合法")
    private String assetNo;
}
