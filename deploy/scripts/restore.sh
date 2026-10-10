#!/bin/sh
# =====================================================================
# 一键恢复脚本（：必须设计数据库恢复流程并定期演练）
#
# 用法（在 deploy 目录下执行）：
#   bash scripts/restore.sh /volume1/backups/ticket-system/db-backup-20260919-020001.tar.gz
#   bash scripts/restore.sh <归档> --with-env      # 同时还原 .env 到 .env.restored（不覆盖现有 .env）
#   bash scripts/restore.sh <归档> --yes           # 跳过二次确认（用于演练脚本 / 自动化）
#
# 执行顺序（每一步都不可省）：
#   1) 校验归档可读、内容完整
#   2) 停后端 —— 恢复期间绝不能让应用写入，否则会往「刚恢复一半」的库里写入脏数据
#   3) 恢复数据库（先 DROP 再导入，保证与归档完全一致，不残留归档中没有的表）
#   4) 恢复附件目录（原目录改名保留，不直接删除 —— 万一归档选错了还能退回去）
#   5) 启动后端并等待健康检查通过
#   6) 抽样验证（健康接口 + 关键表行数）
#
# ⚠️ 本脚本会 DROP 生产库。执行前请确认：
#    - 归档文件的时间点是你真正想回到的时间点；
#    - 当前库中若存在归档之后的新数据，恢复后这些数据将丢失（必要时先手工做一份当前状态备份）。
# =====================================================================
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$DEPLOY_DIR"

ARCHIVE=""
WITH_ENV="false"
ASSUME_YES="false"
for arg in "$@"; do
  case "$arg" in
    --with-env) WITH_ENV="true" ;;
    --yes) ASSUME_YES="true" ;;
    -*) echo "未知参数：$arg" >&2; exit 2 ;;
    *) ARCHIVE="$arg" ;;
  esac
done

if [ -z "$ARCHIVE" ]; then
  echo "用法：bash scripts/restore.sh <备份归档路径> [--with-env] [--yes]" >&2
  echo "可用归档：" >&2
  ls -lh "${BACKUP_DIR:-/var/backups/ticket-system}"/db-backup-*.tar.gz 2>/dev/null >&2 || echo "  （未找到）" >&2
  exit 2
fi

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [restore] $*"; }
die() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [restore] ERROR $*" >&2; exit 1; }

[ -f "$ARCHIVE" ] || die "归档文件不存在：$ARCHIVE"
[ -f .env ] || die "当前目录没有 .env（请在 deploy 目录下执行，且 .env 已按 .env.production.example 配置）"

# 载入 .env：数据库口令、端口、数据目录都从这里取，避免「脚本里的值和实际部署不一致」
set -a
# shellcheck disable=SC1091
. ./.env
set +a

: "${DATA_ROOT:?}" "${MYSQL_ROOT_PASSWORD:?}" "${MYSQL_DATABASE:=ticket_system}" "${WEB_PORT:=8080}"

# ---------------------------------------------------------------------
# 1. 校验归档
# ---------------------------------------------------------------------
log "校验归档：$ARCHIVE"
gzip -t "$ARCHIVE" || die "归档完整性校验失败（gzip -t）"
MEMBERS="$(tar -tzf "$ARCHIVE")" || die "无法读取归档内容"
echo "$MEMBERS" | grep -q 'database.sql' || die "归档中缺少 database.sql"
echo "$MEMBERS" | grep -q 'manifest.txt' || die "归档中缺少 manifest.txt"

MANIFEST_TMP="$(mktemp -d)"
trap 'rm -rf "$MANIFEST_TMP"' EXIT
tar -xzf "$ARCHIVE" -C "$MANIFEST_TMP" manifest.txt
echo "---------------- 归档信息 ----------------"
cat "$MANIFEST_TMP/manifest.txt"
echo "------------------------------------------"

# ---------------------------------------------------------------------
# 2. 二次确认
# ---------------------------------------------------------------------
if [ "$ASSUME_YES" != "true" ]; then
  printf '\n⚠️  即将【清空并覆盖】当前生产数据库 %s，且恢复附件目录。\n' "$MYSQL_DATABASE"
  printf '    恢复后，归档时间点之后新增的数据将全部丢失。\n'
  printf '    如确认继续，请输入大写 YES：'
  read -r answer
  [ "$answer" = "YES" ] || die "已取消"
fi

# ---------------------------------------------------------------------
# 3. 停后端（保留 nginx：它会把请求转成 503 JSON，用户看到的是「服务暂不可用」而不是连接被拒）
# ---------------------------------------------------------------------
log "停止后端服务…"
docker compose stop backend || die "停止后端失败"

# ---------------------------------------------------------------------
# 4. 恢复数据库
# ---------------------------------------------------------------------
log "解压归档…"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$MANIFEST_TMP" "$WORK_DIR"' EXIT
tar -xzf "$ARCHIVE" -C "$WORK_DIR"

log "重建数据库 ${MYSQL_DATABASE}…"
# MYSQL_PWD 传口令，避免口令出现在宿主机的 ps 输出里
docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysql -uroot -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" \
  || die "重建数据库失败"

log "导入数据（大库可能耗时数分钟，请勿中断）…"
docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysql -uroot < "$WORK_DIR/database.sql" || die "导入数据库失败"

# ---------------------------------------------------------------------
# 5. 恢复附件
# ---------------------------------------------------------------------
if [ -d "$WORK_DIR/attachments" ]; then
  TARGET="$DATA_ROOT/attachments"
  if [ -d "$TARGET" ]; then
    ARCHIVED_OLD="$DATA_ROOT/attachments.before-restore-$(date +%Y%m%d-%H%M%S)"
    log "现有附件目录改名保留：$ARCHIVED_OLD"
    mv "$TARGET" "$ARCHIVED_OLD" || die "无法移动现有附件目录（确认 $DATA_ROOT 可写）"
  fi
  mkdir -p "$DATA_ROOT"
  # -p 保留权限与时间戳；最后统一修正属主，因为后端容器以 10001:10001 运行
  tar -xzf "$ARCHIVE" -C "$DATA_ROOT" attachments || die "恢复附件目录失败"
  if [ "$(id -u)" = "0" ]; then
    chown -R 10001:10001 "$TARGET" 2>/dev/null || log "WARN 修正附件目录属主失败（非致命，但后端可能无法读写附件）"
  fi
  log "附件已恢复：$TARGET"
else
  log "WARN 归档中没有 attachments 条目，跳过附件恢复"
fi

# ---------------------------------------------------------------------
# 6. 恢复配置（默认只还原到 .env.restored，绝不直接覆盖生产 .env）
# ---------------------------------------------------------------------
if [ -f "$WORK_DIR/config/.env" ]; then
  if [ "$WITH_ENV" = "true" ]; then
    cp "$WORK_DIR/config/.env" ./.env.restored
    chmod 600 ./.env.restored
    log "配置文件已还原为 .env.restored（未覆盖 .env）"
    log "  如需使用：diff .env .env.restored 确认差异后手工替换，并重启 backend"
  else
    log "归档内含 config/.env；未指定 --with-env，保留在归档中未释放"
  fi
fi

# ---------------------------------------------------------------------
# 7. 启动后端并等待健康
# ---------------------------------------------------------------------
log "启动后端服务…"
docker compose start backend || die "启动后端失败"

log "等待后端健康检查通过（最多 180 秒）…"
WAITED=0
HEALTH_OK="false"
while [ "$WAITED" -lt 180 ]; do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${WEB_PORT}/api/health" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then
    HEALTH_OK="true"
    break
  fi
  sleep 5
  WAITED=$((WAITED + 5))
done
[ "$HEALTH_OK" = "true" ] || die "后端在 180 秒内未就绪，请查看：docker compose logs --tail=200 backend"

# ---------------------------------------------------------------------
# 8. 抽样验证
# ---------------------------------------------------------------------
echo
echo "================ 恢复结果验证 ================"
printf '健康接口        : HTTP %s\n' "$CODE"

for TABLE in employee device borrow_order order_approval_node attachment operation_log; do
  COUNT="$(docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
    mysql -uroot -N -B -e "SELECT COUNT(*) FROM \`$MYSQL_DATABASE\`.\`$TABLE\`;" 2>/dev/null | tr -d '\r')" || COUNT="读取失败"
  printf '%-16s: %s 行\n' "$TABLE" "$COUNT"
done

MIGRATION="$(docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysql -uroot -N -B -e "SELECT MAX(version) FROM \`$MYSQL_DATABASE\`.flyway_schema_history WHERE success = 1;" 2>/dev/null | tr -d '\r')" || MIGRATION="读取失败"
printf '%-16s: v%s\n' "Flyway 版本" "$MIGRATION"

echo
echo "下一步人工验证（必做）："
echo "  1) 浏览器访问 https://<你的域名>/ 用真实账号登录"
echo "  2) 打开「工单管理 → 全部工单」抽查最近几条记录是否与备份时间点一致"
echo "  3) 打开「系统配置 → 附件」随机下载一个附件，确认文件可正常打开（验证附件与库记录匹配）"
echo "  4) 若能接受，删除改名保留的旧附件目录：$DATA_ROOT/attachments.before-restore-*"
echo "=============================================="
log "恢复动作已全部完成"
