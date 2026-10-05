package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import com.enterprise.ticket.module.upgrade.mapper.UpgradeTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
            if (currentVersion.isPresent() && currentVersion.get().equals(task.getTargetVersion())) {
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
