# syntax=docker/dockerfile:1
# 在仓库根目录构建：docker build -t unicorn-backend:latest ./backend

# 使用 pom.xml 指定的 Java 21，通过 jar profile 仅打包主 application.yml。
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Pjar -DskipTests clean package \
    && cp target/unicorn-studio-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

ENV TZ=Asia/Shanghai

# app.backup.mysqldump-path=mysqldump；curl 用于容器内健康检查。
RUN apt-get update \
    && apt-get install -y --no-install-recommends mysql-client curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 unicorn \
    && useradd --system --uid 10001 --gid unicorn \
        --home-dir /app --shell /usr/sbin/nologin unicorn \
    && mkdir -p /app/data/uploads /app/data/backups /app/logs \
    && chown -R unicorn:unicorn /app/data /app/logs

COPY --from=build --chown=unicorn:unicorn /build/app.jar /app/app.jar

USER unicorn:unicorn

# application.yml 中 ${user.dir}=/app，上传、备份与日志均落在以下挂载点。
VOLUME ["/app/data", "/app/logs"]
EXPOSE 8080

# management.server 保持 YAML 的 127.0.0.1:8081，通过容器内部探测。
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "4", "http://127.0.0.1:8081/actuator/health"]

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]

# Linux 服务器：保留 YAML 中的回环地址，MySQL、Redis、nginx 均在宿主机运行。
# host 网络共享宿主机网络，无需 -p；nginx 继续反代 127.0.0.1:8080。
# docker run -d --name unicorn-backend --network host \
#   --restart unless-stopped --stop-timeout 70 \
#   -v unicorn-data:/app/data -v unicorn-logs:/app/logs \
#   unicorn-backend:latest
#
# 使用桥接网络或 Docker Desktop 时，需要运行期覆盖：
#   SERVER_ADDRESS=0.0.0.0
#   SPRING_DATASOURCE_URL=jdbc:mysql://<可达的数据库地址>:3306/unicorn_studio?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
#   SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD
#   SPRING_DATA_REDIS_HOST、SPRING_DATA_REDIS_PORT、SPRING_DATA_REDIS_PASSWORD
# 然后用 -p 127.0.0.1:8080:8080 映射业务端口；管理端口仍通过 docker exec 访问。
# Docker Desktop 上访问宿主机可用 host.docker.internal，其他容器可用同网络的服务名。
# 如改用宿主机目录挂载，先确保 data 与 logs 目录对 UID/GID 10001 可写。
