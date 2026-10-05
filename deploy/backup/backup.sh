#!/bin/sh
# =====================================================================
# 每日备份脚本（ 数据备份与恢复）
#
# 产出：${BACKUP_DIR}/db-backup-YYYYmmdd-HHMMSS.tar.gz
# 内容（规范要求的三样，打成一个 gzip 包）：
#   1) database.sql   —— MySQL 全库逻辑导出
#   2) attachments/   —— 附件文件目录（用户上传的原始资料，必须可恢复）
#   3) config/.env    —— 配置文件（含数据库口令、JWT 密钥等，缺了它无法完整重建系统）
#   另含 manifest.txt —— 本次备份的元信息，便于日后核对归档来源
#
# 关键设计（每一条都对应一类真实事故）：
#   * umask 077 + chmod 600：归档内含 .env 明文口令，绝不能被同机其它账号读到；
#   * 先校验再清理：只有【本次归档校验通过】才执行保留策略，
#     否则「某天备份失败 + 同时删掉旧备份」会叠加成「一个能用的备份都不剩」；
#   * 校验三层：文件非空 → gzip 完整性 → 归档内确实含 database.sql；
#   * 失败必告警：写站内消息给超管；连告警都发不出去时落一个本地告警文件，
#     保证「失败」在任何情况下都不会完全静默；
#   * 失败原因截断到前 8 行：mysqldump 的报错可能很长，全量塞进消息会被截断得莫名其妙。
# =====================================================================
set -eu

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH

# 新建文件默认 600：先收紧再创建任何东西，避免「先建后改权限」之间出现可读窗口
umask 077

# ---------------------------------------------------------------------
# 配置（全部来自环境变量，由 docker-compose 注入）
# ---------------------------------------------------------------------
BACKUP_DIR="${BACKUP_DIR:-/backup}"
RETENTION="${BACKUP_RETENTION_DAYS:-30}"
MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-ticket_system}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
ATTACHMENTS_DIR="${ATTACHMENTS_DIR:-/source/attachments}"
CONFIG_DIR="${CONFIG_DIR:-/deploy}"
ALERT_URL="${ALERT_URL:-}"
INTERNAL_ALERT_TOKEN="${INTERNAL_ALERT_TOKEN:-}"
NOTIFY_ON_SUCCESS="${BACKUP_NOTIFY_SUCCESS:-false}"

TS="$(date +%Y%m%d-%H%M%S)"
HOSTNAME_VALUE="$(hostname 2>/dev/null || echo unknown)"
ARCHIVE="$BACKUP_DIR/db-backup-$TS.tar.gz"
STAGE="$BACKUP_DIR/.stage-$TS"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] [backup] $*"
}

# ---------------------------------------------------------------------
# JSON 转义：失败原因里常出现引号、反斜杠、换行，
# 不转义会拼出非法 JSON，后端直接 400 —— 告警通道在最需要它的时刻失效
# ---------------------------------------------------------------------
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\r/\\r/g' -e 's/\t/\\t/g' \
    -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g'
}

cleanup_stage() {
  [ -n "${STAGE:-}" ] && [ -d "$STAGE" ] && rm -rf "$STAGE"
  return 0
}

# ---------------------------------------------------------------------
# 上报结果到后端（由后端向全部超管发站内消息）
# ---------------------------------------------------------------------
notify() {
  status="$1"
  size_json="$2"
  error_text="$3"

  if [ -z "$ALERT_URL" ] || [ -z "$INTERNAL_ALERT_TOKEN" ]; then
    log "WARN 未配置 ALERT_URL / INTERNAL_ALERT_TOKEN，跳过结果上报"
    return 0
  fi

  short_error="$(printf '%s' "$error_text" | head -n 8)"
  payload="$(printf '{"status":"%s","fileName":"%s","sizeBytes":%s,"error":"%s","host":"%s","retentionDays":%s}' \
    "$(json_escape "$status")" \
    "$(json_escape "$(basename "$ARCHIVE")")" \
    "$size_json" \
    "$(json_escape "$short_error")" \
    "$(json_escape "$HOSTNAME_VALUE")" \
    "${RETENTION:-0}")"

  code=000
  if command -v curl >/dev/null 2>&1; then
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 \
      -X POST "$ALERT_URL" \
      -H 'Content-Type: application/json; charset=utf-8' \
      -H 'X-Requested-With: XMLHttpRequest' \
      -H "X-Internal-Token: ${INTERNAL_ALERT_TOKEN}" \
      --data-binary "$payload" 2>/dev/null || echo 000)"
  fi

  if [ "$code" = "200" ]; then
    log "结果已上报后端（status=${status}）"
    return 0
  fi

  # 告警通道本身失败：落本地文件，确保「备份失败」这件事有第二个落点。
  # 只写日志会随容器重建丢失，因此写到宿主备份目录里。
  log "WARN 结果上报失败（HTTP ${code}），改写入本地告警文件"
  {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 备份结果上报失败 HTTP=${code} status=${status}"
    echo "  archive=${ARCHIVE}"
    echo "  host=${HOSTNAME_VALUE}"
    echo "  error=${short_error}"
    echo "  alertUrl=${ALERT_URL}"
  } >> "$BACKUP_DIR/ALERT-SEND-FAILED.log" 2>/dev/null || log "WARN 本地告警文件同样写入失败"
  return 0
}

fail() {
  reason="$1"
  log "ERROR ${reason}"
  cleanup_stage
  notify "FAILED" "null" "$reason"
  exit 1
}

trap 'cleanup_stage' EXIT

# ---------------------------------------------------------------------
# 0. 前置校验
# ---------------------------------------------------------------------
[ -n "$MYSQL_PASSWORD" ] || fail "MYSQL_PASSWORD 为空，无法连接数据库（请检查 .env 与 crontab 环境注入）"
case "$BACKUP_DIR" in
  "" | "/") fail "BACKUP_DIR 取值非法（${BACKUP_DIR}），拒绝在根目录下操作" ;;
esac

mkdir -p "$BACKUP_DIR" || fail "无法创建/访问备份目录 ${BACKUP_DIR}"
[ -w "$BACKUP_DIR" ] || fail "备份目录不可写 ${BACKUP_DIR}"

# 同盘检查：备份与生产数据放在同一块盘时，磁盘故障会一起丢掉。
# 用 inode 设备号判断「是否同一个文件系统」；无法判断时只提示不阻断。
if [ -d "$ATTACHMENTS_DIR" ]; then
  dev_backup="$(stat -c %d "$BACKUP_DIR" 2>/dev/null || echo '')"
  dev_data="$(stat -c %d "$ATTACHMENTS_DIR" 2>/dev/null || echo '')"
  if [ -n "$dev_backup" ] && [ "$dev_backup" = "$dev_data" ]; then
    log "WARN 备份目录与附件目录位于同一文件系统，磁盘故障时两者会一同丢失；建议 BACKUP_DIR 指向另一块盘"
  fi
fi

log "==== 开始备份 ===="
log "数据库=${MYSQL_USERNAME}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}  归档=${ARCHIVE}"

mkdir -p "$STAGE"

# ---------------------------------------------------------------------
# 1. 数据库全量导出
#    MYSQL_PWD 传口令而不是 -p：命令行参数会出现在 ps 输出里，同机任何账号都能看到口令
#    --single-transaction：InnoDB 一致性快照，导出期间不锁表（业务不中断）
#    --set-gtid-purged=OFF：让导出文件可直接导入到未开启 GTID 的新实例
#    --no-tablespaces：避免因缺少 PROCESS 权限而导出失败
# ---------------------------------------------------------------------
log "导出数据库…"
if ! MYSQL_PWD="$MYSQL_PASSWORD" mysqldump \
      --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USERNAME" \
      --single-transaction --quick --routines --triggers --events \
      --default-character-set=utf8mb4 \
      --set-gtid-purged=OFF --no-tablespaces \
      --databases "$MYSQL_DATABASE" > "$STAGE/database.sql" 2> "$STAGE/mysqldump.err"; then
  fail "mysqldump 执行失败：$(head -n 8 "$STAGE/mysqldump.err" | tr '\n' ' ')"
fi

[ -s "$STAGE/database.sql" ] || fail "数据库导出文件为空"

# 内容级校验：只判断「文件非空」会漏掉「只导出了警告、没有任何建表语句」这种半失败
if ! grep -q 'CREATE TABLE' "$STAGE/database.sql"; then
  fail "数据库导出内容异常：未发现任何 CREATE TABLE 语句（疑似连到了错误的库）"
fi

TABLE_COUNT="$(grep -c 'CREATE TABLE' "$STAGE/database.sql" || echo 0)"
log "数据库导出完成，包含 ${TABLE_COUNT} 张表"

# ---------------------------------------------------------------------
# 2. 附件目录（直接从源目录打包，不做拷贝，避免占用双倍磁盘）
# ---------------------------------------------------------------------
if [ -d "$ATTACHMENTS_DIR" ]; then
  ATT_PARENT="$(dirname "$ATTACHMENTS_DIR")"
  ATT_NAME="$(basename "$ATTACHMENTS_DIR")"
  ATT_COUNT="$(find "$ATTACHMENTS_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')"
  log "打包附件目录（${ATT_COUNT} 个文件）"
else
  log "WARN 附件目录不存在：${ATTACHMENTS_DIR}，归档中将只放一份说明文件"
  mkdir -p "$STAGE/attachments"
  printf '备份时附件目录不存在：%s\n请确认 ATTACHMENTS_DIR 挂载是否正确。\n' "$ATTACHMENTS_DIR" \
    > "$STAGE/attachments/MISSING-ATTACHMENTS.txt"
  ATT_PARENT="$STAGE"
  ATT_NAME="attachments"
fi

# ---------------------------------------------------------------------
# 3. 配置文件（.env）
# ---------------------------------------------------------------------
mkdir -p "$STAGE/config"
if [ -f "$CONFIG_DIR/.env" ]; then
  cp -p "$CONFIG_DIR/.env" "$STAGE/config/.env"
  log "已包含配置文件 ${CONFIG_DIR}/.env"
else
  log "WARN 未找到 ${CONFIG_DIR}/.env，归档中将只放一份说明文件"
  printf '备份时未找到 .env（查找路径：%s/.env）。\n' "$CONFIG_DIR" > "$STAGE/config/MISSING-ENV.txt"
fi

# ---------------------------------------------------------------------
# 4. manifest
# ---------------------------------------------------------------------
{
  echo "备份时间: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "执行主机: ${HOSTNAME_VALUE}"
  echo "数据库  : ${MYSQL_USERNAME}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
  echo "建表数量: ${TABLE_COUNT}"
  echo "保留策略: ${RETENTION} 天"
  echo "归档名称: $(basename "$ARCHIVE")"
  echo
  echo "恢复步骤见 deploy/README.md（恢复演练章节）与 scripts/restore.sh。"
} > "$STAGE/manifest.txt"

# ---------------------------------------------------------------------
# 5. 打包
# ---------------------------------------------------------------------
log "生成归档…"
if ! tar -czf "$ARCHIVE" \
      -C "$STAGE" manifest.txt database.sql config \
      -C "$ATT_PARENT" "$ATT_NAME" 2> "$STAGE/tar.err"; then
  fail "打包失败：$(head -n 8 "$STAGE/tar.err" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------
# 6. 校验（：备份必须自动校验，不能只「跑完就算」）
# ---------------------------------------------------------------------
[ -s "$ARCHIVE" ] || fail "归档文件为空（0 字节）"

if ! gzip -t "$ARCHIVE" 2>/dev/null; then
  fail "归档完整性校验失败（gzip -t 不通过，文件可能被写坏或磁盘空间不足）"
fi

if ! tar -tzf "$ARCHIVE" > /dev/null 2>&1; then
  fail "归档无法读取（tar -tzf 失败）"
fi

if ! tar -tzf "$ARCHIVE" 2>/dev/null | grep -q 'database.sql'; then
  fail "归档中缺少 database.sql，内容不完整"
fi

SIZE_BYTES="$(wc -c < "$ARCHIVE" | tr -d ' ')"
if [ "$SIZE_BYTES" -lt 1024 ]; then
  fail "归档大小异常（${SIZE_BYTES} 字节，疑似内容为空）"
fi

# 归档内含 .env 明文口令，权限必须只对 root 开放
chmod 600 "$ARCHIVE" || fail "无法设置归档权限（chmod 600）"
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))
log "归档校验通过：$(basename "$ARCHIVE")（${SIZE_MB} MB，权限 600）"

# ---------------------------------------------------------------------
# 7. 保留策略（：清理旧归档）
#    刻意放在「校验通过之后」：只有今天这份确认可用，才允许删掉历史归档。
#    反过来（先清理后校验）会出现「备份失败 + 旧归档被删」= 一个可用备份都不剩。
# ---------------------------------------------------------------------
if [ "$RETENTION" -gt 0 ]; then
  log "清理超过 ${RETENTION} 天的历史归档"
  OLD_COUNT="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'db-backup-*.tar.gz' \
      -mtime "+$RETENTION" 2>/dev/null | wc -l | tr -d ' ')"
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'db-backup-*.tar.gz' -mtime "+$RETENTION" -delete 2>/dev/null || true
  [ "$OLD_COUNT" -gt 0 ] && log "已清理 ${OLD_COUNT} 个过期归档"
else
  log "保留策略为 0（永久保留），跳过清理"
fi

# 清理异常中断残留的临时目录（超过 2 天说明是上次崩溃留下的）
find "$BACKUP_DIR" -maxdepth 1 -type d -name '.stage-*' -mtime +2 -exec rm -rf {} + 2>/dev/null || true

# ---------------------------------------------------------------------
# 8. 结果上报
# ---------------------------------------------------------------------
if [ "$NOTIFY_ON_SUCCESS" = "true" ]; then
  notify "SUCCESS" "$SIZE_BYTES" ""
else
  # 成功不推消息：每日一条成功消息会在 30 天内堆成噪音，
  # 最终让用户连真正的失败告警也一起忽略。成功记录见操作日志（模块=运维作业）。
  log "成功（按配置不推送消息；如需推送请设置 BACKUP_NOTIFY_SUCCESS=true）"
fi

log "==== 备份结束 ===="
exit 0
