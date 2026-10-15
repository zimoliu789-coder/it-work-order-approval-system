package com.enterprise.ticket.module.upgrade.support;

/**
 * 在线升级的配置键与默认值（V49 引入，V51 拆为两个开关）
 *
 * <h2>它把「开关」从 .env 搬进了数据库</h2>
 * <p>改造前「能不能在线升级」由环境变量 {@code UPGRADE_ENABLED} 决定，
 * 于是每一次开关都要 SSH 改 {@code .env} 再重建容器 —— 对一个「页面上点一下」的动作来说太重了，
 * 而且改错的表现是「功能用不了」，排查还得回头看 .env 到底有没有生效。
 *
 * <p>现在开关落在 {@code system_config}，管理员在「系统设置 → 在线升级」页上点一下即可生效，
 * 与短信 / 邮箱 / 备份等开关完全同一套机制。
 *
 * <h2>为什么从「一个总开关」拆成「两个独立开关」（V51）</h2>
 * <p>V49 时页面按 {@link OpsAgentBridge#agentReady()} <b>二选一</b>显示入口：代理就绪只显示
 * 「一键在线升级」，未就绪只显示「上传升级包」。它的问题有两个：
 * <ol>
 *   <li>两条路径<b>无法同框验证</b> —— 想确认上传链路是否正常，只能把 agent 停掉再看页面；</li>
 *   <li>「就绪」是<b>运行时</b>状态，页面入口会随时间跳来跳去，管理员难以预期。</li>
 * </ol>
 * 需求方 2026-10-13 明确：<b>两个入口都要常显，各自独立开关，且默认都关闭。</b>
 * 因此拆成两个键 —— 执行方式仍是客观事实（在卡片内以提示说明），
 * 但<b>可用性交由管理员显式决定</b>，而不是由心跳推断。
 *
 * <h2>为什么没有「执行方式」键</h2>
 * <p>直觉上还会想要一个 {@code upgrade_apply_mode}（agent / script / manual）。
 * 但执行方式并不是一个 <b>用户偏好</b>，而是一个 <b>客观事实</b>：
 * 能自动执行，当且仅当运维代理（ops-agent）正在心跳。
 * 拆成两个开关改的是「哪个入口可用」，不是「这个入口靠什么执行」——
 * 后者仍由 {@link OpsAgentBridge#agentReady()} 在运行时判定，页面上不做选项。
 *
 * <h2>键名是跨三处的契约</h2>
 * <p>与 {@code ExceptionAlertSettings} 同一约定 —— 键名有多处引用，任一处写错都不会编译报错，
 * 只会让开关「点了没反应」或「读到一个不存在的键」。这三处必须逐字一致：
 * <ol>
 *   <li>本类的 {@link #KEY_ONLINE_ENABLED} / {@link #KEY_PACKAGE_ENABLED}；</li>
 *   <li>Flyway 迁移 {@code V51__split_upgrade_switches.sql} 的初值；</li>
 *   <li>读取它的服务层（{@code UpgradeService#onlineEnabled()} / {@code #packageEnabled()}）。</li>
 * </ol>
 *
 * <p><b>刻意不进 {@code SystemConfigCatalog}</b>：那个目录是「系统参数页」的渲染来源，
 * 而这两个开关的界面入口就在<b>在线升级页</b>上 —— 每张入口卡片自带一个开关。
 * 同一件事在两张页面上各有一个开关，只会让人怀疑「这两个是不是同一个」。
 * 数据库里仍保留 {@code config_group='upgrade'} 以便将来归类与导出。
 */
public final class UpgradeSettings {

    private UpgradeSettings() {
    }

    /** 系统参数页里承载本组的分区编码（与 {@code SystemConfigCatalog} 的分组 code 一致） */
    public static final String GROUP_CODE = "upgrade";

    /**
     * 「一键在线升级」开关（拉取最新镜像并重建容器）。
     *
     * <p>V51 起独立控制升级页的在线升级入口；不再与上传包共用总开关。
     */
    public static final String KEY_ONLINE_ENABLED = "upgrade_online_enabled";

    /**
     * 「上传升级包」开关（校验 → 备份 → 落 staging → 待应用）。
     *
     * <p>V51 起独立控制升级页的上传入口；不再与在线升级共用总开关。
     */
    public static final String KEY_PACKAGE_ENABLED = "upgrade_package_enabled";

    /**
     * 两个开关默认都是 <b>关闭</b>。
     *
     * <p>与 {@code app.upgrade.enabled}（环境变量 {@code UPGRADE_ENABLED}）、
     * {@code backup_enabled}、主备开关同一取向：
     * <b>会改变系统运行形态的能力，默认都不开启。</b>
     * 升级能替换服务器上的可执行文件，等价于代码执行能力 —— 装好不等于该打开。
     */
    public static final boolean DEFAULT_ENABLED = false;

    /**
     * 升级入口。每个入口<b>携带自己的配置键</b> ——
     * 这样「按入口读写开关」只需传枚举，不必在多处重复写字符串常量，
     * 也就不会出现「接口传 ONLINE、服务层读了 PACKAGE 的键」这类静默错配。
     */
    public enum Mode {

        /** 一键在线升级：拉取最新镜像并重建容器（需要运维代理） */
        ONLINE(KEY_ONLINE_ENABLED, "一键在线升级"),

        /** 上传升级包：只服务非容器化部署 */
        PACKAGE(KEY_PACKAGE_ENABLED, "上传升级包");

        private final String configKey;
        private final String label;

        Mode(String configKey, String label) {
            this.configKey = configKey;
            this.label = label;
        }

        /** 该入口在 {@code system_config} 里的开关键 */
        public String configKey() {
            return configKey;
        }

        /** 中文名，用于日志与错误提示 */
        public String label() {
            return label;
        }

        /**
         * 解析前端传来的模式串；无法识别时返回 {@code null}（由调用方决定如何报错）。
         *
         * <p>不抛异常、也不静默回落到某个默认值：静默回落会把
         * 「前端传了拼错的值」变成「改错了另一个开关」，是最难查的一类配置事故。
         */
        public static Mode of(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.trim();
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }
}
