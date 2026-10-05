package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外部应用执行器
 *
 * <h2>为什么「替换 + 重启」必须交给进程外的脚本</h2>
 * <p>后端进程<b>无法替换自己正在运行的 jar</b>：
 * <ul>
 *   <li>Windows 上文件被运行中的进程占用，替换直接失败；</li>
 *   <li>Linux 上替换可以成功，但<b>已加载的类不会重新载入</b> ——
 *       进程看起来换了新 jar，跑的却仍然是旧字节码，必须重启才生效。</li>
 * </ul>
 * 所以后端只把产物备好并落到 staging，然后发一条命令出去，
 * 由受控的 root 编排脚本完成「停止 → 替换 → 启动 → 健康检查 → 失败回滚」。
 *
 * <h2>⚠️ 真正的坑：systemd 的 cgroup 会连脚本一起杀掉</h2>
 * <p>如果后端是以 systemd 服务运行的（默认 {@code KillMode=control-group}），
 * 那么它派生的子进程<b>仍在同一个 cgroup 里</b>。重启服务时 systemd 会杀掉整个
 * cgroup —— 包括我们刚启动的、正打算执行重启的那个脚本。结果是：
 * <b>脚本被自己发起的重启杀死，替换做到一半，系统停在半死不活的状态。</b>
 *
 * <p>因此 {@code app.upgrade.apply-command} 的部署建议是让脚本成为<b>独立单元</b>：
 * <pre>
 *   # 推荐：作为一次性 transient unit 启动，脱离后端服务的 cgroup
 *   systemd-run --unit=ticket-upgrade-{taskNo} --collect --no-block \
 *       /opt/ticket/bin/upgrade-apply.sh {taskNo} {stagingDir} {backupDir}
 *
 *   # Docker 部署：交给宿主上的脚本（同样不要是容器的子进程）
 *   docker exec -d ticket-upgrade-agent /opt/ticket/bin/upgrade-apply.sh ...
 * </pre>
 * {@code --no-block} 让 {@code systemd-run} 立即返回，不会把请求线程拖住。
 * 这一点写在本类的 javadoc 里而不是只放在部署文档里，是因为它属于
 * <b>「代码为什么要这样设计」</b>的一部分：本类刻意<b>不等待</b>外部命令结束，
 * 正是为了不与「后端即将被杀掉」这一事实对抗。
 *
 * <h2>为什么本类不等待子进程结束</h2>
 * <p>外部脚本的头几件事往往就是停掉后端。若这里 {@code waitFor}，请求线程会
 * 与后端一起被杀在半途，而脚本的命运取决于 cgroup 配置（见上）。
 * 不等待 + 输出重定向到日志文件，是唯一能同时满足「后端可能随时消失」
 * 与「运维能看到脚本输出」的做法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpgradeApplier {

    private final AppProperties appProperties;
    private final UpgradeStorage storage;

    /** 是否配置了外部应用命令 */
    public boolean isConfigured() {
        return StringUtils.hasText(appProperties.getUpgrade().getApplyCommand());
    }

    /** 外部应用脚本的日志文件（与任务一一对应，便于运维按 taskNo 查） */
    public Path logFile(String taskNo) {
        return storage.storageRoot().resolve("state").resolve("apply-" + taskNo + ".log");
    }

    /**
     * 发起外部应用（fire-and-forget）。
     *
     * <p><b>调用时机极其关键</b>：必须在「任务状态已置为 APPLYING」的事务
     * <b>提交之后</b>才调用（见 {@code UpgradeServiceImpl#apply} 里的
     * {@code afterCommit} 回调）。若在事务提交前就启动脚本，脚本可能在替换完成、
     * 重启后端之后，才轮到那个尚未提交的事务回滚 —— 结果是
     * <b>新版本已经跑起来了，数据库里那条任务却仍是「已就绪待应用」</b>，
     * 一个无法自洽的残留状态。
     *
     * @param taskNo     升级业务号
     * @param stagingDir 新产物目录
     * @param backupDir  本次备份目录
     */
    public void launch(String taskNo, Path stagingDir, Path backupDir) {
        if (!isConfigured()) {
            throw BusinessException.of(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED);
        }
        String command = render(appProperties.getUpgrade().getApplyCommand(),
                taskNo, stagingDir, backupDir);

        List<String> argv = new ArrayList<>();
        if (isWindows()) {
            // 本地验证用：cmd /c 起一条独立命令行。生产部署在 Linux，
            // 这一段的存在只是为了「开发机上也能把链路走到发起这一步」
            argv.add("cmd.exe");
            argv.add("/c");
            argv.add(command);
        } else {
            argv.add("/bin/sh");
            argv.add("-c");
            argv.add(command);
        }

        // 局部变量刻意不叫 log：它会遮蔽 Lombok 生成的日志字段，让下面几行
        // 报「找不到符号 log.info(...)」—— 一个看起来完全莫名其妙的编译错误
        Path logPath = logFile(taskNo);
        try {
            if (logPath.getParent() != null) {
                java.nio.file.Files.createDirectories(logPath.getParent());
            }
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            // 把 stdin 接到空设备：脚本里若有 read / 交互式命令，
            // 会在无输入的情况下立刻拿到 EOF 而不会永久挂住
            builder.redirectInput(isWindows()
                    ? ProcessBuilder.Redirect.from(new java.io.File("NUL"))
                    : ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

            Process process = builder.start();
            // 刻意不 waitFor：见类注释。这里只记 pid，用于运维在日志里对上号。
            log.info("[升级] 已发起外部应用命令 taskNo={} pid={} log={}", taskNo, process.pid(), logPath);
        } catch (IOException e) {
            // 真走到这里说明「命令根本起不来」（可执行文件不存在 / 无权限 / shell 缺失）——
            // 与「脚本跑起来但失败了」是两回事，后者由脚本写结果回执告知
            log.error("[升级] 外部应用命令启动失败 taskNo={} cmd={}", taskNo, command, e);
            throw new BusinessException(ErrorCode.UPGRADE_APPLY_COMMAND_FAILED,
                    "外部应用命令启动失败：" + e.getMessage(), e);
        }
    }

    /** 渲染命令模板；占位符按「长名优先」替换，避免 {stagingDir} 被 {staging} 之类的短名切坏 */
    private String render(String template, String taskNo, Path stagingDir, Path backupDir) {
        return template
                .replace("{stagingDir}", quote(stagingDir.toString()))
                .replace("{backupDir}", quote(backupDir.toString()))
                .replace("{taskNo}", taskNo);
    }

    /**
     * 路径加引号：路径里出现空格（{@code /opt/ticket system/}）时，
     * 不加引号会被 shell 拆成两个参数，脚本收到的是半截路径 ——
     * 而它多半会一路失败到「文件不存在」，排查方向完全被带偏。
     */
    private String quote(String value) {
        if (isWindows()) {
            return "\"" + value + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
