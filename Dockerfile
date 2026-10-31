# Production image for NEPSE Top Turnover analysis. Builds the jar from source, so the server needs only Docker.
# Build context is the repository root: docker compose up -d --build

# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Download dependencies first, so a code-only change reuses this layer.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests

COPY src src
RUN mvn -B -q package -DskipTests

# ---- Run ----
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="NEPSE Top Turnover analysis"

ENV TZ=Asia/Kathmandu \
    SERVER_PORT=8083 \
    JAVA_OPTS=""

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system nepse && useradd --system --gid nepse --home-dir /app nepse \
    && mkdir -p /app/config /app/logs && chown -R nepse:nepse /app

WORKDIR /app
COPY --from=build /src/target/nepse-topturnover.jar /app/nepse-topturnover.jar
# Market holidays (read from config/holidays.txt relative to /app); compose can mount an edited copy over it.
COPY --chown=nepse:nepse config/holidays.txt /app/config/holidays.txt
USER nepse

EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health/liveness" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/nepse-topturnover.jar"]
