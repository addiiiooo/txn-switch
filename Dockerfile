# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
# Dependencies resolve in their own layer, before any source is copied, so a code
# change does not re-download the world.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline -DskipTests

COPY src/ src/
# Tests need a database and run in CI, not here. Formatting is checked in CI too.
RUN ./mvnw -B -q package -DskipTests -Dspotless.check.skip=true \
    && java -Djarmode=tools -jar target/txn-switch-*.jar extract --layers --launcher --destination extracted

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Runs as nobody in particular, on purpose.
RUN addgroup -S txnswitch && adduser -S txnswitch -G txnswitch
WORKDIR /app

# Ordered least- to most-frequently-changed, so a code change rebuilds one small layer.
COPY --from=build --chown=txnswitch:txnswitch /build/extracted/dependencies/ ./
COPY --from=build --chown=txnswitch:txnswitch /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=txnswitch:txnswitch /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=txnswitch:txnswitch /build/extracted/application/ ./

USER txnswitch
EXPOSE 8080

# The container, not the host, decides the heap: MaxRAMPercentage respects the cgroup
# limit, so shrinking the container shrinks the heap with it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Readiness, not liveness: the container is healthy when it can actually serve, which
# is what compose waits for.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
