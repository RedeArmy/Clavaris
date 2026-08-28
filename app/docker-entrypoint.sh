#!/bin/sh
# ADR-0019: single, unconditional entrypoint — every secret this project has comes from
# process env vars (docker-compose.yml's/docker-compose.prod.yml's own required-`${VAR:?...}`
# pattern), the same TD-SEC-013 "no silent default" posture every other secret already follows.
# Previously dual-mode (ADR-0014, self-hosted Infisical) — removed 2026-08-28 (ADR-0019) once
# ADR-0018 decided .env is the real production path, leaving that second path built but never
# actually exercised by any real deployment. See ADR-0019 for the full removal reasoning.
set -eu

exec java -jar app.jar
