package com.enterprise.ticket.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 扫码核对请求（P2）
 *
 * <p>用**资产编号**而不是明细 id 来定位：手机上扫码得到的就是资产编号，
 * 若要求客户端先查到 itemId 再提交，等于把「码 → 记录」的匹配责任推给前端 ——
 * 而那个匹配恰好是服务端最容易做对的事（同任务内 asset_no 唯一）。
 *
 * <p>这也让「扫到一个不在本任务范围内的设备」能被明确拒绝，而不是静默把结果写到别的任务上。
 */
@Data
public class InventoryCheckRequest {

    @NotBlank(message = "请提供资产编号")
    @Size(max = 64, message = "资产编号过长")
    private String assetNo;

    /** IN_PLACE / MISSING / WRONG_LOCATION（见 {@code InventoryCheckResult}） */
    @NotBlank(message = "请选择核对结果")
    private String checkResult;

    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}
