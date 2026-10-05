#!/usr/bin/env bash
# =====================================================================
# VIP 切换演练 / 手动切换
#
# 原理：不直接停 keepalived，而是放一个「维护标记」文件，
#   让 check-ticket.sh 主动判本节点不健康 → keepalived 降低优先级 →
#   对端在 fall(2)×interval(5s) ≈ 10~15 秒内接管 VIP。
#   这比「停掉 keepalived」更接近真实故障：演练的是完整判定链路，
#   而不是一个「进程没了」的特例。
#
# 用法（在【当前持有 VIP 的那台】执行）：
#   sudo bash scripts/switchover.sh to-peer   # 交出 VIP，交给对端
#   sudo bash scripts/switchover.sh back      # 取消维护标记（本机恢复可竞选）
#   sudo bash scripts/switchover.sh status    # 查看本机是否持有 VIP
#
# 演练建议（见 DEPLOY.md ）：to-peer → 用浏览器验证服务可用 → back，
#   全程观察会话是否保持（Redis 哨兵切换会让部分临时锁失效，属预期内）。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"

MAINT_FLAG="/etc/keepalived/MAINT"

if [ "$(id -u)" -ne 0 ]; then
  echo "✗ 需要 root 权限" >&2
  exit 1
fi

if [ ! -f "${ENV_FILE}" ]; then
  echo "✗ 找不到 ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

: "${HA_VRRP_IFACE:?}"
: "${VIP_WEB:?}"
: "${VIP_DB:?}"

hold_vip() {
  # 判断某个 VIP 是否落在本机网卡上（精确匹配，避免 100 误匹配 1001）
  ip -4 addr show "${HA_VRRP_IFACE}" 2>/dev/null | grep -qw "${1}"
}

show_status() {
  echo "  节点        : ${HA_NODE_NAME:-?} (${HA_NODE_IP:-?})"
  echo "  维护标记    : $([ -f "${MAINT_FLAG}" ] && echo '存在（本机主动让位）' || echo '无')"
  echo "  Web VIP     : ${VIP_WEB}  →  $(hold_vip "${VIP_WEB}" && echo '本机持有' || echo '不在本机')"
  echo "  DB  VIP     : ${VIP_DB}  →  $(hold_vip "${VIP_DB}" && echo '本机持有' || echo '不在本机')"
}

case "${1:-}" in
  to-peer)
    echo "== 交出 VIP（置维护标记）=="
    mkdir -p /etc/keepalived
    : > "${MAINT_FLAG}"
    echo "  · 已创建 ${MAINT_FLAG}"
    echo "  · 等待健康检查判失败并降优先级（最长约 15 秒）…"
    sleep 16
    show_status
    if hold_vip "${VIP_WEB}" || hold_vip "${VIP_DB}"; then
      echo
      echo "  ! 本机仍持有 VIP。可能原因：" >&2
      echo "    - 对端 keepalived 未运行或优先级配置有误" >&2
      echo "    - check-ticket.sh 未生效（确认 /etc/keepalived/check-ticket.sh 为 root:root 700）" >&2
      echo "    - 对端 check-ticket.sh 也判自身不健康（对端服务不通）" >&2
      exit 1
    fi
    echo "  ✓ VIP 已交出。此时请用浏览器访问业务地址，确认服务由对端正常提供。"
    ;;

  back)
    echo "== 取消维护标记 =="
    rm -f "${MAINT_FLAG}"
    echo "  · 已删除 ${MAINT_FLAG}"
    echo "  · 注意：keepalived 配置为 nopreempt（不抢占），"
    echo "    因此本机【不会】自动抢回 VIP。这是刻意的设计 —— 避免故障恢复造成第二次中断。"
    echo "    如需把 VIP 挪回本机，请在【对端】执行 to-peer。"
    show_status
    ;;

  status)
    echo "== VIP 归属 =="
    show_status
    echo
    echo "  提示：VIP 归属由优先级决定（${HA_NODE_PRIORITY:-?} vs 对端）。"
    echo "        nopreempt 下不会自动回切，这是为了避免「修复即再中断一次」。"
    ;;

  *)
    echo "用法：sudo bash scripts/switchover.sh {to-peer|back|status}"
    exit 1
    ;;
esac
