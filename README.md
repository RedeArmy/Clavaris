# Clavaris

[![CI](https://github.com/RedeArmy/Clavaris/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/RedeArmy/Clavaris/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-proprietary-red.svg)](LICENSE)

A standalone, self-hosted identity provider — OIDC/OAuth2-compliant, reusable across any project regardless of language or framework.

Built with Java 25 and Spring Boot 4.1 (Spring Authorization Server as the protocol-compliance foundation), PostgreSQL 16, and Redis. Consumers integrate via standard OIDC — no custom SDK required.

> Working OIDC/OAuth2 identity provider, not a skeleton — real login, token issuance, and per-tenant isolation all run end to end against Postgres/Redis today, gated behind an unusually heavy CI bar (SonarCloud Quality Gate, OWASP dependency scan, Trivy image scan, full ArchUnit hexagonal-boundary enforcement). Zero P1 security findings open as of 2026-08-24 — see `docs/05-engineering/technical-debt-register.md`. Every v1-scoped capability is built and live-verified (social login shipped 2026-08-30, `Workspace`/team-membership and the admin account-/organization-deletion APIs in late August) — see `docs/01-product/roadmap-and-release-plan.md` §2 for the per-row status. What stands between this and real traffic is not code: the mandatory external security review, the OpenID conformance run, and a first real consumer integration.

## Modules

- `identity-module` — accounts (each scoped to one `Organization`, ADR-0010), password credentials, sessions, email verification, password recovery, refresh-token rotation with reuse detection, per-tenant signing-key (JWKS) management with rotation-with-overlap. Social login (Google/GitHub, ADR-0020) with explicit account-linking confirmation, passkeys, and account deletion.
- `organization-module` — `Organization` (tenant isolation boundary, one per consuming system, implemented) with an editable profile (application name, description, brand colour, logo) that brands the sign-in and consent pages, emails and passkey prompt its consuming system's people see + two-layer rate-limit policy management. `Workspace` (team/company grouping within one Organization — the old "organization" concept, renamed by ADR-0010) is implemented, with consumer-defined roles (ADR-0027) and teams (ADR-0028).
- `client-registry-module` — registered consuming applications (OAuth clients, each belonging to one Organization), the full Authorization Code + PKCE flow (discovery/JWKS/userinfo/revoke), plus the platform-tier `PlatformClient` that authenticates the management API itself (ADR-0010, Organization provisioning) — implemented, including self-service rotate/revoke.
- `webhook-module` — signed (HMAC-SHA256) event delivery to a consuming system's own endpoints, with retry/backoff, secret rotation and replayable delivery history (ADR-0007). Reads the outbox the other modules write; none of them knows it exists.
- `app` — the Spring Boot bootstrap module: single deployable entry point, all Spring Security/OIDC filter-chain wiring, rate limiting, audit logging, Actuator health checks. No `domain/` of its own — every OIDC-protocol/security-config class here composes the business modules above, it doesn't own business rules.
- `common` — shared kernel, given its first real content in `audit_events` (`AuditEvent`/`AuditEventRecorder`, consumed by 3 of the other 4 modules) once a third module actually needed the same thing (ADR-0001's own "no shared code until it's genuinely shared" principle).

## Running locally

```bash
cp .env.example .env   # fill in local values — never commit .env
docker compose up --build
```

Running the app outside Docker (from an IDE, or `java -jar`) needs `SPRING_PROFILES_ACTIVE=dev`: the
application refuses to start with the public development secrets from `application.yml` under any
other profile — or none — and names the settings to change.

- App: `http://localhost:8080`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- Readiness: `http://localhost:8080/actuator/health/readiness` (checks DB + Redis)

See `docs/` for architecture, product, and domain documentation.

## Security

Found a vulnerability? See [`SECURITY.md`](SECURITY.md) for how to report it responsibly — please don't open a public issue for it.

## License

Proprietary — all rights reserved. See [`LICENSE`](LICENSE). This repository is public for visibility; it is not licensed for reuse, redistribution, or derivative works.
