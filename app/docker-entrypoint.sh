#!/bin/sh
# ADR-0019: single, unconditional entrypoint — every secret this project has comes from
# process env vars (docker-compose.yml's/docker-compose.prod.yml's own required-`${VAR:?...}`
# pattern), the same TD-SEC-013 "no silent default" posture every other secret already follows.
# Previously dual-mode (ADR-0014, self-hosted Infisical) — removed 2026-08-28 (ADR-0019) once
# ADR-0018 decided .env is the real production path, leaving that second path built but never
# actually exercised by any real deployment. See ADR-0019 for the full removal reasoning.
set -eu

# TD-PERF-007: previously no -Xmx at all — the JVM's own default heap ceiling (1/4 of container-
# visible memory, which is unbounded without a container memory limit either, see
# docker-compose.prod.yml's own mem_limit) against server.tomcat.threads.max=50 concurrent Argon2id
# verifications (~16-19MiB working memory each, ADR-0005) was an unreasoned number nothing in this
# config accounted for. 1536m comfortably covers that worst case (50 * ~19MiB ≈ 950MiB) plus normal
# heap usage/GC headroom; -Xms half that avoids paying the full ceiling as a startup floor on a
# host that never gets close to the worst case. Overridable per-deployment via JAVA_OPTS (e.g. a
# larger heap on a host with more cores/higher TOMCAT_MAX_THREADS) — this is a reasoned default,
# not a hardcoded ceiling nothing can change.
JAVA_OPTS="${JAVA_OPTS:--Xmx1536m -Xms512m}"

# shellcheck disable=SC2086 # JAVA_OPTS is deliberately word-split — a real flag list, not one
# opaque argument; every value this project sets it to (above, or an operator's own override) is
# a fixed, non-attacker-controlled set of JVM flags, not external input needing quoting.
exec java $JAVA_OPTS -jar app.jar
