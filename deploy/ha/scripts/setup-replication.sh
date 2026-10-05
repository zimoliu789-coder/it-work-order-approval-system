#!/usr/bin/env bash
# =====================================================================
# 建立 MySQL 主主双向复制
#
# 在两台机器上【各执行一次】。脚本做的是「本机 → 对端」这一半：
#   A 机执行 → A 从 B 拉取；B 机执行 → B 从 A 拉取。两边都执行完才是双向。
#
# 幂等：可重复执行。每次都会重设复制源（先 RESET REPLICA ALL 再重新 CHANGE），
# 因此复制断开、口令轮换、误配之后重跑本脚本即可修好，不需要手工敲 SQL。
#
# 用法：
#   cd deploy/ha
#   bash scripts/setup-replication.sh
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${HA_DIR}"

ENV_FILE="${ENV_FILE:-.env.ha}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.ha.yml}"

if [ ! -f "${ENV_FILE}" ]; then
  echo "✗ 找不到 ${HA_DIR}/${ENV_FILE}，请先 cp .env.ha.example .env.ha 并填写" >&2
  exit 1
fi

# 载入 .env.ha（set -a 让其中的变量自动导出给子进程）
set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

: "${MYSQL_ROOT_PASSWORD:?请在 .env.ha 中设置 MYSQL_ROOT_PASSWORD}"
: "${HA_NODE_IP:?请在 .env.ha 中设置 HA_NODE_IP}"
: "${HA_PEER_IP:?请在 .env.ha 中设置 HA_PEER_IP}"
: "${MYSQL_REPL_USER:=repl}"
: "${MYSQL_REPL_PASSWORD:?请在 .env.ha 中设置 MYSQL_REPL_PASSWORD}"
: "${MYSQL_DATABASE:=ticket_system}"

DC=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

# 统一的容器内 mysql 客户端调用入口。
# 用 docker exec 而不是宿主 mysql 客户端：NAS / 精简服务器上未必装了客户端，
# 而 mysql 镜像里一定有，这样脚本对宿主环境零要求。
mysql_exec() {
  "${DC[@]}" exec -T mysql \
    mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4 "$@"
}

wait_mysql() {
  local i
  for i in $(seq 1 90); do
    if "${DC[@]}" exec -T mysql \
        mysqladmin ping -h 127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
      echo "  · MySQL 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "✗ MySQL 未在 180 秒内就绪，请先确认容器状态：${DC[*]} ps" >&2
  return 1
}

echo "== 1/5 等待本机 MySQL 就绪 =="
wait_mysql

echo "== 2/5 校验 GTID 已启用（主主复制的前提）=="
GTID_MODE="$(mysql_exec -N -B -e 'SELECT @@gtid_mode;' | tr -d '\r')"
if [ "${GTID_MODE}" != "ON" ]; then
  echo "✗ gtid_mode=${GTID_MODE}（期望 ON）。请确认已挂载 mysql/conf.d/10-replication-common.cnf，然后重建容器。" >&2
  exit 1
fi
SERVER_ID="$(mysql_exec -N -B -e 'SELECT @@server_id;' | tr -d '\r')"
INC="$(mysql_exec -N -B -e 'SELECT @@auto_increment_increment;' | tr -d '\r')"
OFFSET="$(mysql_exec -N -B -e 'SELECT @@auto_increment_offset;' | tr -d '\r')"
echo "  · gtid_mode=ON  server_id=${SERVER_ID}  自增步长=${INC}  偏移=${OFFSET}"
if [ "${INC}" != "2" ]; then
  echo "✗ auto_increment_increment=${INC}（期望 2）。两台机的自增未错开，主主并发写入可能撞主键。" >&2
  exit 1
fi

echo "== 3/5 创建/更新复制账号 ${MYSQL_REPL_USER}@% =="
# CREATE ... IF NOT EXISTS 后紧跟 ALTER USER 改口令，保证「账号已存在但口令变了」时也能收敛
mysql_exec -e "
CREATE USER IF NOT EXISTS '${MYSQL_REPL_USER}'@'%' IDENTIFIED BY '${MYSQL_REPL_PASSWORD}';
ALTER USER '${MYSQL_REPL_USER}'@'%' IDENTIFIED BY '${MYSQL_REPL_PASSWORD}';
GRANT REPLICATION SLAVE ON *.* TO '${MYSQL_REPL_USER}'@'%';
FLUSH PRIVILEGES;"
echo "  · 复制账号就绪（权限仅 REPLICATION SLAVE）"

echo "== 4/5 检查对端 ${HA_PEER_IP}:3306 可达 =="
if ! "${DC[@]}" exec -T mysql sh -c "nc -z -w 3 ${HA_PEER_IP} 3306" >/dev/null 2>&1; then
  echo "  ! 容器内探测对端 3306 失败。常见原因：" >&2
  echo "    - 对端尚未启动，或对端防火墙未放行本节点 IP" >&2
  echo "    - 对端 mysql 未发布 3306 端口（见 docker-compose.ha.yml 的 ports 段）" >&2
  echo "  继续尝试建立复制，若失败请先解决连通性。" >&2
fi

echo "== 5/5 建立复制（本机 ← 对端 ${HA_PEER_IP}） =="
# 先彻底重置：STOP 在「尚未配置复制」时会报错，故用 || true 容忍
mysql_exec -e "STOP REPLICA;" >/dev/null 2>&1 || true
mysql_exec -e "RESET REPLICA ALL;" >/dev/null 2>&1 || true

# SOURCE_AUTO_POSITION=1 —— 自动追平的核心：按 GTID 集合定位起点，
#   备机宕机数日后重启、或主从做过切换，都能自动从断点继续，不需要人工算 binlog 位点。
# GET_SOURCE_PUBLIC_KEY=1 —— MySQL 8 默认 caching_sha2_password，
#   非 SSL 连接下必须允许向主节点索取 RSA 公钥来加密口令，否则报
#   "Authentication plugin 'caching_sha2_password' reported error"。
mysql_exec -e "
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='${HA_PEER_IP}',
  SOURCE_PORT=3306,
  SOURCE_USER='${MYSQL_REPL_USER}',
  SOURCE_PASSWORD='${MYSQL_REPL_PASSWORD}',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;"

echo "  · 复制已启动，等待 3 秒后校验状态…"
sleep 3

STATUS="$(mysql_exec -e 'SHOW REPLICA STATUS\G' 2>/dev/null || true)"
IO="$(printf '%s' "${STATUS}" | grep -E '^\s*Replica_IO_Running:' | head -1 | awk '{print $2}' | tr -d '\r')"
SQL="$(printf '%s' "${STATUS}" | grep -E '^\s*Replica_SQL_Running:' | head -1 | awk '{print $2}' | tr -d '\r')"
BEHIND="$(printf '%s' "${STATUS}" | grep -E '^\s*Seconds_Behind_Source:' | head -1 | awk '{print $2}' | tr -d '\r')"
LAST_ERR="$(printf '%s' "${STATUS}" | grep -E '^\s*Last_IO_Error:|^\s*Last_SQL_Error:' | sed 's/^[[:space:]]*//' | tr -d '\r')"

echo "--------------------------------------------------------------"
echo "  本节点    : ${HA_NODE_NAME:-?}  (${HA_NODE_IP})"
echo "  复制来源  : ${HA_PEER_IP}:3306"
echo "  IO 线程   : ${IO:-未知}"
echo "  SQL 线程  : ${SQL:-未知}"
echo "  复制延迟  : ${BEHIND:-未知} 秒"
[ -n "${LAST_ERR}" ] && echo "  最近错误  : ${LAST_ERR}"
echo "--------------------------------------------------------------"

if [ "${IO}" = "Yes" ] && [ "${SQL}" = "Yes" ]; then
  echo "✓ 本机单向复制已建立。请记得在【另一台】机器上同样执行本脚本，双向复制才完整。"
else
  echo "✗ 复制未进入 Yes/Yes 状态，请检查上方「最近错误」与端口连通性。" >&2
  exit 1
fi
