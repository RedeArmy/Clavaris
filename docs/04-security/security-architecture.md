# Security Architecture — Clavaris

🟡 En revisión

## 1. Scope

This document describes how Clavaris protects the credentials and tokens it is solely responsible for issuing and verifying. Unlike JobSeeker (which delegates this entire concern to Clavaris — see JobSeeker's own `docs/04-security/security-architecture.md` §1-2), this *is* the system of record for authentication, so this document carries the full weight that JobSeeker's no longer needs to.

## 2. Credential storage

- Passwords: Argon2id (ADR-0005), never logged, never returned in any API response, never stored anywhere in plaintext including in-memory longer than the single verification call requires.
- Client secrets: hashed at rest, same principle as passwords (`data-model.md` §2).
- Tokens (refresh, verification, authorization codes): only their hash is persisted; the bearer value exists only in transit and on the holder's side (`data-model.md` §2).

## 3. Token signing and key management

- RS256, asymmetric (ADR-0002) — private key never leaves Clavaris, never stored in the application database (`data-model.md` §2), referenced via `TOKEN_SIGNING_KEY_STORE_PATH`.
- **Per-`Organization` since ADR-0010 §5**: each tenant has its own key pair, its own issuer (`{clavarisBaseUrl}/o/{organizationId}`, path-based), and its own `/oauth2/jwks` document — a signing-key compromise is a single-tenant incident, not a Clavaris-wide one. Extension of Spring Authorization Server needed to serve per-tenant JWKS correctly is not yet validated in code — see ADR-0003's addendum for the required spike.
- Keys rotate with overlap: a new key becomes active for signing while the previous key remains published until every token issued under it has naturally expired, so no consumer ever fails verification mid-rotation. v1 rotation is a manually-triggered, audited management-API operation per Organization (ADR-0010 §5.2), not a scheduler-driven job — that's v1.1.
- Key-compromise incident response: `incident-response-signing-key-compromise.md` — written against the current implementation, which does not yet have the per-tenant rotation endpoint ADR-0010 §5.2 describes; the runbook's own §6 documents that gap explicitly rather than assuming it away.
- `PlatformClient` bootstrap-credential compromise (the highest-value credential in the system, ADR-0010's Organization-provisioning tier) has its own separate runbook, `incident-response-platform-client-compromise.md` — writing it surfaced a new, previously untracked gap: no self-service way exists to revoke or rotate a `PlatformClient` at all today, only raw SQL against production (TD-SEC-018).

## 4. Rate limiting

Bucket-based rate limiting (mirroring JobSeeker's own Bucket4j + Redis approach, ADR-0011 there — not yet formalized as its own ADR here, but the same technology choice is expected). Since ADR-0010 §6, this is two layers, not one:
- **Anti-abuse (fixed, system-defined, never tenant-configurable)** — `/login` and `/oauth2/token`, keyed by `(organization_id, account_or_ip_identifier)`, tuned against credential stuffing, distinct (tighter) thresholds than a generic API rate limit (BR-ID-06). No tenant can loosen this, even in v1.1.
- **Capacity ceiling (per-`Organization` aggregate, operator-managed in v1)** — keyed by `organization_id` alone, protects one tenant's traffic from exhausting budget shared with unrelated tenants (`RateLimitPolicy`, ADR-0010 §6.2). Tenant self-service is v1.1, gated on audit logging shipping first.
- registration and verification-email resend — tuned against email-quota exhaustion, same two-layer principle applies if this ever needs per-tenant tuning (not yet a concrete requirement).

## 5. Transport and headers

- TLS required for every environment beyond local development — no exceptions for the OIDC surface, since tokens and authorization codes in transit are the entire attack surface PKCE and RS256 are designed to protect against.
- Standard security headers (`Strict-Transport-Security`, `X-Content-Type-Options`, `Content-Security-Policy` on the hosted login/consent UI) — exact policy to be finalized alongside implementation, not yet written.

## 6. Logging and observability

- Structured JSON logs for every authentication event, with credential/token values never included (BR-DATA-01) — full detail in `docs/01-product/nfr-quality-attributes.md` §5.
- Refresh token reuse-detection firing is treated as a security signal worthy of alerting, not just logging (`nfr-quality-attributes.md` §5) — this is one of the few events in the system that indicates an active, not just potential, compromise.

## 7. Account deletion (management API)

Clavaris exposes `POST /api/v1/admin/accounts/{id}:delete` (BR-DATA-02) for consumers to call once their own local data-handling process (e.g. JobSeeker's grace-period-then-anonymize flow, ADR-0013 there) completes. On call:

| Step | Action |
|---|---|
| 1 | All sessions and refresh tokens for the account are revoked immediately |
| 2 | All workspace memberships are removed (`WorkspaceMembership`, ADR-0010 — renamed from the pre-ADR-0010 `Membership`) |
| 3 | Social identity links are deleted |
| 4 | The account record itself is hard-deleted (BR-DATA-03) |

Clavaris does not run its own independent grace period for this — the calling consumer owns that policy decision; Clavaris treats the call as final. The multi-consumer-identity caveat (one account shared across two consumers) that used to be flagged here as unresolved **is resolved by ADR-0010**: `Account` is scoped to exactly one `Organization` (one consuming system's isolated account pool), so a deletion request against one Organization's account can never reach an identity another consumer depends on — that scenario no longer exists by construction, not by policy. See `business-rules.md` BR-ORG-01/03 and `domain-model.md` §8.

## 8. Dependency and supply chain

Standard practice expected (not yet formalized as an ADR): automated dependency vulnerability scanning in CI, consistent with what JobSeeker's own CI/CD pipeline is expected to run. No divergent policy needed here.

## 9. External review gate

Non-negotiable: no consumer sends real user traffic to Clavaris until an external security review finds zero open critical/high findings. This document, `threat-model-stride.md`, and the known-gaps list in both are the primary inputs to that review — closing the gaps listed in `threat-model-stride.md` §5 is a precondition for scheduling it, not something to be discovered during it.
