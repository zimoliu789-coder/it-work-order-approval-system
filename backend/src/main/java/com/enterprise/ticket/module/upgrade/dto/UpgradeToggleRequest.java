package com.enterprise.ticket.module.upgrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 关闭某一个升级入口（V49 引入，V51 增加 {@code mode}）
 *
 * <p>用包装类型 {@code Boolean} + {@code @NotNull} 而不是原始 {@code boolean}：
 * 原始类型在字段缺失时会被静默当成 {@code false}，
 * 于是一次「请求体写错字段名」的调用会把升级功能<b>悄悄关掉</b>而不报错。
 * 这是配置类接口上最容易发生、也最难排查的一类事故。
 *
 * <p>V51 起 {@code mode} 也是必填（{@code ONLINE} / {@code PACKAGE}）——
 * 同样是为了不静默：若允许缺省，前端一次漏传就会变成「改了另一个开关」，
 * 而管理员看到的只是「我明明拨了这个开关，怎么没反应 / 怎么那个也变了」。
 */
@Data
public class UpgradeToggleRequest {

    /** 要改哪个入口：{@code ONLINE}（一键在线升级）或 {@code PACKAGE}（上传升级包） */
    @NotBlank(message = "mode 不能为空（ONLINE 或 PACKAGE）")
    private String mode;

    /** 该入口是否启用 */
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
