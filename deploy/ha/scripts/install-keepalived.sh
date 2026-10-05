#!/usr/bin/env bash
# =====================================================================
# 安装并启动 keepalived（在两台机器上【各执行一次】）
#
# 做的事：
#   1. 读 .env.ha，把 keepalived.conf.tmpl 渲染成本节配置
#   2. 安装健康检查脚本与切换通知脚本（均 root:root 700，满足 enable_script_security 要求）
#   3. 生成 /etc/keepalived/ticket-ha.env（供这两个脚本读 WEB_PORT 与 HA_DIR）
#   4. keepalived 语法自检 → 启动并设为开机自启
#
# 用法：
#   cd deploy/ha
#   sudo bash scripts/install-keepalived.sh
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"
KEEPALIVED_DIR="/etc/keepalived"
TPL="${HA_DIR}/keepalived/keepalived.conf.tmpl"

if [ "$(id -u)" -ne 0 ]; then
  echo "✗ 需要 root 权限（要写 /etc/keepalived 并管理系统服务）" >&2
  exit 1
fi

if [ ! -f "${ENV_FILE}" ]; then
  echo "✗ 找不到 ${ENV_FILE}，请先 cp .env.ha.example .env.ha 并填写" >&2
  exit 1
fi

if ! command -v keepalived >/dev/null 2>&1; then
  echo "✗ 未安装 keepalived。安装方法：" >&2
  echo "    Debian/Ubuntu : apt-get install -y keepalived" >&2
  echo "    RHEL/CentOS   : yum install -y keepalived" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

: "${HA_NODE_NAME:?}"
: "${HA_NODE_IP:?}"
: "${HA_PEER_IP:?}"
: "${HA_NODE_PRIORITY:?}"
: "${HA_VRRP_IFACE:?}"
: "${HA_VRRP_ROUTER_ID_WEB:?}"
: "${HA_VRRP_ROUTER_ID_DB:?}"
: "${HA_VRRP_AUTH_PASS:?}"
: "${VIP_WEB:?}"
: "${VIP_DB:?}"
: "${VIP_MASK:?}"
: "${WEB_PORT:=8080}"

# keepalived 的 PASS 认证上限 8 字符：超长会被【静默截断】，
# 两台若一条被截断、一条没有，就会出现「只有一台认得对方」的脑裂。
if [ "${#HA_VRRP_AUTH_PASS}" -gt 8 ]; then
  echo "✗ HA_VRRP_AUTH_PASS 长度 ${#HA_VRRP_AUTH_PASS} > 8，keepalived PASS 认证会截断，请改短" >&2
  exit 1
fi

echo "== 1/5 渲染 keepalived 配置（节点 ${HA_NODE_NAME}，优先级 ${HA_NODE_PRIORITY}）=="
mkdir -p "${KEEPALIVED_DIR}"

RENDERED="$(mktemp)"
sed \
  -e "s|__ROUTER_ID__|${HA_NODE_NAME}|g" \
  -e "s|__IFACE__|${HA_VRRP_IFACE}|g" \
  -e "s|__VRRP_ID_WEB__|${HA_VRRP_ROUTER_ID_WEB}|g" \
  -e "s|__VRRP_ID_DB__|${HA_VRRP_ROUTER_ID_DB}|g" \
  -e "s|__PRIORITY__|${HA_NODE_PRIORITY}|g" \
  -e "s|__NODE_IP__|${HA_NODE_IP}|g" \
  -e "s|__PEER_IP__|${HA_PEER_IP}|g" \
  -e "s|__VRRP_AUTH_PASS__|${HA_VRRP_AUTH_PASS}|g" \
  -e "s|__VIP_WEB__|${VIP_WEB}|g" \
  -e "s|__VIP_DB__|${VIP_DB}|g" \
  -e "s|__VIP_MASK__|${VIP_MASK}|g" \
  "${TPL}" > "${RENDERED}"

# 占位符残留检查：漏替换会让 keepalived 以字面量 "__VIP_WEB__" 当地址，启动即失败；
# 与其等到启动报错，不如在这里明确指出来。
if grep -n '__[A-Z_]*__' "${RENDERED}" >/dev/null 2>&1; then
  echo "✗ 渲染后仍有未替换的占位符：" >&2
  grep -n '__[A-Z_]*__' "${RENDERED}" >&2
  rm -f "${RENDERED}"
  exit 1
fi

install -m 644 "${RENDERED}" "${KEEPALIVED_DIR}/keepalived.conf"
rm -f "${RENDERED}"
echo "  · 已写入 ${KEEPALIVED_DIR}/keepalived.conf"

echo "== 2/5 安装健康检查脚本与切换通知脚本 =="
install -m 700 -o root -g root "${HA_DIR}/keepalived/check-ticket.sh" "${KEEPALIVED_DIR}/check-ticket.sh"
echo "  · 已写入 ${KEEPALIVED_DIR}/check-ticket.sh（700 root:root）"
# 切换通知脚本同样要 700 root:root：它由 keepalived 以 root 身份执行，
#   若可被非 root 改写，等于给了任何本地用户一个 root 执行入口。
install -m 700 -o root -g root "${HA_DIR}/keepalived/notify-ha.sh" "${KEEPALIVED_DIR}/notify-ha.sh"
echo "  · 已写入 ${KEEPALIVED_DIR}/notify-ha.sh（700 root:root）"

echo "== 3/5 生成脚本用的环境文件 =="
cat > "${KEEPALIVED_DIR}/ticket-ha.env" <<EOF
# 由 install-keepalived.sh 生成，供 check-ticket.sh 与 notify-ha.sh 读取
WEB_PORT=${WEB_PORT}
# 通知脚本要借它定位 scripts/ha-heartbeat.sh —— 因此 deploy/ha 目录
#   安装后【不可随意移动或删除】，否则切换通知会静默失效。
HA_DIR=${HA_DIR}
EOF
chmod 600 "${KEEPALIVED_DIR}/ticket-ha.env"
echo "  · WEB_PORT=${WEB_PORT}  HA_DIR=${HA_DIR}"

echo "== 4/5 keepalived 配置语法自检 =="
if keepalived -t -f "${KEEPALIVED_DIR}/keepalived.conf" >/dev/null 2>&1; then
  echo "  · 语法通过"
else
  echo "  ! 语法自检未通过（部分版本的 -t 需要保持前台运行，属正常）。继续启动观察。" >&2
fi

echo "== 5/5 启动 keepalived 并设置开机自启 =="
systemctl enable keepalived >/dev/null 2>&1 || true
systemctl restart keepalived
sleep 2

if systemctl is-active --quiet keepalived; then
  echo "  · keepalived 运行中"
else
  echo "✗ keepalived 未处于运行状态，请查看：journalctl -u keepalived -n 50" >&2
  exit 1
fi

echo "--------------------------------------------------------------"
echo "  本节点    : ${HA_NODE_NAME} (${HA_NODE_IP})  优先级 ${HA_NODE_PRIORITY}"
echo "  对端      : ${HA_PEER_IP}"
echo "  Web VIP   : ${VIP_WEB}/${VIP_MASK}   DB VIP: ${VIP_DB}/${VIP_MASK}"
echo "  当前持有  : $(ip -4 addr show "${HA_VRRP_IFACE}" 2>/dev/null | grep -o "${VIP_WEB}" | head -1 || true)${VIP_WEB:+}"
echo "--------------------------------------------------------------"
echo "  查看 VIP：ip -4 addr show ${HA_VRRP_IFACE} | grep inet"
echo "  查看日志：journalctl -u keepalived -f"
echo "  注意：两台都安装完后再做切换演练（见 DEPLOY.md ）"
