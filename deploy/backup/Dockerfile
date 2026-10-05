# =====================================================================
# 备份作业镜像（规范 §32：每日全量备份 + 失败不得静默）
#
# 基础镜像选择：直接复用 mysql:8.0。
#   理由一（关键）：mysqldump 与被备份的 MySQL 8 服务端版本一致。
#     Alpine 上只能装 mariadb-client，其客户端连接 MySQL 8 默认的
#     caching_sha2_password 认证在非 TLS 场景下常需额外参数，
#     而「备份工具连不上数据库」这种事往往要等到真正需要恢复时才暴露。
#   理由二：该镜像与 mysql 服务同源，宿主机上已经拉取过，几乎不增加额外磁盘占用。
#
# 构建：docker compose build backup   （在 deploy/ 目录执行）
# =====================================================================
FROM mysql:8.0

LABEL org.opencontainers.image.title="ticket-system-backup" \
      org.opencontainers.image.description="企业内部设备借用工单系统 - 每日备份作业"

# 同时兼容 Oracle Linux(microdnf) 与 Debian(apt) 两种基础镜像形态：
# mysql:8.0 官方标签在不同阶段切换过基础镜像，写死一种包管理器会在换标签时直接构建失败。
RUN if command -v microdnf >/dev/null 2>&1; then \
      microdnf install -y --setopt=install_weak_deps=0 --allowerasing \
        cronie gzip findutils tzdata curl \
      && microdnf clean all \
      && rm -rf /var/cache/dnf /var/cache/yum; \
    elif command -v apt-get >/dev/null 2>&1; then \
      apt-get update \
      && apt-get install -y --no-install-recommends cron gzip findutils tzdata curl ca-certificates \
      && rm -rf /var/lib/apt/lists/*; \
    else \
      echo "不支持的备份基础镜像：找不到 microdnf 或 apt-get" >&2; exit 1; \
    fi

COPY backup.sh entrypoint.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/backup.sh /usr/local/bin/entrypoint.sh \
 && mkdir -p /backup /source/attachments /deploy /var/log

ENV TZ=Asia/Shanghai

# 健康检查：备份容器是常驻的 cron 守护，只要 crond 进程在就算存活。
# 容器本身不提供 HTTP 端点，故用进程存在性判定。
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
  CMD pgrep -x crond >/dev/null || pgrep -x cron >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
