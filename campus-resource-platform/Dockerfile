FROM eclipse-temurin:17.0.20_8-jdk-alpine-3.24@sha256:27914526e62114707ca237c837bfda43277f182934db5c484281894163101c2c AS builder

WORKDIR /workspace

# 先复制依赖描述并预取 Maven 依赖，让业务源码变化可以复用依赖层缓存。
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY src src
RUN sh ./mvnw -B -ntp -DskipTests package \
    && cp target/campus-resource-platform-*.jar /workspace/app.jar

FROM eclipse-temurin:17.0.20_8-jre-alpine-3.24@sha256:45d19def67191d1df7d233282051ac2f6908c6b2d7eaef75441d7e7c721c6d01

# 生产进程使用非 root 用户；上传目录需在切换用户前创建并授权。
RUN addgroup -S app \
    && adduser -S -G app app \
    && mkdir -p /app /data/uploads \
    && chown -R app:app /app /data/uploads

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/app.jar ./app.jar

ENV APP_UPLOAD_STORAGE_PATH=/data/uploads \
    JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError"

USER app
EXPOSE 8080

# 就绪探针同时检查 MySQL、Redis 和上传存储；HTTP 503 会使 wget 返回非零状态。
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/api/v1/health/readiness >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
