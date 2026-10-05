#!/usr/bin/env bash
# =====================================================================
# keepalived 状态变化 → 上报「切换事件」（）
#
# 由 keepalived.conf 的 notify_master / notify_backup / notify_fault 调用：
#   notify_master "/etc/keepalived/notify-ha.sh MASTER"
#   notify_backup "/etc/keepalived/notify-ha.sh BACKUP"
#   notify_fault  "/etc/keepalived/notify-ha.sh FAULT"
# 安装位置由 scripts/install-keepalived.sh 负责（700 root:root，满足
#   keepalived.conf 里 enable_script_security 的要求）。
#
# 为什么必须有这个脚本：
#   「切换发生时通知管理员」（）这条要求，后端只能被动接收 ——
#   后端看不到 VRRP 状态机，唯一能告知它的就是 keepalived 自己。
#   没有这个钩子，页面上的「最后切换时间」永远是空，
#   而管理员恰恰最需要知道「什么时候漂过、谁接的手」。
#
# ⚠️ 本脚本【任何情况下都以 0 退出】，且不使用 set -e：
#   notify 脚本非零退出或中途异常，keepalived 会在 syslog 里记脚本错误，
#   极端情况下干扰状态机判断。上报只是可观测性，绝不该反向影响 VIP 归属 ——
#   宁可少一条通知，也不能让「通知脚本失败」变成「集群行为异常」。
# =====================================================================
set -uo pipefail

STATE="${1:-UNKNOWN}"
TICKET_ENV="/etc/keepalived/ticket-ha.env"

log() {
  echo "$(date '+%F %T') [notify-ha] $*"
  if command -v logger >/dev/null 2>&1; then
    logger -t ticket-ha "$*" 2>/dev/null || true
  fi
}

case "${STATE}" in
  MASTER)
    ROLE="MASTER"
    ;;
  BACKUP)
    ROLE="STANDBY"
    ;;
  FAULT)
    # FAULT = 本机健康检查连续失败，已放弃 VIP，但【接管方尚未确定】。
    #   此刻若上报「切换发生」，会把「本机不健康」直接说成「对端已接管」——
    #   而对端能否接管取决于它自己的健康与优先级。
    #   真正的切换由【接管方】在它自己的 notify_master 里上报，那条才是事实。
    log "本机进入 FAULT（服务不健康，已放弃 VIP）；等待接管方上报切换事件"
    exit 0
    ;;
  *)
    log "收到未知状态 '${STATE}'，已忽略"
    exit 0
    ;;
esac

# ticket-ha.env 由 install-keepalived.sh 生成（含 HA_DIR 与 WEB_PORT）
HA_DIR=""
if [ -f "${TICKET_ENV}" ]; then
  # shellcheck disable=SC1090
  . "${TICKET_ENV}"
fi

if [ -z "${HA_DIR:-}" ]; then
  log "ticket-ha.env 中缺少 HA_DIR，无法定位心跳脚本；请重新执行 install-keepalived.sh"
  exit 0
fi

HEARTBEAT="${HA_DIR}/scripts/ha-heartbeat.sh"
if [ ! -f "${HEARTBEAT}" ]; then
  log "找不到 ${HEARTBEAT}，无法上报切换事件"
  exit 0
fi

# 触发原因只说【本机确证的事】：keepalived 判定本机进入该状态。
#   不猜「对端宕机」还是「对端计划性让出」—— 本机看不到对端的动作，
#   猜错会把计划内维护报成故障（后端 HaHeartbeatRequest#trigger 的注释同此理由）。
TRIGGER="keepalived 状态机：本机进入 ${STATE}"

# --no-sync：切换瞬间复制状态正在变化，此刻带上一个过时的快照只会误导；
#   同步信息由紧随其后的心跳周期补上。
if bash "${HEARTBEAT}" --once --no-sync --event SWITCHOVER --role "${ROLE}" --trigger "${TRIGGER}"; then
  log "切换事件已上报（本机角色 → ${ROLE}）"
else
  log "切换事件上报失败（不影响 keepalived；节点状态会由心跳周期继续上报）"
fi

exit 0
