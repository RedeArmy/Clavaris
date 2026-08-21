# Clavaris

[![CI](https://github.com/RedeArmy/Clavaris/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/RedeArmy/Clavaris/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-proprietary-red.svg)](LICENSE)

A standalone, self-hosted identity provider — OIDC/OAuth2-compliant, reusable across any project regardless of language or framework.

Built with Java 25 and Spring Boot 4.1 (Spring Authorization Server as the protocol-compliance foundation), PostgreSQL 16, and Redis. Consumers integrate via standard OIDC — no custom SDK required.

> Project skeleton — module domain/application code hasn't been written yet. What exists so far is infrastructure scaffolding (Maven module wiring, health checks, the local Docker stack, CI) plus a fully designed and **spike-validated** architecture: multi-tenant issuer/JWKS on Spring Authorization Server is proven to work end-to-end (`docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md`, **GO**), and `Organization` provisioning is fully designed (`ADR-0010`). The next work is the first real use case, not further design.

## Modules

- `identity-module` — accounts (each scoped to one `Organization`, ADR-0010), credentials (password, social identities), sessions, email verification, password recovery, token issuance
- `organization-module` — `Organization` (tenant isolation boundary, one per consuming system) + `Workspace` (team/company grouping within one Organization — the old "organization" concept, renamed by ADR-0010)
- `client-registry-module` — registered consuming applications (OAuth clients, each belonging to one Organization), authorization codes, plus the platform-tier `PlatformClient` that authenticates the management API itself (ADR-0010, Organization provisioning)
- `app` — the Spring Boot bootstrap module: single deployable entry point, Actuator health checks, no domain code
- `common` — shared kernel: base exceptions, value types, no business logic

## Running locally

```bash
cp .env.example .env   # fill in local values — never commit .env
docker compose up --build
```

- App: `http://localhost:8080`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- Readiness: `http://localhost:8080/actuator/health/readiness` (checks DB + Redis)

See `docs/` for architecture, product, and domain documentation.

## Security

Found a vulnerability? See [`SECURITY.md`](SECURITY.md) for how to report it responsibly — please don't open a public issue for it.

## License

Proprietary — all rights reserved. See [`LICENSE`](LICENSE). This repository is public for visibility; it is not licensed for reuse, redistribution, or derivative works.
