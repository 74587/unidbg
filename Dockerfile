# 使用 Distroless Java 25
FROM gcr.io/distroless/java25-debian13:latest

WORKDIR /app

# 设置时区
ENV TZ=Asia/Shanghai
ENV LOG_DIR=/tmp/logs

# 复制 jar 文件
COPY target/fqnovel.jar /app/fqnovel.jar

# 暴露端口
ENV SERVER_PORT=9999
EXPOSE 9999

# JVM 参数（可通过环境变量覆盖）
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

# 启动应用
ENTRYPOINT ["java", "-jar", "/app/fqnovel.jar"]
