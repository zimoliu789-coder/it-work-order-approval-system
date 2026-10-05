#!/usr/bin/env bash
# =====================================================================
# 挂载 NAS 共享存储（两台机器都要挂，且挂到同一个 SHARED_ROOT）
#
# 为什么附件必须放共享存储：
#   应用是双机双跑、VIP 漂移的。若附件存在本机磁盘，
#   用户在 A 机上传的文件在漂移到 B 机后下载会 404 ——
#   而且这个 404 只在切换后才出现，事后极难联想到「附件没共享」。
#
# 用法：
#   cd deploy/ha
#   sudo bash scripts/mount-nas.sh            # 挂载
#   sudo bash scripts/mount-nas.sh --umount   # 卸载
#
# 持久化：脚本只做本次挂载。开机自动挂载请按 DEPLOY.md  写 /etc/fstab。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${ENV_FILE:-${HA_DIR}/.env.ha}"

if [ "$(id -u)" -ne 0 ]; then
  echo "✗ 需要 root 权限（挂载属于内核级操作）" >&2
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

: "${SHARED_ROOT:?请在 .env.ha 中设置 SHARED_ROOT}"
: "${SHARED_TYPE:?请在 .env.ha 中设置 SHARED_TYPE}"
: "${NAS_SERVER:?请在 .env.ha 中设置 NAS_SERVER}"
: "${NAS_EXPORT:?请在 .env.ha 中设置 NAS_EXPORT}"

CIFS_CRED_FILE="/etc/ticket-cifs.credentials"

# 容器内后端以 uid/gid 10001 运行（见 backend/Dockerfile）。
# 宿主侧共享挂载点内的文件必须让这个 uid 可写，否则上传附件会因权限被拒，
# 而报错只出现在上传动作上，容易被误判为「业务 bug」。
APP_UID=10001
APP_GID=10001

if [ "${1:-}" = "--umount" ]; then
  echo "== 卸载 ${SHARED_ROOT} =="
  if mountpoint -q "${SHARED_ROOT}"; then
    umount "${SHARED_ROOT}"
    echo "  · 已卸载"
  else
    echo "  · 未挂载，跳过"
  fi
  exit 0
fi

echo "== 1/4 准备挂载点 ${SHARED_ROOT} =="
mkdir -p "${SHARED_ROOT}"

if mountpoint -q "${SHARED_ROOT}"; then
  echo "  · 已是挂载点（$(findmnt -n -o SOURCE "${SHARED_ROOT}")），跳过挂载"
else
  case "${SHARED_TYPE}" in
    nfs)
      echo "== 2/4 以 NFS 挂载 ${NAS_SERVER}:${NAS_EXPORT} =="
      # 默认选项说明：
      #   vers=4.1   ：NFSv4.1 支持会话与并行读，NAS 支持时优先它（不支持会被服务端拒绝，报错明确）
      #   hard       ：服务端无响应时持续重试而不是报 EIO。
      #                软挂载（soft）在 NAS 抖动时会向应用返回 I/O 错误，
      #                表现为「附件随机读失败」，比短暂卡顿难排查得多。
      #   timeo=600  ：单次超时 60 秒 × retrans=2 次后进入硬重试循环
      #   _netdev    ：声明本挂载依赖网络（fstab 中同样需要），避免开机时网络未就绪就挂载
      NFS_OPTS="${NAS_MOUNT_OPTIONS:-vers=4.1,hard,timeo=600,retrans=2,_netdev}"
      mount -t nfs -o "${NFS_OPTS}" "${NAS_SERVER}:${NAS_EXPORT}" "${SHARED_ROOT}"
      ;;
    cifs)
      echo "== 2/4 以 CIFS/SMB 挂载 //${NAS_SERVER}/${NAS_EXPORT} =="
      if [ -z "${NAS_USERNAME:-}" ] || [ -z "${NAS_PASSWORD:-}" ]; then
        echo "✗ CIFS 挂载需要 NAS_USERNAME 与 NAS_PASSWORD" >&2
        exit 1
      fi
      # 口令写入 600 权限的 credentials 文件，而不是放在 -o 参数里：
      #   mount 的命令行会被同机其它用户通过 ps 看到，也会进入 shell 历史。
      umask 077
      cat > "${CIFS_CRED_FILE}" <<EOF
username=${NAS_USERNAME}
password=${NAS_PASSWORD}
EOF
      chmod 600 "${CIFS_CRED_FILE}"
      # uid/gid 10001：让 CIFS 侧呈现的文件属主正好等于容器内运行用户的 uid，
      #   否则容器写附件时会被判为「他人文件」而拒绝。
      CIFS_OPTS="${NAS_MOUNT_OPTIONS:-credentials=${CIFS_CRED_FILE},vers=3.0,uid=${APP_UID},gid=${APP_GID},file_mode=0640,dir_mode=0750,_netdev}"
      mount -t cifs -o "${CIFS_OPTS}" "//${NAS_SERVER}/${NAS_EXPORT}" "${SHARED_ROOT}"
      ;;
    *)
      echo "✗ 不支持的 SHARED_TYPE=${SHARED_TYPE}（只支持 nfs / cifs）" >&2
      exit 1
      ;;
  esac
  echo "  · 挂载完成：$(findmnt -n -o SOURCE,FSTYPE "${SHARED_ROOT}")"
fi

echo "== 3/4 创建子目录（attachments / exports / backup）=="
mkdir -p "${SHARED_ROOT}/attachments" "${SHARED_ROOT}/exports"
# 备份目录落在共享盘而不是本机：本机磁盘故障是双机高可用要防的场景之一，
# 备份若只在故障机本地，等于没有备份。
mkdir -p "${BACKUP_DIR:?请在 .env.ha 中设置 BACKUP_DIR}"

echo "== 4/4 校正属主为 ${APP_UID}:${APP_GID}（容器内后端运行用户）=="
# NFS 上 chown 可能因 root_squash 失败，此时不视为致命错误：
# 只要 NAS 导出配置里已把该目录交给对应用户，属主本就是对的。
if chown -R "${APP_UID}:${APP_GID}" "${SHARED_ROOT}/attachments" "${SHARED_ROOT}/exports" 2>/dev/null; then
  echo "  · 属主已校正"
else
  echo "  ! chown 失败（NFS root_squash 的常见表现）。请确认 NAS 导出配置中"
  echo "    该目录对 uid ${APP_UID} 可写，否则上传附件会报权限错误。" >&2
fi

echo "--------------------------------------------------------------"
echo "  共享根目录: ${SHARED_ROOT}  (${SHARED_TYPE} ← ${NAS_SERVER}:${NAS_EXPORT})"
echo "  附件目录  : ${SHARED_ROOT}/attachments"
echo "  导出目录  : ${SHARED_ROOT}/exports"
echo "  备份目录  : ${BACKUP_DIR}"
echo "--------------------------------------------------------------"
echo "  下一步：把挂载写入 /etc/fstab 以便开机自动挂载（见 DEPLOY.md ），"
echo "         并确认【另一台机器】挂了同一个共享。"
