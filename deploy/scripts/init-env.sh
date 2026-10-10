#!/usr/bin/env bash
# =====================================================================
# 一键生成 deploy/.env（从 .env.production.example 派生）
#
# 为什么需要它：模板里有 4 个密钥要手工 openssl 生成再分别粘贴到不同小节，
#   还要自己判断 COOKIE_SECURE 该填 true 还是 false（这一项填错会导致
#   「登录返回 200 但立刻被弹回登录页」，极难自查）。本脚本把这两件事自动化。
#
# 用法（在 deploy 目录下）：
#   bash scripts/init-env.sh              # 交互式，已存在 .env 时拒绝覆盖
#   bash scripts/init-env.sh --force      # 覆盖已存在的 .env（会先备份为 .env.bak-<时间>）
#   bash scripts/init-env.sh --yes        # 全部取默认值，零交互（用于自动化）
#
# 生成后会自动执行 scripts/preflight.sh 体检（不存在则跳过）。
# 退出码：0 = 生成成功；1 = 失败（不留下半成品 .env）
# =====================================================================
set -eu

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TPL="$DEPLOY_DIR/.env.production.example"
OUT="$DEPLOY_DIR/.env"
FORCE=0
ASSUME_YES=0
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    --yes) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$a（可用：--force / --yes）" >&2; exit 1 ;;
  esac
done

cd "$DEPLOY_DIR"
[ -f "$TPL" ] || { echo "找不到模板 $TPL" >&2; exit 1; }

if [ -e "$OUT" ] && [ "$FORCE" -ne 1 ]; then
  echo "✗ $OUT 已存在。"
  echo "  · 想保留原文件：什么都不用做 —— 本脚本只负责「从零生成」。"
  echo "  · 想重新生成：bash scripts/init-env.sh --force（会先备份为 .env.bak-<时间>）"
  exit 1
fi

# ---------------------------------------------------------------- 随机源
RAND_KIND=""
if command -v openssl >/dev/null 2>&1; then
  RAND_KIND="openssl"
elif [ -r /dev/urandom ]; then
  RAND_KIND="urandom"
else
  echo "✗ 既没有 openssl 也读不到 /dev/urandom，无法生成安全随机密钥" >&2
  exit 1
fi

# gen <base64|hex> <字节数>
gen() {
  _kind="$1"; _bytes="$2"
  if [ "$RAND_KIND" = "openssl" ]; then
    if [ "$_kind" = "hex" ]; then
      openssl rand -hex "$_bytes"
    else
      openssl rand -base64 "$_bytes"
    fi
  elif [ "$_kind" = "hex" ]; then
    head -c "$_bytes" /dev/urandom | od -An -tx1 | tr -d ' \n'
  else
    head -c "$_bytes" /dev/urandom | base64 | tr -d '\n'
  fi
}

# ---------------------------------------------------------------- 交互
ask() { # ask <提示> <默认值>
  if [ "$ASSUME_YES" -eq 1 ]; then printf '%s' "$2"; return; fi
  printf '%s' "$1" >&2
  IFS= read -r _ans || _ans=""
  if [ -n "$_ans" ]; then printf '%s' "$_ans"; else printf '%s' "$2"; fi
}

DEFAULT_ROOT="/opt/ticket-system/data"
case "$DEPLOY_DIR" in
  /volume*/docker/*) DEFAULT_ROOT="$(dirname "$DEPLOY_DIR")/data" ;;
esac

echo "=============================================================="
echo " 生成 $OUT"
echo " 随机源：$RAND_KIND"
echo "=============================================================="
echo
echo "下面只问 2 个真正跟你环境相关的问题，其余全部自动生成。"
echo

DATA_ROOT="$(ask "① 数据根目录（MySQL/Redis/日志/附件都放这里）[$DEFAULT_ROOT]: " "$DEFAULT_ROOT")"
case "$DATA_ROOT" in /*) ;; *) echo "✗ 必须是绝对路径（以 / 开头）：$DATA_ROOT" >&2; exit 1 ;; esac

echo >&2
echo "② 你打算怎么访问系统？（决定 Cookie 的 Secure 标记）" >&2
echo "   1) 前面挂了 HTTPS 反向代理（群晖 DSM / 宿主 Nginx / Caddy）—— 推荐" >&2
echo "   2) 直接用 http://<IP>:<端口> 访问，暂时没有 HTTPS" >&2
ACCESS="$(ask "   选 1 或 2 [1]: " "1")"
case "$ACCESS" in
  2|http|HTTP|false) COOKIE_SECURE="false" ;;
  *) COOKIE_SECURE="true" ;;
esac
if [ "$COOKIE_SECURE" = "true" ]; then
  echo "   → COOKIE_SECURE=true（Cookie 只在 HTTPS 下保存）" >&2
else
  echo "   → COOKIE_SECURE=false（允许明文 HTTP，仅供暂时联调；正式上线请改回 true）" >&2
fi

echo >&2
UNATTENDED="$(ask "③ 需要「无人值守自动建超管」吗？不需要则首次访问走初始化向导 [N]: " "N")"
ADMIN_USER=""; ADMIN_PASS=""; ADMIN_NAME="超级管理员"
case "$UNATTENDED" in
  y|Y|yes|YES)
    ADMIN_USER="$(ask "   超管登录名 [itadmin]: " "itadmin")"
    ADMIN_NAME="$(ask "   超管显示名 [超级管理员]: " "超级管理员")"
    ADMIN_PASS="$(gen base64 18)"
    echo "   已生成随机初始口令，生成后可在 .env 里查看（首次登录会强制改密）" >&2
    ;;
esac

# ---------------------------------------------------------------- 生成
MYSQL_PW="$(gen base64 24)"
REDIS_PW="$(gen base64 24)"
JWT_SEC="$(gen base64 48)"
ALERT_TOK="$(gen hex 32)"

# sed 替换值里的特殊字符（& 与 \ 在替换串中有含义；base64/hex 本身不含这两个，
# 但 DATA_ROOT 等用户输入可能含，统一转义更稳）
esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }

TMP="$OUT.tmp.$$"
trap 'rm -f "$TMP"' EXIT INT TERM

sed \
  -e "s|^DATA_ROOT=.*|DATA_ROOT=$(esc "$DATA_ROOT")|" \
  -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=$(esc "$MYSQL_PW")|" \
  -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(esc "$REDIS_PW")|" \
  -e "s|^JWT_SECRET=.*|JWT_SECRET=$(esc "$JWT_SEC")|" \
  -e "s|^INTERNAL_ALERT_TOKEN=.*|INTERNAL_ALERT_TOKEN=$(esc "$ALERT_TOK")|" \
  -e "s|^COOKIE_SECURE=.*|COOKIE_SECURE=$COOKIE_SECURE|" \
  "$TPL" > "$TMP"

# 无人值守模式：把注释掉的 SUPER_ADMIN_* 打开并填值
if [ -n "$ADMIN_USER" ]; then
  sed -i \
    -e "s|^# *SUPER_ADMIN_USERNAME=.*|SUPER_ADMIN_USERNAME=$(esc "$ADMIN_USER")|" \
    -e "s|^# *SUPER_ADMIN_DISPLAY_NAME=.*|SUPER_ADMIN_DISPLAY_NAME=$(esc "$ADMIN_NAME")|" \
    -e "s|^# *SUPER_ADMIN_INIT_PASSWORD=.*|SUPER_ADMIN_INIT_PASSWORD=$(esc "$ADMIN_PASS")|" \
    "$TMP"
fi

# 兜底：确认没有把占位值漏出去。
# 只看真正的赋值行 —— 模板注释里出现「__CHANGE_ME__」是正常的说明文字，不能误判。
LEAK="$(grep -nE '^[A-Z_][A-Z0-9_]*=.*__CHANGE_ME__' "$TMP" || true)"
if [ -n "$LEAK" ]; then
  echo "✗ 生成结果里仍有赋值项保留 __CHANGE_ME__ 占位值：" >&2
  echo "$LEAK" >&2
  exit 1
fi

if [ -e "$OUT" ]; then
  BAK="$OUT.bak-$(date +%Y%m%d-%H%M%S)"
  mv "$OUT" "$BAK"
  echo "已备份原 .env → $(basename "$BAK")"
fi
mv "$TMP" "$OUT"
chmod 600 "$OUT"
trap - EXIT INT TERM

echo
echo "✅ 已生成 $OUT（权限 600，含密钥请勿外传）"
echo "   已自动填好：DATA_ROOT / 4 个随机密钥 / COOKIE_SECURE"
echo
echo "接下来（本目录下执行）："
echo "  bash scripts/preflight.sh          # 体检 .env 与目录权限"
echo "  docker compose pull                # 拉取 GHCR 预构建镜像"
echo "  docker compose up -d               # 启动"
echo
echo "需要独立备份容器时："
echo "  docker compose --profile backup up -d"
echo "并把 .env 的 BACKUP_DIR 指向另一块物理盘（留空则落到 \$DATA_ROOT/backup，同盘不算备份）。"

if [ -x "$DEPLOY_DIR/scripts/preflight.sh" ]; then
  echo
  echo "=============================================================="
  echo " 自动执行体检"
  echo "=============================================================="
  sh "$DEPLOY_DIR/scripts/preflight.sh" || echo "（体检有告警/错误，请按上方提示处理后再启动）"
fi
