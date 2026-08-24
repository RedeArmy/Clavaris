#!/bin/sh
# ADR-0014 (TD-FUT-014): dual-mode entrypoint — fetches secrets from self-hosted Infisical when
# INFISICAL_CLIENT_ID/INFISICAL_CLIENT_SECRET are set, falls back to reading them directly from
# process env vars (docker-compose's own ${VAR:?...} pattern) otherwise. Deliberately dual-mode,
# not a hard cutover: Infisical's own one-time bootstrap (admin account, project, machine identity —
# docs/05-engineering/infisical-setup.md) requires a human clicking through its web UI, something
# nothing in this repo can do on a fresh `docker compose up` before that bootstrap has happened.
# Forcing every environment through Infisical from day one would break the existing, working
# local-dev flow for that gap. Once bootstrapped, setting the two INFISICAL_* vars below switches
# this same container to the real secrets-manager path with zero other changes.
set -eu

if [ -n "${INFISICAL_CLIENT_ID:-}" ] && [ -n "${INFISICAL_CLIENT_SECRET:-}" ]; then
  echo "docker-entrypoint: INFISICAL_CLIENT_ID set — authenticating to Infisical (${INFISICAL_DOMAIN:-https://app.infisical.com/api}) before starting the app."

  # A fresh short-lived access token per container start, not a long-lived static token committed
  # anywhere — the machine identity's client id/secret (themselves in .env, same as every other
  # bootstrap credential this project already treats this way — see infisical-setup.md) are the
  # only long-lived secret this path needs; --plain strips the CLI's own decorative output so the
  # variable holds exactly the token and nothing else.
  INFISICAL_TOKEN=$(infisical login \
    --method=universal-auth \
    --client-id="$INFISICAL_CLIENT_ID" \
    --client-secret="$INFISICAL_CLIENT_SECRET" \
    --domain="${INFISICAL_DOMAIN:-https://app.infisical.com/api}" \
    --silent --plain)
  export INFISICAL_TOKEN

  # `infisical run` injects every secret in this project/environment as a real process env var
  # before exec'ing the app — application.yml's existing ${VAR:default} placeholders pick them up
  # completely unchanged, zero Spring/Java code needed for this migration.
  exec infisical run \
    --token="$INFISICAL_TOKEN" \
    --domain="${INFISICAL_DOMAIN:-https://app.infisical.com/api}" \
    --projectId="$INFISICAL_PROJECT_ID" \
    --env="${INFISICAL_ENVIRONMENT:-dev}" \
    -- java -jar app.jar
else
  echo "docker-entrypoint: INFISICAL_CLIENT_ID not set — reading secrets directly from process env vars (docker-compose.yml's own required-var pattern)."
  exec java -jar app.jar
fi
