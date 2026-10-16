package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import com.enterprise.ticket.module.upgrade.mapper.UpgradeTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 升级状态启动对账
 *
 * <h2>为什么必须有这个组件</h2>
 * <p>升级的最后一步是<b>重启后端</b>。这意味着「发起升级的那个进程」与
 * 「升级完成后运行的那个进程」<b>不是同一个</b> —— 发起的进程根本没机会写终态。
 * 若不做启动对账，每条升级任务都会永远停在 {@code APPLYING}，
 * 既挡住后续所有升级（活跃唯一键），又让管理员完全无法判断上次到底成没成。
 *
 * <h2>判定依据：两个独立证据，都不依赖「发起的进程还活着」</h2>
 * <ol>
 *   <li><b>结果回执</b>（{@code state/result/&lt;taskNo&gt;.json}）——
 *       外部脚本在替换 + 健康检查之后写下结论。这是最直接的证据；</li>
 *   <li><b>当前版本号</b>（{@code state/current.json}）—— 若它与任务的
 *       {@code targetVersion} 一致，说明新版本此刻正在运行，升级<b>事实上已经成功</b>。
 *       这条兜底非常关键：外部脚本可能在写回执之前就被重启连带杀掉了
 *       （systemd cgroup 的经典现象），此时没有回执，但升级是成功的。</li>
 * </ol>
 * 两者都拿不到时，只在<b>超时</b>后才判失败 —— 否则会在「脚本正在跑」的窗口里
 * 把一条正常推进的任务误判成失败。
 *
 * <h2>为什么不在「事务里」跑</h2>
 * <p>{@link #reconcile(LocalDateTime)} 内每一步都用 CAS 条件更新
 * （{@code WHERE status = 'APPLYING'}）。启动阶段即使被 Spring 的
 * {@code ApplicationRunner} 串行调用，也不假设独占 ——
 * 若将来改成定时任务或双节点同时启动，CAS 仍然保证只有一方写成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpgradeRecoveryRunner implements ApplicationRunner {

    private final UpgradeTaskMapper upgradeTaskMapper;
    private final UpgradeStorage storage;

    /**
     * 「浮动标签」——取值不随某一次升级而变化，因此**不能**用来判断版本是否切换过。
     *
     * <p>不追求穷尽：主判据是「源版本 == 目标版本」（见
     * {@link #versionComparisonMeaningful}），这里只是把明显不具信息量的值提前挡掉。
     */
    private static final Set<String> FLOATING_TAGS =
            Set.of("latest", "main", "master", "dev", "develop", "edge", "stable");

    /** 回执完整性体检的回看窗口：只查这段时间内写入的回执（成本可控） */
    private static final Duration RECEIPT_HEALTH_LOOKBACK = Duration.ofHours(6);

    @Override
    public void run(ApplicationArguments args) {
        try {
            int changed = reconcile(LocalDateTime.now());
            if (changed > 0) {
                log.info("[升级] 启动对账完成：更新 {} 条升级任务状态", changed);
            }
        } catch (Exception e) {
            // 对账失败绝不能让应用起不来 —— 它只影响「任务状态显示」，
            // 而应用起不来会让整个系统不可用，两者不是一个量级
            log.warn("[升级] 启动对账失败（已忽略，不影响启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 对账入口（纯逻辑，可在单测里用固定时间直接调用）。
     *
     * @param now 判定超时用的「当前时间」；由调用方传入以便测试
     * @return 被更新状态的任务数
     */
    public int reconcile(LocalDateTime now) {
        int timeoutMinutes = storage.config().getApplyTimeoutMinutes();
        int changed = 0;

        // ---- ⓪ 回执完整性体检 ----
        // 必须放在最前面：它要抓的场景（回执已到、任务却早已收尾）恰恰发生在
        // `selectApplying()` 为空的时候，放在末尾会被下面那句提前 return 跳过。
        // 它只记日志、不改状态，与下面的收尾逻辑互不干涉。
        warnOnDroppedReceipt();

        // ---- ① 处理中（PENDING / VALIDATING / BACKING_UP / STAGING）但已过期的僵尸任务 ----
        // 场景：后端在「上传处理」过程中被杀掉（部署、OOM、误 kill）。
        // 这些任务不会有人再推进，却一直占着 V30 的活跃唯一键，
        // 让后续每一次升级都被「已有未完成的升级任务」挡在门外。
        List<UpgradeTask> stale = upgradeTaskMapper.selectStaleProcessing(now.minusMinutes(timeoutMinutes));
        for (UpgradeTask task : stale) {
            changed += upgradeTaskMapper.finish(task.getId(), task.getStatus(), UpgradeStatus.FAILED.name(),
                    "升级处理中断（进程在处理过程中退出），请重新上传升级包");
        }

        // ---- ② 已发起外部应用（APPLYING）但还没回填终态的任务 ----
        List<UpgradeTask> applying = upgradeTaskMapper.selectApplying();
        if (applying.isEmpty()) {
            return changed;
        }

        for (UpgradeTask task : applying) {
            Optional<UpgradeApplyResult> result = storage.readApplyResult(task.getTaskNo());
            if (result.isPresent()) {
                changed += finishByResult(task, result.get());
                continue;
            }

            Optional<String> currentVersion = storage.readCurrentVersion();
            // ⚠️ 先问「这次比较到底有没有信息量」——见 versionComparisonMeaningful。
            //    在浮动标签（latest / main …）或「源版本 == 目标版本」的场景下，
            //    这个条件恒为真，等于**无条件判成功**：
            //    任务会在 pull 还在进行时就被标成 SUCCESS，而随后到达的 FAILED 回执
            //    因为任务已不再是 APPLYING 被静默丢弃 —— 失败被伪装成成功。
            //    （2026-10-16 现场实跑踩到，见 deploy/README.md「一键在线升级的真实前提」）
            if (versionComparisonMeaningful(task)
                    && currentVersion.isPresent()
                    && currentVersion.get().equals(task.getTargetVersion())) {
                changed += upgradeTaskMapper.finish(task.getId(), UpgradeStatus.APPLYING.name(),
                        UpgradeStatus.SUCCESS.name(),
                        "重启后确认新版本 " + currentVersion.get() + " 已生效（未收到外部回执，依据当前版本号判定）");
                continue;
            }

            // 既无回执、版本也未变：只有超时才判失败，避免误杀正在执行的脚本
            LocalDateTime reference = task.getUpdatedAt() != null ? task.getUpdatedAt() : task.getCreatedAt();
            if (reference != null && reference.isBefore(now.minusMinutes(timeoutMinutes))) {
                changed += upgradeTaskMapper.finish(task.getId(), UpgradeStatus.APPLYING.name(),
                        UpgradeStatus.FAILED.name(),
                        "外部应用超时（超过 " + timeoutMinutes + " 分钟）未收到结果回执，且当前版本仍为 "
                                + currentVersion.orElse("未知") + "，请人工确认后处理");
            }
        }
        return changed;
    }

    /**
     * 「当前版本 == 目标版本 ⇒ 判定升级成功」这个兜底，**本次是否具备信息量**。
     *
     * <h2>为什么必须有这道闸</h2>
     * 版本比对的逻辑是「新版本此刻正在运行 ⇒ 升级事实上已成功」，它的前提是
     * <b>目标版本是一个会变的确定值</b>。下列两种情况下这个前提不成立，
     * 比较结果**恒为真**，等价于「无条件判成功」：
     * <ul>
     *   <li>{@code targetVersion} 是浮动标签（{@code latest} / {@code main} …）——
     *       它永远等于自己的上一次取值；</li>
     *   <li>{@code targetVersion} 与 {@code sourceVersion} 相同 —— 本次升级本来就没有
     *       版本变化（例如同标签重拉），也就无从用它区分「升没升」。</li>
     * </ul>
     *
     * <p>真实的坏结果（2026-10-16 现场实跑）：任务在 {@code docker compose pull}
     * 还在进行时就被标成 SUCCESS；容器几秒后才被重建；随后 agent 写出真实回执时，
     * 任务已不是 APPLYING，{@code selectApplying()} 不再选它 ⇒ <b>回执被静默丢弃</b>。
     * 若那次升级最终失败，页面将永远显示成功 —— <b>失败被伪装成成功</b>。
     *
     * <p>排除这两种情况后，宁可等回执、或等超时判失败（fail-closed），
     * 也不要拿一个恒真的条件去「猜成功」。
     *
     * @return true 表示这次版本比对有意义，可以据此判定
     */
    private boolean versionComparisonMeaningful(UpgradeTask task) {
        String target = normalizeVersion(task.getTargetVersion());
        if (target.isEmpty()) {
            return false;
        }
        if (FLOATING_TAGS.contains(target)) {
            return false;
        }
        // 源 == 目标 ⇒ 本次升级没有版本变化，无从判定
        return !target.equals(normalizeVersion(task.getSourceVersion()));
    }

    private static String normalizeVersion(String version) {
        return version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 回执完整性体检：**回执说失败、任务却说成功** —— 这份回执被静默丢弃了。
     *
     * <h2>为什么这个组合一定要留下痕迹</h2>
     * 正常情况下回执先于收尾到达，任务终态由回执决定，两者不可能矛盾。
     * 一旦矛盾，说明该任务在回执到达**之前**就被判定了（例如曾经的浮动标签版本比对恒真：
     * 任务在 {@code pull} 途中被判成功，等真回执到达时它已不是 APPLYING，
     * {@code selectApplying()} 不再选它）。若那份回执是 FAILED，
     * 页面就会把一个**失败的升级长期显示为成功** —— 这是最坏的一类失效：
     * 不报错、不影响功能，只在最需要知道真相时给出假的「成功」。
     *
     * <p>只记日志、**绝不改状态**：对账的职责是「把没走完的流程收尾」，
     * 不是替管理员改写历史结论。真正的处置应由人看到这条 WARN 后判断。
     */
    private void warnOnDroppedReceipt() {
        try {
            for (UpgradeApplyResult receipt : storage.recentApplyResults(RECEIPT_HEALTH_LOOKBACK)) {
                if (!UpgradeStatus.FAILED.name().equals(receipt.result())) {
                    continue;   // 只关心「回执说失败」
                }
                UpgradeTask task = upgradeTaskMapper.selectByTaskNo(receipt.taskNo());
                if (task == null || !UpgradeStatus.SUCCESS.name().equals(task.getStatus())) {
                    continue;   // 只关心「任务却说成功」
                }
                log.warn("[升级] 回执与任务状态矛盾：任务 {} 记为 {}，但回执结果是 {}（{}）"
                                + "——说明该任务在回执到达前就被判定了，回执被丢弃；请人工核对实际状态",
                        receipt.taskNo(), task.getStatus(), receipt.result(), receipt.message());
            }
        } catch (Exception e) {
            // 体检是「顺手做的事」，失败绝不能影响对账本身
            log.debug("[升级] 回执完整性体检跳过：{}", e.getMessage());
        }
    }

    private int finishByResult(UpgradeTask task, UpgradeApplyResult result) {
        UpgradeStatus target = mapResult(result.result());
        String message = result.message() == null || result.message().isBlank()
                ? "外部编排脚本回执：" + result.result()
                : result.message();
        return upgradeTaskMapper.finish(task.getId(), UpgradeStatus.APPLYING.name(), target.name(), message);
    }

    /**
     * 回执结果 → 任务状态。
     *
     * <p>无法识别的结果一律落到 {@code FAILED}（fail-closed）：
     * 把不认识的值当成成功，会让一次实际失败的升级在界面上显示为绿标 ——
     * 而管理员据此认为「已经升上去了」，是最坏的一种错。
     */
    private UpgradeStatus mapResult(String result) {
        if (result == null) {
            return UpgradeStatus.FAILED;
        }
        return switch (result.toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> UpgradeStatus.SUCCESS;
            case "ROLLED_BACK" -> UpgradeStatus.ROLLED_BACK;
            default -> UpgradeStatus.FAILED;
        };
    }
}
