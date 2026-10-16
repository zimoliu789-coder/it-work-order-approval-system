# =====================================================================
# 前端镜像（规范 §30：Docker Compose 跨平台部署）
#
# 多阶段构建：node 构建静态产物 → nginx:alpine 只负责静态托管。
# 产物是纯静态文件，运行期不需要 Node，最终镜像约 50MB。
#
# 说明：本容器**只托管静态资源**，不转发 /api。
#       /api 由边缘 Nginx（deploy/nginx）直接代理到后端容器，
#       这样「前端静态资源」与「后端接口」的路由规则集中在一处，不跨两层容器传递。
#
# 构建：docker compose build frontend   （在 deploy/ 目录执行）
# =====================================================================

# ---------------- 第一阶段：构建 ----------------
# ⚠️ 必须带 `--platform=$BUILDPLATFORM`。
#
# 本阶段是纯「编译」，产物（dist/ 静态文件）与 CPU 架构**无关**；但如果不加这一行，
# 多架构构建（CI 里 platforms: linux/amd64,linux/arm64）会让 `npm ci` + `vite build`
# 在 arm64 那一份上**走 QEMU 模拟**执行 —— 前端构建是整套流水线里最费 CPU 的一环，
# 模拟执行会让它慢一个数量级，还极易卡死（2026-10-15 就有一个前端 job 卡了 1.5 小时）。
#
# 加上之后：构建只在宿主原生架构（amd64）跑**一次**，arm64 只重做最后那个
# nginx 静态层（秒级）。既保住 ARM 群晖的兼容性，又不付模拟的代价。
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /build

# 先只拷依赖清单并安装：只要锁文件不变，改业务代码不会重新装依赖
COPY package.json package-lock.json ./
# 用 ci 而不是 install：严格按锁文件安装，避免 NAS 上构建出与开发机不同的依赖版本
RUN npm ci --no-audit --no-fund

COPY . .
# 生产变量来自 .env.production（VITE_API_BASE_URL=/api、VITE_USE_MOCK=false）
RUN npm run build

# ---------------- 第二阶段：运行 ----------------
FROM nginx:1.27-alpine
LABEL org.opencontainers.image.title="ticket-system-frontend" \
      org.opencontainers.image.description="企业内部设备借用工单系统 - 前端静态站"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
