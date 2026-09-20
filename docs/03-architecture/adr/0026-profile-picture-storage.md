# ADR-0026: Profile picture storage — Supabase S3-compatible bucket, proxied through Clavaris

**Status:** ✅ Approved (2026-09-20)

## Context

Clerk-dashboard parity work (2026-09-19 SDE-III review) added the Users tab's operator-facing view
of an Account's profile (firstName/lastName/phoneNumber). The natural next piece — a real profile
picture, both uploadable by the Account holder and captured automatically from Google/GitHub at
social sign-up — has no supporting infrastructure anywhere in this codebase yet: no file/object
storage adapter of any kind exists (confirmed by search — no `MultipartFile` handling, no S3
client, no object-storage bucket in any `docker-compose*.yml`), and `Account` carries no picture
field. This is a genuinely new storage-backend decision, the same tier as ADR-0004
(PostgreSQL + Redis) — locked-decision territory, not a detail to bury inside a use case's own
Javadoc.

The OIDC-facing half of this feature is *not* new infrastructure: `WorkspaceAwareOidcUserInfoMapper`
already forwards the full standard `profile` claim group (`name`, `picture`, `preferred_username`,
…) from the ID token to `/userinfo` — its own Javadoc states plainly that this codebase "doesn't
populate any of `profile`'s own sub-claims today, so that half is currently a no-op, but must stay
correct the day it isn't." That day is this ADR.

Three storage options were weighed:

1. **PostgreSQL `bytea`** — zero new infrastructure, but this codebase's own Postgres instance is
   already the schema of record for every tenant's accounts/organizations/clients; growing it with
   binary blob storage is exactly the kind of scope creep ADR-0004 didn't sign up for.
2. **Local disk (Docker volume)**, same shape as `clavaris-signing-keys-data` — breaks the moment
   `app` runs as more than one instance without a shared volume, a real constraint on this
   self-hosted, single-tenant-deployable system's own eventual production topology.
3. **Supabase Storage (S3-compatible)** — the option actually chosen (operator's own explicit
   infrastructure choice, not a default this ADR invented): Supabase exposes an S3-compatible
   endpoint (`https://<project-ref>.supabase.co/storage/v1/s3`) alongside access-key/secret
   credentials, so the well-vetted `software.amazon.awssdk:s3` client talks to it exactly as it
   would talk to real AWS S3 — no proprietary Supabase SDK dependency needed, consistent with
   CLAUDE.md's "build on vetted foundations, don't hand-roll" posture (§1).

## Decision

Profile pictures are stored in a Supabase Storage bucket via the S3-compatible API, **but no
Supabase/S3 URL is ever handed out directly** — not in the OIDC `picture` claim, not in any
Clavaris-rendered page. Every consumer (Clavaris's own hosted UI, a consuming application's own UI
reading the `picture` claim, JobSeeker or any future consumer) is handed one stable, Clavaris-owned
URL instead: `GET /o/{organizationId}/avatars/{accountId}`. That endpoint is what actually reaches
into Supabase (streaming the object back) when a real uploaded picture exists, or synthesizes an
initials-plus-deterministic-color SVG on the fly when it doesn't (never a 404, never an omitted
claim — see the profile-picture use cases' own Javadoc for the initials-avatar algorithm).

This indirection is deliberate, not incidental complexity:

1. **Storage backend stays swappable.** `ProfilePictureStorage` (application-layer port,
   `identity-module`) is the only thing that knows Supabase exists; a future move to a different S3-
   compatible provider, or back to local disk, touches one infrastructure adapter, never a consuming
   application's integration.
2. **No public-bucket requirement.** The Supabase bucket can stay private — Clavaris holds the only
   credentials that can read it, exactly the same "Clavaris is the trust boundary" posture ADR-0002
   (asymmetric signing keys) and ADR-0010 (per-Organization JWKS) already establish for every other
   credential in this system.
3. **A stable URL for a claim that outlives any single request.** An S3 presigned URL expires; the
   `picture` claim on an already-issued ID token does not get re-issued just because a presigned URL
   ran out. A Clavaris-owned URL that always resolves to "this Account's current picture, whatever
   it is right now" has no such expiry problem.
4. **Cache-Control lives at the one place that should own it.** The avatar endpoint sets caching
   headers itself (same `ResourceUrlEncodingFilter`-adjacent reasoning as TD-PERF-024's static-asset
   work), independent of whatever caching behavior Supabase's own CDN layer does or doesn't provide.

Scope: this ADR covers `Account` (tenant end users) only. `PlatformAccount` (Clavaris's own internal
operators) is explicitly out of scope — no requirement named it, and the platform dashboard's own
account menu still uses its static generic-person SVG icon.

## Consequences

- **Positive:** the OIDC-standard `profile` claim group Clavaris already plumbs through to
  `/userinfo` becomes genuinely populated — a consuming application gets `picture`/`name`/
  `preferred_username` for free via standard OIDC client libraries, no bespoke API, matching CLAUDE.md
  §2's "integration cost for a new consumer: standard OIDC client libraries, under a day" metric.
- **Positive:** every avatar Clavaris ever serves — real upload, social-provider-sourced, or the
  generated-initials default — comes from the exact same URL shape and code path, so a consuming
  application's `<img src>` never needs to special-case "no photo."
- **Negative:** a real new secret class to protect (`SUPABASE_S3_ACCESS_KEY_ID`/
  `SUPABASE_S3_SECRET_ACCESS_KEY`) — mitigated the same way ADR-0022's own social-credential secret
  is: a dedicated `.env.example` entry from day one, never silently reused from an unrelated key.
- **Negative:** Clavaris's own `app` instance is now a mandatory proxy in the read path for every
  avatar view — an extra network hop (Clavaris → Supabase) on a cache miss that a direct-to-bucket
  URL wouldn't have. Accepted deliberately (see Decision, point 3) in exchange for URL stability and
  never needing a public bucket.
- **Negative:** Supabase is a new external dependency this system did not have before — an outage on
  their end degrades avatar rendering (never blocks login/token issuance; the avatar endpoint's own
  initials-SVG fallback only covers "no picture set," not "storage backend unreachable," which is a
  known, accepted gap for v1, not silently pretended away).

## Alternatives considered

- **PostgreSQL `bytea`** — rejected: no new infrastructure, but grows this system's schema of record
  with binary blob storage ADR-0004 never scoped it for, and every read competes with the same
  connection pool serving actual auth traffic.
- **Local disk volume** — rejected: breaks under horizontal scaling of the `app` instance, a
  constraint this system's own production topology should not be permanently locked into for a
  feature this narrow.
- **Hand out Supabase's own URL (public bucket or presigned) directly, no Clavaris proxy** — rejected
  per Decision above: presigned URLs expire against a claim that outlives the request, and a public
  bucket surrenders the "Clavaris is the one trust boundary" posture every other credential in this
  system already follows.
