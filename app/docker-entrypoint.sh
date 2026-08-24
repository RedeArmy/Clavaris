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

  # TD-SEC-025: the client secret is handed to `infisical login` via INFISICAL_UNIVERSAL_AUTH_
  # CLIENT_ID/_SECRET env vars, not --client-id/--client-secret flags — a CLI flag lands in the
  # process's own argv, readable by anyone who can `docker top`/`ps` this container (or exec into
  # it) for as long as the process runs, env vars don't leak the same way. Found in this project's
  # own SDE-III review pass, not by design; confirmed live against Infisical's own CLI reference
  # that these env vars are the documented equivalent, not a workaround. A fresh short-lived access
  # token per container start, not a long-lived static token committed anywhere — the machine
  # identity's client id/secret (themselves in .env, same as every other bootstrap credential this
  # project already treats this way — see infisical-setup.md) are the only long-lived secret this
  # path needs; --plain strips the CLI's own decorative output so the variable holds exactly the
  # token and nothing else.
  # (a bare `A=x B=y NAME=$(cmd)` line would NOT do it — with no command word present, POSIX
  # treats that as three ordinary persistent assignments in this shell, not env vars scoped to
  # the subprocess `cmd` spawns, so they'd never actually reach `infisical login` — export is
  # required for a child process to see them at all.)
  export INFISICAL_UNIVERSAL_AUTH_CLIENT_ID="$INFISICAL_CLIENT_ID"
  export INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET="$INFISICAL_CLIENT_SECRET"
  INFISICAL_TOKEN=$(infisical login \
    --method=universal-auth \
    --domain="${INFISICAL_DOMAIN:-https://app.infisical.com/api}" \
    --silent --plain)
  export INFISICAL_TOKEN

  # `infisical run` injects every secret in this project/environment as a real process env var
  # before exec'ing the app — application.yml's existing ${VAR:default} placeholders pick them up
  # completely unchanged, zero Spring/Java code needed for this migration. No --token flag here
  # either, same reasoning as above — the CLI already reads the INFISICAL_TOKEN this script just
  # exported, confirmed live against Infisical's own CLI reference, not assumed.
  exec infisical run \
    --domain="${INFISICAL_DOMAIN:-https://app.infisical.com/api}" \
    --projectId="$INFISICAL_PROJECT_ID" \
    --env="${INFISICAL_ENVIRONMENT:-dev}" \
    -- java -jar app.jar
else
  echo "docker-entrypoint: INFISICAL_CLIENT_ID not set — reading secrets directly from process env vars (docker-compose.yml's own required-var pattern)."
  exec java -jar app.jar
fi
