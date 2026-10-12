# Build and run in one file. The dependency layer is cached separately from the
# source, so a code change does not re-download the world.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# curl is only for the health check below
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/pib-lms-1.0.0.jar app.jar
# FILES_DIR points here; on Railway this path is a mounted Volume
RUN mkdir -p /data/files
# Heap is a share of the container's memory limit, so set mem_limit in compose
# (3g on an 8 GB server, 6g on 16 GB) and the heap follows. Without a limit the JVM
# sizes itself from the whole host's RAM and competes with Mongo's cache.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"
ENV FILES_DIR=/data/files
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://127.0.0.1:${PORT:-8080}/api/health || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]