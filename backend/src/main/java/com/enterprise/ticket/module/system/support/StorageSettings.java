package com.enterprise.ticket.module.system.support;

import org.springframework.util.StringUtils;

/**
 * 文件存储与保留天数参数契约（）。
 *
 * <h2>三件事为什么放在一起</h2>
 * <p>它们共同回答同一个运维问题：「磁盘上的东西放在哪、留多久」。
 * 拆开登记会让配置页出现三张各说一句话的卡片，而这个问题的答案必须在一处看全 ——
 * 尤其是当管理员准备把附件目录指向 NAS 挂载点时，他同时需要知道
 * 「导出的临时文件也会跟着占空间」以及「保留天数决定多久回收」。
 *
 * <h2>路径为空时怎么办</h2>
 * <p>{@code storage_attachment_path} 留空 = <b>沿用 {@code app.attachment.storage-root}</b>
 * （开发环境是 {@code ./data/attachments}）。刻意不在这里写死一个默认路径：
 * 那个路径属于「部署形态」而非「业务参数」，由 {@code application.yml} 决定，
 * 两边各写一份必然在换部署环境时对不上。
 * 判定集中在 {@link #resolveRoot(String, String)}。
 *
 * <h2>为什么保留天数有下限</h2>
 * <p>配成 0 意味着「刚删就清」，而「删除工单」与「附件落盘」之间存在时间差 ——
 * 一旦清理任务恰好在窗口内跑，会把仍在引用中的文件删掉。
 * 下限 1 天是给这个窗口留的安全余量。
 */
public final class StorageSettings {

    private StorageSettings() {
    }

    // ------------------------------------------------------------------
    // 配置键（与 V28 迁移脚本里的 config_key 逐字对应）
    // ------------------------------------------------------------------

    /** 附件存储根目录（支持 NAS 挂载路径，如 /mnt/nas/attachments） */
    public static final String KEY_ATTACHMENT_PATH = "storage_attachment_path";

    /** 已删除工单的附件保留天数（超期物理删除） */
    public static final String KEY_ATTACHMENT_RETENTION_DAYS = "attachment_retention_days";

    /** 导出 Excel 临时文件保留天数 */
    public static final String KEY_EXPORT_RETENTION_DAYS = "export_retention_days";

    // ------------------------------------------------------------------
    // 内置默认值
    // ------------------------------------------------------------------

    /** 附件路径默认留空（= 沿用部署配置里的本地路径） */
    public static final String DEFAULT_ATTACHMENT_PATH = "";

    /**
     * 已删除工单附件默认保留 30 天。
     *
     * <p>取值理由：工单被删除后仍可能有「误删恢复」诉求，附件是恢复时最不可再生的部分；
     * 30 天覆盖了绝大多数「发现删错了」的时间窗，同时避免磁盘长期堆积。
     */
    public static final int DEFAULT_ATTACHMENT_RETENTION_DAYS = 30;

    /**
     * 导出临时文件默认保留 7 天。
     *
     * <p>与既有 {@code app.export.expire-days} 默认值保持一致 ——
     * 导出文件是纯派生物，可随时重导，因此留存期远短于附件。
     */
    public static final int DEFAULT_EXPORT_RETENTION_DAYS = 7;

    /**
     * 求生效的附件根目录。
     *
     * @param configured 配置页填写的路径（可为空）
     * @param fallback   部署配置里的本地路径（来自 {@code app.attachment.storage-root}）
     * @return 生效路径（必然非空）
     */
    public static String resolveRoot(String configured, String fallback) {
        return StringUtils.hasText(configured) ? configured.trim() : fallback;
    }

    /** 配置页展示的说明（不参与运行时判定） */
    public static final String[] REFERENCE_NOTES = {
            "附件目录可指向 NAS 挂载点（如 /mnt/nas/attachments）；留空则沿用部署配置里的本地目录。",
            "主备双机部署时，两台机器必须挂载同一个 NAS 路径，否则备机看不到主机写入的附件。",
            "目录需要运行账号有读写权限；路径变更不会自动搬迁已有文件，迁移历史附件请手工拷贝。"
    };
}
