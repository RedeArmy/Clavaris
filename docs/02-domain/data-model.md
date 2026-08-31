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
    PLATFORM_ACCOUNTS ||--o{ ORGANIZATIONS : "owns (ADR-0012)"
    PLATFORM_ACCOUNTS ||--o{ PLATFORM_PASSWORD_CREDENTIALS : has
    PLATFORM_ACCOUNTS ||--o{ PLATFORM_VERIFICATION_TOKENS : requests
    ACCOUNTS ||--o{ PASSWORD_CREDENTIALS : has
    ACCOUNTS ||--o{ SOCIAL_IDENTITIES : links
    ACCOUNTS ||--o{ SESSIONS : opens
    ACCOUNTS ||--o{ VERIFICATION_TOKENS : requests
    SESSIONS ||--o{ REFRESH_TOKENS : issues
    ACCOUNTS ||--o{ WORKSPACE_MEMBERSHIPS : "belongs to"
    WORKSPACES ||--o{ WORKSPACE_MEMBERSHIPS : has
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
    PLATFORM_ACCOUNTS {
        uuid id PK
        varchar email UK
        timestamptz email_verified_at
        varchar status
        timestamptz created_at
    }
    PLATFORM_PASSWORD_CREDENTIALS {
        uuid id PK
        uuid platform_account_id FK
        varchar password_hash
        timestamptz updated_at
    }
    PLATFORM_VERIFICATION_TOKENS {
        uuid id PK
        uuid platform_account_id FK
        varchar type
        varchar token_hash UK
        timestamptz expires_at
        timestamptz consumed_at
    }
    ORGANIZATIONS {
        uuid id PK
        varchar name
        timestamptz created_at
        uuid owner_platform_account_id
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
        uuid account_id "no FK - cross-module, see table notes"
        varchar role
        timestamptz created_at
    }
    OAUTH_CLIENTS {
        uuid id PK
        uuid organization_id FK
        varchar client_id UK
        varchar client_secret_hash
        text redirect_uris
        text allowed_grant_types
        text allowed_scopes
        boolean require_consent
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

- **`accounts.organization_id`** — ADR-0010: mandatory, the tenant isolation boundary. Plain `uuid`, no `REFERENCES organizations` constraint — a cross-module FK from identity-module's own `accounts` table to organization-module's `organizations` table was tried (BR-DATA-02/03's own organization-deletion work) and reverted: each module's own Testcontainers-backed test suite only scans its own `db/migration` folder, so the migration failed identity-module's own isolated tests with "relation does not exist" even though it passed a combined `app`-context verification. Referential integrity here is enforced at the application layer instead — `DeleteOrganizationService` (organization-module) explicitly erases every Account an Organization owns, via a cross-module port/bridge, before deleting the `organizations` row itself. `accounts.email` is **no longer** globally unique — uniqueness is `(organization_id, email)` (§3); the same email may exist as unrelated `Account` rows in different organizations. `status` is an enum (`ACTIVE`, `SUSPENDED`, `DELETED` — deletion per BR-DATA-03 is a hard delete in v1, so a `DELETED` status is transitional at most, present mainly for audit-log correlation before physical removal completes).
- **`organizations`** — ADR-0010: one row per consuming system (tenant isolation boundary), not to be confused with `workspaces` below. Owns its own isolated `accounts` and `oauth_clients`.
- **`organizations.owner_platform_account_id`** — ADR-0012: mandatory, the owning `platform_accounts` row. Plain `uuid`, no `REFERENCES` constraint — organization-module and identity-module stay schema-independent, same deliberate gap already recorded on `accounts.organization_id`.
- **`platform_accounts`**/**`platform_password_credentials`**/**`platform_verification_tokens`** — ADR-0012: mirror `accounts`/`password_credentials`/`verification_tokens` exactly, minus any `organization_id` scoping — `platform_accounts.email` is globally unique (no Organization to scope it by). No `platform_sessions`/`platform_refresh_tokens` table exists: a `PlatformAccount` authenticates via a plain `HttpSession`, never an OAuth token, so there is nothing here for those tables to model.
- **`workspaces`**, **`workspace_memberships`** — ADR-0010 §3 addendum (2026-08-27): renamed from the pre-ADR-0010 `organizations`/`memberships` tables (same "company/team grouping" semantics, renamed to free up "organization" for the new tenant-isolation meaning). `workspaces.organization_id` is mandatory and has a real `REFERENCES organizations (id) ON DELETE CASCADE` FK — both tables are this same module's own, no cross-module test-isolation concern applies (same reasoning `rate_limit_policies.organization_id` already established). `workspace_memberships.workspace_id` likewise has a real, same-module, `ON DELETE CASCADE` FK to `workspaces`. `workspace_memberships.account_id` has **no** FK — it references identity-module's `accounts` table, same cross-module test-isolation gap already recorded on `accounts.organization_id`; a dangling row is prevented at the application layer instead (`WorkspaceMembershipEraserBridge`, called synchronously from `DeleteAccountService` before an Account row disappears — ADR-0007). `role` is `ADMIN | MEMBER` only in v1 (no `OWNER`, no DB check constraint — enforced at the application layer, `business-rules.md` BR-WS-01/05). `workspace_invitations` is **deferred to v1.1+, not built** — v1 provisions members directly (BR-WS-04); no invitation table exists yet.
- **`password_credentials`**, **`social_identities`** — deliberately separate tables from `accounts`, never a nullable `password_hash` column bolted onto `accounts` — keeps BR-ID-02 (multiple auth methods, never zero) a natural query (`COUNT` across both tables) rather than a null-check special case.
- **`social_identities`** (ADR-0020, BR-ID-09) — `provider varchar` (`GOOGLE`/`GITHUB` in v1), `provider_user_id varchar` (the provider's own opaque subject id, never the email), `account_id` FK to `accounts`, `linked_at timestamptz`. `UNIQUE(provider, provider_user_id)` — the same real-world Google/GitHub identity can never link to two different `accounts` rows at once; `UNIQUE(account_id, provider)` — one link per provider per account (an `Account` can have at most one linked Google identity and one linked GitHub identity). BR-DATA-04: cascades away on account deletion.
- **`platform_social_identities`** (ADR-0020, BR-ID-10) — mirrors `social_identities` exactly, `platform_account_id` instead of `account_id`, no `Organization` scoping (same mirroring convention `platform_password_credentials` already establishes).
- **`pending_social_links`** (ADR-0020 Decision 1, BR-ID-09) — the confirmation-step table: `account_id` (or `platform_account_id`), candidate `provider`/`provider_user_id`, `confirmation_token_hash` (never the raw token, same principle as `verification_tokens.token_hash`), `expires_at`, `consumed_at`. Consuming it (matching token, unexpired) is what inserts the real `social_identities`/`platform_social_identities` row — this table is never itself a valid authentication method.
- **`organizations.social_login_enabled`** (`boolean NOT NULL DEFAULT false`) / **`organizations.allowed_social_providers`** (`text`, JSON array, e.g. `["GOOGLE","GITHUB"]`) — ADR-0020 Decision 3, BR-ID-12: governs only whether social login is *additionally* offered on top of email/password, which stays permanently available regardless of this row's own values — no column anywhere gates email. Same "`text` JSON array in v1, normalize later if needed" convention `oauth_clients.allowed_scopes`/`redirect_uris` already established (TD-ARCH-003) — a bounded, small provider set doesn't yet justify a child table.
- **`sessions.scopes`** — BR-ID-03, real (not placeholder) shape: `text` (JSON array, same convention as `oauth_clients.allowed_scopes`), fixed at open time — RFC 6749 §6 forbids a refresh grant from ever widening scope beyond what the original authorization actually granted, so every `refresh_tokens` row in this session's chain is validated against this same, unchanging set. Replaces this document's original placeholder `user_agent` column — still no use case populates one; the "list your active sessions/devices" feature this note used to say didn't exist yet (BR-ID-13) now does, but it's built on the Spring-Session-backed `HttpSession` store instead (no table — Redis-only, see `SessionDeviceAttributes`), not on this table.
- **`known_devices`** (BR-ID-14, new-device login email notification) — `account_id` FK (`REFERENCES accounts (id) ON DELETE CASCADE`, from day one — unlike the social-login tables, which needed a follow-up migration to add this), `user_agent varchar(512)` (the raw header, not hashed — it isn't a secret), `first_seen_at`/`last_seen_at timestamptz`. `UNIQUE(account_id, user_agent)`. Deliberately a separate table from `sessions`/`HttpSession` — a device fingerprint needs to outlive any one 30-minute-TTL `HttpSession`, which gets a brand-new id on every login even from the same physical browser.
- **`refresh_tokens.token_hash`** — the token itself is never stored, only its hash (same principle as `verification_tokens.token_hash` and `authorization_codes.code_hash`, and the same principle JobSeeker applied to its own retired refresh-token design before this module absorbed it). Deliberately never looked up via `oauth2_authorization` below, even though that table also tracks a `refresh_token_value` column — BR-ID-03's rotation/reuse-detection logic is fully decoupled from SAS's own table, which is what kept this one hash-only from day one, before TD-SEC-019 (below) closed the same gap for `oauth2_authorization` itself.
- **`refresh_tokens.rotated_from_id`** — self-referencing FK forming the rotation chain domain-model.md §2 describes. The real implementation's reuse check doesn't walk this chain at runtime — a presented token whose own row already has `revoked_at` set is reuse, full stop (rotating away and the BR-ID-03 revocation cascade are the only two things that ever set it) — `rotated_from_id` is kept purely as an audit trail for investigation, same spirit as `signing_keys.retired_at`. See `RefreshToken`'s own Javadoc (identity-module) for the full reasoning.
- **`signing_keys`** — the private key material itself is **not** in this table; only metadata (`kid`, algorithm, validity window). Actual key material lives in a key store referenced by `TOKEN_SIGNING_KEY_STORE_PATH` (`.env.example`), never in the database. `organization_id` is mandatory (ADR-0010 §5) — JWKS is per-`Organization`, so `kid` is only unique *within* an organization, not globally (§3). No `REFERENCES organizations` constraint — same cross-module test-isolation reason recorded on `accounts.organization_id` above; `DeleteOrganizationService` erases every SigningKey an Organization ever rotated through, application-layer, before deleting the `organizations` row. Every `Organization`'s issuer is `{clavarisBaseUrl}/o/{organization_id}` (ADR-0010 §5.1, path-based, not subdomain) — `iss` and `jwks_uri` are both scoped under that path. Rotation in v1 is a manually-triggered, audited management-API operation per organization (ADR-0010 §5.2), not a scheduled job.
- **`rate_limit_policies`** — ADR-0010 §6.2: **capacity ceiling only**, one optional row per `Organization` (`UNIQUE(organization_id)`), overriding the system-default aggregate request ceiling for that tenant. A missing row means "use the system default." The application layer, not this table, enforces the hard system-wide ceiling an Organization's own policy can never exceed. **v1: operator-managed only** (no tenant self-service until v1.1, gated on audit logging). This table does **not** govern anti-abuse/credential-stuffing thresholds — those are fixed, system-wide, keyed by `(organization_id, account_or_ip_identifier)`, enforced directly in Redis with no corresponding table (ADR-0010 §6.1) so they can never be loosened by a tenant.
- **`oauth_clients.organization_id`** — ADR-0010: mandatory, the tenant isolation boundary. No `REFERENCES organizations` constraint — same cross-module test-isolation reason recorded on `accounts.organization_id` above; isolation itself is enforced by every lookup/query being scoped by this column, not by a DB-level FK, and `DeleteOrganizationService` erases every OAuthClient an Organization ever registered, application-layer, before deleting the `organizations` row. One organization may register several `oauth_clients` (e.g. web + mobile for the same system), all sharing that organization's isolated account pool.
- **`oauth_clients.client_secret_hash`** — same hash-not-plaintext principle as passwords; `redirect_uris`/`allowed_grant_types`/`allowed_scopes` stored as `text` (JSON array) in v1 — a normalized child table is a reasonable future refactor if per-URI querying is ever needed, not needed yet.
- **`oauth_clients.require_consent`** — TD-SEC-026/ADR-0017: per-client, secure-by-default (`true`) — whether SAS must show the end user a consent screen before issuing this client an authorization code. `V20260825100000` backfills every already-registered client to `true`; an operator opts a trusted first-party client out explicitly (`requireConsent: false` at registration), never by omission.
- **`oauth2_authorization`** — TD-SEC-003 (closed): backs Spring Authorization Server's own `JdbcOAuth2AuthorizationService`, not a bespoke table this project designed — every authorization code, access token, refresh token, and OIDC ID token either issuer tier issues, one row per authorization. Its real shape is the framework's own upstream schema (Postgres-adapted per that file's own embedded instructions), richer than this document's original placeholder sketch (`AUTHORIZATION_CODES`, now retired) since it tracks every token type SAS itself tracks per grant, not just the authorization code. **Deliberately one shared table for both the platform tier and every Organization**, not the usual two-tables-per-tier split this document uses everywhere else (`platform_clients`/`oauth_clients`, `platform_signing_keys`/`signing_keys`) — `JdbcOAuth2AuthorizationService` hardcodes its own table name internally, so two differently-named tables aren't supported without forking the class; `registered_client_id` still fully separates platform-tier rows from Organization-tier rows, since the two tiers' client id spaces are already structurally disjoint. Bearer values (`*_value` columns): **TD-SEC-019 (closed)** — now HMAC-SHA256-hashed, matching this project's own `password_credentials`/`refresh_tokens` convention after all. `JdbcOAuth2AuthorizationService.findByToken` itself gives no knob for this (a fixed, non-overridable SQL `WHERE` clause comparing the literal presented value), so a wrapping `HashedTokenOAuth2AuthorizationService` (`app` module, keyed by `clavaris.oauth2.token-hash-secret`) hashes the value before it ever reaches this table and, on lookup, hashes the search term, queries, then restores the raw value in memory for the one matching token slot SAS's own revocation/introspection providers immediately re-compare it against — never re-persisted in that form. `refresh_token_value`/`user_code_value`/`device_code_value`/`state` are never populated by this codebase's own grant wiring (refresh tokens use `refresh_tokens.token_hash` above instead, fully decoupled from this table) and are passed through unhashed on the rare off-chance a lookup ever targets them. BR-ID-03's refresh-grant handling (`RefreshTokenRotationAuthenticationProvider`) saves a brand-new row here on every rotation rather than updating the previous grant's row in place — TD-ARCH-005, unbounded row growth on a long-lived, frequently-refreshed session, tracked rather than silently accepted.
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
| `workspace_memberships` | unique `(workspace_id, account_id)` | BR-WS uniqueness — one Account provisioned once per Workspace (v1 has no re-add flow) |
| `workspace_memberships` | `(account_id)` | `WorkspaceMembershipEraserBridge`'s own cross-module cascade query (ADR-0007) |
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
- `event_outbox` retention **is now designed and shipped** (TD-TEST-002, 2026-08-24): `EventOutboxRetentionJob` (identity-module) sweeps rows older than `clavaris.event-outbox.retention-days` (90 by default) daily, by age alone — not by `published_at`, since `webhook-module`'s dispatcher doesn't exist yet and every row's `published_at` stays `NULL` regardless of age. See that class's own Javadoc for why this is the honest interim policy and what has to be revisited the day `webhook-module` ships. `webhook_deliveries` retention remains genuinely undesigned — that table doesn't exist yet (same "revisit once real usage data exists" stance, ADR-0007's own open questions).
