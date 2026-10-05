#!/usr/bin/env bash
# =====================================================================
# 安装主备心跳上报（在两台机器上【各执行一次】）
#
# 做什么：
#   1. 渲染 systemd 单元 ticket-ha-heartbeat.service（把部署目录写死进去）
#   2. 安装到 /etc/systemd/system 并 enable --now
#   3. 立即跑一次上报，把结果打出来 —— 「装完到底通没通」当场就能看到
#
# 为什么必须装它：
#   不装心跳，页面上的节点会一直停在「未知」、「最后同步时间」永远为空，
#   切换也不会产生任何通知。它不改变任何业务数据，只在「页面上能不能
#   看到集群现在什么状态」这件事上起作用 —— 而这正是 要求的东西。
#
# 用法：
#   cd deploy/ha
#   sudo bash scripts/install-ha-heartbeat.sh
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"
UNIT_SRC="${HA_DIR}/systemd/ticket-ha-heartbeat.service"
UNIT_DST="/etc/systemd/system/ticket-ha-heartbeat.service"
UNIT_NAME="ticket-ha-heartbeat"

if [ "$(id -u)" -ne 0 ]; then
  echo "✗ 需要 root 权限（要写 /etc/systemd/system 并管理系统服务）" >&2
  exit 1
fi

if [ ! -f "${ENV_FILE}" ]; then
  echo "✗ 找不到 ${ENV_FILE}，请先 cp .env.ha.example .env.ha 并填写" >&2
  exit 1
fi

if [ ! -f "${HA_DIR}/scripts/ha-heartbeat.sh" ]; then
  echo "✗ 找不到 ${HA_DIR}/scripts/ha-heartbeat.sh，deploy/ha 资产不完整" >&2
  exit 1
fi

if [ ! -f "${UNIT_SRC}" ]; then
  echo "✗ 找不到 ${UNIT_SRC}，deploy/ha 资产不完整" >&2
  exit 1
fi

echo "== 1/3 渲染 systemd 单元（部署目录 ${HA_DIR}）=="
TMP="$(mktemp)"
sed -e "s|__HA_DIR__|${HA_DIR}|g" "${UNIT_SRC}" > "${TMP}"

# 占位符残留检查：漏替换会让 systemd 去执行一个字面量 __HA_DIR__/... 的路径，
#   报错信息是「No such file or directory」，看不出根因 —— 在这里直接拦下。
if grep -n '__[A-Z_]*__' "${TMP}" >/dev/null 2>&1; then
  echo "✗ 渲染后仍有未替换的占位符：" >&2
  grep -n '__[A-Z_]*__' "${TMP}" >&2
  rm -f "${TMP}"
  exit 1
fi

install -m 644 "${TMP}" "${UNIT_DST}"
rm -f "${TMP}"
chmod +x "${HA_DIR}/scripts/ha-heartbeat.sh" 2>/dev/null || true
echo "  · 已写入 ${UNIT_DST}"

echo "== 2/3 启用并启动 =="
systemctl daemon-reload
systemctl enable "${UNIT_NAME}" >/dev/null 2>&1 || true
systemctl restart "${UNIT_NAME}"
sleep 3

if systemctl is-active --quiet "${UNIT_NAME}"; then
  echo "  · ${UNIT_NAME} 运行中"
else
  echo "✗ ${UNIT_NAME} 未处于运行状态，请查看：journalctl -u ${UNIT_NAME} -n 50 --no-pager" >&2
  exit 1
fi

echo "== 3/3 立刻验证一次上报 =="
if bash "${HA_DIR}/scripts/ha-heartbeat.sh" --once; then
  echo "  · 上报成功 —— 稍后回到「系统设置 → 主备配置」页刷新，节点状态应变为「运行中 / 待命」"
else
  echo "  ! 上报失败，按上面的提示处理。" >&2
  echo "    最常见的两种：" >&2
  echo "      HTTP 401/403 → 本机 .env.ha 的 INTERNAL_ALERT_TOKEN 与后端不一致（两台必须完全相同）" >&2
  echo "      连接失败     → 本机后端未启动。请先确认 http://127.0.0.1:8080/api/health 可访问" >&2
  exit 1
fi

echo "--------------------------------------------------------------"
echo "  单元     : ${UNIT_NAME}.service（开机自启）"
echo "  日志     : journalctl -u ${UNIT_NAME} -f"
echo "  手动一次 : bash ${HA_DIR}/scripts/ha-heartbeat.sh --once"
echo "  只看不发 : bash ${HA_DIR}/scripts/ha-heartbeat.sh --once --dry-run"
echo "  改节奏   : 在 /etc/ticket-ha/heartbeat.env 里设 HA_HEARTBEAT_INTERVAL / HA_SYNC_INTERVAL"
echo "--------------------------------------------------------------"
echo "  注意：心跳间隔须明显小于页面上的「心跳超时」阈值（默认 10 秒），"
echo "        否则节点会在页面上反复闪红。网络较差时可把页面阈值调到 30 秒。"
