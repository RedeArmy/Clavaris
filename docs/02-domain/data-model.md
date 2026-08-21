# Data Model — Clavaris

🟡 En revisión

Companion to `domain-model.md` — this document is the persistence-level translation (tables, columns, indexes), not a restatement of the domain reasoning.

## 1. ERD

```mermaid
erDiagram
    ORGANIZATIONS ||--o{ ACCOUNTS : "isolates (ADR-0010)"
    ORGANIZATIONS ||--o{ OAUTH_CLIENTS : "isolates (ADR-0010)"
    ORGANIZATIONS ||--o{ SIGNING_KEYS : "isolates (ADR-0010)"
    ORGANIZATIONS ||--o| RATE_LIMIT_POLICIES : "overrides default (ADR-0010)"
    ORGANIZATIONS ||--o{ WORKSPACES : contains
    ACCOUNTS ||--o{ PASSWORD_CREDENTIALS : has
    ACCOUNTS ||--o{ SOCIAL_IDENTITIES : links
    ACCOUNTS ||--o{ SESSIONS : opens
    ACCOUNTS ||--o{ VERIFICATION_TOKENS : requests
    SESSIONS ||--o{ REFRESH_TOKENS : issues
    ACCOUNTS ||--o{ WORKSPACE_MEMBERSHIPS : "belongs to"
    WORKSPACES ||--o{ WORKSPACE_MEMBERSHIPS : has
    WORKSPACES ||--o{ WORKSPACE_INVITATIONS : issues
    OAUTH_CLIENTS ||--o{ OAUTH2_AUTHORIZATION : authorizes
    OAUTH_CLIENTS ||--o{ WEBHOOK_ENDPOINTS : registers
    OAUTH_CLIENTS ||--o| CLIENT_DOMAIN_CONFIGS : embeds_via
    OAUTH_CLIENTS ||--o| CLIENT_BRANDINGS : themes
    WEBHOOK_ENDPOINTS ||--o{ WEBHOOK_DELIVERIES : receives
    EVENT_OUTBOX ||--o{ WEBHOOK_DELIVERIES : "fans out to"

    %% PLATFORM_CLIENTS and PLATFORM_SIGNING_KEYS deliberately have NO
    %% relationship to ORGANIZATIONS — that's the entire point (ADR-0010,
    %% Organization provisioning). They authenticate the operations that
    %% create/manage Organizations, so they cannot themselves belong to one.

    ACCOUNTS {
        uuid id PK
        uuid organization_id FK
        varchar email
        timestamptz email_verified_at
        varchar status
        timestamptz created_at
    }
    PASSWORD_CREDENTIALS {
        uuid id PK
        uuid account_id FK
        varchar password_hash
        timestamptz updated_at
    }
    SOCIAL_IDENTITIES {
        uuid id PK
        uuid account_id FK
        varchar provider
        varchar provider_user_id
        timestamptz linked_at
    }
    SESSIONS {
        uuid id PK
        uuid account_id FK
        text scopes
        timestamptz created_at
        timestamptz last_seen_at
        timestamptz revoked_at
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid account_id FK
        uuid session_id FK
        varchar token_hash UK
        uuid rotated_from_id FK
        timestamptz issued_at
        timestamptz expires_at
        timestamptz revoked_at
    }
    SIGNING_KEYS {
        uuid id PK
        uuid organization_id FK
        varchar kid
        varchar algorithm
        timestamptz active_from
        timestamptz retired_at
    }
    PLATFORM_SIGNING_KEYS {
        uuid id PK
        varchar kid UK
        varchar algorithm
        timestamptz active_from
        timestamptz retired_at
    }
    RATE_LIMIT_POLICIES {
        uuid id PK
        uuid organization_id FK UK
        varchar endpoint_scope
        int max_requests
        int window_seconds
        timestamptz updated_at
    }
    VERIFICATION_TOKENS {
        uuid id PK
        uuid account_id FK
        varchar type
        varchar token_hash UK
        timestamptz expires_at
        timestamptz consumed_at
    }
    ORGANIZATIONS {
        uuid id PK
        varchar name
        timestamptz created_at
    }
    WORKSPACES {
        uuid id PK
        uuid organization_id FK
        varchar name
        timestamptz created_at
    }
    WORKSPACE_MEMBERSHIPS {
        uuid id PK
        uuid workspace_id FK
        uuid account_id FK
        varchar role
        timestamptz joined_at
    }
    WORKSPACE_INVITATIONS {
        uuid id PK
        uuid workspace_id FK
        varchar invited_email
        varchar proposed_role
        varchar token_hash UK
        timestamptz expires_at
        timestamptz consumed_at
    }
    OAUTH_CLIENTS {
        uuid id PK
        uuid organization_id FK
        varchar client_id UK
        varchar client_secret_hash
        text redirect_uris
        text allowed_grant_types
        text allowed_scopes
        timestamptz created_at
    }
    PLATFORM_CLIENTS {
        uuid id PK
        varchar client_id UK
        varchar client_secret_hash
        text allowed_scopes
        timestamptz created_at
    }
    OAUTH2_AUTHORIZATION {
        varchar id PK
        varchar registered_client_id FK
        varchar principal_name
        varchar authorization_grant_type
        varchar authorized_scopes
        text attributes
        varchar state
        text authorization_code_value
        timestamptz authorization_code_expires_at
        text access_token_value
        timestamptz access_token_expires_at
        text oidc_id_token_value
        text refresh_token_value
        timestamptz refresh_token_expires_at
    }
    EVENT_OUTBOX {
        uuid id PK
        varchar aggregate_type
        uuid aggregate_id
        varchar event_type
        jsonb payload
        int schema_version
        timestamptz occurred_at
        timestamptz published_at
    }
    WEBHOOK_ENDPOINTS {
        uuid id PK
        uuid oauth_client_id FK
        varchar url
        varchar secret_hash
        text subscribed_event_types
        varchar status
        timestamptz created_at
    }
    WEBHOOK_DELIVERIES {
        uuid id PK
        uuid webhook_endpoint_id FK
        uuid event_id FK
        varchar event_type
        jsonb payload
        varchar status
        int attempt_count
        int response_status_code
        timestamptz next_attempt_at
        timestamptz last_attempt_at
    }
    CLIENT_DOMAIN_CONFIGS {
        uuid id PK
        uuid oauth_client_id FK UK
        varchar mode
        varchar custom_domain UK
        varchar verification_status
        varchar verification_token
        timestamptz verified_at
    }
    CLIENT_BRANDINGS {
        uuid id PK
        uuid oauth_client_id FK UK
        varchar logo_url
        varchar primary_color_hex
        varchar app_display_name
    }
```

## 2. Table notes

- **`accounts.organization_id`** — ADR-0010: mandatory FK to `organizations`, the tenant isolation boundary. `accounts.email` is **no longer** globally unique — uniqueness is `(organization_id, email)` (§3); the same email may exist as unrelated `Account` rows in different organizations. `status` is an enum (`ACTIVE`, `SUSPENDED`, `DELETED` — deletion per BR-DATA-03 is a hard delete in v1, so a `DELETED` status is transitional at most, present mainly for audit-log correlation before physical removal completes).
- **`organizations`** — ADR-0010: one row per consuming system (tenant isolation boundary), not to be confused with `workspaces` below. Owns its own isolated `accounts` and `oauth_clients`.
- **`workspaces`**, **`workspace_memberships`**, **`workspace_invitations`** — ADR-0010: renamed from the pre-ADR-0010 `organizations`/`memberships`/`invitations` tables (same "company/team grouping" semantics, renamed to free up "organization" for the new tenant-isolation meaning). `workspaces.organization_id` is mandatory — a workspace always belongs to exactly one tenant, and its memberships can only reference accounts already confined to that same tenant.
- **`password_credentials`**, **`social_identities`** — deliberately separate tables from `accounts`, never a nullable `password_hash` column bolted onto `accounts` — keeps BR-ID-02 (multiple auth methods, never zero) a natural query (`COUNT` across both tables) rather than a null-check special case.
- **`sessions.scopes`** — BR-ID-03, real (not placeholder) shape: `text` (JSON array, same convention as `oauth_clients.allowed_scopes`), fixed at open time — RFC 6749 §6 forbids a refresh grant from ever widening scope beyond what the original authorization actually granted, so every `refresh_tokens` row in this session's chain is validated against this same, unchanging set. Replaces this document's original placeholder `user_agent` column — no use case populates one yet (no "list your active sessions/devices" feature exists); add it, with the real HTTP plumbing behind it, only once one does.
- **`refresh_tokens.token_hash`** — the token itself is never stored, only its hash (same principle as `verification_tokens.token_hash` and `authorization_codes.code_hash`, and the same principle JobSeeker applied to its own retired refresh-token design before this module absorbed it). Deliberately never looked up via `oauth2_authorization` below, even though that table also tracks a `refresh_token_value` column — BR-ID-03's rotation/reuse-detection logic is fully decoupled from SAS's own table, which is what keeps this one hash-only instead of inheriting TD-SEC-019's plaintext-storage gap.
- **`refresh_tokens.rotated_from_id`** — self-referencing FK forming the rotation chain domain-model.md §2 describes. The real implementation's reuse check doesn't walk this chain at runtime — a presented token whose own row already has `revoked_at` set is reuse, full stop (rotating away and the BR-ID-03 revocation cascade are the only two things that ever set it) — `rotated_from_id` is kept purely as an audit trail for investigation, same spirit as `signing_keys.retired_at`. See `RefreshToken`'s own Javadoc (identity-module) for the full reasoning.
- **`signing_keys`** — the private key material itself is **not** in this table; only metadata (`kid`, algorithm, validity window). Actual key material lives in a key store referenced by `TOKEN_SIGNING_KEY_STORE_PATH` (`.env.example`), never in the database. `organization_id` is mandatory (ADR-0010 §5) — JWKS is per-`Organization`, so `kid` is only unique *within* an organization, not globally (§3). Every `Organization`'s issuer is `{clavarisBaseUrl}/o/{organization_id}` (ADR-0010 §5.1, path-based, not subdomain) — `iss` and `jwks_uri` are both scoped under that path. Rotation in v1 is a manually-triggered, audited management-API operation per organization (ADR-0010 §5.2), not a scheduled job.
- **`rate_limit_policies`** — ADR-0010 §6.2: **capacity ceiling only**, one optional row per `Organization` (`UNIQUE(organization_id)`), overriding the system-default aggregate request ceiling for that tenant. A missing row means "use the system default." The application layer, not this table, enforces the hard system-wide ceiling an Organization's own policy can never exceed. **v1: operator-managed only** (no tenant self-service until v1.1, gated on audit logging). This table does **not** govern anti-abuse/credential-stuffing thresholds — those are fixed, system-wide, keyed by `(organization_id, account_or_ip_identifier)`, enforced directly in Redis with no corresponding table (ADR-0010 §6.1) so they can never be loosened by a tenant.
- **`oauth_clients.organization_id`** — ADR-0010: mandatory FK to `organizations`. One organization may register several `oauth_clients` (e.g. web + mobile for the same system), all sharing that organization's isolated account pool — this FK is the actual mechanism that keeps one tenant's login flow from ever authenticating another tenant's accounts.
- **`oauth_clients.client_secret_hash`** — same hash-not-plaintext principle as passwords; `redirect_uris`/`allowed_grant_types`/`allowed_scopes` stored as `text` (JSON array) in v1 — a normalized child table is a reasonable future refactor if per-URI querying is ever needed, not needed yet.
- **`oauth2_authorization`** — TD-SEC-003 (closed): backs Spring Authorization Server's own `JdbcOAuth2AuthorizationService`, not a bespoke table this project designed — every authorization code, access token, refresh token, and OIDC ID token either issuer tier issues, one row per authorization. Its real shape is the framework's own upstream schema (Postgres-adapted per that file's own embedded instructions), richer than this document's original placeholder sketch (`AUTHORIZATION_CODES`, now retired) since it tracks every token type SAS itself tracks per grant, not just the authorization code. **Deliberately one shared table for both the platform tier and every Organization**, not the usual two-tables-per-tier split this document uses everywhere else (`platform_clients`/`oauth_clients`, `platform_signing_keys`/`signing_keys`) — `JdbcOAuth2AuthorizationService` hardcodes its own table name internally, so two differently-named tables aren't supported without forking the class; `registered_client_id` still fully separates platform-tier rows from Organization-tier rows, since the two tiers' client id spaces are already structurally disjoint. Bearer values (`*_value` columns) are the actual token text SAS needs to verify later reuse/introspection against — not hashed, unlike this project's own `password_credentials`/`refresh_tokens` convention, because that convention is this project's own choice for tables it designed, not a knob `JdbcOAuth2AuthorizationService` exposes. BR-ID-03's refresh-grant handling (`RefreshTokenRotationAuthenticationProvider`) saves a brand-new row here on every rotation rather than updating the previous grant's row in place — TD-ARCH-005, unbounded row growth on a long-lived, frequently-refreshed session, tracked rather than silently accepted.
- **`platform_clients`** / **`platform_signing_keys`** — ADR-0010 (Organization provisioning). Deliberately **no** `organization_id` column, not even nullable — these authenticate the operations that create/manage `organizations` rows themselves, so making them belong to one would be circular and would reopen the exact cross-tenant blast-radius risk ADR-0010 §1–§2 close for everything else. The first `platform_clients` row is seeded from `PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET` (`.env.example`) via an idempotent startup check, not an HTTP endpoint. `allowed_scopes` values are namespaced `platform:*`, reserved and disjoint from any per-organization management scope.
- **`workspace_memberships`** — composite uniqueness on `(workspace_id, account_id)` — one membership row per account per workspace, role changes update the row rather than creating a new one.
- **`event_outbox`** — 🟡 proposed, see ADR-0007. Written in the same transaction as the domain state change it records (BR-WEBHOOK-05); `published_at IS NULL` marks a row still waiting for the `webhook-module` dispatcher to fan it out. `payload` is the event's own versioned JSON shape (`schema_version`), independent of the management API's URL-based versioning (ADR-0008).
- **`webhook_endpoints.secret_hash`** — same hash-not-plaintext principle as `oauth_clients.client_secret_hash`; the raw secret is shown exactly once, at creation.
- **`webhook_deliveries`** — one row per `(webhook_endpoint, event)` pair; `status` is `PENDING | SUCCEEDED | FAILED | EXHAUSTED` (BR-WEBHOOK-03). A unique constraint on `(webhook_endpoint_id, event_id)` makes delivery-row creation itself idempotent — the dispatcher can safely re-scan `event_outbox` after a crash without creating duplicate deliveries.
- **`client_domain_configs`** — 🟡 proposed, see ADR-0009. One-to-one with `oauth_clients` (unique `oauth_client_id`), same "optional, never a nullable column on the core entity" convention as `password_credentials`. `custom_domain` is globally unique (two clients can't claim the same subdomain). `verification_status` is `PENDING | VERIFIED | FAILED`; BR-CLIENT-04 requires `VERIFIED` before the embedded login experience is usable in production.
- **`client_brandings`** — 🟡 proposed, see ADR-0009. One-to-one with `oauth_clients`; read by the Thymeleaf hosted-UI templates to theme the login/consent screens (logo, primary color, display name).

## 3. Indexes

| Table | Index | Reason |
|---|---|---|
| `accounts` | unique `(organization_id, email)` | login lookup scoped to tenant, uniqueness enforcement (ADR-0010) |
| `refresh_tokens` | unique `(token_hash)` | token validation lookup |
| `refresh_tokens` | `(session_id, rotated_from_id)` | reuse-detection chain walk |
| `verification_tokens` | unique `(token_hash)` | token validation lookup |
| `signing_keys` | unique `(organization_id, kid)` | per-tenant JWKS lookup; `kid` is only unique within an organization (ADR-0010 §5) |
| `signing_keys` | `(organization_id)` where `retired_at IS NULL` | active-key lookup for token signing at issuance time |
| `rate_limit_policies` | unique `(organization_id)` | one capacity-ceiling override per tenant (ADR-0010 §6.2) |
| `workspaces` | `(organization_id)` | list a tenant's workspaces |
| `workspace_memberships` | unique `(workspace_id, account_id)` | BR-WS uniqueness |
| `workspace_invitations` | `(workspace_id, invited_email)` where `consumed_at IS NULL` | pending-invitation lookup |
| `oauth_clients` | unique `(client_id)` | client lookup at `/authorize` and `/token` |
| `oauth_clients` | `(organization_id)` | list a tenant's registered clients |
| `platform_clients` | unique `(client_id)` | client lookup at the platform issuer's `/oauth2/token` (ADR-0010, Organization provisioning) |
| `platform_signing_keys` | unique `(kid)` | JWKS lookup for the platform issuer — globally unique since there is only ever one platform tier |
| `authorization_codes` | unique `(code_hash)` | code exchange lookup |
| `event_outbox` | `(published_at)` where `published_at IS NULL` | dispatcher poll query — partial index keeps it small regardless of total outbox history |
| `webhook_deliveries` | unique `(webhook_endpoint_id, event_id)` | idempotent delivery-row creation on dispatcher re-scan |
| `webhook_deliveries` | `(status, next_attempt_at)` where `status IN ('PENDING','FAILED')` | retry-scheduler poll query |
| `client_domain_configs` | unique `(oauth_client_id)`, unique `(custom_domain)` | one config per client; no two clients can claim the same subdomain |

## 4. Migrations

Flyway (`org.flywaydb:flyway-core` + `flyway-database-postgresql`, versions managed by the Spring Boot BOM — ADR-0004: PostgreSQL) is the single source of truth for schema. Runs **automatically on every application startup**, in every environment (local `docker compose`, CI's Testcontainers-backed integration tests, staging, production) — this is the whole point: no environment is ever brought to the current schema by someone running a `.sql` file by hand. `spring.jpa.hibernate.ddl-auto` is `validate` (never `update`/`create`), so Hibernate can never silently diverge from what a migration actually defines — if an entity and the schema disagree, the app fails to start, loudly, rather than quietly patching itself differently per environment.

- **Location convention**: each bounded-context module owns its own migrations under its own `src/main/resources/db/migration/` (already scaffolded per module). Flyway's default scan path (`classpath:db/migration`) picks up all of them at once at runtime, because the `app` bootstrap module depends on every business module — one linear migration history for the one shared database (`ADR-0004`), ownership still split by module in the source tree.
- **Versioning convention — timestamp-based, not sequential**: `V{yyyyMMddHHmmss}__description.sql` (e.g. `V20260817163000__create_accounts_table.sql`), not `V1`, `V2`, … A shared sequential counter across independently-developed modules is a guaranteed future collision; a timestamp isn't.
- **Schema ships with its owning use case, not pre-created**: consistent with this project's own convention that "no `application/usecase/` subfolders exist yet; they're named and created as each use case is actually designed" — a table arrives in the same change as the entity/repository that needs it, not speculatively ahead of it. The one exception is `V1__enable_pgcrypto.sql` (`app` module) — a genuinely cross-cutting baseline (every table's PK is a `uuid`, `data-model.md` §1) that doesn't belong to any single bounded context, added to prove the Flyway wiring itself works end-to-end before any real schema exists.
- **Data preservation across migrations is a tested property, not an assumption**: any migration that alters a table with existing rows (rename, type change, split/merge, a drop with data implications) must be exercised against seeded data, not just an empty schema — `test-strategy.md` §3 makes this mandatory. `app/src/test/java/com/clavaris/app/migration/MigrationDataPreservationTest.java` is the worked-example template: apply schema to version N-1, seed representative rows, apply migration N, assert the data is intact and correctly transformed. Live-verified both ways during development — a drop-and-recreate version of the example migration (a common real mistake when "renaming" a column) was confirmed to fail this test before the correct `RENAME COLUMN` version was confirmed to pass it. Copy this pattern for the first real migration that touches existing data; a migration that only ever adds new, empty structures doesn't need it.

## 5. Open questions

- ADR-0010's own open questions (the system-wide rate-limit ceiling value, automated key-rotation trigger criteria) directly affect `rate_limit_policies` — tracked in the ADR, not duplicated here.
- Whether `sessions`/`refresh_tokens` need partitioning or an aggressive TTL-based cleanup job once volume grows — not a concern at current expected scale (single-digit consumers), flagged for revisit once real usage data exists.
- Exact representation of `redirect_uris`/`allowed_grant_types`/`allowed_scopes` (JSON text column vs. normalized child tables) — deferred to implementation time, noted as a real open design choice rather than settled.
- `event_outbox`/`webhook_deliveries` retention and archival policy — not designed yet, same "revisit once real usage data exists" stance (ADR-0007's own open questions).
