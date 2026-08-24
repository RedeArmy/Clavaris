# Infisical Setup — Clavaris

🟡 En revisión

Self-hosted Infisical, ADR-0014/TD-FUT-014 (Phase 2 of the secrets-management plan). Optional today — its three services live in a **separate** compose file, `docker-compose.infisical.yml` (that file's own header comment explains why a separate file, not a `profiles:` block inside `docker-compose.yml` itself — a real bug caught live: Compose validates every required-variable check in a file regardless of profile activation), and `app`'s own `docker-entrypoint.sh` dual-mode fallback means nothing here is required for the default `docker compose up` local-dev flow.

Steps §1–§2 below were verified live against a real `eclipse-temurin:25-jre` container and this project's actual `docker-compose.yml`/`Dockerfile` before being written down. §3–§5 (creating the first admin account, project, and machine identity) are Infisical's own documented first-run web UI flow — described here from Infisical's own documentation, not independently re-verified against a running instance, since that step genuinely requires a human clicking through a browser, which nothing in this repository's tooling can do.

## 1. Generate Infisical's own bootstrap secrets

Two values Infisical's own backend needs to encrypt/sign everything it stores — analogous to `TOKEN_SIGNING_KEY_STORE_PASSWORD` for Clavaris's own signing keys, not a value to reuse across environments once real secrets exist behind it:

```bash
# ENCRYPTION_KEY — 16 random bytes, hex-encoded (matches Infisical's own sample format)
openssl rand -hex 16

# AUTH_SECRET — 32 random bytes, base64-encoded
openssl rand -base64 32
```

Put both into `.env` as `INFISICAL_ENCRYPTION_KEY`/`INFISICAL_AUTH_SECRET`, plus a real `INFISICAL_DB_PASSWORD` (any long random value — this is Infisical's own dedicated Postgres instance, never Clavaris's own `postgres` service). `INFISICAL_DB_USER`/`INFISICAL_DB_NAME`/`INFISICAL_SITE_URL`/`INFISICAL_PORT` all have working defaults in `docker-compose.yml` (`infisical`/`infisical`/`http://localhost:8081`/`8081`) — only override them if `8081` collides with something else already running.

## 2. Start Infisical

```bash
docker compose -f docker-compose.yml -f docker-compose.infisical.yml up -d infisical-db infisical-redis infisical
```

Wait for `docker compose ps` to show `infisical` healthy, then open `http://localhost:8081` (or your own `INFISICAL_PORT`).

## 3. Create the first admin account

A fresh Infisical instance shows its own account-creation screen on first visit — no default credentials exist to look up. Create the admin account here; this is Infisical's own login, entirely separate from any Clavaris `Account`/`PlatformAccount` — don't confuse the two.

## 4. Create a project

Inside Infisical (not Clavaris — **Infisical's own "Organization" concept, created automatically for your admin account, is unrelated to Clavaris's own `Organization`, ADR-0010**; don't conflate the two just because the word is the same in both products), create a new project — name it `clavaris`. Infisical seeds it with `dev`/`staging`/`prod` environments by default; `dev` is what `docker-compose.yml`'s own `INFISICAL_ENVIRONMENT` default already expects.

## 5. Create a machine identity for Clavaris's own `app` container

1. **Organization Settings → Access Control → Identities → Create identity.** Name it something like `clavaris-app`. Universal Auth is configured automatically.
2. On the new identity, **Create Client Secret** — copy the **Client ID** and **Client Secret** shown; the secret is shown exactly once, same handling as every other machine credential this project already treats this way (`RegisterOAuthClientService`'s own Javadoc, `PlatformClient` bootstrap).
3. In the `clavaris` project itself: **Project Settings → Access Control → Machine Identities → Add identity** — select `clavaris-app`, assign a role with read access to secrets (a project **Viewer**/read-only role is enough; this identity only ever needs to fetch secrets, never write them).

## 6. Populate Clavaris's own application secrets into Infisical

Add each of these as a secret in the `clavaris` project's `dev` environment (Infisical's own UI, or the CLI once logged in interactively: `infisical secrets set KEY=value --env=dev --projectId=<id>`) — the exact same names `application.yml`/`docker-compose.yml` already read via `${VAR:?...}`:

- `TOKEN_SIGNING_KEY_STORE_PASSWORD`
- `OAUTH2_TOKEN_HASH_SECRET`
- `RATE_LIMIT_KEY_HASH_SECRET`
- `PLATFORM_BOOTSTRAP_CLIENT_ID` / `PLATFORM_BOOTSTRAP_CLIENT_SECRET`
- `DB_DEV_PASSWORD` (and `DB_DEV_USER`/`DB_DEV_NAME` if you want Infisical to own those too, not just the password)
- `RESEND_API_KEY` (once TD-SEC-004's email delivery is configured for this environment)

## 7. Switch `app` onto the real secrets-manager path

In `.env`, set:

```bash
INFISICAL_CLIENT_ID=<the Client ID from §5>
INFISICAL_CLIENT_SECRET=<the Client Secret from §5>
INFISICAL_PROJECT_ID=<the clavaris project's own id, visible in its Project Settings>
INFISICAL_ENVIRONMENT=dev
INFISICAL_DOMAIN=http://infisical:8080/api
```

`INFISICAL_DOMAIN` above is the **in-network** address (`docker-compose.yml`'s own service name, port 8080 — Infisical's container-internal port, not the host-mapped `8081`) — this is what `app`'s own container reaches Infisical through, not the browser-facing `localhost:8081` URL from §2 onward.

Restart `app` (`docker compose up -d app`) and check its logs: `docker-entrypoint: INFISICAL_CLIENT_ID set — authenticating to Infisical...` confirms it took the real path, not the fallback. If authentication fails, the container exits non-zero (verified live — a fail-closed default, never a silent fallback to missing secrets, same posture as every other required-secret check in this project).

## 8. Rolling back

Blank out the five `INFISICAL_*` client vars in `.env` and restart `app` — `docker-entrypoint.sh` falls back to reading the plaintext vars directly, exactly as it did before this setup, with zero other changes needed.
