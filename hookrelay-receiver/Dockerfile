# Build from the repository root: docker build -f hookrelay-receiver/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY hookrelay-receiver hookrelay-receiver
COPY hookrelay-server hookrelay-server
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl hookrelay-receiver -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home receiver
WORKDIR /app
COPY --from=build /src/hookrelay-receiver/target/hookrelay-receiver-0.1.0-SNAPSHOT-exec.jar app.jar
USER 10001
EXPOSE 9000
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:9000/received > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
