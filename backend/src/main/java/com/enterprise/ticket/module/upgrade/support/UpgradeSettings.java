package com.enterprise.ticket.module.upgrade.support;

/**
 * 在线升级的配置键与默认值（V49）
 *
 * <h2>它把「开关」从 .env 搬进了数据库</h2>
 * <p>改造前「能不能在线升级」由环境变量 {@code UPGRADE_ENABLED} 决定，
 * 于是每一次开关都要 SSH 改 {@code .env} 再重建容器 —— 对一个「页面上点一下」的动作来说太重了，
 * 而且改错的表现是「功能用不了」，排查还得回头看 .env 到底有没有生效。
 *
 * <p>现在开关落在 {@code system_config}，管理员在「系统参数 → 在线升级」里点一下即可生效，
 * 与短信 / 邮箱 / 备份等开关完全同一套机制。
 *
 * <h2>为什么只有一个键（没有「执行方式」键）</h2>
 * <p>直觉上还会想要一个 {@code upgrade_apply_mode}（agent / script / manual）。
 * 但执行方式并不是一个**用户偏好**，而是一个**客观事实**：
 * 能自动执行，当且仅当运维代理（ops-agent）正在心跳。让管理员去选一个他无法验证的选项，
 * 只会制造「选了 agent 但其实没装」这类假配置。
 * 因此执行方式由 {@link OpsAgentBridge#agentReady()} 在运行时自动判定，页面上不做选项。
 *
 * <h2>键名是跨三处的契约</h2>
 * <p>与 {@code ExceptionAlertSettings} 同一约定 —— 键名有多处引用，任一处写错都不会编译报错，
 * 只会让开关「点了没反应」或「读到一个不存在的键」。这三处必须逐字一致：
 * <ol>
 *   <li>本类的 {@link #KEY_ENABLED}；</li>
 *   <li>Flyway 迁移 {@code V49__upgrade_settings.sql} 的初值；</li>
 *   <li>读取它的服务层（{@code UpgradeService#enabled()}）。</li>
 * </ol>
 *
 * <p><b>刻意不进 {@code SystemConfigCatalog}</b>：那个目录是「系统参数页」的渲染来源，
 * 而本开关的界面入口就在<b>在线升级页</b>上（需求：「页面上直接放个启用在线升级开关，打开就能用」）。
 * 同一件事在两张页面上各有一个开关，只会让人怀疑「这两个是不是同一个」。
 * 因此它只出现在升级页 —— 配置目录不为它登记，也就不会被系统参数页渲染出来。
 * 数据库里仍需一行 {@code config_group='upgrade'} 以便将来归类与导出。
 */
public final class UpgradeSettings {

    private UpgradeSettings() {
    }

    /** 系统参数页里承载本组的分区编码（与 {@code SystemConfigCatalog} 的分组 code 一致） */
    public static final String GROUP_CODE = "upgrade";

    /**
     * 在线升级总开关。默认**关闭**。
     *
     * <p>与 {@code app.upgrade.enabled}（环境变量 {@code UPGRADE_ENABLED}）默认 false 同一取向：
     * <b>会改变系统运行形态的能力，默认都不开启。</b>
     * 在线升级能替换服务器上的可执行文件，等价于代码执行能力 —— 装好不等于该打开。
     */
    public static final String KEY_ENABLED = "upgrade_enabled";
    public static final boolean DEFAULT_ENABLED = false;
}
