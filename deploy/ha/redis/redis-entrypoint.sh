#!/bin/sh
# =====================================================================
# Redis 数据节点启动脚本（HA 版）
#
# 为什么不用 compose 的 command 直接拼参数：
#   HA 场景下「本节点是主还是从」由 .env.ha 的 REDIS_REPLICAOF 决定，
#   需要在启动时做一次分支判断；写进脚本比塞进 compose 的一行长命令可读得多，
#   也便于把「为什么给了 masterauth」这类理由就近注释。
# =====================================================================
set -e

: "${REDIS_PASSWORD:?REDIS_PASSWORD 未设置}"
REDIS_MAXMEMORY="${REDIS_MAXMEMORY:-256mb}"

# 基础参数说明：
#   --appendonly yes + everysec：临时锁与限流计数可容忍秒级丢失，但重启后不应全空
#   --masterauth    ：本节点被哨兵降级为从时，需要用主节点口令去同步（漏配会导致
#                     切换后从节点一直处于「等待主认证」而同步不上，表现为数据不再更新）
#   --maxmemory-policy noeviction：本实例存的是临时锁、限流计数、令牌黑名单。
#                     用 LRU 淘汰会让「同一设备只能被一人锁定」静默失效、
#                     让已登出的令牌重新可用。宁可写入失败（可观测）也不静默丢安全数据。
set -- \
  --dir /data \
  --appendonly yes \
  --appendfsync everysec \
  --requirepass "$REDIS_PASSWORD" \
  --masterauth "$REDIS_PASSWORD" \
  --maxmemory "$REDIS_MAXMEMORY" \
  --maxmemory-policy noeviction \
  --replica-read-only yes

if [ -n "${REDIS_REPLICAOF:-}" ]; then
  # REDIS_REPLICAOF 形如 "<宿主IP> 6379"。
  # 故意不加引号：它需要按空格拆成 host 与 port 两个独立参数传给 redis-server。
  # shellcheck disable=SC2086
  set -- "$@" --replicaof ${REDIS_REPLICAOF}
  echo "[redis] 以【从节点】启动，初始复制自 ${REDIS_REPLICAOF}"
else
  echo "[redis] 以【主节点】启动"
fi

exec redis-server "$@"
