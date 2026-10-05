package com.enterprise.ticket.module.upgrade.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.UpgradeStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.upgrade.dto.vo.UpgradeOverviewVO;
import com.enterprise.ticket.module.upgrade.dto.vo.UpgradeTaskVO;
import com.enterprise.ticket.module.upgrade.entity.UpgradeTask;
import com.enterprise.ticket.module.upgrade.mapper.UpgradeTaskMapper;
import com.enterprise.ticket.module.upgrade.support.UpgradeApplier;
import com.enterprise.ticket.module.upgrade.support.UpgradePackageValidator;
import com.enterprise.ticket.module.upgrade.support.UpgradeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 在线一键升级
 *
 * <h2>职责边界（本类最容易改坏的地方）</h2>
 * <p>本服务只做三件事：<b>校验、备份、把新产物落到 staging</b>，
 * 然后把「替换 + 重启」交给进程外的编排脚本。
 * 后端进程无法替换自己正在运行的 jar（Windows 占用、Linux 已加载类不重载），
 * 所以 {@code READY_TO_APPLY} 是后端职责的<b>终点</b>，不是「还没做完」。
 * 这是刻意的边界，不是能力缺失。
 *
 * <h2>为什么「上传」与「应用」是两个独立动作</h2>
 * <p>真实运维里，这两件事的时机完全不同：上传包可以在任何时间做（不碰运行中的系统），
 * 而应用（替换 + 重启）必须放在维护窗口。合成一个「上传即升级」的按钮，
 * 等于让一次误点直接触发重启 —— 对大文件传输还会让这个窗口长达几分钟。
 * 拆开之后，管理员可以先上传、看到「校验通过、已备份、已就绪」，
 * 再从容地选择时机点「应用」。
 *
 * <h2>为什么全程不用长事务</h2>
 * <p>每一步状态迁移都是一条独立的条件 UPDATE（自动提交）。好处有两个：
 * <ul>
 *   <li><b>进程中途被杀也留下线索</b>：任务行停在最后一个完成的步骤上
 *       （「备份中」而不是「什么都没有」），运维一眼能看出走到哪一步断的；</li>
 *   <li><b>「先改状态、后发命令」的顺序天然安全</b>：状态落库与外部命令启动之间
 *       没有未提交事务，不存在「新版本已经跑起来、数据库却说还没应用」的窗口
 *       （这一点在 {@link #apply(String)} 里是关键）。</li>
 * </ul>
 * 代价是失败时需要显式把任务标为 FAILED（见 {@link #upload} 的 catch 分支）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpgradeService {

    private final AppProperties appProperties;
    private final UpgradeStorage storage;
    private final UpgradePackageValidator validator;
    private final UpgradeApplier applier;
    private final UpgradeTaskMapper upgradeTaskMapper;

    private static final DateTimeFormatter TASK_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // ==================================================================
    // 查询
    // ==================================================================

    /** 能力总览：开关状态、当前版本、是否配置了外部应用命令、进行中的任务 */
    public UpgradeOverviewVO overview() {
        AppProperties.Upgrade config = storage.config();
        UpgradeTask active = upgradeTaskMapper.selectActive();
        return new UpgradeOverviewVO(
                config.isEnabled(),
                storage.readCurrentVersion().orElse(null),
                applier.isConfigured(),
                config.isRollbackEnabled(),
                config.getPackageMaxSizeMb(),
                active == null ? null : active.getTaskNo());
    }

    /** 升级历史（倒序） */
    public List<UpgradeTaskVO> history() {
        int limit = Math.max(1, storage.config().getHistoryLimit());
        return upgradeTaskMapper.selectHistory(limit).stream()
                .map(task -> UpgradeTaskVO.of(task, storage.config().isRollbackEnabled()))
                .toList();
    }

    /** 单条任务详情 */
    public UpgradeTaskVO detail(String taskNo) {
        return UpgradeTaskVO.of(requireTask(taskNo), storage.config().isRollbackEnabled());
    }

    // ==================================================================
    // 上传（校验 → 备份 → 落盘 → 待应用）
    // ==================================================================

    /**
     * 接收升级包并完成「校验 → 备份 → 落盘」。
     *
     * <p><b>为什么用裸字节流而不是 multipart</b>：升级包可达数百 MB，
     * 而 multipart 的体量上限是<b>全局配置</b>（{@code spring.servlet.multipart.max-file-size}）。
     * 为了一个偶发的大文件把全局上限从 20MB 抬到 300MB，等于同时放宽了附件上传的
     * 容器层防护 —— 那里正是「业务层限额之外再挡一层」的设计所在。
     * 因此这里改用 {@code application/octet-stream} 直接收字节流，
     * 由 {@link #writeBounded} 边写边计数、超限即断，既保住了全局配置，
     * 又避免了把整个包缓冲进内存。
     *
     * @param fileName 原始文件名（仅用于展示与留痕，不参与任何路径拼接）
     * @param body     请求体字节流
     */
    public UpgradeTaskVO upload(String fileName, InputStream body) {
        requireEnabled();

        UpgradeTask active = upgradeTaskMapper.selectActive();
        if (active != null) {
            throw new BusinessException(ErrorCode.UPGRADE_TASK_CONFLICT,
                    "已有未完成的升级任务（" + active.getTaskNo() + "，状态："
                            + UpgradeStatus.labelOf(active.getStatus()) + "），请先完成或回滚后再发起");
        }

        String taskNo = generateTaskNo();
        String safeName = normalizeFileName(fileName);

        UpgradeTask task = newTask(taskNo, safeName);
        try {
            upgradeTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 走到这里说明两个上传请求同时通过了 selectActive 检查，
            // 由 V30 的 active_flag 唯一索引兜住。这正是它存在的全部意义。
            throw new BusinessException(ErrorCode.UPGRADE_TASK_CONFLICT, "已有未完成的升级任务，请刷新后重试");
        }

        Path temp = storage.tempDir().resolve(taskNo + ".upload");
        try {
            storage.ensureDir(storage.tempDir());

            // ① 落临时文件（有界）
            long written = writeBounded(body, temp, (long) storage.config().getPackageMaxSizeMb() * 1024 * 1024);
            stage(task, UpgradeStatus.VALIDATING, "VALIDATE", 15, "正在校验升级包…");

            // ② 校验（包结构 / manifest / 每个文件的 SHA-256 / 路径安全）
            UpgradePackageValidator.UpgradePlan plan = validator.validate(temp);

            task.setSourceVersion(storage.readCurrentVersion().orElse(null));
            task.setTargetVersion(plan.version());
            task.setPackageSha256(plan.packageSha256());
            task.setPackageSize(written);
            // 立刻落库：进程若在后续步骤被杀，库里至少已经记下了目标版本与包哈希 ——
            // 那正是排查「当时上的到底是哪个包」最需要的两条信息
            upgradeTaskMapper.updatePackageInfo(task.getId(), task.getSourceVersion(),
                    task.getTargetVersion(), task.getPackageSha256(), task.getPackageSize());

            // ③ 备份当前产物
            stage(task, UpgradeStatus.BACKING_UP, "BACKUP", 40, "正在备份当前版本产物…");
            String backupPath = backup(taskNo);
            task.setBackupPath(backupPath);

            // ④ 解压新产物到 staging
            stage(task, UpgradeStatus.STAGING, "STAGE", 65, "正在解压新产物…");
            Path stagingDir = storage.stagingDir(taskNo);
            storage.deleteTree(stagingDir);
            storage.ensureDir(stagingDir);
            validator.extract(temp, stagingDir);
            task.setStagingPath(storage.toStoredPath(stagingDir));
            upgradeTaskMapper.updateArtifacts(task.getId(), backupPath, task.getStagingPath());

            // ⑤ 待应用
            String ready = applier.isConfigured()
                    ? "已就绪，等待应用（将调用外部编排脚本完成替换与重启）"
                    : "已就绪，等待应用（未配置外部应用命令，需由运维手动应用 staging 目录内容）";
            stage(task, UpgradeStatus.READY_TO_APPLY, "READY", 80, ready);

            log.info("[升级] 升级包已就绪 taskNo={} target={} source={}",
                    taskNo, plan.version(), task.getSourceVersion());
            return detail(taskNo);
        } catch (BusinessException e) {
            markFailed(task, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // 非业务异常（磁盘满、权限、NPE…）同样要落 FAILED ——
            // 否则任务会一直占着活跃唯一键，把后续所有升级挡在门外
            markFailed(task, "升级处理异常：" + e.getMessage());
            throw e;
        } finally {
            // 临时文件只服务校验与解压，两者都已读进 staging / 内存结论
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("[升级] 清理上传临时文件失败（忽略）：{} - {}", temp, e.getMessage());
            }
        }
    }

    // ==================================================================
    // 应用
    // ==================================================================

    /**
     * 发起应用：把任务置为 APPLYING 并调用外部编排脚本。
     *
     * <h2>顺序是本方法唯一的设计要点</h2>
     * <p><b>必须先让状态更新落到数据库（本方法不居于事务中，UPDATE 即自动提交），
     * 再去启动外部命令。</b> 反过来的话，外部脚本可能已经完成替换并重启了后端，
     * 而那个「尚未提交」的状态更新会随进程一起消失 ——
     * 结果是新版本在跑，数据库里这条任务却仍是「已就绪待应用」。
     * 因此这里刻意不加 {@code @Transactional}：单条 CAS UPDATE 本身就是原子的，
     * 不需要事务包裹，而包上事务反而会引入上面那个窗口。
     */
    public UpgradeTaskVO apply(String taskNo) {
        requireEnabled();
        UpgradeTask task = requireTask(taskNo);

        if (!applier.isConfigured()) {
            throw new BusinessException(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED,
                    "未配置外部应用命令（app.upgrade.apply-command），无法在线应用；"
                            + "请配置编排脚本，或由运维手动应用 staging 目录中的产物");
        }

        int updated = upgradeTaskMapper.markApplying(task.getId(), "已发起外部应用命令，等待其回执…");
        if (updated == 0) {
            throw new BusinessException(ErrorCode.UPGRADE_TASK_STATUS_INVALID,
                    "当前任务状态为「" + UpgradeStatus.labelOf(task.getStatus()) + "」，不允许发起应用");
        }

        try {
            Path stagingDir = storage.fromStoredPath(task.getStagingPath());
            Path backupDir = storage.fromStoredPath(task.getBackupPath());
            if (stagingDir == null || !Files.isDirectory(stagingDir)) {
                throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED, "新产物目录不存在，请重新上传升级包");
            }
            applier.launch(taskNo, stagingDir, backupDir);
        } catch (RuntimeException e) {
            // 命令根本没起来：产物完好，退回「待应用」让管理员修好配置后重试，
            // 而不是判 FAILED 逼他重传一遍几百 MB 的包
            upgradeTaskMapper.revertToReady(task.getId(), "外部应用命令启动失败：" + e.getMessage());
            throw e;
        }

        return detail(taskNo);
    }

    // ==================================================================
    // 回滚
    // ==================================================================

    /**
     * 手动回滚：把当前产物还原成该任务备份的版本。
     *
     * <h2>为什么要先「把现场另存一份」再还原</h2>
     * <p>回滚的直觉做法是「删掉现产物 → 复制备份回去」。但复制可能失败（磁盘满、权限），
     * 那一瞬间<b>两边都没有了</b> —— 系统彻底起不来。
     * 因此实际顺序是：
     * <ol>
     *   <li>把当前产物复制到 {@code backup/&lt;taskNo&gt;-pre-rollback/}（留存现场）；</li>
     *   <li>清空当前产物目录；</li>
     *   <li>把备份复制回去。</li>
     * </ol>
     * 任何一步失败，第 1 步留存的那份都能人工还原，
     * 而且失败信息里会写明它在哪里 —— 而不是把管理员扔在「什么都没了」的状态里。
     *
     * <p><b>回滚后仍需重启</b>：还原的是磁盘上的文件，运行中的进程加载的仍是旧字节码。
     * 这一点会写进返回的任务说明里，避免管理员以为点完就生效了。
     */
    public UpgradeTaskVO rollback(String taskNo) {
        requireEnabled();
        AppProperties.Upgrade config = storage.config();
        if (!config.isRollbackEnabled()) {
            throw BusinessException.of(ErrorCode.UPGRADE_ROLLBACK_DISABLED);
        }

        UpgradeTask task = requireTask(taskNo);
        UpgradeStatus status = UpgradeStatus.of(task.getStatus());
        if (status == null || !status.isRollbackable()) {
            throw new BusinessException(ErrorCode.UPGRADE_TASK_STATUS_INVALID,
                    "当前任务状态为「" + UpgradeStatus.labelOf(task.getStatus()) + "」，不允许回滚");
        }

        Path backupDir = storage.fromStoredPath(task.getBackupPath());
        if (backupDir == null || !Files.isDirectory(backupDir)) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "该任务没有可用的备份目录，无法回滚（首次部署时不存在上一版产物）");
        }

        Path current = storage.currentArtifactDir();
        Path safety = storage.backupDir(taskNo + "-pre-rollback");

        if (Files.isDirectory(current)) {
            storage.deleteTree(safety);
            storage.copyTree(current, safety);
        }
        storage.clearDir(current);
        storage.copyTree(backupDir, current);

        String message = "已回滚到版本 " + display(task.getSourceVersion()) + "；"
                + "还原的是磁盘文件，需重启后端 / 重建容器后生效"
                + "（回滚前现场留存于 backup/" + taskNo + "-pre-rollback）";

        int updated = upgradeTaskMapper.finish(task.getId(), task.getStatus(),
                UpgradeStatus.ROLLED_BACK.name(), message);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.UPGRADE_TASK_STATUS_INVALID,
                    "任务状态已被其它操作改变，请刷新后重试");
        }

        log.warn("[升级] 已回滚 taskNo={} 由 {} 操作，还原到 {}",
                taskNo, SecurityUtils.getCurrentUsername(), display(task.getSourceVersion()));
        return detail(taskNo);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void requireEnabled() {
        if (!storage.config().isEnabled()) {
            throw BusinessException.of(ErrorCode.UPGRADE_DISABLED);
        }
    }

    private UpgradeTask requireTask(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) {
            throw BusinessException.of(ErrorCode.UPGRADE_TASK_NOT_FOUND);
        }
        UpgradeTask task = upgradeTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UpgradeTask>()
                        .eq(UpgradeTask::getTaskNo, taskNo));
        if (task == null) {
            throw BusinessException.of(ErrorCode.UPGRADE_TASK_NOT_FOUND);
        }
        return task;
    }

    private UpgradeTask newTask(String taskNo, String fileName) {
        UpgradeTask task = new UpgradeTask();
        task.setTaskNo(taskNo);
        task.setPackageName(fileName);
        // packageSha256 在库中是 NOT NULL：先占位，校验通过后写真实值。
        // 之所以不把列改成可空 —— 一条「没有哈希」的升级记录等同于没有留痕，
        // 让它在数据层面不可表达比靠约定更可靠。
        task.setPackageSha256("0".repeat(64));
        task.setTargetVersion("-");
        task.setStatus(UpgradeStatus.PENDING.name());
        task.setStep("RECEIVE");
        task.setProgress(5);
        task.setMessage("已接收升级包，准备校验…");
        task.setOperatorId(SecurityUtils.getCurrentUserId());
        var user = SecurityUtils.getCurrentUser();
        task.setOperatorName(user == null ? null : user.getDisplayName());
        task.setStartedAt(LocalDateTime.now());
        return task;
    }

    /** 过程态落库（同时刷新内存中的 task 副本，供后续步骤使用） */
    private void stage(UpgradeTask task, UpgradeStatus status, String step, int progress, String message) {
        task.setStatus(status.name());
        task.setStep(step);
        task.setProgress(progress);
        task.setMessage(message);
        upgradeTaskMapper.updateStage(task.getId(), status.name(), step, progress, message);
    }

    private void markFailed(UpgradeTask task, String reason) {
        String message = reason == null || reason.isBlank() ? "升级失败" : reason;
        try {
            upgradeTaskMapper.finish(task.getId(), task.getStatus(), UpgradeStatus.FAILED.name(), message);
        } catch (RuntimeException e) {
            // 标失败本身不能再抛：调用方正在处理原始异常，
            // 这里再抛会把它替换成一个完全无关的报错，掩盖真正的失败原因
            log.warn("[升级] 标记任务失败时出错（忽略）：{} - {}", task.getTaskNo(), e.getMessage());
        }
    }

    /**
     * 备份当前产物；不存在时返回 null 并记提示。
     *
     * <p>「当前产物目录不存在」不是错误：首次部署（或本地演示环境）本来就没有
     * 「上一版」可备份。此时升级照样可以进行，只是**没有回滚的退路** ——
     * 这个事实会写进 {@code backupPath} 为 null 这件事上，
     * 并在回滚时给出明确提示（「该任务没有可用的备份目录」）。
     */
    private String backup(String taskNo) {
        Path current = storage.currentArtifactDir();
        if (!Files.isDirectory(current)) {
            log.info("[升级] 当前产物目录不存在，跳过备份（首次部署属正常）：{}", current);
            return null;
        }
        Path target = storage.backupDir(taskNo);
        storage.deleteTree(target);
        storage.copyTree(current, target);
        log.info("[升级] 已备份当前产物：{} → {}", current, target);
        return storage.toStoredPath(target);
    }

    /**
     * 有界写入：边读边计数，超限立即中断。
     *
     * <p>先看 {@code Content-Length} 再读也有价值，但它只是<b>提示</b> ——
     * 分块传输（chunked）时没有这个头，且它由客户端提供、完全可伪造。
     * 唯一可信的判据是「实际读出来多少字节」，所以计数必须发生在读取循环里。
     */
    private long writeBounded(InputStream body, Path target, long maxBytes) {
        if (body == null) {
            throw BusinessException.of(ErrorCode.UPGRADE_PACKAGE_REQUIRED);
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_TOO_LARGE,
                            "升级包超过上限 " + (maxBytes / 1024 / 1024) + "MB");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPGRADE_STORAGE_FAILED,
                    "保存升级包失败：" + e.getMessage(), e);
        }
        if (total == 0) {
            throw new BusinessException(ErrorCode.UPGRADE_PACKAGE_REQUIRED, "升级包为空");
        }
        return total;
    }

    private String generateTaskNo() {
        // 时间戳便于人工按时间定位，4 位随机后缀避免同秒并发撞号
        return LocalDateTime.now().format(TASK_NO_FORMAT)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 归一化上传文件名。
     *
     * <p>它只用于展示与审计，<b>绝不参与路径拼接</b> ——
     * 所有落盘路径都由服务端按 taskNo 生成。即便如此仍然要剥掉目录分量与控制字符：
     * 一个名为 {@code ../../etc/passwd} 的文件名若被原样存进库里，
     * 它会出现在日志、页面与运维的复制粘贴里，成为一条容易被误用的脏数据。
     */
    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upgrade.zip";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "upgrade.zip";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private String display(String version) {
        return (version == null || version.isBlank()) ? "（未知）" : version;
    }
}
