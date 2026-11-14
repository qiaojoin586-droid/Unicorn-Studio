# 默认固定 Ubuntu 24.04；也可通过 --build-arg 复用已确认兼容的本地镜像。
ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jdk-noble
FROM ${JAVA_BASE_IMAGE}

# 备份由后端进程调用 mysqldump；宿主机或数据库容器中的客户端不可供它直接执行。
# 使用发行版提供的 mysql-client 包，兼容 Ubuntu 22.04/24.04 的 8.0 和 26.04 的 8.4。
# 构建时检查客户端可运行，避免缺少二进制或动态库的镜像进入部署。
RUN apt-get update \
    && apt-get install -y --no-install-recommends mysql-client \
    && mysqldump --version \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 先运行 Maven package；本地 .env 和源码不进入构建上下文。
COPY target/*.jar /app/app.jar
RUN mkdir -p /app/logs /app/data/uploads /app/data/backups

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
