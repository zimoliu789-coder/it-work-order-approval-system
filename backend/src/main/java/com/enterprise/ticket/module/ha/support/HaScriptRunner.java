package com.enterprise.ticket.module.ha.support;

import com.enterprise.ticket.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 运维脚本执行器
 *
 * <h2>为什么是「同步等待 + 超时」而不是像升级模块那样 fire-and-forget</h2>
 * <p>{@code UpgradeApplier} 刻意不等待子进程，因为那个脚本的头几件事就是
 * 停掉后端自己 —— 等待等于跟「请求线程即将被杀」这一事实对抗。
 * 本模块的场景完全不同：{@code switchover.sh} 只是置一个维护标记
 * （{@code /etc/keepalived/MAINT}）然后退出，<b>几秒内必然结束</b>，
 * 而且它的输出（"已标记本机为维护状态，等待对端接管"）正是维护人员
 * 需要立刻看到的确认信息。因此这里等待并回收输出 —— 让按钮点下去就有明确回音。
 *
 * <h2>⚠️ 为什么必须带 {@code sudo -n}</h2>
 * <p>{@code -n}（non-interactive）会让 sudo 在<b>需要密码时立即失败</b>，
 * 而不是弹一个提示等待输入。后端进程没有 tty，也<b>永远不应该</b>替管理员输入密码 ——
 * 不加 {@code -n} 的后果是：sudo 在无 tty 环境下要么直接报错、要么挂在那里
 * 一直等到超时，而维护人员看到的是「按钮转圈 30 秒后失败」，
 * 完全无法知道真实原因是「这台机器的 sudo 需要密码」。
 * 带 {@code -n} 时错误信息是 {@code sudo: a password is required}，
 * 维护人员据此就知道要去配 {@code /etc/sudoers} 的 NOPASSWD。
 *
 * <h2>⚠️ 参数用 List 传递，绝不拼 shell 字符串</h2>
 * <p>{@link java.lang.ProcessBuilder} 接收的是参数<b>列表</b>，不经过 shell 解析 ——
 * 因此不存在命令注入的可能。如果为了「写起来像命令行」而拼成一个字符串再交给
 * {@code sh -c}，那么任何进入参数的用户输入（节点 IP、节点名）都可能被解释成命令。
 * 本模块的所有命令都只由后端自己拼装、参数来自<b>已通过格式校验</b>的配置值，
 * 但仍然坚持用列表形式 —— 这是让「注入」在这个类里<b>结构上不可能</b>，
 * 而不是靠「我们记得校验了每个入口」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HaScriptRunner {

    private final AppProperties appProperties;

    /**
     * 执行一次命令并等待结束。
     *
     * @param argv    命令与参数（列表形式，不经过 shell）
     * @param timeoutSeconds 等待上限（秒）
     */
    public Result run(List<String> argv, int timeoutSeconds) {
        String display = String.join(" ", argv);
        ProcessBuilder builder = new ProcessBuilder(argv);
        // 合并 stderr：脚本的失败原因几乎都写在 stderr，分开收集会让维护人员
        // 必须先知道「该看哪个流」才能定位问题 —— 而他们通常不知道
        builder.redirectErrorStream(true);
        // stdin 接空设备：脚本里若有 read / 交互式提示，会立刻拿到 EOF 而不是永久挂住
        builder.redirectInput(isWindows()
                ? ProcessBuilder.Redirect.from(new java.io.File("NUL"))
                : ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        Process process = null;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 「根本起不来」：可执行文件 / shell 缺失、无权限。与「跑起来但失败」是两回事
            log.error("[主备] 脚本启动失败 cmd={}", display, e);
            throw new ScriptLaunchException(display, e);
        }

        OutputCollector collector = new OutputCollector(process, maxOutputChars());
        Thread reader = new Thread(collector, "ha-script-output");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断会让上层的停止逻辑失效
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            log.warn("[主备] 等待脚本被中断 cmd={}", display);
            return new Result(null, collector.text(), true);
        }

        if (!finished) {
            process.destroyForcibly();
            log.warn("[主备] 脚本执行超时（{}s）cmd={}", timeoutSeconds, display);
            return new Result(null, collector.text(), true);
        }

        // 等读取线程收尾。子进程已退出，管道里的剩余数据读完即返回；
        // 给 2 秒余量是防止「进程已退但管道 fd 被孙进程继承」时无限等待
        try {
            reader.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int exitCode = process.exitValue();
        log.info("[主备] 脚本执行结束 exit={} cmd={}", exitCode, display);
        return new Result(exitCode, collector.text(), false);
    }

    public boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private int maxOutputChars() {
        int configured = appProperties.getHa().getMaxOutputChars();
        return configured > 0 ? configured : 8000;
    }

    /**
     * 执行结果。
     *
     * @param exitCode 退出码；{@link #timedOut} 或未执行时为 null
     * @param output   合并后的 stdout + stderr（已按上限截断）
     * @param timedOut 是否因超时被强制终止（此时 {@code exitCode} 无意义）
     */
    public record Result(Integer exitCode, String output, boolean timedOut) {

        public boolean ok() {
            return !timedOut && exitCode != null && exitCode == 0;
        }
    }

    /** 脚本无法启动（与「脚本跑起来但退出码非 0」区分） */
    public static class ScriptLaunchException extends RuntimeException {
        public ScriptLaunchException(String command, Throwable cause) {
            super("脚本无法启动：" + command, cause);
        }
    }

    /**
     * 输出收集：持续读取直到流结束，但<b>只保留前 N 个字符</b>。
     *
     * <p>必须持续读、不能「读够 N 个字符就停」：管道缓冲区（通常 64KB）
     * 写满之后子进程会阻塞在 write 上，永远不退出 ——
     * 那会表现为「脚本明明没事却一直跑到超时」。
     * 因此读动作不能停，只是超出上限后丢弃内容。
     */
    private static final class OutputCollector implements Runnable {

        private final Process process;
        private final int limit;
        private final StringBuilder buffer = new StringBuilder();

        private OutputCollector(Process process, int limit) {
            this.process = process;
            this.limit = limit;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (buffer) {
                        if (buffer.length() < limit) {
                            buffer.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException e) {
                // 进程被强制终止时读管道会抛 IOException，属于预期路径，不记 error
                log.debug("[主备] 脚本输出读取结束（{}）", e.getMessage());
            }
        }

        private String text() {
            synchronized (buffer) {
                return buffer.toString().trim();
            }
        }
    }
}
