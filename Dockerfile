FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

# 默认使用中国时区（可通过运行时设置 TZ 覆盖）
ENV TZ=Asia/Shanghai
RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata ca-certificates bash grep \
  && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
  && echo $TZ > /etc/timezone \
  && rm -rf /var/lib/apt/lists/*

# 运行时如果 /app 下存在 application.yml，会优先使用（否则使用 jar 内置默认配置）
COPY target/fqnovel.jar /app/fqnovel.jar

ENV SERVER_PORT=9999
EXPOSE 9999

ENV JAVA_OPTS=""
# 过滤 unidbg/metasec 相关噪音（会导致 Docker 日志快速膨胀）；如需关闭：设置环境变量 FQ_FILTER_NATIVE_NOISE=false
ENTRYPOINT ["bash","-lc","FILTER=${FQ_FILTER_NATIVE_NOISE:-true}; if [ \"$FILTER\" = \"false\" ]; then exec java $JAVA_OPTS -jar /app/fqnovel.jar; else exec java $JAVA_OPTS -jar /app/fqnovel.jar 2> >(grep -Ev 'E/METASEC:|MSTaskManager::DoLazyInit\\(\\)|SDK not init, crashing' >&2); fi"]
