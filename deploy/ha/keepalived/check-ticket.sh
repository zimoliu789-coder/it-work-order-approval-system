#!/bin/sh
# =====================================================================
# keepalived 健康检查脚本
#
# 由 keepalived 作为 track_script 每 5 秒调用一次：
#   退出 0 = 本节点健康（保持 / 提升优先级）
#   退出 1 = 本节点不健康（降低优先级，让对端接管 VIP）
#
# 探测路径：经本机边缘 Nginx 打后端的 /api/health。
#   为什么不分别探测「Nginx 活着」与「后端活着」：
#   这条链路才是用户实际走的路。Nginx 活着而后端已死时，用户拿到的是 503，
#   此时若不切换，反倒是最该切却没切的情形。
#
# 端口来源：/etc/keepalived/ticket-ha.env（由 install-keepalived.sh 生成），
#   缺省回落 8080。
# =====================================================================

# 注意：keepalived 以 root 身份、在受限环境下执行本脚本，
# 因此这里不依赖任何用户级 profile，也不做交互输出。
if [ -f /etc/keepalived/ticket-ha.env ]; then
  # shellcheck disable=SC1091
  . /etc/keepalived/ticket-ha.env
fi

# 维护标记：存在 /etc/keepalived/MAINT 时一律判不健康。
# 用途是计划内维护与切换演练 —— 让本节点「主动让位」给对端，
# 而不用停掉 keepalived。这样演练的是完整判定链路（含降权与漂移），
# 而不是一个「进程消失」的特例。见 scripts/switchover.sh。
if [ -f /etc/keepalived/MAINT ]; then
  exit 1
fi

PORT="${WEB_PORT:-8080}"

# -f：HTTP 非 2xx/3xx 即失败（后端未就绪时 /api/health 会返回 503）
# -s：静默，不把响应体打到 keepalived 日志里
# --max-time 3：必须小于 keepalived 的 timeout(4)，否则脚本会被强杀而非正常返回
#   —— 被强杀时 keepalived 也判失败，但日志里只会留下「脚本超时」，
#      看不到真正的失败原因，非常不利于排障。
curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1
