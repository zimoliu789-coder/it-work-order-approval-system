# =====================================================================
# 后端镜像（规范 §30：Docker Compose 跨平台部署）
#
# 多阶段构建：第一阶段用 Maven + JDK21 编译，第二阶段只保留 JRE + jar。
# 为什么在镜像内编译，而不是「宿主先 mvn package 再把 jar 拷进来」：
#   群晖 NAS 上既没有 JDK 也没有 Maven，只有 Container Manager。
#   镜像内多阶段构建才能做到「宿主机装好 Docker 就能部署」，无需在 NAS 上装构建链。
#
# 构建：docker compose build backend     （在 deploy/ 目录执行）
# =====================================================================

# ---------------- 第一阶段：构建 ----------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom.xml 预热依赖：只要 pom 不变，这一层就命中缓存，
# 后续只改 Java 代码不会触发重新下载全部依赖（NAS 上第一次构建的耗时主要在这里）
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
# 跳过测试：单元测试依赖 Spring 上下文与 Redis，属于宿主 CI 的职责；
# 镜像构建只保证「能编译出可运行产物」，否则 NAS 上每次构建都要跑满测试套件
RUN mvn -B -DskipTests package

# ---------------- 第二阶段：运行 ----------------
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="ticket-system-backend" \
      org.opencontainers.image.description="企业内部设备借用工单系统 - 后端服务"

RUN apk add --no-cache curl tzdata \
 && addgroup -g 10001 -S app \
 && adduser -u 10001 -S -G app -h /app app

WORKDIR /app
COPY --from=build /build/target/ticket-system.jar ./app.jar

# 日志 / 附件 / 导出目录统一挂在 DATA_ROOT 之下（见 docker-compose.yml 的卷映射）。
# 非 root 运行是本阶段的加固要求之一：以 uid 10001 运行，
# 一旦应用被攻破，影响面限于这些挂载目录，而不是整个容器根文件系统。
# 注意：宿主上的这些目录必须属于 10001:10001，否则应用无法写日志 —— 用 deploy/scripts/preflight.sh 准备目录。
RUN mkdir -p /data/logs /data/attachments /data/exports \
 && chown -R app:app /data /app

USER 10001:10001
EXPOSE 8080

ENV TZ=Asia/Shanghai \
    LOG_PATH=/data/logs \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

# 健康探针直接打业务健康接口（该接口免认证且不参与限流）：
# 只有 Flyway 迁移完成、Redis/MySQL 都连通才可能返回 200，
# 因此它同时充当「依赖就绪」判定，compose 的 depends_on 条件依赖此处
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
