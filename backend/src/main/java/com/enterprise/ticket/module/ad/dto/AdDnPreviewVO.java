package com.enterprise.ticket.module.ad.dto;

import lombok.Data;

/**
 * DN 自动推导预览结果
 *
 * <p>与保存流程<b>共用同一份规则</b>（{@code AdDnResolver.resolve}），
 * 因此「预览显示的值」与「落库的值」必然一致 —— 不会出现
 * 「界面上写 DC=company,DC=com，保存后变成别的」这种信任崩塌。
 */
@Data
public class AdDnPreviewVO {

    /** 推导 / 归一后的基础 DN；无法推导时为 {@code null}（前端应提示改填「高级选项 → 自定义基础 DN」） */
    private String baseDn;

    /** 推导 / 归一后的绑定身份；未填时为 {@code null} */
    private String bindDn;

    /** 基础 DN 是否来自自动推导（true = 维护人员没填，由域地址推出） */
    private boolean baseDnAuto;

    /** 绑定账号是否被改写过（true = 输入的是 {@code company\query} 之类的写法，已转成完整 DN） */
    private boolean bindDnAuto;

    /** 面向维护人员的一句话说明（直接展示，无需前端再拼文案） */
    private String hint;
}
