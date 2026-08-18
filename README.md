# Clavaris

A standalone, self-hosted identity provider — OIDC/OAuth2-compliant, reusable across any project regardless of language or framework.

Built with Java 21 and Spring Boot 3.4 (Spring Authorization Server as the protocol-compliance foundation), PostgreSQL 16, and Redis. Consumers integrate via standard OIDC — no custom SDK required.

> Project skeleton — module domain/application code hasn't been written yet (`CLAUDE.md` §11). What exists so far is infrastructure scaffolding (Maven module wiring, health checks, the local Docker stack, CI) plus a fully designed and **spike-validated** architecture: multi-tenant issuer/JWKS on Spring Authorization Server is proven to work end-to-end (`docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md`, **GO**), and `Organization` provisioning is fully designed (`ADR-0010`). The next work is the first real use case, not further design.

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

## Relationship to other projects

Clavaris has no product-specific logic — it doesn't know what "JobSeeker" or any other consumer is. [JobSeeker](../JobSeeker) is its first client, integrating as a standard OIDC relying party.

See `docs/` for architecture, product, and domain documentation.
