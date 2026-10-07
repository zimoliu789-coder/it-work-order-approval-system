#!/bin/sh
# =====================================================================
# 部署前体检（ / ）
#
# 目的：把「启动后才发现」的问题提前到「启动前一次性说清楚」。
# 覆盖以下真实踩坑点：
#   - .env 仍留着 __CHANGE_ME__ 占位值（最常见）→ 容器能起来但登录不上 / 密钥无效
#   - 密钥过短（JWT_SECRET < 32 字节）→ 后端启动即报错退出，日志要翻很久才看懂
#   - 数据目录属主不对 → 后端（以 uid 10001 运行）写不了日志，健康检查永远不通过
#   - BACKUP_DIR 与 DATA_ROOT 同盘 → 磁盘故障时备份与生产数据一起消失（仅启用备份容器时相关）
#   - WEB_PORT 被占用 → nginx 起不来，报「address already in use」
#   - 磁盘空间不足 → mysqldump 跑到一半把盘写满，连带影响生产库
#
# 用法（在 deploy 目录下执行）：bash scripts/preflight.sh
# 退出码：0 = 全部通过（允许有 WARN）；1 = 存在必须处理的问题
# =====================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$DEPLOY_DIR"

ERRORS=0
WARNINGS=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; WARNINGS=$((WARNINGS + 1)); }
err()  { printf '  \033[31m✗\033[0m %s\n' "$*"; ERRORS=$((ERRORS + 1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$*"; }

printf '\033[1m========== 设备借用工单系统 · 部署前体检 ==========\033[0m\n'

# ---------------------------------------------------------------------
head_ "1. 运行环境"
# ---------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  ok "docker：$(docker --version 2>/dev/null | head -n 1)"
else
  err "未找到 docker 命令（群晖请通过 Container Manager 的终端 / SSH 执行，或安装 Docker 套件）"
fi

if docker compose version >/dev/null 2>&1; then
  ok "docker compose：$(docker compose version 2>/dev/null | head -n 1)"
elif command -v docker-compose >/dev/null 2>&1; then
  warn "只找到旧版 docker-compose（v1）。本项目的 depends_on.condition 需要 v2，请升级到 docker compose plugin"
else
  err "未找到 docker compose（v2）"
fi

if command -v openssl >/dev/null 2>&1; then
  ok "openssl 可用（用于生成密钥）"
else
  warn "未找到 openssl，生成密钥时请改用其它方式（如 docker run --rm alpine/openssl rand -base64 48）"
fi

# ---------------------------------------------------------------------
head_ "2. .env 配置文件"
# ---------------------------------------------------------------------
if [ ! -f .env ]; then
  err "当前目录没有 .env。请先执行：cp .env.production.example .env 并填写"
  printf '\n\033[1m体检未通过：存在 %s 个必须处理的问题\033[0m\n' "$ERRORS"
  exit 1
fi
ok "找到 .env"

# 载入用于后续检查（-a 使变量自动 export）
set -a
# shellcheck disable=SC1091
. ./.env
set +a

# 需要检查的必填项
# 注意：SUPER_ADMIN_INIT_PASSWORD **不在必填之列** —— 留空表示走「初始化向导」，
#       由管理员首次访问时现场设定账号名与密码，这是推荐的默认路径。
# 注意：BACKUP_DIR 不在必填之列 —— 备份容器默认不启动（docker compose --profile backup 才启用），
#       启用后留空会自动落到 ${DATA_ROOT}/backup。
for VAR in DATA_ROOT MYSQL_ROOT_PASSWORD REDIS_PASSWORD JWT_SECRET INTERNAL_ALERT_TOKEN; do
  eval "VALUE=\"\${$VAR:-}\""
  if [ -z "$VALUE" ]; then
    err "$VAR 未设置或为空"
  elif echo "$VALUE" | grep -q '__CHANGE_ME__'; then
    err "$VAR 仍是模板占位值 __CHANGE_ME__，必须替换"
  else
    ok "$VAR 已设置"
  fi
done

# 密钥强度
if [ -n "${JWT_SECRET:-}" ] && ! echo "$JWT_SECRET" | grep -q '__CHANGE_ME__'; then
  LEN=$(printf '%s' "$JWT_SECRET" | wc -c | tr -d ' ')
  if [ "$LEN" -lt 32 ]; then
    err "JWT_SECRET 只有 ${LEN} 字节，必须 ≥ 32 字节（建议：openssl rand -base64 48）"
  else
    ok "JWT_SECRET 长度 ${LEN} 字节"
  fi
fi

for VAR in MYSQL_ROOT_PASSWORD REDIS_PASSWORD SUPER_ADMIN_INIT_PASSWORD; do
  eval "VALUE=\"\${$VAR:-}\""
  if [ -n "$VALUE" ] && ! echo "$VALUE" | grep -q '__CHANGE_ME__'; then
    LEN=$(printf '%s' "$VALUE" | wc -c | tr -d ' ')
    if [ "$LEN" -lt 12 ]; then
      warn "$VAR 只有 ${LEN} 位，建议 ≥ 12 位（建议：openssl rand -base64 24）"
    fi
  fi
done

if [ -n "${INTERNAL_ALERT_TOKEN:-}" ] && ! echo "$INTERNAL_ALERT_TOKEN" | grep -q '__CHANGE_ME__'; then
  LEN=$(printf '%s' "$INTERNAL_ALERT_TOKEN" | wc -c | tr -d ' ')
  [ "$LEN" -lt 16 ] && warn "INTERNAL_ALERT_TOKEN 只有 ${LEN} 位，建议 ≥ 16 位（建议：openssl rand -hex 32）"
fi

# 同一口令复用检查
if [ -n "${MYSQL_ROOT_PASSWORD:-}" ] && [ "${MYSQL_ROOT_PASSWORD}" = "${REDIS_PASSWORD:-}" ]; then
  warn "MYSQL_ROOT_PASSWORD 与 REDIS_PASSWORD 相同：一个泄露会同时影响数据库与缓存"
fi

# ---------------------------------------------------------------------
head_ "3. 路径规划"
# ---------------------------------------------------------------------
case "${DATA_ROOT:-}" in
  "" | "/") err "DATA_ROOT 取值非法（${DATA_ROOT:-空}）：不允许使用根目录" ;;
  *) ok "DATA_ROOT = $DATA_ROOT" ;;
esac

case "${BACKUP_DIR:-}" in
  # 未填 = 未启用备份容器（profile backup 未激活），合法
  "") : ;;
  "/") err "BACKUP_DIR 取值非法：不允许使用根目录" ;;
  *) ok "BACKUP_DIR = $BACKUP_DIR" ;;
esac

if [ -n "${DATA_ROOT:-}" ] && [ -n "${BACKUP_DIR:-}" ]; then
  if [ "$BACKUP_DIR" = "$DATA_ROOT" ]; then
    err "BACKUP_DIR 与 DATA_ROOT 相同：备份与生产数据在同一目录，失去备份意义（ 明确要求分离）"
  elif echo "$BACKUP_DIR" | grep -q "^${DATA_ROOT}/"; then
    err "BACKUP_DIR 位于 DATA_ROOT 之内：整目录迁移/误删会同时带走备份"
  else
    ok "备份目录与数据目录已分离"
  fi
fi

# ---------------------------------------------------------------------
head_ "4. 数据目录准备"
# ---------------------------------------------------------------------
if [ -n "${DATA_ROOT:-}" ] && [ "$DATA_ROOT" != "/" ]; then
  for SUB in mysql redis logs logs/nginx attachments exports; do
    if mkdir -p "$DATA_ROOT/$SUB" 2>/dev/null; then
      ok "目录就绪：$DATA_ROOT/$SUB"
    else
      err "无法创建目录：$DATA_ROOT/$SUB（检查父目录是否存在及当前用户权限）"
    fi
  done

  # 后端镜像以 uid 10001 运行，这三类目录必须是它的属主，否则启动时就写不了日志
  if [ "$(id -u)" = "0" ]; then
    if chown -R 10001:10001 "$DATA_ROOT/logs" "$DATA_ROOT/attachments" "$DATA_ROOT/exports" 2>/dev/null; then
      ok "已设置属主 10001:10001（logs / attachments / exports）"
    else
      err "设置属主失败（logs / attachments / exports 必须属于 10001:10001）"
    fi
  else
    warn "当前不是 root，无法自动设置属主。请以 root 执行：chown -R 10001:10001 \"$DATA_ROOT/logs\" \"$DATA_ROOT/attachments\" \"$DATA_ROOT/exports\""
    warn "（否则后端容器启动后会因无法写日志而反复重启，健康检查始终不通过）"
  fi
fi

if [ -n "${BACKUP_DIR:-}" ] && [ "$BACKUP_DIR" != "/" ]; then
  if mkdir -p "$BACKUP_DIR" 2>/dev/null; then
    # 备份内含 .env 明文口令，目录只允许 root 进入
    chmod 700 "$BACKUP_DIR" 2>/dev/null || true
    ok "备份目录就绪：$BACKUP_DIR（权限 700）"
  else
    err "无法创建备份目录：$BACKUP_DIR"
  fi
  if [ "$(id -u)" != "0" ]; then
    warn "当前不是 root，备份容器以 root 运行才能写入 $BACKUP_DIR 并设置 600 权限，请确认目录权限"
  fi
fi

# ---------------------------------------------------------------------
head_ "5. 端口与磁盘"
# ---------------------------------------------------------------------
WEB_PORT_VALUE="${WEB_PORT:-8080}"
if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${WEB_PORT_VALUE}$"; then
    err "端口 ${WEB_PORT_VALUE} 已被占用（群晖上 80/443 通常被 DSM 占用，建议使用 8080 等高位端口）"
  else
    ok "端口 ${WEB_PORT_VALUE} 空闲"
  fi
elif command -v netstat >/dev/null 2>&1; then
  if netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${WEB_PORT_VALUE}$"; then
    err "端口 ${WEB_PORT_VALUE} 已被占用"
  else
    ok "端口 ${WEB_PORT_VALUE} 空闲"
  fi
else
  warn "缺少 ss / netstat，跳过端口占用检查"
fi

if [ -n "${DATA_ROOT:-}" ] && [ -d "$DATA_ROOT" ]; then
  AVAIL_KB=$(df -Pk "$DATA_ROOT" 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${AVAIL_KB:-}" ]; then
    AVAIL_GB=$((AVAIL_KB / 1024 / 1024))
    if [ "$AVAIL_GB" -lt 5 ]; then
      warn "DATA_ROOT 可用空间仅 ${AVAIL_GB} GB：MySQL 数据 + 附件 + 日志 + 导出都在这块盘上，建议 ≥ 20 GB"
    else
      ok "DATA_ROOT 可用空间 ${AVAIL_GB} GB"
    fi
  fi
fi

if [ -n "${BACKUP_DIR:-}" ] && [ -d "$BACKUP_DIR" ]; then
  AVAIL_KB=$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${AVAIL_KB:-}" ]; then
    AVAIL_GB=$((AVAIL_KB / 1024 / 1024))
    if [ "$AVAIL_GB" -lt 5 ]; then
      warn "BACKUP_DIR 可用空间仅 ${AVAIL_GB} GB：归档含全库 + 附件，建议 ≥ 3 倍数据量"
    else
      ok "BACKUP_DIR 可用空间 ${AVAIL_GB} GB"
    fi
  fi
fi

# ---------------------------------------------------------------------
head_ "6. Compose 配置校验"
# ---------------------------------------------------------------------
if docker compose config -q >/dev/null 2>&1; then
  ok "docker-compose.yml 语法与变量引用校验通过"
else
  err "docker compose config 校验失败，请执行 docker compose config 查看具体报错"
fi

if [ -n "${TRUSTED_PROXIES:-}" ]; then
  ok "TRUSTED_PROXIES = $TRUSTED_PROXIES"
  printf '    提示：这就是允许携带 X-Forwarded-For 的可信网段。若外层反代不在此范围内，\n'
  printf '          审计日志与限流会退化成「所有用户同一个 IP」。\n'
else
  warn "未设置 TRUSTED_PROXIES，将使用默认内网网段"
fi

# ---------------------------------------------------------------------
printf '\n\033[1m================== 体检结论 ==================\033[0m\n'
printf '  必须处理：%s 项\n' "$ERRORS"
printf '  提示建议：%s 项\n' "$WARNINGS"

if [ "$ERRORS" -gt 0 ]; then
  printf '\n\033[31m未通过：请先解决上述「必须处理」项，再执行 docker compose up -d --build\033[0m\n'
  exit 1
fi

printf '\n\033[32m通过：可以执行 docker compose up -d --build 启动服务\033[0m\n'
printf '\n启动后建议按顺序验证：\n'
printf '  1) docker compose ps                     确认各容器 healthy\n'
printf '  2) curl -s http://127.0.0.1:%s/api/health  查看 clientIp / scheme / trustedProxyRules\n' "${WEB_PORT:-8080}"
printf '  3) 经外层反代（https://<域名>/）访问，确认 scheme=https 且 clientIp 是你的真实出口 IP\n'
printf '  4) BACKUP_RUN_ON_START=true 启动一次备份容器，当天即可验证备份链路\n'
printf '==============================================\n'
exit 0
