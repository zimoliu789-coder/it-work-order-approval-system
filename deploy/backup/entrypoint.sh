#!/bin/sh
# =====================================================================
# 备份容器入口：把环境变量固化进 crontab → 启动守护进程
#
# 为什么要「把环境变量写进 crontab」：
#   cron 作业【不会继承】docker 传入的进程环境。若不做这一步，
#   backup.sh 里读到的 MYSQL_PASSWORD / ALERT_URL 全是空值，
#   表现为「每天准时跑、每天都失败」，而失败原因只看得到「口令为空」。
#   crontab 里的 NAME=value 行是字面量赋值（不做 shell 展开），
#   因此口令里的 $ / " / % 等字符不会被二次解释，是这里最安全的传递方式。
# =====================================================================
set -eu

BACKUP_CRON="${BACKUP_CRON:-0 2 * * *}"
LOG_FILE=/var/log/backup.log

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] [entrypoint] $*"
}

# ---------------------------------------------------------------------
# 1. 校验 cron 表达式（5 段：分 时 日 月 周）
#    写错就退出而不是「带着坏表达式硬起」：后者会让容器显示 running 却永不备份，
#    是最危险的静默失败形态。
# ---------------------------------------------------------------------
# shellcheck disable=SC2086
set -- $BACKUP_CRON
if [ "$#" -ne 5 ]; then
  echo "[FATAL] BACKUP_CRON 必须是标准 5 段表达式（分 时 日 月 周），当前值：${BACKUP_CRON}" >&2
  echo "        示例：每天凌晨 2 点 = '0 2 * * *'" >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 2. 定位 crontab 文件（cronie 与 Debian cron 的路径不同）
# ---------------------------------------------------------------------
if [ -d /var/spool/cron/crontabs ]; then
  CRONTAB_FILE=/var/spool/cron/crontabs/root
elif [ -d /var/spool/cron ]; then
  CRONTAB_FILE=/var/spool/cron/root
else
  mkdir -p /var/spool/cron
  CRONTAB_FILE=/var/spool/cron/root
fi
mkdir -p "$(dirname "$CRONTAB_FILE")"
chmod 700 "$(dirname "$CRONTAB_FILE")"

# ---------------------------------------------------------------------
# 3. 生成 crontab：白名单前缀的环境变量 + 一行作业
#    作业输出重定向到 LOG_FILE，再由下面的 tail 转到容器 stdout，
#    这样 `docker compose logs backup` 能直接看到每次备份的结果与失败原因。
# ---------------------------------------------------------------------
{
  echo "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  env | grep -E '^(TZ|BACKUP_[A-Z_]*|MYSQL_[A-Z_]*|INTERNAL_ALERT_TOKEN|ALERT_URL|ATTACHMENTS_DIR|CONFIG_DIR)=' || true
  echo "${BACKUP_CRON} /usr/local/bin/backup.sh >> ${LOG_FILE} 2>&1"
} > "$CRONTAB_FILE"
chmod 600 "$CRONTAB_FILE"

log "备份计划已装载：${BACKUP_CRON}（时区 ${TZ:-未设置}）"
log "保留策略：${BACKUP_RETENTION_DAYS:-30} 天；备份目录：${BACKUP_DIR:-/backup}"

touch "$LOG_FILE"

# ---------------------------------------------------------------------
# 4. 可选：启动即执行一次
#    用途：部署当天就能验证「备份是否真的能跑通」，而不必等到凌晨 2 点。
#    仅在显式设置 BACKUP_RUN_ON_START=true 时启用，避免每次重启都产生一份额外归档。
# ---------------------------------------------------------------------
if [ "${BACKUP_RUN_ON_START:-false}" = "true" ]; then
  log "BACKUP_RUN_ON_START=true，立即执行一次备份"
  /usr/local/bin/backup.sh >> "$LOG_FILE" 2>&1 || log "启动即备份失败，详见上方日志"
fi

# ---------------------------------------------------------------------
# 5. 启动 cron 守护（前台运行，作为容器主进程）
# ---------------------------------------------------------------------
tail -F "$LOG_FILE" 2>/dev/null &

if command -v crond >/dev/null 2>&1; then
  log "启动 crond（cronie）"
  exec crond -n
elif command -v cron >/dev/null 2>&1; then
  log "启动 cron（Debian）"
  exec cron -f
else
  echo "[FATAL] 基础镜像中既没有 crond 也没有 cron，无法调度备份" >&2
  exit 1
fi
