# Production image for Sunrise ERP. Builds the jar from source, so the droplet needs only Docker.
# Build context is the repository root: docker compose build

# ---- Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Download dependencies first, so a code-only change reuses this layer.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY core/pom.xml core/pom.xml
COPY api-erp/pom.xml api-erp/pom.xml
COPY logging/pom.xml logging/pom.xml
#RUN #chmod +x mvnw && ./mvnw -B -q -pl api-erp -am dependency:go-offline -DskipTests || true
# Download Maven dependencies
#RUN mvn -q -e -DskipTests dependency:go-offline

COPY core core
COPY api-erp api-erp
COPY logging logging
# -Dexec.skip skips the bill-calc.js test, which needs Node (not in this image).
RUN sh ./mvnw -B -pl api-erp -am package -DskipTests -Dexec.skip=true

# ---- Run ----
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Sunrise ERP"

ENV TZ=Asia/Kathmandu \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="" \
    SERVER_PORT=8082

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system erp && useradd --system --gid erp --home-dir /app erp \
    && mkdir -p /app/data/files && chown -R erp:erp /app

WORKDIR /app
COPY --from=build /src/api-erp/target/api-erp.jar /app/api-erp.jar
USER erp

EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS http://localhost:8082/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/api-erp.jar"]
