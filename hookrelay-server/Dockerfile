# Build from the repository root: docker build -f hookrelay-server/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY hookrelay-receiver hookrelay-receiver
COPY hookrelay-server hookrelay-server
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl hookrelay-server -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home hookrelay
WORKDIR /app
COPY --from=build /src/hookrelay-server/target/hookrelay-server-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
