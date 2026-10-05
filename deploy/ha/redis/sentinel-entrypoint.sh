#!/bin/sh
# =====================================================================
# Redis 哨兵启动脚本（HA 版）
#
# 为什么要脚本生成配置而不是挂一份静态 sentinel.conf：
#   哨兵配置里的「监控目标地址」「quorum」「announce-ip」都随节点与网段而变，
#   用环境变量生成才能做到「两台机共用一份部署资产」。
#
# 为什么配置只在首次生成（已存在则复用）：
#   哨兵在运行期会【重写自己的配置文件】来记录当前拓扑（主是谁、有几个从）。
#   若每次启动都用初始值覆盖，容器重建后哨兵会退回「初始主」的认知 ——
#   而此时真正的主可能已经是被提升的备机，结果是一次错误的二次切换。
#   需要「重置拓扑」时才删掉 $DATA_ROOT/sentinel/sentinel.conf 再重启。
# =====================================================================
set -e

: "${REDIS_SENTINEL_MASTER:?REDIS_SENTINEL_MASTER 未设置}"
: "${REDIS_INITIAL_MASTER_IP:?REDIS_INITIAL_MASTER_IP 未设置}"
: "${REDIS_PASSWORD:?REDIS_PASSWORD 未设置}"
: "${REDIS_SENTINEL_PASSWORD:?REDIS_SENTINEL_PASSWORD 未设置}"
: "${REDIS_ANNOUNCE_IP:?REDIS_ANNOUNCE_IP 未设置}"

REDIS_MASTER_PORT="${REDIS_MASTER_PORT:-6379}"
REDIS_SENTINEL_QUORUM="${REDIS_SENTINEL_QUORUM:-2}"
SENTINEL_PORT="${SENTINEL_PORT:-26379}"

CONF=/data/sentinel.conf

if [ ! -f "$CONF" ]; then
  cat > "$CONF" <<EOF
# 本文件由 sentinel-entrypoint.sh 首次启动时生成；
# 运行期哨兵会自行重写它来记录当前拓扑，请勿手工编辑。
port ${SENTINEL_PORT}
dir /data

# 关闭保护模式：哨兵在容器内运行，容器网络与宿主之间存在 NAT，
# 开着保护模式会把哨兵之间的合法通信也拒掉。
protected-mode no

# 监控的主节点。地址必须是【宿主 IP】，不能用容器名：
# 哨兵在切换后要把「新主地址」广播给其它哨兵与客户端，
# 容器内网地址出了这台机器就不可路由。
sentinel monitor ${REDIS_SENTINEL_MASTER} ${REDIS_INITIAL_MASTER_IP} ${REDIS_MASTER_PORT} ${REDIS_SENTINEL_QUORUM}
sentinel auth-pass ${REDIS_SENTINEL_MASTER} ${REDIS_PASSWORD}

# 5s 判主观下线：与后端连接池的失败感知时间量级匹配，太快会因一次 GC 停顿误判
sentinel down-after-milliseconds ${REDIS_SENTINEL_MASTER} 5000
# 60s 完成一次切换：给足从节点完成全量同步的时间，避免「切换完成但数据未就绪」
sentinel failover-timeout ${REDIS_SENTINEL_MASTER} 60000
# 同一时刻只允许 1 个从节点做同步，避免新主被多个全量同步打满
sentinel parallel-syncs ${REDIS_SENTINEL_MASTER} 1

# 上报本哨兵的地址（宿主 IP + 对外端口）
sentinel announce-ip ${REDIS_ANNOUNCE_IP}
sentinel announce-port ${SENTINEL_PORT}

# 禁止通过 SENTINEL SET 在运行期改写脚本相关配置：
# 哨兵能执行本机脚本，是「能连上哨兵就等于能执行命令」的经典放大面
sentinel deny-scripts-reconfig yes

# 哨兵自身口令：否则任何能连到 26379 的人都能命令它切换主从
requirepass ${REDIS_SENTINEL_PASSWORD}
EOF
  chmod 600 "$CONF"
  echo "[sentinel] 首次生成配置：${CONF}（初始主 ${REDIS_INITIAL_MASTER_IP}:${REDIS_MASTER_PORT}，quorum=${REDIS_SENTINEL_QUORUM}）"
else
  echo "[sentinel] 复用已有配置（保留运行期拓扑）：${CONF}"
fi

exec redis-sentinel "$CONF"
