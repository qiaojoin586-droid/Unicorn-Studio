# 复用服务器已有的 Java 21 JDK 镜像，其中包含运行 JAR 所需的环境。
FROM eclipse-temurin:21-jdk

WORKDIR /app
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 先运行 Maven package；本地 .env 和源码不进入构建上下文。
COPY target/*.jar /app/app.jar
RUN mkdir -p /app/logs /app/data/uploads

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
