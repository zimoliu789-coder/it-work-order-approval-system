package com.enterprise.ticket.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 在线升级任务状态
 *
 * <h2>状态流转</h2>
 * <pre>
 *   后端进程内完成的部分（一次上传请求里跑完）：
 *     PENDING → VALIDATING → BACKING_UP → STAGING → READY_TO_APPLY
 *                                                        │
 *                            配了 apply-command 时由后端发起外部脚本 ↓
 *                                                     APPLYING
 *                                                        │
 *                          下次启动读 state/result/&lt;taskNo&gt;.json 回填 ↓
 *                                       SUCCESS / ROLLED_BACK / FAILED
 *   任意步骤失败（未进入 APPLYING 前）：
 *     → FAILED（备份保留，可手动回滚）
 *   应用前主动放弃 / 应用后手动还原：
 *     → ROLLED_BACK
 * </pre>
 *
 * <h2>为什么把「后端完成」与「外部应用」分成两段状态</h2>
 * <p>后端进程<b>无法替换自己正在运行的 jar</b>：Windows 上文件被占用直接失败，
 * Linux 上替换成功但已加载的类不会重新载入，仍须重启。因此「替换 + 重启」必须落在
 * 进程外的编排脚本上，{@link #READY_TO_APPLY} 就是这条职责边界的显式表达 ——
 * 它同时是<b>后端职责的终点</b>与<b>运维视角的起点</b>。
 *
 * <p>这条边界带来的直接好处：整条链路在「没有 systemd / 没有 Docker」的开发机上
 * 也能完整跑通并验收（停在 READY_TO_APPLY 即为成功），
 * 不需要「进程真的重启成功」才能证明功能正确。
 *
 * <h2>为什么 READY_TO_APPLY 算「活跃」而不是终态</h2>
 * <p>它代表「产物已备好、但还没换上去」。此时若允许发起第二次升级，第二个包会覆盖
 * staging / 抢占唯一约束，让管理员彻底搞不清最后生效的是哪个包。
 * 因此它必须计入活跃集合（见 {@link #isActive()}），由 V30 的生成列唯一索引挡住并发。
 * 代价是：一个卡住的任务会一直阻塞后续升级 —— 这是刻意的 fail-closed，
 * 管理员必须显式「回滚」或「标记完成」才能继续。
 */
public enum UpgradeStatus {

    /** 已受理，尚未开始处理 */
    PENDING("待处理"),

    /** 校验中：包结构 / manifest / SHA-256 / 路径安全 */
    VALIDATING("校验中"),

    /** 备份中：把当前生效产物复制到 backup/&lt;taskNo&gt; */
    BACKING_UP("备份中"),

    /** 落盘中：解压新产物到 staging/&lt;taskNo&gt; */
    STAGING("落盘中"),

    /** 已就绪待应用：后端职责完成，等待外部编排脚本替换并重启 */
    READY_TO_APPLY("已就绪待应用"),

    /** 应用中：已发起外部脚本，等待其回执（进程可能随时被替换掉） */
    APPLYING("应用中"),

    /** 升级成功（由外部回执确认） */
    SUCCESS("升级成功"),

    /** 升级失败（原因见 message） */
    FAILED("升级失败"),

    /** 已回滚（外部脚本健康检查失败后自动回滚，或管理员手动回滚） */
    ROLLED_BACK("已回滚");

    /**
     * 活跃状态集合 —— 与 V30 迁移里 {@code active_flag} 生成列的 CASE 表达式
     * 必须<b>逐字一致</b>：SQL 侧的枚举值写错（例如漏了 STAGING），
     * 并发保护会静默失效，而应用层看起来一切正常。
     */
    private static final Set<String> ACTIVE = Set.of(
            PENDING.name(), VALIDATING.name(), BACKING_UP.name(),
            STAGING.name(), READY_TO_APPLY.name(), APPLYING.name());

    /** 全部状态名（供 SQL / 前端筛选项引用，避免各处硬编码字符串） */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    private final String label;

    UpgradeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否为活跃状态（进行中，未达终态） */
    public boolean isActive() {
        return ACTIVE.contains(name());
    }

    /** 是否为终态（不会再变化） */
    public boolean isTerminal() {
        return !isActive();
    }

    /** 是否允许手动回滚：已就绪但未应用、或已失败 */
    public boolean isRollbackable() {
        return this == READY_TO_APPLY || this == FAILED || this == SUCCESS;
    }

    public static UpgradeStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (UpgradeStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        UpgradeStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
