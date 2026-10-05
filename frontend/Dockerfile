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
FROM node:22-alpine AS build
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
