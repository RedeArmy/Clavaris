# Asset Inventory & Data Classification — Clavaris

🟡 En revisión

TD-FUT-016 (ISO/IEC 27001 + SOC 2 Type II readiness, ADR-0016): ISO Annex A 5.9 and SOC 2's own
Common Criteria both expect a real inventory of what data/systems exist and how sensitive each is —
not because this project is guessing at the answer for the first time (`data-model.md` and
`security-architecture.md` already describe every table and its handling in detail), but because
neither of those documents states the classification explicitly in one place an auditor can scan.
This document adds that layer; it doesn't re-describe the schema (`data-model.md` §2 remains the
source of truth for structure).

## 1. Classification levels

| Level | Meaning | Handling |
|---|---|---|
| **Restricted** | Compromise directly enables impersonation or full-system takeover | Never leaves its owning system in plaintext form beyond the single in-memory operation that requires it; never logged (BR-DATA-01) |
| **Confidential** | Real PII or business-sensitive data; compromise is a real incident but scoped | Encrypted in transit always (TLS, `security-architecture.md` §5); access limited to the owning `Organization`'s own data by construction (ADR-0010) |
| **Internal** | Operationally useful, not independently exploitable on its own | Normal access control, no special handling beyond that |

## 2. Data assets

| Asset | System | Classification | Notes |
|---|---|---|---|
| Password hashes (`PasswordCredential`) | Postgres | **Restricted** | Argon2id (ADR-0005); never returned in any API response, never logged |
| Signing key private material | PKCS12 keystore file (`TOKEN_SIGNING_KEY_STORE_PATH`) | **Restricted** | Never in Postgres, never in a log — `security-architecture.md` §3 |
| `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` | Environment (`.env`, `chmod 600` — ADR-0019) | **Restricted** | This project's single highest-value credential — §5, root CLAUDE.md |
| Bearer token values (access/refresh/authorization code/ID token) | Postgres (hashed, TD-SEC-019), Redis (session cache) | **Restricted** while live | Only a keyed HMAC digest persists in Postgres; the raw value exists only in transit and briefly in memory (`security-architecture.md` §2) |
| Client secrets (`OAuthClient`, `PlatformClient`) | Postgres | **Restricted** | Hashed at rest, same principle as passwords |
| Account email addresses | Postgres | **Confidential** | Real PII; scoped to one `Organization`'s account pool, never linked across tenants (ADR-0010) |
| Audit event log (`audit_events`, TD-SEC-007) | Postgres | **Confidential** | Records *that* an action happened and by whom, not credential material itself |
| Structured application logs | Log aggregation (destination TBD, `nfr-quality-attributes.md` §5) | **Internal** | Deliberately contains no credential/token/PII values by design (BR-DATA-01) — classified Internal precisely *because* Restricted/Confidential data is kept out of it, not despite it |
| Rate-limit counters (Redis) | Redis | **Internal** | Keyed by an HMAC digest of the real identifier (TD-SEC-023), not the raw email/IP itself |
| `event_outbox` rows | Postgres | **Internal** | Domain event payloads for a not-yet-built webhook dispatcher (ADR-0007); see `data-model.md` §5 for the retention policy (TD-TEST-002) now governing how long these persist |
| Source code | GitHub | **Internal** | Contains no secrets by construction — CI (Trivy, dependency-check, SonarCloud) is one of the controls that keeps this true, not an assumption |

## 3. System assets

| System | Role | Classification driver |
|---|---|---|
| PostgreSQL (primary) | System of record for every table above | Highest-value system asset — holds every Restricted/Confidential row; `risk-register.md` §3 names its own loss (no backup/DR, TD-FUT-006) as this project's most significant unmitigated availability risk |
| Redis | Rate-limit counters, distributed `HttpSession` (TD-ARCH-002) | Internal-classified data only, but availability-critical (fails open by design, TD-SEC-022) |
| Application (`app` module, the one deployable) | Terminates TLS, enforces every access-control decision | No independent data store of its own — its sensitivity is entirely a function of what it's trusted to reach in Postgres/Redis/the keystore |
| CI (GitHub Actions) | Builds, tests, scans every change before merge | Internal-classified inputs (source), but a compromised CI pipeline could inject malicious code — supply-chain risk, tracked via normal dependency/image-scanning hygiene, not duplicated here |

## 4. Review cadence

New tables/systems are classified in the same change that introduces them (the migration or
config that creates them names its own classification in a comment, matching this project's own
commenting standard, §8 root CLAUDE.md) — this document is then updated to match at the next full
review, not treated as the primary place classification decisions are made.
