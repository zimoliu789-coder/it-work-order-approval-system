#!/bin/sh
# =====================================================================
# 部署后冒烟验证（ 限流 /  备份告警 /  部署）
#
# 用途：容器起来之后，用一条命令确认「限流加固」与「内部告警通道」在真实链路上生效，
#       而不是等到出事那天才发现某一层根本没装上。
#
# 用法（在 deploy 目录下执行）：
#   bash scripts/smoke-test.sh                     # 默认 http://127.0.0.1:${WEB_PORT:-8080}
#   bash scripts/smoke-test.sh https://ticket.example.com
#   bash scripts/smoke-test.sh --notify            # 额外演练「备份失败→超管站内消息」全链路
#
# 退出码：0 = 全部通过；1 = 存在失败项
#
# 说明：
#   - 第 3 项会用垃圾账号连续请求登录，故意触发限流；不会影响任何真实账号
#     （限流命中早于失败计数锁定：账号维度 3 次/分钟先于 5 次锁定生效）。
#   - --notify 会给全部超管真实推送一条站内消息，仅在需要验证告警链路时使用。
# =====================================================================
set -u

RELEASE_NOTIFY="false"
BASE=""
for arg in "$@"; do
  case "$arg" in
    --notify) RELEASE_NOTIFY="true" ;;
    http*://*) BASE="$arg" ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

# 从 .env 读取端口与内部令牌（存在则用，不存在则用默认值）
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi
: "${WEB_PORT:=8080}"
: "${INTERNAL_ALERT_TOKEN:=}"
[ -n "$BASE" ] || BASE="http://127.0.0.1:${WEB_PORT}"

command -v curl >/dev/null 2>&1 || { echo "缺少 curl，无法执行冒烟" >&2; exit 1; }

# --noproxy '*' 是必须的：一旦环境里存在 HTTP(S)_PROXY，curl 会把本机地址也走代理，
# 表现为「健康检查空响应」，而不是明确的连接错误，排查成本很高。
cget() { curl -s --noproxy '*' "$@"; }

PASS=0
FAIL=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS + 1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL + 1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$*"; }

printf '\033[1m========== 设备借用工单系统 · 部署后冒烟 ==========\033[0m\n'
printf '目标：%s\n' "$BASE"

# ---------------------------------------------------------------------
head_ "1. 健康检查与真实客户端 IP 识别"
# ---------------------------------------------------------------------
HEALTH="$(cget "$BASE/api/health" 2>/dev/null)"
if [ -z "$HEALTH" ]; then
  bad "健康检查无响应（后端未就绪 / 端口不通 / 代理干扰）"
else
  echo "$HEALTH" | grep -q '"status":"UP"' && ok "健康检查 UP" || bad "健康检查未返回 UP：$HEALTH"

  for FIELD in clientIp scheme trustedProxyRules; do
    echo "$HEALTH" | grep -q "\"$FIELD\"" && ok "响应含 $FIELD" || bad "响应缺少 $FIELD（部署不完整）"
  done

  echo "$HEALTH" | grep -q '"trustedProxyRules":0' \
    && bad "trustedProxyRules=0：TRUSTED_PROXIES 为空，转发头一律不被采信（限流 IP 维度会失真）" \
    || ok "可信代理网段已配置"

  echo "$HEALTH" | grep -q '"scheme":"https"' \
    && ok "外层反代已正确传递 X-Forwarded-Proto（scheme=https）" \
    || printf '  \033[33m!\033[0m scheme 非 https —— 若本次为直连测试可忽略；经反代访问时必须为 https\n'
fi

# ---------------------------------------------------------------------
head_ "2. 内部告警通道鉴权（）"
# ---------------------------------------------------------------------
CODE="$(cget -o /dev/null -w '%{http_code}' -X POST "$BASE/api/internal/alerts/backup" \
  -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
  -d '{"status":"SUCCESS"}' 2>/dev/null)"
case "$CODE" in
  401|403) ok "无令牌被拒绝（HTTP $CODE）" ;;
  *) bad "无令牌竟返回 HTTP $CODE —— 内部告警通道存在未授权访问风险" ;;
esac

if [ -n "$INTERNAL_ALERT_TOKEN" ]; then
  if [ "$RELEASE_NOTIFY" = "true" ]; then
    BODY='{"status":"FAILED","fileName":"smoke-test.tar.gz","error":"部署后冒烟演练","host":"smoke","retentionDays":30}'
    NOTE="（已向超管推送一条真实告警）"
  else
    BODY='{"status":"SUCCESS","fileName":"smoke-test.tar.gz","sizeBytes":1024,"host":"smoke","retentionDays":30}'
    NOTE="（成功态不推消息；如需演练告警请加 --notify）"
  fi
  RESP="$(cget -w '\nHTTP %{http_code}' -X POST "$BASE/api/internal/alerts/backup" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -H "X-Internal-Token: $INTERNAL_ALERT_TOKEN" -d "$BODY" 2>/dev/null)"
  echo "$RESP" | grep -q 'HTTP 200' && ok "正确令牌被接受 $NOTE" || bad "正确令牌被拒绝：$RESP"
else
  printf '  \033[33m!\033[0m 未提供 INTERNAL_ALERT_TOKEN，跳过令牌正确性校验\n'
fi

# ---------------------------------------------------------------------
head_ "3. 登录限流（同账号 3 次/分钟 · 同 IP 5 次/分钟）"
# ---------------------------------------------------------------------
SEEN_429="false"
RETRY_AFTER=""
i=1
while [ "$i" -le 6 ]; do
  HEAD="$(cget -D - -o /dev/null -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d '{"username":"smoke_rate_probe","password":"invalid"}' 2>/dev/null | tr -d '\r')"
  if echo "$HEAD" | grep -q '429'; then
    SEEN_429="true"
    RETRY_AFTER="$(echo "$HEAD" | grep -i '^retry-after:' | head -n 1 | sed 's/.*: *//')"
    printf '    第 %s 次 → 429（Retry-After: %s）\n' "$i" "${RETRY_AFTER:-未提供}"
  fi
  i=$((i + 1))
done
[ "$SEEN_429" = "true" ] && ok "连续错误登录已触发 429" || bad "连续 6 次错误登录仍未限流（ 未生效）"
[ -n "$RETRY_AFTER" ] && ok "429 携带 Retry-After 响应头" || bad "429 缺少 Retry-After（前端无法提示等待秒数）"

# ---------------------------------------------------------------------
head_ "4. 速率限制事件留痕"
# ---------------------------------------------------------------------
printf '  提示：本次冒烟产生的 429 应可在「操作日志」中按模块 AUTH / 动作「登录限流触发」检索到；\n'
printf '        模块「运维作业」下应有一条「备份作业告警上报」。\n'

# ---------------------------------------------------------------------
printf '\n\033[1m================== 冒烟结论 ==================\033[0m\n'
printf '  通过：%s 项\n' "$PASS"
printf '  失败：%s 项\n' "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '\n\033[31m存在失败项，请按上方提示排查后再上线\033[0m\n'
  exit 1
fi
printf '\n\033[32m全部通过\033[0m\n'
exit 0
