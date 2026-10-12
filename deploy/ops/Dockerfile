# =====================================================================
# 运维代理（ops-agent）镜像（V49 · 需求 2/3）
#
# 它替管理员做两件「后端做不到」的事：
#   ① 首次部署：在整套服务起来之前提供一个网页安装向导 —— Spring Boot 启动时就要
#      确定 DataSource，而主 compose 里 MYSQL_ROOT_PASSWORD 是 ${VAR:?}（缺失即拒绝解析），
#      所以向导**不可能**跑在应用自己的启动路径上，必须由一个独立容器承担；
#   ② 在线升级：执行 docker compose pull + up -d —— 后端不能重建自己
#      （up -d 会把正在执行命令的那个容器杀掉，栈会停在半更新状态）。
#
# 依赖只有四项，且全部来自 alpine 官方仓库（无 pip / 无 npm ⇒ 装不上包的风险为零）：
#   python3            仅用标准库实现 HTTP 服务与文件轮询
#   docker-cli         调用 Docker API
#   docker-cli-compose compose 插件（pull / up）
#   bash curl tzdata   引导脚本与健康轮询
#
# 构建：docker build -t <镜像> deploy/ops
# =====================================================================
FROM alpine:3.20

LABEL org.opencontainers.image.title="ticket-system-ops-agent" \
      org.opencontainers.image.description="企业内部设备借用工单系统 - 运维代理（安装向导 / 在线升级执行）"

RUN apk add --no-cache python3 docker-cli docker-cli-compose bash curl tzdata \
 && addgroup -g 10001 -S ops \
 && adduser -u 10001 -S -G ops -h /opt/ops ops

WORKDIR /opt/ops
COPY agent.py ./agent.py
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh && chown -R ops:ops /opt/ops

ENV TZ=Asia/Shanghai \
    OPS_PORT=8090 \
    OPS_UI_ENABLED=true \
    DEPLOY_DIR=/deploy \
    OPS_SHARED_DIR=/deploy/ops-data \
    COMPOSE_PROJECT_NAME=ticket-system

EXPOSE 8090

# 以非 root 运行。它仍然持有 docker.sock（等价于宿主 root）——
# 这一点无法靠容器内权限缓解，只能靠「不对外暴露 + 装完即失效 + 白名单命令」三件事收窄，
# 见 deploy/README.md 的「运维代理与 docker.sock」一节。
USER 10001:10001

ENTRYPOINT ["/opt/ops/entrypoint.sh"]
