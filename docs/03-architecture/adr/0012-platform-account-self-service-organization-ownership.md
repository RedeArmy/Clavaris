# ADR-0012: PlatformAccount — self-service human ownership of Organizations

**Status:** ✅ Aprobado

## Context

Until now, `Organization` creation was gated exclusively by `PlatformClient` (ADR-0010) — a machine credential (`client_credentials` grant), env-seeded at bootstrap, never reachable via a human-facing signup flow. That is correct for the platform's own trust root, but it means there is no way for a human to sign up to Clavaris itself and self-service-create/manage the Organization(s) they own — every Organization today is provisioned by an operator running a script against the management API.

Separately, building branded transactional email (TD-SEC-004, ADR-0011) surfaced a real distinction this project's domain model didn't have a name for yet: an email tied to a specific `Organization` (e.g., a "Kiniela" end-user's verification email, branded with that Organization's name) is a fundamentally different kind of recipient than a person who *owns* one or more Organizations at the platform level and needs a generic, "Clavaris"-branded email instead.

## Decision

Introduce **`PlatformAccount`** — a human, self-service identity that lives at the platform tier, structurally parallel to `Account` (a tenant end-user, always scoped to exactly one `Organization`) and to `PlatformClient` (a machine credential, belongs to no `Organization`). `PlatformAccount` belongs to no `Organization` either, but **owns** zero or more of them: `organizations.owner_platform_account_id`, single-owner model (one owner per Organization; one owner may own many Organizations — no membership/roles model yet).

**Identity mechanics** mirror `Account`'s exactly (register, password login, email verification, password reset — same `VerificationToken`-shaped machinery, a new parallel `PlatformVerificationToken`/`PlatformPasswordCredential`), with generic "Clavaris" branding (never a per-Organization one, since a `PlatformAccount` isn't scoped to one) via a new `PlatformMailSender` port, implemented by the same `ResendMailSender` adapter that already implements the tenant-tier `MailSender`.

**Authentication model:** a plain, session-authenticated `HttpSession` (`/platform/login`), *not* an OAuth token issuance. The platform tier's own OAuth issuer (`{clavarisBaseUrl}/oauth2/...`, ADR-0010) stays `client_credentials`-only — adding a full second Authorization-Code+PKCE flow just for Clavaris's own first-party dashboard would duplicate the entire `OrganizationAuthorizationServerConfig`/`LoginController`/`AuthenticatedSessionEstablisher` stack for no real benefit, since no third party ever consumes a `PlatformAccount`'s identity via a token. This also means no `PlatformSession`/`PlatformRefreshToken` domain tables — BR-ID-04's "revoke everywhere on password reset" equivalent uses Spring Security's own `SessionRegistry`/`ConcurrentSessionFilter` (`sessionConcurrency` wiring, `expiredUrl` set to `/platform/login` for a real redirect rather than the framework's own default plain-text "session expired" response — confirmed live that the default isn't a redirect before fixing it).

**Dashboard:** a new session-authenticated web surface, `PlatformOrganizationDashboardController` (organization-module) — lists and creates the authenticated `PlatformAccount`'s own Organizations, calling the *same* `CreateOrganizationUseCase` the existing Bearer-token REST endpoint (`POST /api/v1/admin/organizations`, unchanged, still `PlatformClient`-gated for ops/automation use) already used. `CreateOrganizationCommand` now always carries `ownerPlatformAccountId` — derived from the session principal on the dashboard path, supplied explicitly in the request body on the REST path (an ops script creating an Organization on a customer's behalf must name who owns it).

## Consequences

- **Positive:** a real self-service path to Clavaris's own "customer" role exists — pulls forward part of what `roadmap-and-release-plan.md` §3 calls "self-service client registration console" (v1.1), specifically the *account* half of it, ahead of schedule, because the branding work needed the concept to exist regardless.
- **Positive:** no new OAuth surface, no new signing-key tier, no new token-revocation cascade to build/maintain — the session-based choice keeps this addition proportional to what it actually needs to do.
- **Negative, known limitation:** a single `HttpSession` holds one `SecurityContext`. A person logged into a tenant `Account` (`/o/{organizationId}/login`) and the platform dashboard (`/platform/login`) in the same browser session will have one authentication silently overwrite the other. Not a security hole (each request is still checked against whichever context is actually current) but a real UX gap for the rare case of one person acting as both their own end-user and their own platform owner in one browser tab. Not solved here — tracked as a real gap, not silently ignored.
- **Negative:** `organizations.owner_platform_account_id` is a plain `UUID` column, not a real foreign key — same deliberate cross-module-FK gap already recorded on `accounts.organization_id`'s own migration, organization-module and identity-module stay schema-independent too. Referential integrity for this column is application-layer only.
- **Negative:** single-owner-per-Organization is a real simplification — no way yet for two people to jointly manage one Organization's billing/settings (explicitly deferred, see Alternatives below).

## Alternatives considered

- **Multi-owner / membership model** (a `Workspace`-style `OWNER`/`ADMIN`/`MEMBER` table at the platform tier) — rejected for now: real additional surface (invitations, roles, a new membership table) with no immediate consumer; single-owner is the simpler model that doesn't block adding roles later without a breaking migration (an owner-membership row can be added alongside the existing FK).
- **Full OAuth Authorization-Code issuance for `PlatformAccount`** (mirroring the tenant tier exactly, `PlatformSession`/`PlatformRefreshToken` tables, a first-party `RegisteredClient` for the dashboard) — rejected: no third party ever needs a `PlatformAccount`'s identity as a portable token; a plain session is simpler, and this project's own "don't build ahead of the need" principle applies directly.
