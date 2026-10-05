#!/usr/bin/env bash
# =====================================================================
# 单节点升级应用（）
#
# 本脚本是「替换 + 重启」这一步的唯一执行者。后端进程把它当作外部命令拉起
# （见 app.upgrade.apply-command），也可以由运维手动执行。
#
# 为什么必须由进程外的脚本来做：
#   后端无法替换自己正在运行的 jar —— Windows 上文件被占用直接失败；
#   Linux 上替换成功但【已加载的类不会重新载入】，仍须重启才生效。
#   因此后端只负责「校验 → 备份 → 落到 staging」，本脚本负责剩下的一半。
#
# ⚠️ 与 systemd / Docker 的交互有一个必须知道的坑：
#   若后端以 systemd 服务运行（默认 KillMode=control-group），它派生的子进程
#   仍在同一个 cgroup 里 —— 重启服务时 systemd 会把整个 cgroup 一起杀掉，
#   包括本脚本自己。结果是「脚本被自己发起的重启杀死，替换做到一半」。
#   因此 apply-command 应当让本脚本成为【独立单元】：
#     systemd-run --unit=ticket-upgrade-{taskNo} --collect --no-block \
#         /opt/ticket/bin/upgrade-apply.sh {taskNo} {stagingDir} {backupDir}
#   --no-block 让 systemd-run 立即返回，不会把后端请求线程拖住。
#
# 用法：
#   upgrade-apply.sh <taskNo> <stagingDir> [<backupDir>]
#
# 环境变量（可来自 .env.ha，也可由后端以 UPGRADE_* 注入）：
#   UPGRADE_ARTIFACT_DIR     当前生效产物目录（宿主路径，内含 backend.jar 与 frontend/dist）
#   UPGRADE_STATE_DIR        后端 state 目录，用于写回执 state/result/<taskNo>.json
#   UPGRADE_DEPLOY_MODE      docker | systemd | none（默认 none，表示用 UPGRADE_RESTART_CMD）
#   UPGRADE_RESTART_CMD      deploy-mode=none 时的重启命令
#   UPGRADE_HEALTH_URL       健康检查地址（默认 http://127.0.0.1:8080/api/health）
#   UPGRADE_HEALTH_TIMEOUT   健康检查总超时秒数（默认 180）
#   UPGRADE_HEALTH_INTERVAL  健康检查间隔秒数（默认 5）
#   UPGRADE_COMPOSE_FILES    docker 模式：compose 文件（空格分隔，按顺序 -f 传入）
#   UPGRADE_COMPOSE_DIR      docker 模式：compose 工作目录
#   UPGRADE_SERVICES         docker 模式：需要重建的服务（默认 backend frontend）
#   DRY_RUN=1                只打印计划，不做任何写操作（用于演练与排错）
#
# 退出码：0 = 应用成功；非 0 = 已回滚或回滚也失败（两者都会写结果回执）
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

# 用【显式检查 + die】而不是 ${VAR:?}：
# bash 的 ${VAR:?} 会直接以退出码 1 结束进程 —— 既绕过了 write_result（不写回执），
# 又让「配置缺失」在退出码语义上等同于「已回滚」。
# 后端拿不到回执就只能等到 apply-timeout-minutes（默认 30 分钟）才判失败，
# 而管理员在这 30 分钟里看到的是「升级中」。
# 先查 STATE_DIR：没有它连回执该写到哪里都不知道，这一步失败时只能直接报错。
if [ -z "${UPGRADE_STATE_DIR:-}" ]; then
  echo "[upgrade-apply] ✗ 必须设置 UPGRADE_STATE_DIR（后端 state 目录，用于写结果回执）" >&2
  exit 1
fi
# ARTIFACT_DIR 的检查刻意【不写在这里】：die / write_result 要到下方才定义，
# 在当前位置调用只会得到 "die: command not found"（退出码 127，且不写回执）。
# 它放在 preflight 里 —— 执行时机一样早（都在动产物之前），但那时函数已就绪，
# 失败能写出 FAILED 回执，后端不必等到 30 分钟超时。

UPGRADE_DEPLOY_MODE="${UPGRADE_DEPLOY_MODE:-none}"
UPGRADE_HEALTH_URL="${UPGRADE_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
UPGRADE_HEALTH_TIMEOUT="${UPGRADE_HEALTH_TIMEOUT:-180}"
UPGRADE_HEALTH_INTERVAL="${UPGRADE_HEALTH_INTERVAL:-5}"
UPGRADE_SERVICES="${UPGRADE_SERVICES:-backend frontend}"
DRY_RUN="${DRY_RUN:-0}"

RESULT_DIR="${UPGRADE_STATE_DIR}/result"
RESULT_FILE="${RESULT_DIR}/${TASK_NO}.json"
WORK_DIR="${UPGRADE_STATE_DIR}/pre-apply-${TASK_NO}"

# 产物是否已被替换。ERR trap 靠它判断「该不该回滚」——
# 还没换过产物时回滚毫无意义（备份与现场都还是完好的）。
SWAPPED=0

# ---------------------------------------------------------------------
# 输出与回执
# ---------------------------------------------------------------------
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# 写结果回执 —— 这是后端唯一能拿到的结论，因此【成功与失败都必须写】。
# 漏写会让任务一直停在 APPLYING 直到超时，而后端早已重启完成，
# 管理员看到的是「升级中」的假象。
write_result() {
  local result="$1"
  local message="$2"

  if [ "${DRY_RUN}" = "1" ]; then
    log "DRY_RUN：本应写回执 ${RESULT_FILE} → result=${result} message=${message}"
    return 0
  fi

  mkdir -p "${RESULT_DIR}"
  # 手工拼 JSON：只含 4 个固定字段，用 printf 比依赖 jq 更省事（NAS / 最小化镜像上未必有 jq）。
  # message 里的双引号与反斜杠必须转义，否则回执会变成一段非法 JSON，
  # 后端读不出来 → 任务超时判失败，而实际结果可能是成功。
  local escaped
  escaped="$(printf '%s' "${message}" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  printf '{"taskNo":"%s","result":"%s","message":"%s","finishedAt":"%s"}\n' \
    "${TASK_NO}" "${result}" "${escaped}" "$(date '+%Y-%m-%dT%H:%M:%S')" > "${RESULT_FILE}"
  log "已写结果回执：${RESULT_FILE} → ${result}"
}

die() {
  local message="$1"
  log "✗ ${message}"
  # 前置检查阶段失败（还没动过产物）也写回执：后端需要知道「这次没成」，
  # 否则它会一直等到超时。回滚分支由 apply_upgrade 内部处理，不走这里。
  write_result "FAILED" "${message}"
  exit 1
}

# ---------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------
preflight() {
  log "== 前置检查 =="
  # 放在这里而不是脚本顶部：此处 die/write_result 已定义，失败能写 FAILED 回执
  [ -n "${UPGRADE_ARTIFACT_DIR:-}" ] || die "必须设置 UPGRADE_ARTIFACT_DIR（当前生效产物目录）"
  [ -n "${TASK_NO}" ] || die "缺少参数 taskNo"
  [ -n "${STAGING_DIR}" ] || die "缺少参数 stagingDir"
  [ -d "${STAGING_DIR}" ] || die "staging 目录不存在：${STAGING_DIR}"
  [ -f "${STAGING_DIR}/backend.jar" ] || die "staging 目录内缺少 backend.jar：${STAGING_DIR}"
  [ -f "${STAGING_DIR}/manifest.json" ] || die "staging 目录内缺少 manifest.json：${STAGING_DIR}"

  case "${UPGRADE_DEPLOY_MODE}" in
    docker|systemd|none) ;;
    *) die "UPGRADE_DEPLOY_MODE 取值不合法：${UPGRADE_DEPLOY_MODE}（应为 docker / systemd / none）" ;;
  esac

  # 重启所需的配置必须在【替换产物之前】就校验掉。
  # 若留到 restart_app 才发现缺配置，此刻产物已经被换掉 ——
  # 那是一个「新版本已就位、进程还没起、也没触发回滚」的中间状态。
  if [ "${UPGRADE_DEPLOY_MODE}" = "systemd" ]; then
    [ -n "${UPGRADE_SYSTEMD_UNIT:-}" ] || die "deploy-mode=systemd 时必须设置 UPGRADE_SYSTEMD_UNIT"
  fi
  if [ "${UPGRADE_DEPLOY_MODE}" = "docker" ]; then
    [ -n "${UPGRADE_COMPOSE_DIR:-}" ] || die "deploy-mode=docker 时必须设置 UPGRADE_COMPOSE_DIR"
  fi

  mkdir -p "${UPGRADE_ARTIFACT_DIR}"

  # 【关键闸门】必须存在可用的回退源，否则拒绝执行。
  # 在「没有退路」的情况下替换生产产物，一旦新版本起不来就彻底不可恢复 ——
  # 宁可这次升级失败，也不能把系统置于不可回退的状态。
  if [ -n "${BACKUP_DIR}" ] && [ -d "${BACKUP_DIR}" ] && [ -n "$(ls -A "${BACKUP_DIR}" 2>/dev/null)" ]; then
    ROLLBACK_SOURCE="${BACKUP_DIR}"
    log "回退源：使用后端已完成的备份 ${ROLLBACK_SOURCE}"
  else
    # 后端没有备份（首次部署时没有「上一版」可备）⇒ 本脚本自己留一份现场。
    # 不这样做的话，「首次上线」这一次升级就是无退路的。
    if [ -n "$(ls -A "${UPGRADE_ARTIFACT_DIR}" 2>/dev/null)" ]; then
      ROLLBACK_SOURCE="${WORK_DIR}"
      log "回退源：后端未提供备份，脚本自行留存现场 → ${ROLLBACK_SOURCE}"
      if [ "${DRY_RUN}" != "1" ]; then
        rm -rf "${WORK_DIR}"
        mkdir -p "${WORK_DIR}"
        cp -a "${UPGRADE_ARTIFACT_DIR}/." "${WORK_DIR}/"
      fi
    else
      # 产物目录本身为空 ⇒ 这是全新安装，没有「回退到旧版本」这个概念。
      # 允许继续，但必须把这件事说清楚（回滚时会找不到可还原的东西）。
      ROLLBACK_SOURCE=""
      log "⚠ 产物目录为空且无备份：本次视为全新安装，失败时无法回滚到旧版本"
    fi
  fi
}

# ---------------------------------------------------------------------
# 重启
# ---------------------------------------------------------------------
restart_app() {
  log "  重启应用（deploy-mode=${UPGRADE_DEPLOY_MODE}）"
  if [ "${DRY_RUN}" = "1" ]; then
    log "  DRY_RUN：跳过重启"
    return 0
  fi
  case "${UPGRADE_DEPLOY_MODE}" in
    docker)
      # preflight 已经拦过；这里是纵深防御（若被绕过，die 仍会写回执）
      [ -n "${UPGRADE_COMPOSE_DIR:-}" ] || die "docker 模式必须设置 UPGRADE_COMPOSE_DIR"
      local args=()
      local file
      for file in ${UPGRADE_COMPOSE_FILES:-docker-compose.ha.yml}; do
        args+=(-f "${file}")
      done
      ( cd "${UPGRADE_COMPOSE_DIR}" && docker compose "${args[@]}" up -d --force-recreate ${UPGRADE_SERVICES} )
      ;;
    systemd)
      systemctl restart "${UPGRADE_SYSTEMD_UNIT}"
      ;;
    none)
      if [ -n "${UPGRADE_RESTART_CMD:-}" ]; then
        # shellcheck disable=SC2086
        ${UPGRADE_RESTART_CMD}
      else
        log "  ⚠ 未配置重启命令：产物已替换，请自行重启后端以生效"
      fi
      ;;
  esac
}

# ---------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------
wait_healthy() {
  local deadline=$(( $(date +%s) + UPGRADE_HEALTH_TIMEOUT ))
  log "  等待健康检查：${UPGRADE_HEALTH_URL}（最多 ${UPGRADE_HEALTH_TIMEOUT}s）"
  if [ "${DRY_RUN}" = "1" ]; then
    log "  DRY_RUN：视为健康"
    return 0
  fi
  while [ "$(date +%s)" -lt "${deadline}" ]; do
    # --noproxy '*' 是必须的：宿主若设了 http_proxy，健康检查会绕到代理上，
    # 于是「本机后端明明活着」却一直报不通，排查方向会被彻底带偏。
    if curl -fsS --max-time 3 --noproxy '*' "${UPGRADE_HEALTH_URL}" >/dev/null 2>&1; then
      log "  ✓ 健康检查通过"
      return 0
    fi
    sleep "${UPGRADE_HEALTH_INTERVAL}"
  done
  log "  ✗ 健康检查超时（${UPGRADE_HEALTH_TIMEOUT}s）"
  return 1
}

# ---------------------------------------------------------------------
# 替换产物
# ---------------------------------------------------------------------
swap_in() {
  log "  替换产物：${STAGING_DIR} → ${UPGRADE_ARTIFACT_DIR}"
  if [ "${DRY_RUN}" = "1" ]; then
    log "  DRY_RUN：跳过替换"
    return 0
  fi
  # --delete 不能省：新版本【删掉】的文件若残留在产物目录里，
  # 得到的是一个新旧混合的目录 —— 而旧文件在旧版本的启动流程里可能仍被引用，
  # 表现为「升级后行为诡异地像没升」。
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "${STAGING_DIR}/" "${UPGRADE_ARTIFACT_DIR}/"
  else
    rm -rf "${UPGRADE_ARTIFACT_DIR:?}"/* 2>/dev/null || true
    cp -a "${STAGING_DIR}/." "${UPGRADE_ARTIFACT_DIR}/"
  fi
}

restore_backup() {
  log "  回滚：${ROLLBACK_SOURCE} → ${UPGRADE_ARTIFACT_DIR}"
  if [ "${DRY_RUN}" = "1" ]; then
    log "  DRY_RUN：跳过回滚"
    return 0
  fi
  if [ -z "${ROLLBACK_SOURCE}" ]; then
    log "  ✗ 没有可用的回退源，无法回滚"
    return 1
  fi
  rm -rf "${UPGRADE_ARTIFACT_DIR:?}"/* 2>/dev/null || true
  cp -a "${ROLLBACK_SOURCE}/." "${UPGRADE_ARTIFACT_DIR}/"
}

# 把新版本信息写进 state/current.json —— 后端启动对账靠它判定「新版本是否已生效」。
# 这是除了结果回执之外的第二条独立证据：即使脚本在写回执之前就被重启杀掉，
# 后端只要看到 current.json 的版本等于目标版本，就能正确判定升级成功。
update_current_version() {
  if [ "${DRY_RUN}" = "1" ]; then
    return 0
  fi
  local version
  # 末尾的 `|| true` 是给 pipefail 用的：sed 的输出若超过管道缓冲区，
  # 提前退出的 head 会让 sed 收到 SIGPIPE，pipefail 下整条管道判失败 →
  # 在 set -e 下直接终止脚本 —— 而这一步在【成功路径】上。
  version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${STAGING_DIR}/manifest.json" | head -n 1 || true)"
  [ -n "${version}" ] || return 0
  if cp "${STAGING_DIR}/manifest.json" "${UPGRADE_STATE_DIR}/current.json" 2>/dev/null; then
    log "  已更新 state/current.json → version=${version}"
  else
    # current.json 只用于「升级前版本」的展示与启动对账的辅助证据。
    # 健康检查已经证明新版本在跑，因为写不了这个文件就把升级判失败（甚至回滚），
    # 等于自己制造一次停机。
    log "  ⚠ 更新 state/current.json 失败（忽略，不影响本次升级结论）"
  fi
  return 0
}

# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------
# ---------------------------------------------------------------------
# 统一的失败收尾
#
# 无论失败是怎么来的（健康检查超时、docker 重启报错、脚本自身抛错），
# 收尾动作都必须是这三步且顺序固定：
#   1) 能回滚就回滚（回退源由 preflight 保证存在，除非是全新安装）
#   2) 回滚后重启，并再确认一次健康
#   3) 【无论结果如何都写回执】—— 回执是后端唯一能拿到的结论
#
# 退出码沿用对外契约：1 = 已回滚到升级前版本；2 = 回滚也失败，需人工介入。
# ---------------------------------------------------------------------
do_rollback() {
  local reason="$1"

  # 先摘掉 ERR trap：do_rollback 自己也会失败（restore/restart 都可能报错），
  # 不摘掉就会递归回自己，把真正的失败原因冲掉
  trap - ERR

  log "== ✗ ${reason}，开始回滚 =="

  if [ "${SWAPPED}" != "1" ]; then
    # 还没替换过产物：没有「回滚」这回事，备份与现场都是完好的
    write_result "FAILED" "${reason}（产物尚未被替换，无需回滚）"
    log "== 未替换产物，已记录失败 =="
    return 2
  fi

  if restore_backup; then
    restart_app || true
    if wait_healthy; then
      write_result "ROLLED_BACK" "${reason}，已回滚到升级前产物并重启成功"
      log "== 已回滚，系统恢复到升级前状态 =="
      return 1
    fi
    write_result "FAILED" "${reason}，回滚后旧版本仍不健康，需人工介入"
    log "== 回滚后仍不健康，需人工介入 =="
    return 2
  fi

  write_result "FAILED" "${reason}，且回滚未成功（无可用的回退源）"
  log "== 回滚失败，需人工介入 =="
  return 2
}

# set -e 触发的未预期错误（任何没被 if 包住的非零返回）也会经过这里。
# 没有它的话，一次意外错误会让脚本静默终止：不回滚、不写回执，
# 而后端只会看到「任务一直停在 APPLYING」。
on_error() {
  local lineno="$1"
  do_rollback "应用过程中发生未预期的错误（第 ${lineno} 行）"
  exit $?
}

main() {
  trap 'on_error $LINENO' ERR

  log "== 开始应用升级 taskNo=${TASK_NO} =="
  preflight

  log "== ① 替换产物 =="
  swap_in
  SWAPPED=1

  log "== ② 重启 =="
  restart_app

  log "== ③ 健康检查 =="
  if wait_healthy; then
    update_current_version || log "  ⚠ 更新 state/current.json 失败（不影响本次升级结论）"
    write_result "SUCCESS" "新版本已替换并重启，健康检查通过"
    log "== 应用成功 =="
    return 0
  fi

  # 必须先摘掉 ERR trap 再调用 do_rollback：
  # do_rollback 以非零（1 = 已回滚）返回，而它在 set -e 下是裸调用 ——
  # 不摘 trap 就会再次触发 ERR → on_error 又回滚一遍。
  # 实测症状：第一次回滚已写出正确的 ROLLED_BACK 回执，随后被第二次覆盖成
  # 「应用过程中发生未预期的错误（第 N 行）」，真实原因与正确结论双双丢失，
  # 还白白多一次「还原 + 重启」的停机。
  trap - ERR
  local rc=0
  do_rollback "新版本健康检查失败" || rc=$?
  return "${rc}"
}

main
