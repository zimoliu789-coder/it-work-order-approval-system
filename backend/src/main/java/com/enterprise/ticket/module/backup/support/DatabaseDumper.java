package com.enterprise.ticket.module.backup.support;

import com.enterprise.ticket.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * 数据库逻辑导出器（P0）—— 调 {@code mysqldump} 并 gzip
 *
 * <h2>为什么不复用 {@code deploy/backup/backup.sh}</h2>
 * <ol>
 *   <li><b>调度权</b>：需求是「在系统参数里配备份时间」，而侧车 cron 读的是容器环境变量，
 *       改不了；只有由应用掌控调度，参数才生效。</li>
 *   <li><b>部署形态</b>：侧车是 Docker 专用，而本功能的缺口恰恰是<b>非容器环境</b>
 *       （Windows 裸机 / 单机直跑）。</li>
 *   <li><b>产物范围不同</b>：侧车做「数据库 + 附件 + 配置」三合一，本类只做<b>数据库</b>
 *       （P0 的命题就是「数据库自动备份」）。两者互补，但<b>不要同时开</b>（见参数说明）。</li>
 * </ol>
 *
 * <h2>口令怎么传（不用命令行）</h2>
 * 走临时 {@code --defaults-extra-file}（权限 600，用完即删）。两条理由：
 * <ul>
 *   <li>{@code -p<口令>} 会出现在进程列表里，同机任何账号都能 {@code ps} 看到；</li>
 *   <li>即使用 {@code -p}，mysqldump 也会往 <b>stderr</b> 打一句
 *       「Using a password on the command line interface can be insecure」——
 *       这句警告会混进我们收集的失败原因里，让真正的错误被一句废话盖住。</li>
 * </ul>
 *
 * <h2>为什么先落 {@code .sql} 再在 Java 里 gzip</h2>
 * 而不是「让 shell 管道接 gzip」：管道会把 mysqldump 的退出码吃掉
 * （{@code cmd | gzip} 的退出码是 gzip 的），于是「mysqldump 失败」会以
 * 「成功产出一个 0 字节 gz」的形式静默通过。分两步后，只有 mysqldump
 * 退出码为 0 才会进入压缩阶段，判据不会丢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseDumper {

    /** 失败原因只留前若干行：mysqldump 的报错可能很长，全量塞进消息反而看不清重点 */
    private static final int MAX_ERROR_LINES = 8;

    private final AppProperties appProperties;
    private final DataSourceProperties dataSourceProperties;

    /** 一次导出的产物 */
    public record Artifact(Path file, long size) {
    }

    /**
     * 执行一次导出。
     *
     * @param backupDir 备份目录（调用方已确保存在）
     * @param fileName  归档文件名（不含目录），例如 {@code db-backup-20261003-020000.sql.gz}
     * @return 产物路径与字节数
     * @throws BackupException 目录不可写 / 找不到 mysqldump / 导出失败
     */
    public Artifact dump(Path backupDir, String fileName) {
        ensureWritable(backupDir);

        String binary = resolveBinary();
        Path sqlFile = backupDir.resolve(fileName.replace(".sql.gz", ".sql"));
        Path gzFile = backupDir.resolve(fileName);
        Path defaultsFile = null;
        try {
            defaultsFile = writeDefaultsFile(backupDir);
            runDump(binary, defaultsFile, sqlFile);

            long size = gzip(sqlFile, gzFile);
            if (size <= 0) {
                throw new BackupException("导出结果为空文件（0 字节），已按失败处理：" + gzFile);
            }
            return new Artifact(gzFile, size);
        } catch (IOException e) {
            throw new BackupException("备份文件写入失败：" + describe(e), e);
        } finally {
            deleteQuietly(sqlFile);
            deleteQuietly(defaultsFile);
        }
    }

    // ------------------------------------------------------------------
    // 前置探测
    // ------------------------------------------------------------------

    /**
     * 目录可写探测 —— <b>必须提前做，不能等 mysqldump 报错</b>。
     *
     * <p>把备份目录指向 NAS 是很常见的用法（需求原话「备份文件存 NAS」），
     * 而 NAS 掉线 / 权限变更 / 未挂载时，目录会表现为「不存在」或「只读」。
     * 若不做这一层，用户看到的是 mysqldump 报的一串 errno，
     * 既看不出是哪个目录，也看不出该去挂载还是该改权限。
     */
    private void ensureWritable(Path backupDir) {
        String problem = checkWritable(backupDir);
        if (problem != null) {
            throw new BackupException(problem);
        }
    }

    /**
     * 目录可用性探测：返回 {@code null} 表示可用，否则返回一句能直接展示给管理员的原因。
     *
     * <p>抽成<b>公开</b>方法是为了让「备份概览」在<b>还没失败之前</b>就能告诉管理员
     * 「目录现在不可写」—— 否则这个事实只能等到某天备份真的跑失败了才暴露，
     * 而那时距离运维发现已经过去很久（甚至一直没人发现）。
     */
    public String checkWritable(Path backupDir) {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return "备份目录无法创建：" + backupDir + "（" + describe(e) + "）。"
                    + "请检查该路径是否存在、是否需要先挂载 NAS，或改用系统参数 backup_dir 指定其它目录。";
        }
        if (!Files.isDirectory(backupDir)) {
            return "备份目录不是一个目录：" + backupDir
                    + "。请修正系统参数 backup_dir 或应用配置 app.backup.dir。";
        }
        if (!Files.isWritable(backupDir)) {
            return "备份目录不可写：" + backupDir
                    + "。请检查目录权限或 NAS 挂载状态，或改用系统参数 backup_dir 指定其它目录。";
        }
        return null;
    }

    /** 解析 mysqldump 可执行文件：配置项优先，其次 PATH */
    private String resolveBinary() {
        String configured = appProperties.getBackup().getMysqldump();
        if (!StringUtils.hasText(configured)) {
            return "mysqldump";
        }
        Path path = Path.of(configured);
        // 配了绝对路径就要求它真的存在 —— 否则报错会晚到 mysqldump 启动失败那一刻，
        // 而那时 stderr 只有一句「系统找不到指定的文件」，看不出配的是哪个路径。
        if (path.isAbsolute() && !Files.isExecutable(path) && !Files.isRegularFile(path)) {
            throw new BackupException("配置的 mysqldump 路径不存在或不可执行：" + configured
                    + "。请修正应用配置 app.backup.mysqldump，或留空以使用系统 PATH 中的 mysqldump。");
        }
        return configured;
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 写临时凭据文件（{@code --defaults-extra-file}）。
     *
     * <p>落在<b>备份目录内</b>而不是系统临时目录：备份目录是管理员自己配置、
     * 且通常是 600 的私有目录；而 {@code /tmp} 在多用户机器上更容易被同机账号窥探。
     * 用完立即删除（见 {@link #deleteQuietly}）。
     */
    private Path writeDefaultsFile(Path backupDir) throws IOException {
        JdbcTarget target = jdbcTarget();
        String user = dataSourceProperties.getUsername();
        String password = dataSourceProperties.getPassword();

        StringBuilder sb = new StringBuilder();
        sb.append("[client]").append('\n');
        sb.append("host=").append(target.host()).append('\n');
        sb.append("port=").append(target.port()).append('\n');
        sb.append("user=").append(user).append('\n');
        if (password != null) {
            sb.append("password=").append(password).append('\n');
        }

        Path file = backupDir.resolve(".backup-credentials.cnf");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        restrictPermissions(file);
        return file;
    }

    /** 收紧权限到 600（非 POSIX 文件系统上静默跳过 —— Windows 下由目录权限兜底） */
    private void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, owner);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("当前文件系统不支持 POSIX 权限，跳过凭据文件权限收紧：{}", file);
        }
    }

    private void runDump(String binary, Path defaultsFile, Path sqlFile) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--defaults-extra-file=" + defaultsFile.toAbsolutePath());
        // 一致性快照：InnoDB 下不锁表，导出期间业务可继续写（ 未强制，但这是最小打扰的做法）
        command.add("--single-transaction");
        command.add("--routines");
        command.add("--events");
        command.add("--triggers");
        command.add("--default-character-set=utf8mb4");
        command.add("--set-gtid-purged=OFF");
        command.add("--result-file=" + sqlFile.toAbsolutePath());
        command.add("--databases");
        command.add(jdbcTarget().database());

        int timeoutSeconds = appProperties.getBackup().getTimeoutSeconds();
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException("mysqldump 超时（超过 " + timeoutSeconds + " 秒）。"
                        + "大库请调大应用配置 app.backup.timeout-seconds。");
            }
            if (process.exitValue() != 0) {
                throw new BackupException("mysqldump 执行失败（退出码 " + process.exitValue() + "）："
                        + truncate(output));
            }
        } catch (IOException e) {
            throw new BackupException("无法启动 mysqldump（" + binary + "）：" + describe(e)
                    + "。请确认已安装 MySQL 客户端，或通过应用配置 app.backup.mysqldump 指定完整路径。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("备份被中断", e);
        }
    }

    /** 流式 gzip，返回产物字节数 */
    private long gzip(Path source, Path target) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source));
             OutputStream out = new GZIPOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(target)))) {
            in.transferTo(out);
        }
        return Files.size(target);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** JDBC 连接三要素（从 spring.datasource.url 解析得到） */
    record JdbcTarget(String host, String port, String database) {
    }

    private JdbcTarget jdbcTarget() {
        return parseJdbcUrl(dataSourceProperties.getUrl());
    }

    /**
     * 解析 {@code jdbc:mysql://host:port/db?params}。
     *
     * <h2>⚠️ 这里曾经用「按分隔符取段」的写法，结果真跑起来才发现是错的</h2>
     * 最早写成「取第一个 {@code //} 之后到第一个 {@code :} 之间 = 主机」、
     * 「取第一个 {@code :} 之后到第一个 {@code /} 之间 = 端口」、
     * 「取第一个 {@code /} 之后到 {@code ?} 之间 = 库名」。
     * 这三条在 {@code jdbc:mysql://localhost:3306/ticket_system} 上<b>全部出错</b>：
     * <ul>
     *   <li>第一个 {@code :} 落在 {@code jdbc:} 里 ⇒ 端口被解析成 {@code jdbc}；</li>
     *   <li>第一个 {@code /} 落在 {@code //} 里 ⇒ 库名被解析成
     *       {@code /localhost:3306/ticket_system}。</li>
     * </ul>
     * 而 mysqldump 的报错是 {@code Unknown database '/localhost:3306/ticket_system'} ——
     * 从这句看不出是「解析错了」还是「库真不存在」，排查成本很高。
     *
     * <p>正确做法：先剥掉 scheme（{@code jdbc:mysql://}），再按
     * {@code authority = host[:port]} + {@code /database[?params]} 的结构切分。
     * 端口取 authority 里<b>最后一个</b> {@code :}（IPv6 字面量含多个冒号，
     * 虽然本项目的部署形态用不到，但用 lastIndexOf 的语义本来就比 indexOf 更贴近
     * 「主机与端口的分界在末尾」这一事实）。
     */
    static JdbcTarget parseJdbcUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return new JdbcTarget("localhost", "3306", "ticket_system");
        }
        String rest = url;
        int schemeEnd = rest.indexOf("//");
        if (schemeEnd >= 0) {
            rest = rest.substring(schemeEnd + 2);
        }
        String authority = rest;
        String database = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            database = rest.substring(slash + 1);
        }
        int params = database.indexOf('?');
        if (params >= 0) {
            database = database.substring(0, params);
        }
        String host = authority;
        String port = "3306";
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        return new JdbcTarget(host.isBlank() ? "localhost" : host,
                port.isBlank() ? "3306" : port,
                database.isBlank() ? "ticket_system" : database);
    }

    /** 失败原因截断到前 N 行，并在截断处明说「已截断」 */
    private String truncate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "（mysqldump 未输出错误信息）";
        }
        String[] lines = raw.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < MAX_ERROR_LINES; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(lines[i].strip());
        }
        if (lines.length > MAX_ERROR_LINES) {
            sb.append(" …（已截断，共 ").append(lines.length).append(" 行）");
        }
        // 兜一道脱敏：万一口令以某种形式出现在输出里，也不要让它进库与进消息
        return sb.toString().replaceAll("(?i)password\\s*=\\s*\\S+", "password=***");
    }

    private String describe(IOException e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时文件删除失败（不影响本次备份结果）：{}", path, e);
        }
    }

    /** 供日志使用：把 Windows / Linux 路径统一成正斜杠，便于在文档里对照 */
    public static String normalize(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** 归档文件名：db-backup-YYYYmmdd-HHmmss.sql.gz（与侧车命名区隔，避免混淆两份产物） */
    public static String fileNameOf(java.time.LocalDateTime at) {
        return "db-backup-" + at.format(java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".sql.gz";
    }
}
