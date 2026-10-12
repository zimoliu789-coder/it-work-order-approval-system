package com.enterprise.ticket.module.upgrade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 关闭在线升级（V49）
 *
 * <p>用包装类型 {@code Boolean} + {@code @NotNull} 而不是原始 {@code boolean}：
 * 原始类型在字段缺失时会被静默当成 {@code false}，
 * 于是一次「请求体写错字段名」的调用会把升级功能<b>悄悄关掉</b>而不报错。
 * 这是配置类接口上最容易发生、也最难排查的一类事故。
 */
@Data
public class UpgradeToggleRequest {

    /** 是否启用在线升级 */
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
