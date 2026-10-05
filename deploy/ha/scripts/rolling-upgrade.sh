#!/usr/bin/env bash
# =====================================================================
# 主主（双活）滚动升级编排（）
#
# 与 DEPLOY.md  是同一套顺序规则，只是把「人工敲的命令」变成一次编排：
#
#     ① 分发升级包到对端
#     ② 升级【备机】（此刻不承载流量，零风险）
#     ③ 把 VIP 从主机交给刚升级好的备机
#     ④ 升级【（原）主机】（此刻它已不承载流量）
#     ⑤ 验收
#
# 【三条不变量】—— 抄自 DEPLOY.md ，改写脚本时不能破坏：
#   1. 先备机、后主机，绝不并发升级两端；
#   2. 动谁之前，先把 VIP 从它身上挪走（否则升级过程中它既在承载流量、
#      又在被替换产物 → 用户可见的失败窗口）；
#   3. 一端升级完并验证健康，才动另一端。
#
# 【与  的差异（刻意的）】
#    的示例是「先把 VIP 挪到 B，再升级 A」。本脚本改为「先升级 B，再把 VIP 挪到 B」。
#   区别在于：包如果是坏的，本脚本在第 ② 步就失败退出了 —— 此时【一次 VIP 切换都没发生】，
#   用户完全无感；而先切 VIP 的写法会让「包是坏的」这件事先付一次切换的代价。
#   备机升级本身不承载流量，所以「先升备机」不存在额外的风险敞口。
#   第 3 条不变量（动之前先挪走流量）在两条路径里都成立。
#
# 用法（在【任意一台】执行，脚本自己判断谁持有 VIP）：
#   sudo bash scripts/rolling-upgrade.sh <taskNo> <stagingDir> [<backupDir>]
#
#   taskNo      后端升级任务号（页面上传后记录里能看到，形如 20261001103000-a1b2c3d4）
#   stagingDir  本机上该任务的 staging 目录（后端 state 树里，见下）
#   backupDir   本机上的备份目录（可选；不传时 upgrade-apply.sh 会自行留存现场）
#
#   例：
#   sudo bash scripts/rolling-upgrade.sh 20261001103000-a1b2c3d4 \
#       /mnt/ticket-shared/upgrade/staging/20261001103000-a1b2c3d4 \
#       /mnt/ticket-shared/upgrade/backup/20261001103000-a1b2c3d4
#
# 环境变量（可写进 .env.ha，或临时注入）：
#   UPGRADE_SYNC_MODE          shared | ssh | none（默认 shared）
#                              shared：staging 在两台共享（NAS，推荐）→ 不复制，只校验路径一致
#                              ssh   ：两台各自本地盘 → 用 tar-over-ssh 推到对端
#                              none  ：确认两端都已就绪，跳过任何分发
#   UPGRADE_PEER_STAGING_DIR   对端 staging 路径（默认与本机相同）
#   UPGRADE_PEER_BACKUP_DIR    对端 backup 路径（可选）
#   UPGRADE_PEER_SSH           ssh 目标（默认 root@${HA_PEER_IP}）
#   UPGRADE_SSH_OPTS           ssh 选项（默认带 BatchMode=yes，绝不在脚本里等口令输入）
#   UPGRADE_APPLY_SCRIPT       upgrade-apply.sh 的路径（默认与本脚本同目录）
#   UPGRADE_SWITCHOVER_SCRIPT  switchover.sh 的路径（默认与本脚本同目录）
#   UPGRADE_ARTIFACT_DIR       产物目录，仅用于透传给对端（本机侧由 .env.ha 决定）
#   UPGRADE_STATE_DIR          state 目录，同上
#   UPGRADE_HEALTH_PORT        健康检查端口（默认 8080）
#   VIP_SETTLE_SECONDS         等待 VIP 漂移的秒数上限（默认 25；VRRP fall×interval ≈ 10~15s）
#   UPGRADE_RESTORE_VIP        1 = 全部升完后把 VIP 挪回原来那台（默认 0，理由见文末）
#   DRY_RUN                    1 = 只打印计划，不做任何写操作（编排演练用）
#   UPGRADE_FORCE_ROLE         holder | standby。强制本机角色并跳过 VIP 探测。
#                              【只能与 DRY_RUN=1 同用】用途是让「先备机后主机」的编排顺序
#                              能在一台没有 keepalived 的机器上先跑一遍，看决策对不对；
#                              真实升级中若允许它，就等于绕过了「VIP 真的挪走了」这道校验。
#
# 退出码：
#   0  两端均升级成功、健康检查通过
#   1  升级失败但已回滚，系统仍是升级前版本（两端中至少一端）
#   2  失败且未能回滚 / 状态不明，需人工介入
#   3  前置检查失败（未做任何改动）
#   4  备机升级失败 → 已中止，主机未被动过、服务不受影响
#
# ⚠️ 关于 nopreempt：keepalived 配置为不抢占，VIP 【不会】自己漂回来。
#    所以「每一步之后 VIP 在哪台」必须由本脚本显式控制（switchover.sh），
#    不能指望它自愈。这也是本脚本要在关键步骤后 sleep/轮询确认的原因。
# =====================================================================
set -euo pipefail

TASK_NO="${1:-}"
STAGING_DIR="${2:-}"
BACKUP_DIR="${3:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi

UPGRADE_APPLY_SCRIPT="${UPGRADE_APPLY_SCRIPT:-${SCRIPT_DIR}/upgrade-apply.sh}"
UPGRADE_SWITCHOVER_SCRIPT="${UPGRADE_SWITCHOVER_SCRIPT:-${SCRIPT_DIR}/switchover.sh}"
UPGRADE_PEER_SSH="${UPGRADE_PEER_SSH:-root@${HA_PEER_IP:-}}"
UPGRADE_PEER_STAGING_DIR="${UPGRADE_PEER_STAGING_DIR:-${STAGING_DIR}}"
UPGRADE_PEER_BACKUP_DIR="${UPGRADE_PEER_BACKUP_DIR:-}"
UPGRADE_SYNC_MODE="${UPGRADE_SYNC_MODE:-shared}"
UPGRADE_ARTIFACT_DIR="${UPGRADE_ARTIFACT_DIR:-}"
UPGRADE_STATE_DIR="${UPGRADE_STATE_DIR:-}"
UPGRADE_HEALTH_PORT="${UPGRADE_HEALTH_PORT:-8080}"
UPGRADE_SSH_OPTS="${UPGRADE_SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new}"
UPGRADE_RESTORE_VIP="${UPGRADE_RESTORE_VIP:-0}"
VIP_SETTLE_SECONDS="${VIP_SETTLE_SECONDS:-25}"
DRY_RUN="${DRY_RUN:-0}"
UPGRADE_FORCE_ROLE="${UPGRADE_FORCE_ROLE:-}"

# 角色：哪台持有 Web VIP 就是「主机」，另一台是「备机」
HOLDER=""
STANDBY=""

# ---------------------------------------------------------------------
# 输出与工具
# ---------------------------------------------------------------------
log() { echo "[rolling-upgrade] $*"; }

die() {
  local code="$1"
  shift
  log "✗ $*"
  exit "${code}"
}

# 人类可读的节点标签，用于日志（local / peer 这两个内部代号对运维没有意义）
label_of() {
  if [ "$1" = "local" ]; then
    printf '%s(%s)' "${HA_NODE_NAME:-本机}" "${HA_NODE_IP:-?}"
  else
    printf '%s(%s)' "${HA_PEER_NAME:-对端}" "${HA_PEER_IP:-?}"
  fi
}

# 某台机器上「本机」的健康检查地址：
#   local → 127.0.0.1，peer → 对端 IP。
# 刻意【不用】 VIP：VIP 此刻可能刚切走，用它检查会得到一个与被测节点无关的结果，
# 看起来像「升级失败」，实际只是探错了对象。
health_url_of() {
  if [ "$1" = "local" ]; then
    printf 'http://127.0.0.1:%s/api/health' "${UPGRADE_HEALTH_PORT}"
  else
    printf 'http://%s:%s/api/health' "${HA_PEER_IP}" "${UPGRADE_HEALTH_PORT}"
  fi
}

# 本机是否持有 Web VIP（精确匹配，避免 100 命中 1001）
vip_is_held_locally() {
  # 演练模式不探测真实网卡：让整条编排流程能在没有 keepalived 的机器上跑起来
  if [ -n "${UPGRADE_FORCE_ROLE}" ]; then
    if [ "${UPGRADE_FORCE_ROLE}" = "holder" ]; then return 0; fi
    return 1
  fi
  local addrs
  addrs="$(ip -4 addr show "${HA_VRRP_IFACE}" 2>/dev/null || true)"
  printf '%s\n' "${addrs}" | grep -qw -- "${VIP_WEB}"
}

# 透传给对端的 UPGRADE_* 覆盖值（本机没设就不传，让对端的 .env.ha 生效）。
# 用 %q 转义：产物目录里带空格时，不加引号会被 shell 拆成两个参数。
remote_env_prefix() {
  local out=""
  if [ -n "${UPGRADE_ARTIFACT_DIR}" ]; then
    out="${out} UPGRADE_ARTIFACT_DIR=$(printf '%q' "${UPGRADE_ARTIFACT_DIR}")"
  fi
  if [ -n "${UPGRADE_STATE_DIR}" ]; then
    out="${out} UPGRADE_STATE_DIR=$(printf '%q' "${UPGRADE_STATE_DIR}")"
  fi
  printf '%s' "${out}"
}

peer_exec() {
  # shellcheck disable=SC2029
  ssh ${UPGRADE_SSH_OPTS} "${UPGRADE_PEER_SSH}" "$1"
}

# ---------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------
preflight() {
  log "== 前置检查 =="

  [ -n "${TASK_NO}" ] || die 3 "缺少参数 taskNo。用法：bash rolling-upgrade.sh <taskNo> <stagingDir> [<backupDir>]"
  [ -n "${STAGING_DIR}" ] || die 3 "缺少参数 stagingDir"
  [ -d "${STAGING_DIR}" ] || die 3 "staging 目录不存在：${STAGING_DIR}"
  [ -f "${STAGING_DIR}/backend.jar" ] || die 3 "staging 目录内缺少 backend.jar：${STAGING_DIR}"

  [ -f "${UPGRADE_APPLY_SCRIPT}" ] || die 3 "找不到升级应用脚本：${UPGRADE_APPLY_SCRIPT}"
  [ -f "${UPGRADE_SWITCHOVER_SCRIPT}" ] || die 3 "找不到 VIP 切换脚本：${UPGRADE_SWITCHOVER_SCRIPT}"

  [ -n "${HA_PEER_IP:-}" ] || die 3 "前置检查失败：.env.ha 缺少 HA_PEER_IP（无法访问对端）"

  # VIP 判定能力：正常执行必须能探测 VIP；演练模式可跳过（见 UPGRADE_FORCE_ROLE）
  if [ -n "${UPGRADE_FORCE_ROLE}" ]; then
    case "${UPGRADE_FORCE_ROLE}" in
      holder|standby) ;;
      *) die 3 "UPGRADE_FORCE_ROLE 取值不合法：${UPGRADE_FORCE_ROLE}（应为 holder / standby）" ;;
    esac
    # 这道限制不能省：真实升级里允许指定角色，等于绕过「VIP 确实挪走了」的校验，
    # 而那正是本脚本存在的意义
    if [ "${DRY_RUN}" != "1" ]; then
      die 3 "UPGRADE_FORCE_ROLE 只能与 DRY_RUN=1 同用（演练专用）"
    fi
    log "  ⚠ 演练模式：UPGRADE_FORCE_ROLE=${UPGRADE_FORCE_ROLE}，跳过 VIP 探测"
  else
    # 这两个变量缺任何一个都无法决定「先升谁」。
    # 用显式检查而非 ${VAR:?}：bash 的 ${VAR:?} 会以退出码 1 终止，
    # 而本脚本的 1 号退出码含义是「已回滚」—— 监控/自动化会把「参数没配」
    # 误读成「升级失败已回滚」，排查方向直接跑偏。
    [ -n "${HA_VRRP_IFACE:-}" ] || die 3 "前置检查失败：.env.ha 缺少 HA_VRRP_IFACE（无法判定 VIP 归属，也就无法决定升级顺序）"
    [ -n "${VIP_WEB:-}" ] || die 3 "前置检查失败：.env.ha 缺少 VIP_WEB"
    command -v ip >/dev/null 2>&1 || die 3 "找不到 ip 命令（iproute2），无法判定 VIP 归属。若只是想演练编排顺序，可设 DRY_RUN=1 UPGRADE_FORCE_ROLE=holder|standby"
  fi

  # 非 root 时 switchover.sh 会拒绝执行。与其等到步骤 ③ 才失败 ——
  # 那时备机已经升级完、VIP 却没挪走，是最难受的中间状态 ——
  # 不如现在就拦住。
  if [ "${DRY_RUN}" != "1" ] && [ "$(id -u)" -ne 0 ]; then
    die 3 "需要 root 权限（步骤 ③ 要调用 switchover.sh 操作 keepalived）。请用：sudo bash $0 <taskNo> <stagingDir>"
  fi

  case "${UPGRADE_SYNC_MODE}" in
    shared|ssh|none) ;;
    *) die 3 "UPGRADE_SYNC_MODE 取值不合法：${UPGRADE_SYNC_MODE}（应为 shared / ssh / none）" ;;
  esac

  if [ "${UPGRADE_SYNC_MODE}" = "shared" ] && [ "${UPGRADE_PEER_STAGING_DIR}" != "${STAGING_DIR}" ]; then
    die 3 "UPGRADE_SYNC_MODE=shared 要求两台 staging 路径相同，但本机=${STAGING_DIR}、对端=${UPGRADE_PEER_STAGING_DIR}。若两台是各自本地盘，请设 UPGRADE_SYNC_MODE=ssh"
  fi

  if [ "${DRY_RUN}" != "1" ]; then
    log "探测对端连通性：${UPGRADE_PEER_SSH}"
    # BatchMode=yes ⇒ 需要口令就直接失败，而不是把脚本挂在交互提示上（cron/无人值守场景必须如此）
    if ! peer_exec "true" 2>/dev/null; then
      die 3 "无法通过 ssh 访问对端：${UPGRADE_PEER_SSH}。请先配置免密（ssh-copy-id），或用 UPGRADE_PEER_SSH 指定其它目标"
    fi
    log "  ✓ 对端可达"
  fi
}

# ---------------------------------------------------------------------
# ① 分发升级包
# ---------------------------------------------------------------------
sync_staging_to_peer() {
  case "${UPGRADE_SYNC_MODE}" in
    none)
      log "  跳过分发（UPGRADE_SYNC_MODE=none，假定两端 staging 均已就绪）"
      return 0
      ;;
    shared)
      # HA 部署里 app.upgrade.storage-root 应落在两台共享的 NAS 路径上
      # （与附件同盘）。这样 staging 天然对两端可见，而且【结果回执也共享】——
      # 对端后端重启后能直接读到本机脚本写的回执，启动对账才不会误判超时。
      log "  staging 由两台共享（NAS），无需复制：${STAGING_DIR}"
      return 0
      ;;
    ssh)
      log "  以 tar 流分发到对端：${STAGING_DIR} → ${UPGRADE_PEER_SSH}:${UPGRADE_PEER_STAGING_DIR}"
      if [ "${DRY_RUN}" = "1" ]; then log "  DRY_RUN：跳过分发"; return 0; fi
      if ! tar czf - -C "${STAGING_DIR}" . | peer_exec "mkdir -p $(printf '%q' "${UPGRADE_PEER_STAGING_DIR}") && tar xzf - -C $(printf '%q' "${UPGRADE_PEER_STAGING_DIR}")"; then
        die 3 "分发 staging 到对端失败"
      fi
      log "  ✓ 分发完成"
      return 0
      ;;
  esac
}

# ---------------------------------------------------------------------
# ② 在某一端应用升级
#
# upgrade-apply.sh 的退出码语义（见该脚本头注释）：
#   0 = 成功；1 = 失败但已回滚；2 = 失败且回滚未成功
# 这里原样透传，调用方据此决定「继续」还是「中止」。
# ---------------------------------------------------------------------
upgrade_node() {
  local target="$1"
  local label
  label="$(label_of "${target}")"

  log "  在 ${label} 上应用升级"
  if [ "${DRY_RUN}" = "1" ]; then log "  DRY_RUN：跳过"; return 0; fi

  local rc=0
  if [ "${target}" = "local" ]; then
    # `|| rc=$?` 而不是 `set +e; …; set -e`：
    # 函数内重新打开 errexit 后，`return 非零` 会让【整个脚本当场退出】，
    # 调用方连 rc 都拿不到，也就无法区分「已回滚」和「回滚失败」。
    bash "${UPGRADE_APPLY_SCRIPT}" "${TASK_NO}" "${STAGING_DIR}" "${BACKUP_DIR}" || rc=$?
  else
    local cmd
    cmd="$(remote_env_prefix) bash $(printf '%q' "${UPGRADE_APPLY_SCRIPT}") $(printf '%q' "${TASK_NO}") $(printf '%q' "${UPGRADE_PEER_STAGING_DIR}")"
    if [ -n "${UPGRADE_PEER_BACKUP_DIR}" ]; then
      cmd="${cmd} $(printf '%q' "${UPGRADE_PEER_BACKUP_DIR}")"
    fi
    # shellcheck disable=SC2029
    ssh ${UPGRADE_SSH_OPTS} "${UPGRADE_PEER_SSH}" "${cmd}" || rc=$?
  fi

  case "${rc}" in
    0)   log "  ✓ ${label} 应用成功" ;;
    1)   log "  ✗ ${label} 应用失败，但已回滚到升级前版本" ;;
    255) log "  ✗ 与 ${label} 的连接中断（ssh 退出码 255），该端状态不明" ;;
    *)   log "  ✗ ${label} 应用失败且回滚未成功" ;;
  esac
  return "${rc}"
}

# ---------------------------------------------------------------------
# 健康检查（直连被测节点，不经 VIP）
# ---------------------------------------------------------------------
verify_health() {
  local target="$1"
  local label url
  label="$(label_of "${target}")"
  url="$(health_url_of "${target}")"

  if [ "${DRY_RUN}" = "1" ]; then
    log "  DRY_RUN：跳过 ${label} 健康检查"
    return 0
  fi

  log "  健康检查 ${label}：${url}"
  # --noproxy '*'：宿主若设了 http_proxy，本机探测会绕到代理上，
  # 于是「明明活着」却一直报不通，排查方向被彻底带偏
  if curl -fsS --max-time 5 --noproxy '*' "${url}" >/dev/null 2>&1; then
    log "  ✓ ${label} 健康"
    return 0
  fi
  log "  ✗ ${label} 健康检查失败"
  return 1
}

# ---------------------------------------------------------------------
# ③ VIP 移交
# ---------------------------------------------------------------------
give_away_vip() {
  local target="$1"
  local label
  label="$(label_of "${target}")"

  log "  让 ${label} 交出 VIP（switchover.sh to-peer）"
  if [ "${DRY_RUN}" = "1" ]; then log "  DRY_RUN：跳过"; return 0; fi

  if [ "${target}" = "local" ]; then
    bash "${UPGRADE_SWITCHOVER_SCRIPT}" to-peer || die 2 "在 ${label} 上执行 VIP 让位失败"
  else
    peer_exec "bash $(printf '%q' "${UPGRADE_SWITCHOVER_SCRIPT}") to-peer" || die 2 "在 ${label} 上执行 VIP 让位失败"
  fi
}

# 等待本机 VIP 归属达到期望状态（held / notheld）
wait_vip_state() {
  local want="$1"
  local deadline=$(( $(date +%s) + VIP_SETTLE_SECONDS ))

  log "  等待本机 VIP 归属 → ${want}（最多 ${VIP_SETTLE_SECONDS}s）"
  if [ "${DRY_RUN}" = "1" ]; then log "  DRY_RUN：跳过等待"; return 0; fi

  while [ "$(date +%s)" -lt "${deadline}" ]; do
    if [ "${want}" = "held" ]; then
      if vip_is_held_locally; then log "  ✓ 本机已持有 VIP"; return 0; fi
    else
      if ! vip_is_held_locally; then log "  ✓ 本机已不再持有 VIP"; return 0; fi
    fi
    sleep 2
  done

  log "  ✗ VIP 归属未在 ${VIP_SETTLE_SECONDS}s 内变为 ${want}"
  return 1
}

# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------
main() {
  log "== 滚动升级开始 taskNo=${TASK_NO} =="
  preflight

  if vip_is_held_locally; then
    HOLDER="local"
    STANDBY="peer"
  else
    HOLDER="peer"
    STANDBY="local"
  fi

  log "本机 ${HA_NODE_NAME:-?}(${HA_NODE_IP:-?}) 角色：$([ "${HOLDER}" = "local" ] && echo 主机 || echo 备机)（持有 VIP=${VIP_WEB:-<未探测>}）"
  log "执行顺序：先升备机 $(label_of "${STANDBY}") → 移交 VIP → 再升主机 $(label_of "${HOLDER}")"

  log "① 分发升级包"
  sync_staging_to_peer

  # ---- ② 备机：此刻不承载流量，即使包是坏的也不会被用户看到 ----
  log "② 升级备机 $(label_of "${STANDBY}")"
  local rc=0
  upgrade_node "${STANDBY}" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    # 中止在这里是【最好的失败方式】：主机未被动过，VIP 未移动过，服务完全不受影响
    die 4 "备机升级失败（rc=${rc}）→ 已中止。主机未做任何改动，服务不受影响；请修好包或环境后重跑"
  fi
  verify_health "${STANDBY}" || die 2 "备机升级后健康检查未通过 → 已中止，主机仍未被动过"

  # ---- ③ 移交 VIP：把流量从「待升级的主机」挪到「已升级的备机」 ----
  log "③ 移交 VIP：$(label_of "${HOLDER}") → $(label_of "${STANDBY}")"
  give_away_vip "${HOLDER}"

  # 期望：VIP 落在 STANDBY 上 ⇒ STANDBY 是本机则本机应持有，否则本机应失去
  if [ "${STANDBY}" = "local" ]; then
    wait_vip_state "held" || die 2 "VIP 未按预期漂移到本机 → 已中止，主机仍未被动过"
  else
    wait_vip_state "notheld" || die 2 "VIP 未按预期从本机漂走 → 已中止，未升级主机（避免带流量重启）"
  fi

  # ---- ④ 原主机：此刻它已不承载流量 ----
  log "④ 升级（原）主机 $(label_of "${HOLDER}")"
  upgrade_node "${HOLDER}" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    # 到这里两端状态可能不同：备机是新版、主机是旧版（或已回滚）。
    # 这【不算灾难】—— 系统仍能对外服务（流量在备机上），但两端版本不一致，
    # 因此必须明确报出来，由人来决定是重试还是回退。
    log "  ⚠ 两端版本可能不一致：备机已升级，主机未升级（或已回滚）"
    die 1 "主机升级失败（rc=${rc}）。服务由 $(label_of "${STANDBY}") 承载，仍可用；请人工确认后再重试"
  fi
  verify_health "${HOLDER}" || die 2 "主机升级后健康检查未通过"

  # ---- ⑤ 收尾 ----
  if [ "${UPGRADE_RESTORE_VIP}" = "1" ]; then
    log "⑤ 把 VIP 挪回原主机 $(label_of "${HOLDER}")（UPGRADE_RESTORE_VIP=1）"
    give_away_vip "${STANDBY}"
    if [ "${HOLDER}" = "local" ]; then
      wait_vip_state "held" || die 2 "VIP 回迁失败，请手工确认归属"
    else
      wait_vip_state "notheld" || die 2 "VIP 回迁失败，请手工确认归属"
    fi
  else
    log "⑤ 跳过 VIP 回迁（UPGRADE_RESTORE_VIP=0）"
  fi

  echo
  log "== 滚动升级完成 =="
  echo "  两端版本：已一致（taskNo=${TASK_NO}）"
  if vip_is_held_locally; then
    echo "  当前 VIP ：在本机 ${HA_NODE_NAME:-?}(${HA_NODE_IP:-?})"
  else
    echo "  当前 VIP ：在对端 ${HA_PEER_NAME:-?}(${HA_PEER_IP})"
  fi
  echo
  echo "  建议随后核对："
  echo "    · sudo bash scripts/switchover.sh status        # VIP 归属符合预期"
  echo "    · docker exec ticket-mysql mysql -uroot -p'<口令>' -e 'SHOW REPLICA STATUS\\G' | grep Running"
  echo "    · 浏览器用真实账号走一遍提交 / 审批 / 归还，确认两端行为一致"
}

main
