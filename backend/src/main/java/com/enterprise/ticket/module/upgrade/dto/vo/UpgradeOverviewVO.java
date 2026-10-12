package com.enterprise.ticket.module.upgrade.dto.vo;

/**
 * 在线升级能力总览
 *
 * <p>页面打开时先拉这个接口，是因为<b>「有没有权限」与「功能开没开」是两件事</b>：
 * 只挂权限码的话，一个开着升级页的超管会在生产环境（开关关闭）
 * 看到一个能点、点了却报错的界面 —— 他只会以为是系统坏了。
 * 先把开关状态与当前版本摆出来，管理员一眼就知道「这个环境能不能在线升级」。
 *
 * <p>V49 起「开关」来自 {@code system_config}（页面可直接改），环境变量只剩兜底作用 ——
 * 因此额外下发 {@link #envForced}：当环境变量把功能钉在「开」上时，
 * 页面必须说清楚「这个开关关不掉，是部署配置决定的」，否则管理员会以为按钮坏了。
 *
 * @param enabled              升级功能总开关（生效值 = 数据库开关 或 环境变量）
 * @param envForced            环境变量强制开启（页面开关无法关闭它）
 * @param currentVersion       当前已应用版本（读 state/current.json，首次部署为空）
 * @param agentReady           运维代理是否就绪（心跳新鲜）—— 就绪时才可能「点一下就自动升级」
 * @param agentVersion         运维代理版本（未就绪为空，仅供展示）
 * @param applyCommandConfigured 是否配置了外部应用命令（agent 未就绪时的退路）
 * @param rollbackEnabled      是否允许回滚
 * @param packageMaxSizeMb     单包大小上限（MB），前端据此做前置校验与文案
 * @param activeTaskNo         当前进行中的任务号（无则为空），前端据此直接恢复进度视图
 */
public record UpgradeOverviewVO(
        boolean enabled,
        boolean envForced,
        String currentVersion,
        boolean agentReady,
        String agentVersion,
        boolean applyCommandConfigured,
        boolean rollbackEnabled,
        int packageMaxSizeMb,
        String activeTaskNo
) {
}
