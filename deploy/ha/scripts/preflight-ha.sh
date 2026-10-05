#!/usr/bin/env bash
# =====================================================================
# HA 部署前体检（两台机器各跑一次，通过后再启动）
#
# 退出码：0 = 通过（允许有 WARN）；1 = 存在必须处理的问题。
#
# 为什么在单机版 preflight 之外还要一份：
#   双机多了三类单机不存在的「静默陷阱」——
#   ① 两台密钥/口令不一致（切换后令牌失效、告警 401）；
#   ② 共享存储没挂或没挂全（切换后附件 404）；
#   ③ 对端端口不通（复制建不起来，但容器看起来都是 healthy）。
#   这三类都不会在本地启动时报错，只会在真正切换的那一刻集中爆发。
#
# 用法：
#   cd deploy/ha
#   bash scripts/preflight-ha.sh
# =====================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"
COMPOSE_FILE="${HA_DIR}/docker-compose.ha.yml"

PASS=0
WARN=0
FAIL=0

ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { WARN=$((WARN+1)); printf '  \033[33m!\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$1"; }

echo "=================================================================="
echo " HA 部署前体检"
echo "=================================================================="

echo
echo "── 一、环境变量 ────────────────────────────────────────────────"
if [ ! -f "${ENV_FILE}" ]; then
  bad "找不到 ${ENV_FILE}（请先 cp .env.ha.example .env.ha）"
  echo; echo "体检无法继续（缺环境文件）"; exit 1
fi
ok "环境文件存在：${ENV_FILE}"

if grep -q '__CHANGE_ME__' "${ENV_FILE}"; then
  bad "仍有 __CHANGE_ME__ 未替换："
  grep -n '__CHANGE_ME__' "${ENV_FILE}" | sed 's/^/      /'
else
  ok "无 __CHANGE_ME__ 残留"
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}" || { bad "无法载入 ${ENV_FILE}（检查是否有语法错误）"; exit 1; }
set +a

for v in HA_NODE_NAME HA_NODE_IP HA_PEER_IP HA_NODE_PRIORITY \
         MYSQL_NODE_CONF VIP_WEB VIP_DB VIP_MASK HA_VRRP_IFACE \
         HA_VRRP_ROUTER_ID_WEB HA_VRRP_ROUTER_ID_DB HA_VRRP_AUTH_PASS \
         REDIS_SENTINEL_NODES REDIS_SENTINEL_MASTER REDIS_INITIAL_MASTER_IP \
         REDIS_ANNOUNCE_IP REDIS_SENTINEL_PASSWORD REDIS_PASSWORD \
         SHARED_ROOT SHARED_TYPE NAS_SERVER NAS_EXPORT \
         DATA_ROOT BACKUP_DIR MYSQL_ROOT_PASSWORD MYSQL_REPL_PASSWORD \
         JWT_SECRET SUPER_ADMIN_INIT_PASSWORD INTERNAL_ALERT_TOKEN; do
  if [ -z "${!v:-}" ]; then bad "变量为空或未设置：${v}"; else ok "${v} 已设置"; fi
done

echo
echo "── 二、跨机一致性（双机部署最容易出事的一类） ──────────────────"

# JWT_SECRET 不一致 → 令牌跨机验签失败，切换后所有用户掉线。
# 打印指纹而非明文，便于两台机器上肉眼比对。
if [ -n "${JWT_SECRET:-}" ]; then
  FP="$(printf '%s' "${JWT_SECRET}" | sha256sum | cut -c1-12)"
  echo "      JWT_SECRET 指纹          : ${FP}"
  echo "      INTERNAL_ALERT_TOKEN 指纹: $(printf '%s' "${INTERNAL_ALERT_TOKEN:-}" | sha256sum | cut -c1-12)"
  echo "      → 请在【另一台】机器上执行本脚本，确认两个指纹完全一致"
fi

if [ -n "${JWT_SECRET:-}" ] && [ "${#JWT_SECRET}" -lt 32 ]; then
  bad "JWT_SECRET 长度 ${#JWT_SECRET} < 32 字节（应用会拒绝启动）"
else
  ok "JWT_SECRET 长度合规（${#JWT_SECRET:-0} 字节）"
fi

if [ -n "${HA_VRRP_AUTH_PASS:-}" ] && [ "${#HA_VRRP_AUTH_PASS}" -gt 8 ]; then
  bad "HA_VRRP_AUTH_PASS 长度 ${#HA_VRRP_AUTH_PASS} > 8（keepalived PASS 认证会静默截断，两台可能截断不一致导致脑裂）"
else
  ok "HA_VRRP_AUTH_PASS 长度合规"
fi

if [ "${HA_NODE_IP:-}" = "${HA_PEER_IP:-}" ]; then
  bad "HA_NODE_IP 与 HA_PEER_IP 相同（应填两台各自的真实 IP）"
else
  ok "两节点 IP 不同"
fi

if [ "${VIP_WEB:-}" = "${VIP_DB:-}" ]; then
  bad "VIP_WEB 与 VIP_DB 相同（两个 vrrp_instance 必须用不同 VIP）"
elif [ "${VIP_WEB:-}" = "${HA_NODE_IP:-}" ] || [ "${VIP_WEB:-}" = "${HA_PEER_IP:-}" ]; then
  bad "VIP_WEB 与某个节点的真实 IP 相同（会与节点地址冲突）"
else
  ok "VIP_WEB 与 VIP_DB 独立且不与节点 IP 冲突"
fi

if [ "${HA_VRRP_ROUTER_ID_WEB:-}" = "${HA_VRRP_ROUTER_ID_DB:-}" ]; then
  bad "HA_VRRP_ROUTER_ID_WEB 与 HA_VRRP_ROUTER_ID_DB 相同（同机两个 instance 必须不同）"
else
  ok "两个 virtual_router_id 不同"
fi

echo
echo "── 三、共享存储 ────────────────────────────────────────────────"
if [ -n "${SHARED_ROOT:-}" ] && mountpoint -q "${SHARED_ROOT}" 2>/dev/null; then
  ok "共享目录已挂载：$(findmnt -n -o SOURCE,FSTYPE "${SHARED_ROOT}")"
  for sub in attachments exports; do
    if [ -d "${SHARED_ROOT}/${sub}" ]; then
      ok "子目录存在：${SHARED_ROOT}/${sub}"
    else
      warn "子目录不存在（启动前用 scripts/mount-nas.sh 创建）：${SHARED_ROOT}/${sub}"
    fi
  done
  # 以容器内运行用户的 uid 试写，直接验证「附件能不能落盘」这件事本身
  if [ -d "${SHARED_ROOT}/attachments" ]; then
    if su -s /bin/sh -c "touch '${SHARED_ROOT}/attachments/.preflight-write-test'" \
         "$(getent passwd 10001 | cut -d: -f1)" 2>/dev/null; then
      rm -f "${SHARED_ROOT}/attachments/.preflight-write-test"
      ok "共享附件目录对 uid 10001 可写"
    else
      warn "无法以 uid 10001 写入共享附件目录（NFS root_squash / CIFS 属主未对齐的常见表现）→ 会导致上传附件失败"
    fi
  fi
else
  bad "共享目录未挂载：${SHARED_ROOT:-<空>}（请先执行 scripts/mount-nas.sh）"
fi

echo
echo "── 四、本机路径与权限 ──────────────────────────────────────────"
for d in "${DATA_ROOT:-}" "${BACKUP_DIR:-}"; do
  [ -z "${d}" ] && continue
  if [ -d "${d}" ]; then
    ok "目录存在：${d}"
  else
    warn "目录不存在（compose 启动时会自动创建，但属主可能是 root）：${d}"
  fi
done

if [ -n "${DATA_ROOT:-}" ] && [ -d "${DATA_ROOT}" ]; then
  OWNER="$(stat -c '%u:%g' "${DATA_ROOT}" 2>/dev/null || echo '?')"
  if [ "${OWNER}" = "10001:10001" ] || [ "${OWNER}" = "0:0" ]; then
    ok "DATA_ROOT 属主 ${OWNER}（后端以 uid 10001 运行，root 属主目录需保证子目录可写）"
  else
    warn "DATA_ROOT 属主为 ${OWNER}，后端以 10001 运行可能无法写日志/附件"
  fi
fi

echo
echo "── 五、容器与工具链 ────────────────────────────────────────────"
command -v docker >/dev/null 2>&1 && ok "docker 已安装：$(docker --version 2>/dev/null)" || bad "未安装 docker"
docker compose version >/dev/null 2>&1 && ok "docker compose 可用：$(docker compose version 2>/dev/null)" || bad "docker compose 插件不可用"
command -v keepalived >/dev/null 2>&1 && ok "keepalived 已安装" || warn "未安装 keepalived（VIP 漂移需要它，见 DEPLOY.md ）"
command -v curl >/dev/null 2>&1 && ok "curl 可用" || warn "缺少 curl（健康检查脚本依赖它）"

if [ -f "${COMPOSE_FILE}" ]; then
  if docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" config >/dev/null 2>&1; then
    ok "compose 文件解析通过"
  else
    bad "compose 文件解析失败，请执行以下命令查看详情："
    echo "      docker compose -f ${COMPOSE_FILE} --env-file ${ENV_FILE} config" >&2
  fi
else
  bad "找不到 ${COMPOSE_FILE}"
fi

echo
echo "── 六、与对端连通性 ────────────────────────────────────────────"
probe() {
  local host="$1" port="$2" name="$3"
  if timeout 3 bash -c "cat < /dev/null > /dev/tcp/${host}/${port}" 2>/dev/null; then
    ok "${name} 可达：${host}:${port}"
  else
    warn "${name} 不可达：${host}:${port}（对端可能尚未启动，或防火墙未放行）"
  fi
}
[ -n "${HA_PEER_IP:-}" ] && probe "${HA_PEER_IP}" 3306 "对端 MySQL"
[ -n "${HA_PEER_IP:-}" ] && probe "${HA_PEER_IP}" 26379 "对端 Redis 哨兵"
[ -n "${HA_PEER_IP:-}" ] && probe "${HA_PEER_IP}" "${WEB_PORT:-8080}" "对端边缘 Nginx"

if [ -n "${HA_PEER_IP:-}" ]; then
  if ping -c 1 -W 2 "${HA_PEER_IP}" >/dev/null 2>&1; then
    ok "对端 ICMP 可达"
  else
    warn "对端 ICMP 无响应（可能被防火墙禁 ping；只要上面端口通就不影响）"
  fi
fi

echo
echo "=================================================================="
printf " 结果：通过 %d / 警告 %d / 失败 %d\n" "${PASS}" "${WARN}" "${FAIL}"
echo "=================================================================="
if [ "${FAIL}" -gt 0 ]; then
  echo " 存在必须处理的问题，请修复后重跑。"
  exit 1
fi
echo " 体检通过（警告项建议逐条确认，尤其「跨机一致性」段的两组指纹）。"
exit 0
