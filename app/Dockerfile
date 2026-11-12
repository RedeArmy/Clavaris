# syntax=docker/dockerfile:1
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
COPY webhook-module ./webhook-module
COPY app ./app

# TD-ARCH-006: Maven Wrapper, not `apt-get install maven` — that installed whatever version apt's
# own package index currently shipped, silently, with no record of which version actually built any
# given image. .mvn/wrapper/maven-wrapper.properties pins an exact Maven version (downloaded once,
# from Apache's own repo, and cached in the build stage's own layer) — the same guarantee this
# project already applies to the JDK (eclipse-temurin:25-*) and every Maven dependency (a pinned
# version in pom.xml), now applied to the build tool itself.
# TD-PROC-011: the build stage's own ~/.m2 (Maven's default local repo) is a BuildKit cache
# mount, not a plain layer — ci.yml's own docker-build job already builds this image via
# docker/build-push-action with the GHA cache backend (cache-from/cache-to: type=gha), which
# persists this mount's contents across CI runs on an otherwise-ephemeral runner, closing the
# "every CI run does a full, cold Maven dependency resolution inside the image build, completely
# isolated from build-and-test's own actions/setup-java cache" gap that row names. Build-stage
# only — never ships in the final image either way.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -pl app -am -DskipTests package

# TD-PERF-063: split the jar into the layers Spring Boot already describes in its layers.idx:
# "dependencies" (about 107 MB of third-party jars that change only when pom.xml does),
# "spring-boot-loader" and "snapshot-dependencies" (empty here), and "application" (about 3 MB: the
# app jar and this reactor's own module jars, which change with every commit). Shipped as one
# `COPY app.jar` the image replaced a layer of about 109 MB on every deploy; as four, a deploy that
# changes only code pulls the last one. The pre-production VM's runner was lost twice on
# 2026-11-11, once while extracting that big layer.
RUN java -Djarmode=tools -jar app/target/clavaris-app.jar extract --layers --destination /workspace/extracted

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is not present in the base JRE image — installed explicitly and only for the HEALTHCHECK
# below, before dropping to a non-root user. ca-certificates: standard baseline for any outbound
# TLS this container's own tooling might make, kept even though today's only real caller (curl,
# HEALTHCHECK) only ever hits plain http://localhost — cheap, conventional hygiene, not load-bearing
# for anything specific.
#
# ADR-0019: this block used to also install the Infisical CLI (ADR-0014, self-hosted secrets
# manager) — removed 2026-08-28 once ADR-0018 decided .env, not Infisical, is the real production
# path, leaving that install's own 7 accepted CVEs (TD-SEC-024) and its gnupg/apt-transport-https
# apt-repo prerequisites paid on every image build for a path no real deployment ever exercised.
# See ADR-0019 for the full removal reasoning.
# CVE-2026-84782 (openssl, HIGH — information disclosure via DTLS handshake retransmission),
# caught by CI's own Trivy gate against the base image's bundled libssl3t64/openssl/
# openssl-provider-legacy (3.5.5-1ubuntu3.5, fixed in 3.5.5-1ubuntu3.6) — not a package this
# Dockerfile installs itself, Ubuntu 26.04's own base image ships it. --only-upgrade targets
# exactly these three packages against the apt lists already freshly fetched by the update
# above, rather than a blanket dist-upgrade that could pull in unrelated, unverified package
# changes — same "smallest safe fix" precedent pom.xml's own CVE comments already establish.
# CVE-2026-109518 (apt and libapt-pkg7.0, HIGH — an on-path attacker against apt's HTTP handling on
# Debian and Ubuntu), caught the same way by CI's Trivy gate: the base image ships 3.2.0 and the
# fix is 3.2.1ubuntu0.1. Both packages are upgraded by name and nothing else, for the reason above.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates \
    && apt-get install -y --only-upgrade \
        libssl3t64 openssl openssl-provider-legacy apt libapt-pkg7.0 \
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

# Least often changing first, so Docker's layer cache (and the registry) reuse the big one.
COPY --from=build --chown=clavaris:clavaris /workspace/extracted/dependencies/ ./
COPY --from=build --chown=clavaris:clavaris /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=clavaris:clavaris /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=clavaris:clavaris /workspace/extracted/application/ ./
COPY --chown=clavaris:clavaris app/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 8080

# Docker-level healthcheck mirrors docker-compose.yml's own definition — kept
# here too so `docker run` alone (outside compose) still self-reports health.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
