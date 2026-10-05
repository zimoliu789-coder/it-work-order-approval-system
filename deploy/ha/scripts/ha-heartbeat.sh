#!/usr/bin/env bash
# =====================================================================
# 主备心跳上报（）
#
# 这是「系统设置 → 主备配置」页面上那两个数字的唯一来源：
#   · 节点列表里的「状态 / 最后心跳」
#   · 数据同步卡片里的「同步状态 / 同步延迟 / 最后同步时间」
# 没有它，页面上的节点会一直停在「未知」，切换也不会触发任何通知。
#
# 上报地址固定为【本机环回地址 http://127.0.0.1:${WEB_PORT}/api/internal/ha/report】，
#   刻意【不走 VIP】。原因：
#     ① 心跳要证明的是「本机后端活着」。若走 VIP，本机后端已经死了而 VIP
#        恰好在对端，请求会由对端后端应答 —— 脚本以为上报成功，
#        而本机实际上已经完全不可用，页面上却一切正常；
#     ② 走 VIP 会让「备节点的心跳」成为跨机流量：两台机互相依赖对方的可用性
#        才能上报自己的状态，故障时两边同时失联。
#   另一半（应用进程自身存活）由 HaHeartbeatMonitorJob 在进程内自行刷新，两条路径互补。
#
# 鉴权：permit-all 白名单 + 请求头 X-Internal-Token（= .env.ha 的 INTERNAL_ALERT_TOKEN），
#   与备份失败上报共用同一个校验实现。两台机器的该值必须【完全一致】。
#   另需 CSRF 第二层防护要求的 X-Requested-With: XMLHttpRequest（见 do_report 内注释）——
#   内部通道也在该过滤器的适用范围里，两个头缺一不可。
#
# 用法：
#   bash scripts/ha-heartbeat.sh --once                 # 上报一次（含同步快照）
#   bash scripts/ha-heartbeat.sh --once --dry-run       # 只打印将要发送的内容，不发请求
#   bash scripts/ha-heartbeat.sh --loop                 # 常驻：心跳 + 按周期附带同步快照
#   bash scripts/ha-heartbeat.sh --event SWITCHOVER --role MASTER --trigger "…"
#
# 常驻形态由 systemd 单元 ticket-ha-heartbeat.service 托管（安装见
#   scripts/install-ha-heartbeat.sh），不要手工 nohup 启动。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"
COMPOSE_FILE="${COMPOSE_FILE:-${HA_DIR}/docker-compose.ha.yml}"

# 心跳节奏。间隔必须明显小于页面上的「心跳超时」阈值（默认 10 秒），
#   否则「上报一次 → 阈值到期 → 判超时 → 再上报」会让节点在页面上反复闪红。
#   5 秒是「阈值 10 秒」下的安全值（一个超时窗口内至少两次心跳）。
HEARTBEAT_INTERVAL="${HA_HEARTBEAT_INTERVAL:-5}"
# 同步快照要连 MySQL 执行 SHOW REPLICA STATUS，比心跳重得多，单独走一个更慢的周期。
SYNC_INTERVAL="${HA_SYNC_INTERVAL:-60}"
# 复制延迟超过这个秒数即视为「滞后」（只在复制线程正常的前提下参与判定）
LAG_THRESHOLD="${HA_SYNC_LAG_THRESHOLD:-30}"
REPORT_TIMEOUT="${HA_REPORT_TIMEOUT:-5}"

MODE="once"
DRY_RUN=0
WITH_SYNC=1
EVENT="HEARTBEAT"
ROLE=""
TRIGGER=""

usage() {
  cat <<'EOF'
用法：bash scripts/ha-heartbeat.sh [选项]

  --once            上报一次后退出（默认）
  --loop            常驻：每 HA_HEARTBEAT_INTERVAL 秒一次心跳，
                    每 HA_SYNC_INTERVAL 秒附带一次数据同步快照
  --dry-run         只打印将要发送的内容，不发请求（用于排查）
  --no-sync         本次不查询 / 不上报数据同步信息（只报心跳）
  --event <名称>    事件类型：HEARTBEAT（默认）/ SWITCHOVER
  --role <角色>     本机角色：MASTER / STANDBY。缺省时按「本机是否持有 Web VIP」推断；
                    keepalived 钩子必须显式传入（见 keepalived/notify-ha.sh）
  --trigger <说明>  切换触发原因（仅 --event SWITCHOVER 有意义）
  -h, --help        显示本帮助

要求 .env.ha 中已配置：INTERNAL_ALERT_TOKEN、HA_NODE_IP。
同步快照还需要 MYSQL_ROOT_PASSWORD 与可用 docker —— 缺少时自动跳过同步部分，
心跳照常上报（把「查不到」当成「复制中断」会推出假告警）。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --once) MODE="once" ;;
    --loop) MODE="loop" ;;
    --dry-run) DRY_RUN=1 ;;
    --no-sync) WITH_SYNC=0 ;;
    --event) EVENT="${2:-}"; shift ;;
    --role) ROLE="${2:-}"; shift ;;
    --trigger) TRIGGER="${2:-}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "✗ 未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
  shift
done

if [ ! -f "${ENV_FILE}" ]; then
  echo "✗ 找不到 ${ENV_FILE}，请先 cp .env.ha.example .env.ha 并填写" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

: "${INTERNAL_ALERT_TOKEN:?请在 .env.ha 中设置 INTERNAL_ALERT_TOKEN（两台机器必须完全一致）}"
: "${HA_NODE_IP:?请在 .env.ha 中设置 HA_NODE_IP}"
: "${WEB_PORT:=8080}"

REPORT_URL="${HA_REPORT_URL:-http://127.0.0.1:${WEB_PORT}/api/internal/ha/report}"

# ---------------------------------------------------------------------
# 本机角色
# ---------------------------------------------------------------------

# 判断某个地址是否落在本机网卡上（精确匹配，避免 100 误匹配 1001）
hold_vip() {
  local vip="$1"
  if [ -z "${vip}" ] || [ -z "${HA_VRRP_IFACE:-}" ]; then
    return 1
  fi
  ip -4 addr show "${HA_VRRP_IFACE}" 2>/dev/null | grep -qw "${vip}"
}

# 角色口径：持有 Web VIP 的那台就是主节点。这与页面上的「运行期角色」一致 ——
#   由 keepalived 的 priority + 漂移决定，而不是 .env.ha 里写死的 HA_NODE_ROLE
#   （那是部署时写死的节点标识，永不变化）。
detect_role() {
  if [ -n "${ROLE}" ]; then
    printf '%s' "${ROLE}"
    return 0
  fi
  if hold_vip "${VIP_WEB:-}"; then
    printf 'MASTER'
  else
    printf 'STANDBY'
  fi
}

# ---------------------------------------------------------------------
# 数据同步快照
#
# 只在【确实查得到】时才输出；查不到（容器没起 / docker 不在 / 未配口令）
#   就什么都不输出，本次上报不带同步字段，后端保留上一次的快照。
#   若这里回写一个 FAILED，会把「本机根本没启动 MySQL」误报成「复制中断」——
#   而复制失败是要推送给全部超管的告警，一次假告警就会让人不再信任这个通道。
#
# 输出三行：state / delaySeconds / lastSyncAt（后两者可能为空行）
# ---------------------------------------------------------------------
collect_sync_snapshot() {
  local status io sql behind state delay last_sync

  if [ "${WITH_SYNC}" -ne 1 ]; then return 0; fi
  if [ ! -f "${COMPOSE_FILE}" ]; then return 0; fi
  if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then return 0; fi
  if ! command -v docker >/dev/null 2>&1; then return 0; fi

  status="$(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T mysql \
      mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e 'SHOW REPLICA STATUS\G' 2>/dev/null || true)"
  if [ -z "${status}" ]; then return 0; fi

  io="$(printf '%s' "${status}" | grep -E '^[[:space:]]*Replica_IO_Running:' | head -1 | awk '{print $2}' | tr -d '\r')"
  sql="$(printf '%s' "${status}" | grep -E '^[[:space:]]*Replica_SQL_Running:' | head -1 | awk '{print $2}' | tr -d '\r')"
  # MySQL 8.0.26+ 是 Seconds_Behind_Source，更早的版本是 Seconds_Behind_Master
  behind="$(printf '%s' "${status}" | grep -E '^[[:space:]]*Seconds_Behind_(Source|Master):' | head -1 | awk '{print $2}' | tr -d '\r')"

  state=""
  delay=""
  last_sync=""
  if [ "${io}" = "Yes" ] && [ "${sql}" = "Yes" ]; then
    if [ -n "${behind}" ] && [ "${behind}" != "NULL" ] && [ "${behind}" -gt "${LAG_THRESHOLD}" ] 2>/dev/null; then
      state="LAGGING"
    else
      state="IN_SYNC"
      # 只有「确实无延迟」时才把 lastSyncAt 推进到当前时刻 ——
      #   该字段的含义是「最近一次成功同步的时间」，拿它当「刚才查过库的时间」用会骗人。
      last_sync="$(date '+%Y-%m-%dT%H:%M:%S')"
    fi
  else
    state="FAILED"
  fi

  printf '%s\n%s\n%s\n' "${state}" "${behind}" "${last_sync}"
}

# ---------------------------------------------------------------------
# 上报
# ---------------------------------------------------------------------

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r\n'
}

# 累积 JSON 字段（刻意不用 `[ … ] && add`：在 set -e 下条件为假会让整条语句
#   返回非零，脚本静默退出 —— 那种 bug 只在「字段恰好为空」时出现，最难查）
HA_PAYLOAD=""
add_field() { # add_field <json 名> <已经是合法 JSON 的值>
  local one="\"$1\":$2"
  if [ -n "${HA_PAYLOAD}" ]; then
    HA_PAYLOAD="${HA_PAYLOAD},${one}"
  else
    HA_PAYLOAD="${one}"
  fi
}

do_report() {
  local role sync_output state delay last_sync payload body code

  role="$(detect_role)"
  HA_PAYLOAD=""

  add_field "event" "\"$(json_escape "${EVENT}")\""
  add_field "nodeIp" "\"$(json_escape "${HA_NODE_IP}")\""
  if [ -n "${HA_NODE_NAME:-}" ]; then
    add_field "nodeName" "\"$(json_escape "${HA_NODE_NAME}")\""
  fi
  if [ -n "${role}" ]; then
    add_field "role" "\"$(json_escape "${role}")\""
  fi
  if [ -n "${TRIGGER}" ]; then
    add_field "trigger" "\"$(json_escape "${TRIGGER}")\""
  fi

  state=""
  delay=""
  if [ "${WITH_SYNC}" -eq 1 ]; then
    sync_output="$(collect_sync_snapshot)"
    if [ -n "${sync_output}" ]; then
      state="$(printf '%s' "${sync_output}" | sed -n '1p')"
      delay="$(printf '%s' "${sync_output}" | sed -n '2p')"
      last_sync="$(printf '%s' "${sync_output}" | sed -n '3p')"
      if [ -n "${state}" ]; then
        add_field "syncState" "\"$(json_escape "${state}")\""
      fi
      if [ -n "${delay}" ] && [ "${delay}" != "NULL" ]; then
        add_field "delaySeconds" "${delay}"
      fi
      if [ -n "${last_sync}" ]; then
        add_field "lastSyncAt" "\"$(json_escape "${last_sync}")\""
      fi
    fi
  fi

  payload="{${HA_PAYLOAD}}"

  if [ "${DRY_RUN}" -eq 1 ]; then
    echo "[dry-run] POST ${REPORT_URL}"
    echo "[dry-run] X-Internal-Token: ${INTERNAL_ALERT_TOKEN:0:4}****（后略）"
    echo "[dry-run] ${payload}"
    return 0
  fi

  body="$(mktemp)"
  # --noproxy '*'：心跳打的是环回地址，绝不能被宿主上的 http_proxy 转发到代理上
  #   （那种失败看起来像「后端 404」，实际是代理在应答）。
  #
  # X-Requested-With：这个头【必须带】。服务端的 CSRF 第二层防护
  #   （security/CsrfHeaderFilter）对所有非 GET 请求一视同仁地要求
  #   `X-Requested-With: XMLHttpRequest`，内部通道也不例外 —— 它不在豁免名单里。
  #   漏掉它的现象极具迷惑性：HTTP 403 + code=CSRF_HEADER_MISSING，
  #   请求【根本走不到令牌校验】，而人看到 403 会先去怀疑令牌不一致，
  #   于是往错的方向查。既有的备份告警通道 deploy/backup/backup.sh 一直带着这个头，
  #   两处保持一致。
  if ! code="$(curl -sS --noproxy '*' --max-time "${REPORT_TIMEOUT}" \
        -o "${body}" -w '%{http_code}' \
        -H 'Content-Type: application/json' \
        -H 'X-Requested-With: XMLHttpRequest' \
        -H "X-Internal-Token: ${INTERNAL_ALERT_TOKEN}" \
        -X POST --data "${payload}" "${REPORT_URL}" 2>/dev/null)"; then
    echo "✗ 无法连接 ${REPORT_URL}（本机后端未启动？）" >&2
    rm -f "${body}"
    return 1
  fi

  case "${code}" in
    2*)
      echo "$(date '+%F %T') 心跳已上报：role=${role:-未识别} event=${EVENT}${state:+ sync=${state}}"
      rm -f "${body}"
      return 0
      ;;
    401|403)
      # 403 有两种成因，且【排查方向完全不同】，必须读响应体区分 ——
      #   否则会把人送去核对令牌，而真实原因是请求头少了一个。
      if grep -q 'CSRF_HEADER_MISSING' "${body}" 2>/dev/null; then
        echo "✗ 上报被 CSRF 防护拦下（HTTP ${code}）：请求缺少 X-Requested-With: XMLHttpRequest。" >&2
        echo "  这是脚本自身的 bug（不是令牌问题）：服务端 CsrfHeaderFilter 对所有非 GET 请求" >&2
        echo "  一视同仁地要求该请求头，内部通道没有豁免。请核对本脚本 do_report 里的两个 -H 参数。" >&2
      else
        echo "✗ 上报被拒绝（HTTP ${code}）：X-Internal-Token 与本机后端的 INTERNAL_ALERT_TOKEN 不一致。" >&2
        echo "  页面上节点状态永远不更新，最常见的原因就是这一条 —— 请核对两台机器的 .env.ha。" >&2
      fi
      ;;
    404)
      echo "✗ 上报地址不存在（HTTP 404）：${REPORT_URL}" >&2
      echo "  请确认本机后端版本包含 /api/internal/ha/report（ 起提供）。" >&2
      ;;
    429)
      echo "✗ 上报被限流（HTTP 429）：请确认 /api/internal/ha/report 在限流白名单内。" >&2
      ;;
    *)
      echo "✗ 上报失败（HTTP ${code}）：$(head -c 300 "${body}" 2>/dev/null || true)" >&2
      ;;
  esac
  rm -f "${body}"
  return 1
}

# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------

if [ "${MODE}" = "once" ]; then
  do_report
  exit $?
fi

SYNC_EVERY=$((SYNC_INTERVAL / HEARTBEAT_INTERVAL))
if [ "${SYNC_EVERY}" -lt 1 ]; then SYNC_EVERY=1; fi

echo "== 主备心跳已启动（心跳 ${HEARTBEAT_INTERVAL}s / 同步快照每 ${SYNC_EVERY} 次心跳一次）=="
echo "   节点：${HA_NODE_NAME:-?} (${HA_NODE_IP})"
echo "   上报：${REPORT_URL}   日志：journalctl -u ticket-ha-heartbeat -f"
trap 'echo "== 主备心跳已停止 =="; exit 0' TERM INT

tick=0
while :; do
  tick=$((tick + 1))
  # 首次立刻带一次完整的同步快照，之后每 SYNC_EVERY 次心跳带一次
  if [ $(( (tick - 1) % SYNC_EVERY )) -eq 0 ]; then
    WITH_SYNC=1
  else
    WITH_SYNC=0
  fi
  # 单次失败不能让常驻循环退出：下一个周期自动重试，错误已由 do_report 打进日志
  do_report || true
  sleep "${HEARTBEAT_INTERVAL}"
done
