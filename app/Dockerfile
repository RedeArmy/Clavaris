# Multi-stage build — the build stage's Maven cache/JDK never ships in the
# final image, keeping the runtime image small and free of build tooling
# (smaller image = smaller attack surface, consistent with this project's
# security-first posture).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the whole multi-module reactor — module interdependencies mean we
# can't build "app" in isolation from a single Dockerfile context.
COPY pom.xml .
COPY common ./common
COPY identity-module ./identity-module
COPY organization-module ./organization-module
COPY client-registry-module ./client-registry-module
COPY app ./app

# No Maven wrapper committed yet — installs Maven directly in
# the build stage instead. Revisit in favor of ./mvnw once the wrapper exists,
# for a pinned Maven version instead of "whatever apt currently ships."
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B -pl app -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is not present in the base JRE image — installed explicitly and only
# for the HEALTHCHECK below, before dropping to a non-root user.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root inside the container. /app must be writable by this user, not just
# root-owned-and-readable — TD-SEC-002's SigningKeyStore writes its PKCS12 file under
# /app/data (docker-compose.yml's own volume mount) at runtime, as this user, not at build time.
RUN useradd --system --uid 10001 clavaris \
    && mkdir -p /app/data \
    && chown -R clavaris:clavaris /app
USER clavaris

COPY --from=build /workspace/app/target/clavaris-app.jar app.jar

EXPOSE 8080

# Docker-level healthcheck mirrors docker-compose.yml's own definition — kept
# here too so `docker run` alone (outside compose) still self-reports health.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
