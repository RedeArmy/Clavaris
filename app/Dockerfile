# Multi-stage build — the build stage's Maven cache/JDK never ships in the
# final image, keeping the runtime image small and free of build tooling
# (smaller image = smaller attack surface, consistent with this project's
# security-first posture).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the whole multi-module reactor — module interdependencies mean we
# can't build "app" in isolation from a single Dockerfile context.
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw .
COPY common ./common
COPY identity-module ./identity-module
COPY organization-module ./organization-module
COPY client-registry-module ./client-registry-module
COPY app ./app

# TD-ARCH-006: Maven Wrapper, not `apt-get install maven` — that installed whatever version apt's
# own package index currently shipped, silently, with no record of which version actually built any
# given image. .mvn/wrapper/maven-wrapper.properties pins an exact Maven version (downloaded once,
# from Apache's own repo, and cached in the build stage's own layer) — the same guarantee this
# project already applies to the JDK (eclipse-temurin:25-*) and every Maven dependency (a pinned
# version in pom.xml), now applied to the build tool itself.
RUN chmod +x mvnw && ./mvnw -B -pl app -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is not present in the base JRE image — installed explicitly and only
# for the HEALTHCHECK below, before dropping to a non-root user.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# TD-SEC-021: the base Ubuntu 26.04 image (eclipse-temurin:25-jre's own base, confirmed by pulling
# and inspecting it directly — not a package apt/dpkg knows about, so `apt-get remove` can't touch
# it) ships /usr/bin/pebble, Let's Encrypt's ACME *test*-CA server binary, unused by this
# application. Live-confirmed via a real Trivy scan: 8 HIGH-severity, fix-available CVEs in its
# bundled Go stdlib/x/net, all fully avoidable — Clavaris never runs an ACME test server, so this
# is pure unnecessary attack surface, not a dependency of anything here. Deleted outright rather
# than accepted or suppressed.
RUN rm -rf /usr/bin/pebble /var/lib/pebble

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
