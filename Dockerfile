FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

# 运行时如果 /app 下存在 application.yml，会优先使用（否则使用 jar 内置默认配置）
COPY target/fqnovel.jar /app/fqnovel.jar

ENV SERVER_PORT=9999
EXPOSE 9999

ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/fqnovel.jar"]

