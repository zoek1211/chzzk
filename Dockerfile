# syntax=docker/dockerfile:1

# ---- 빌드: JDK 25 + Gradle 래퍼로 bootJar ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*[!n].jar app.jar

# ---- 실행: JRE 25, 비루트 ----
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 chzzk \
    && useradd --system --uid 10001 --gid chzzk --home-dir /app --shell /usr/sbin/nologin chzzk \
    && mkdir -p /app /data \
    && chown chzzk:chzzk /app /data
WORKDIR /app
COPY --from=build --chown=chzzk:chzzk /workspace/app.jar app.jar
USER chzzk
# 영상·단독 모드 H2 파일이 놓이는 곳. 클러스터에서는 공유 PVC를 여기에 마운트한다.
ENV CHZZK_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
