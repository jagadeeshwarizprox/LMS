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
COPY --from=build /src/target/pib-lms-1.0.0.jar app.jar
# FILES_DIR points here; on Railway this path is a mounted Volume
RUN mkdir -p /data/files
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]