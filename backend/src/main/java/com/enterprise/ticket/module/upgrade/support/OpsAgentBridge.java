package com.enterprise.ticket.module.upgrade.support;

import com.enterprise.ticket.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 运维代理（ops-agent）通道
 *
 * <h2>为什么用「共享目录」而不是 HTTP</h2>
 * <p>后端需要一件事：把「请把这套栈更新到新版本」这个意图交给一个<b>不受重建影响</b>的角色执行
 * （后端不能重建自己 —— 它一执行 {@code up -d} 就会把自己杀掉，栈会停在半更新状态）。
 *
 * <p>顺手的做法是给 agent 开一个内部 HTTP 接口。但那样会新增三样东西：一个网络暴露面、
 * 一套鉴权（token 怎么发、怎么轮换、配错了怎么报错）、以及一个「后端调用超时」的失败模式。
 * 而这三样全都不必要 —— 两侧本来就在<b>同一台宿主机</b>上，且已经有共享目录。
 *
 * <p>所以这里用最笨也最稳的办法：**写一个文件**。后端把请求写进
 * {@code <ops-dir>/requests/<taskNo>.json}，agent 轮询目录、执行、把结果与心跳写回来。
 * 好处是「没有连接可断、没有口令可泄露、agent 挂了不影响系统运行」——
 * 挂掉只是让「在线升级」这一项能力暂时不可用，页面会如实显示「升级代理未就绪」。
 *
 * <h2>回执为什么不走本类</h2>
 * <p>「替换 + 重启」的结果回执仍在 <b>{@code state/result/<taskNo>.json}</b>，
 * 由既有的 {@link UpgradeApplyResult} + {@code UpgradeRecoveryRunner} 读取 ——
 * 那是后端与进程外脚本之间已经稳定下来的唯一通信载体，agent 只是「另一个写这份回执的脚本」。
 * 再发明一套平行格式，只会让同一个事实存在两个版本、且迟早不一致。
 *
 * <h2>心跳与就绪判定的取向</h2>
 * <p>判定「agent 可用」用的是<b>心跳新鲜度</b>而不是「文件存在」：
 * 一个已经退出、但留下心跳文件的容器会让页面显示「就绪」，
 * 管理员点下「应用」之后什么也不会发生 —— 这是最坏的失败形态（静默）。
 * 因此超过 {@code app.upgrade.agent-heartbeat-timeout-seconds} 未更新即视为不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAgentBridge {

    private static final String HEARTBEAT_FILE = "heartbeat.json";

    /** 请求目录：后端只写、agent 只读并删除 */
    private static final String REQUESTS_DIR = "requests";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 是否为「未配置」：留空表示不使用运维代理（退化为人工执行或外部命令） */
    public boolean configured() {
        return StringUtils.hasText(appProperties.getUpgrade().getOpsDir());
    }

    /** 共享目录（宿主上后端与 agent 各挂一次，指向同一个宿主目录） */
    public Path opsDir() {
        return Paths.get(appProperties.getUpgrade().getOpsDir());
    }

    private Path requestsDir() {
        return opsDir().resolve(REQUESTS_DIR);
    }

    private Path heartbeatFile() {
        return opsDir().resolve(HEARTBEAT_FILE);
    }

    // ------------------------------------------------------------------
    // 心跳 / 就绪
    // ------------------------------------------------------------------

    /**
     * 运维代理心跳（{@code <ops-dir>/heartbeat.json}）。
     *
     * <p>字段由 agent 写出，刻意用 {@code String} 承载时间戳：agent 是 shell/python，
     * 它写什么就原样解析什么，把 {@code at} 声明成时间类型只会引入一条没有收益的解析耦合
     * （与 {@link UpgradeApplyResult#finishedAt} 同一取舍）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Heartbeat(String version, String at, String status) {
    }

    /** 读心跳；文件不存在 / 内容损坏都返回空（对调用方等价于「没有 agent」） */
    public Optional<Heartbeat> heartbeat() {
        if (!configured()) {
            return Optional.empty();
        }
        Path file = heartbeatFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(file);
            return Optional.ofNullable(objectMapper.readValue(raw, Heartbeat.class));
        } catch (IOException | RuntimeException e) {
            // 心跳正在被覆盖写、或写坏了，都属于「这一拍没读到」——不该抛出，
            // 否则一次读取失败会让整个升级页打不开
            log.debug("[升级] 读取运维代理心跳失败（按未就绪处理）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 运维代理是否可用。
     *
     * <p>判据是**心跳新鲜度**，不是「文件存在」——理由见类注释。
     */
    public boolean agentReady() {
        Heartbeat beat = heartbeat().orElse(null);
        if (beat == null || !StringUtils.hasText(beat.at())) {
            return false;
        }
        try {
            LocalDateTime at = LocalDateTime.parse(beat.at().trim(), STAMP);
            long ageSeconds = Duration.between(at, LocalDateTime.now()).getSeconds();
            // 允许轻微的未来时间偏差（两台机器的钟差），否则 agent 刚写的文件会被判成「过期」
            return ageSeconds <= appProperties.getUpgrade().getAgentHeartbeatTimeoutSeconds()
                    && ageSeconds >= -60;
        } catch (DateTimeParseException e) {
            log.debug("[升级] 运维代理心跳时间格式无法解析：{}", beat.at());
            return false;
        }
    }

    /** 运维代理版本号（未就绪时为 null，仅供页面展示） */
    public String agentVersion() {
        return heartbeat().map(Heartbeat::version).orElse(null);
    }

    // ------------------------------------------------------------------
    // 下发请求
    // ------------------------------------------------------------------

    /**
     * 下发一次升级请求。
     *
     * <h2>为什么用「临时文件 + 原子改名」</h2>
     * agent 是**另一个进程**，它可能在任意时刻扫到这个目录。
     * 直接写目标文件会让它读到半截 JSON（解析失败即当次跳过，最坏情况是这次升级被漏掉，
     * 而管理员在页面上看到的是「已下发」）。先写 {@code .tmp} 再 {@code ATOMIC_MOVE}
     * 才能保证「出现在目录里」就等于「内容完整」。
     *
     * <h2>请求里为什么只有这三样</h2>
     * {@code taskNo}（对账用）、{@code targetVersion}（人看）、{@code requestedAt}（排查用）。
     * **刻意不放任何命令、路径或参数** —— agent 侧只认这个固定 schema，
     * 执行什么由它自己写死。这样即使这个目录被写坏，也换不来一条任意命令。
     */
    public void requestApply(String taskNo, String targetVersion) {
        if (!configured()) {
            throw new IllegalStateException("未配置 app.upgrade.ops-dir，无法向运维代理下发请求");
        }
        Path dir = requestsDir();
        Path target = dir.resolve(taskNo + ".json");
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(taskNo + ".json.tmp");
            String json = objectMapper.writeValueAsString(new ApplyRequest(
                    taskNo, targetVersion, LocalDateTime.now().format(STAMP)));
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("[升级] 已向运维代理下发升级请求 taskNo={} target={}", taskNo, targetVersion);
        } catch (IOException e) {
            throw new IllegalStateException("下发升级请求失败：" + e.getMessage(), e);
        }
    }

    /** 下发给 agent 的升级请求（schema 固定，见 {@link #requestApply} 的说明） */
    public record ApplyRequest(String taskNo, String targetVersion, String requestedAt) {
    }
}
