package com.enterprise.ticket.module.upgrade.dto.vo;

/**
 * 在线升级能力总览
 *
 * <p>页面打开时先拉这个接口，是因为<b>「有没有权限」与「功能开没开」是两件事</b>：
 * 只挂权限码的话，一个开着升级页的超管会在生产环境（{@code enabled=false}）
 * 看到一个能点、点了却报错的界面 —— 他只会以为是系统坏了。
 * 先把开关状态与当前版本摆出来，管理员一眼就知道「这个环境能不能在线升级」。
 *
 * @param enabled              升级功能总开关（app.upgrade.enabled）
 * @param currentVersion       当前已应用版本（读 state/current.json，首次部署为空）
 * @param applyCommandConfigured 是否配置了外部应用命令（未配置 ⇒ 只能到「待应用」为止）
 * @param rollbackEnabled      是否允许回滚
 * @param packageMaxSizeMb     单包大小上限（MB），前端据此做前置校验与文案
 * @param activeTaskNo         当前进行中的任务号（无则为空），前端据此直接恢复进度视图
 */
public record UpgradeOverviewVO(
        boolean enabled,
        String currentVersion,
        boolean applyCommandConfigured,
        boolean rollbackEnabled,
        int packageMaxSizeMb,
        String activeTaskNo
) {
}
