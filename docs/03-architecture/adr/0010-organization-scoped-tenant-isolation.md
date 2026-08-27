# ADR-0010: Organization-scoped tenant isolation (Account belongs to exactly one Organization)

**Status:** ✅ Aprobado (2026-08-22 — see "Ratification" addendum below)

## Context

The domain model as originally documented (pre-ADR-0010) defined `Organization` as a lightweight tenant grouping *within* one consumer application's usage of Clavaris — closer to "a company workspace inside a SaaS product" — while `Account` was a single **global** identity shared across every consumer, and every `Organization` a consumer might create.

That shape does not match the actual product requirement: Clavaris must host multiple, independent consuming systems (JobSeeker today, others later) whose user bases must be **structurally incapable** of crossing into each other. A user of consumer system A must not be able to authenticate against consumer system B, and gaining access to B requires a separate, explicit registration — not an invitation, not a shared login. This is closer to a Keycloak "realm" than to a Slack-style workspace.

This also resolves a question that was already flagged as open: `business-rules.md`'s BR-DATA-03 open question asked what happens if "the same person" has one Clavaris account used to log into two different consumers — under the model this ADR establishes, that scenario is structurally impossible by design, not an edge case to handle later.

## Decision

### 1. `Organization` is redefined as the tenant isolation boundary — one row per consuming system

`Organization` no longer means "workspace within one app." It now means: one isolated tenant, corresponding to one consuming system (e.g., "JobSeeker" is one `Organization` row). An `Organization` owns its own, fully independent pool of `Account`s.

- **`Account.organizationId`** is now a mandatory FK. The uniqueness constraint on `accounts` moves from a global `UNIQUE(email)` to `UNIQUE(organization_id, email)`.
- The **same email address may exist as two entirely separate, unrelated `Account` rows** in two different organizations — different password hash, different verification state, no linkage, no shared session, no shared refresh-token family. Registering in Organization A does not create or affect anything in Organization B.
- There is deliberately **no cross-organization SSO or account linking** in this design. A person who needs access to both JobSeeker and a future second consumer registers twice, once per Organization — this is the explicit product requirement, not an oversight.

### 2. `OAuthClient` belongs to exactly one `Organization`; an `Organization` may register more than one `OAuthClient`

`OAuthClient` keeps its existing meaning (a protocol-level registration: `client_id`, `redirect_uris`, allowed grants/scopes) but gains a mandatory `organizationId` FK. One consuming system can register several `OAuthClient`s under the same `Organization` (e.g. a web app and a mobile app for the same system) — they share the same isolated account pool, because they belong to the same tenant.

This is the actual isolation mechanism, not just a labeling convention: the hosted login page rendered for a given `client_id` resolves that client's `Organization` first, and only ever authenticates against that Organization's `Account` pool. There is no code path where a login screen for Client X can see or authenticate an `Account` belonging to a different `Organization` — the accounts simply don't exist in that query space.

### 3. The old "company workspace inside one app" concept survives, renamed to `Workspace`, nested inside an `Organization`

The pre-ADR-0010 `Organization`/`Membership`/`Invitation` triple (roles `OWNER`/`ADMIN`/`MEMBER`, invitations, `BR-ORG-01..03`) was a real, wanted feature — it just collided with the new meaning of the word "Organization." It is renamed:

| Old name | New name |
|---|---|
| `Organization` (workspace meaning) | `Workspace` |
| `Membership` | `WorkspaceMembership` |
| `Invitation` | `WorkspaceInvitation` |

A `Workspace` lives **inside** exactly one `Organization` — `Workspace.organizationId` is mandatory. `WorkspaceMembership.accountId` references an `Account`, and because that `Account` already belongs to the same `Organization` as the `Workspace` (accounts can't exist outside their Organization), workspace membership is structurally confined to one tenant's account pool without needing an extra cross-check. Business rules `BR-ORG-01..03` are renumbered `BR-WS-01..03` (unchanged in substance, only the ID prefix changes to avoid colliding with the new `BR-ORG-*` rules this ADR introduces).

### 4. `organization-module` becomes the shared tenant root that `identity-module` and `client-registry-module` depend on

This is a new cross-module dependency direction, but it does not violate the existing hexagonal dependency rule: both `Account.organizationId` and `OAuthClient.organizationId` reference `organization-module`'s `Organization` **by UUID only**, through each module's own port — the same "no live object reference across the boundary" discipline already used for `Membership.accountId` (`domain-model.md` §6).

### 5. JWKS is per-`Organization` — which requires a per-`Organization` issuer, not just a per-`Organization` key

Resolved (was an open question in an earlier draft of this ADR, then found to be underspecified in design review — see history note at the end of this section). **Each `Organization` gets its own RS256 key pair, its own discovery document, and its own `jwks_uri`.** `SigningKey.organizationId` becomes a mandatory FK.

**5.1 Issuer strategy — path-based, decided now, not deferred**

A JWT's `iss` claim is what a verifier uses to locate `{iss}/.well-known/openid-configuration` and, from there, `jwks_uri` — there is no protocol step where a verifier sends a `client_id` to Clavaris to ask which keys to trust. Per-Organization JWKS is therefore only meaningful if the **issuer itself is per-Organization**: every `Organization` gets its own issuer URL, `{clavarisBaseUrl}/o/{organizationId}`, with its own discovery document, `authorization_endpoint`, `token_endpoint`, and `jwks_uri` scoped underneath it. Every token minted for that Organization carries that issuer in `iss`.

- **Chosen over a subdomain-per-Organization scheme** (`{org-slug}.clavaris.io`) for v1: a subdomain scheme needs either a wildcard TLS certificate or per-tenant dynamic certificate issuance — exactly the operational burden ADR-0009 already identifies as "real infra work, not needed yet" for the *unrelated* branded-login feature, and there is no reason to pay that cost twice for two different features. Path-based issuance works today with the single TLS certificate `clavaris.io` already needs, no DNS or certificate automation required.
- **Directly supported by the existing stack, not a bespoke mechanism:** Spring Authorization Server (ADR-0003) has first-class support for resolving the issuer from the incoming request path (multi-tenancy via `AuthorizationServerContext`) — this is the framework's own documented pattern for exactly this scenario, not something Clavaris has to hand-roll on top of it. Reinforces ADR-0001's own premise (protocol correctness delegated to a vetted framework).
- **This is a decided, load-bearing piece of the data model, not a deployment detail:** `OAuthClient`'s registered `redirect_uris` and the discovery URL a consumer configures are now organization-scoped from day one. Because `iss` is embedded in every issued token and pinned by every verifier's config, changing this shape after a consumer integrates is a breaking migration — deciding it now, before any `Account` or `OAuthClient` row exists, is deliberately the cheapest time to get it right.
- A verifier application only ever needs the JWKS of the one `Organization` it belongs to, fetched once from its own org-scoped discovery document — no `client_id`-based lookup, no ambiguity.

**5.2 Key rotation — scoped for v1, not left as an unscoped "automate this later"**

Key rotation with overlap (never a single key with unbounded lifetime, previous key stays published until every token under it expires) applies **independently per Organization**; rotating one Organization's key has no effect on any other tenant's keys or schedule. Given a solo-developer team and the mandatory external-security-review gate before any consumer sends real traffic, full unattended automation is real scope that a design review flagged as too easily left undesigned — so v1 ships a deliberately narrow slice instead of an open-ended promise:

- **v1:** an authenticated, audited management-API operation (`POST /api/v1/organizations/{id}/signing-keys/rotate`) that generates a new key, marks it active, and sets `retiredAt` on the previous key — but does **not** delete the previous key's material or remove it from JWKS until its last issued token naturally expires (same overlap guarantee, manually triggered instead of scheduled). This is a small, reviewable piece of code, not a cron/scheduler subsystem, and it is enough to satisfy the "keys rotate with overlap" invariant for a small number of organizations operated by hand.
- **v1.1:** scheduled, unattended rotation (a real scheduler-driven job with alerting on failure) once there's enough organization count that manual triggering becomes the operational risk instead of the mitigation. Tracked in the roadmap, not left as an undated aspiration.
- **Rationale:** consistent with the rest of this ADR — a compromised signing key for one tenant must not put any other tenant's tokens at risk. A shared key would make the blast radius of a single key compromise "every consumer of Clavaris," which contradicts the isolation guarantee §1–§2 already establish for accounts and clients. Scoping rotation to a manual-but-real v1 slice avoids the worse failure mode design review flagged: N independent key pairs with **no** designed rotation mechanism at all is a bigger security risk than the shared-key baseline this ADR replaces.

*(History note: an earlier draft of this section asserted JWKS was "resolved via the `client_id` presented" and left key-rotation automation as an undated open question. Both were corrected during design review before implementation began — see git history of this file for the prior text if needed.)*

### 6. Rate limiting is per-`Organization`, split into two layers — only one of which is tenant-configurable

Every `Organization` gets rate-limiting protection on the login (`/oauth2/token`, password login) endpoints from creation (BR-ID-06) — mandatory, never absent. This is **two separate controls**, not one, because collapsing them into a single per-organization aggregate (as an earlier draft of this ADR did) both under-protects against credential stuffing *and* risks throttling legitimate traffic — the exact self-inflicted-outage failure mode BR-ID-06 already exists to avoid (`clerk-feature-analysis.md` §7 item 2).

**6.1 Anti-abuse layer — fixed, system-defined, never tenant-configurable**

Keyed by `(organization_id, account_or_ip_identifier)` — e.g. failed attempts per account, attempts per source IP within one Organization. This is the actual defense BR-ID-06 exists for (an attacker throwing repeated guesses at one account or spraying from one IP), and it is **not** something a tenant can loosen: exposing anti-abuse thresholds as a customer-facing knob is a known anti-pattern (a compromised or careless tenant admin could loosen their own users' protection). System-defined defaults only, applied uniformly to every Organization, not stored per-tenant — no `RateLimitPolicy` row governs this layer.

**6.2 Capacity layer — a per-`Organization` aggregate ceiling, operator-managed in v1**

Keyed by `organization_id` alone (e.g. `ratelimit:{organization_id}:{endpoint}` in Redis, ADR-0004) — this is a **noisy-neighbor guard**, not an anti-abuse control: it exists so one Organization's traffic volume (legitimate burst or an attack absorbing 6.1's per-identifier limits across many accounts) cannot exhaust request budget shared infrastructure-wide with unrelated Organizations, protecting the ≥99.5% availability target per tenant instead of only in aggregate.

- A new entity, `RateLimitPolicy` (`organization-module`, one-to-one with `Organization`, same "separate table, optional override of a system default" convention as `ClientBranding`/`ClientDomainConfig`, `data-model.md` §2), holds this aggregate ceiling. Absence of a row means "use the system default," not "unlimited," and no organization's policy can ever exceed a hard system-wide cap.
- **v1: operator-managed only.** Consistent with `OAuthClient` registration being manual/operator-only in v1 (`prd-mvp.md` §2.2) and self-service client registration being explicitly deferred to v1.1 (`roadmap-and-release-plan.md` §3) — it would be inconsistent to make *rate-limit policy* self-service before *client registration itself* is. A tenant cannot tune its own capacity ceiling via the management API in v1; a Clavaris operator sets it on request.
- **v1.1: tenant self-service**, alongside the self-service client registration console, gated on audit logging of every policy change (who, when, old value, new value) shipping first — a silently loosened capacity ceiling during an active incident must be visible, not a blind spot.

## Consequences

- **Positive:** cross-tenant access is structurally impossible, not policy-enforced — there is no query path, no token, no session that can reach across an `Organization` boundary. This is a stronger guarantee than "Membership gates access," which was the alternative considered and rejected.
- **Positive:** resolves the open question in `business-rules.md` (BR-DATA-03) about a hypothetical shared-identity-across-consumers scenario — that scenario no longer exists by construction.
- **Positive:** matches conventional multi-tenant IdP shape (Keycloak realms, Auth0 tenants), which is a well-understood mental model for anyone integrating a new consumer.
- **Negative:** a real person who is, say, an admin of both JobSeeker and a future second consumer must register and remember two separate credentials — no unified identity across Clavaris-hosted systems. Accepted explicitly per the product requirement driving this ADR; revisit only if a genuine cross-consumer SSO need becomes real (same deferral posture as the FedCM alternative noted in ADR-0009).
- **Negative:** every `identity-module` table effectively gains a tenant dimension (directly via `accounts.organization_id`, transitively via `account_id` for `password_credentials`, `social_identities`, `sessions`, `refresh_tokens`, `verification_tokens`) — this must be reflected in every query and index from day one of implementation, not retrofitted later.
- **Negative:** renaming the pre-existing `Organization` (workspace) concept to `Workspace` touches `domain-model.md`, `data-model.md`, `business-rules.md`, and `prd-mvp.md` — a one-time documentation churn cost, paid now while no code exists yet, which is the cheapest time to pay it.
- **Positive (§5):** per-Organization JWKS makes a signing-key compromise a single-tenant incident, not a Clavaris-wide one — consistent with, and arguably the strongest expression of, the isolation guarantee this ADR exists to establish.
- **Positive (§5.1):** the issuer strategy is decided and load-bearing *before* any `Account`/`OAuthClient` exists, avoiding a breaking post-launch migration; it is also natively supported by Spring Authorization Server, not a bespoke mechanism (ADR-0001, ADR-0003).
- **Negative (§5):** key-rotation operations now scale linearly with tenant count — N organizations means N independent key pairs, N JWKS documents to keep correctly published with overlap. §5.2 scopes v1 to a manually-triggered-but-real rotation endpoint specifically to avoid this becoming undesigned scope; full unattended automation is real v1.1 work, tracked in the roadmap.
- **Positive (§6):** splitting anti-abuse (6.1, fixed, per-identifier) from capacity (6.2, tenant-tunable, per-organization aggregate) means one tenant's credential-stuffing attack or traffic spike cannot degrade another tenant's login availability *and* the core anti-abuse defense can never be loosened by a tenant — closes both the noisy-neighbor gap and the earlier draft's "tenant can loosen their own protection" gap in one design.
- **Negative (§6):** two enforcement layers instead of one is more Redis key surface and more code paths to test than a single flat limiter; the system-wide ceiling for §6.2 (the cap an Organization's own `RateLimitPolicy` can never exceed) and the fixed thresholds for §6.1 both need concrete default values chosen before `identity-module`'s login endpoint ships — not chosen in this ADR.

## Alternatives considered

- **Global `Account` + `Membership`-only access gating** (no token issued for an Organization without an explicit membership row, but the same `Account`/email can hold memberships in several organizations) — **rejected**: this was the model already documented pre-ADR-0010, and it does not satisfy the explicit requirement that a user "deberá registrarse en ambas organizaciones" (must register separately in each) — under that model, registration happens once, and joining a second organization is an invite/membership, not a new registration.
- **A new `Tenant` layer above both `Organization` and `OAuthClient`** — **rejected**, per explicit product decision: would add a third layer of indirection (`Tenant` → `Organization`(s) → `OAuthClient`(s)) not currently needed at this project's scale (single-digit consumers, `data-model.md` §4). Revisit only if a real need for multiple `Organization`s to share one billing/ops boundary emerges — not the case today.
- **Subdomain-per-Organization issuer** (`{org-slug}.clavaris.io`) instead of path-based (§5.1) — **rejected for v1**: requires wildcard or dynamically-issued TLS certificates, the same operational cost ADR-0009 already defers for the unrelated branded-login feature. Revisit only if a consumer specifically needs issuer-in-domain for their own verifier tooling — not a known need today.
- **Single flat per-Organization rate limit** (one bucket, no separate anti-abuse layer) — **rejected**, this was the initial draft of §6: it conflates "protect against a targeted account/IP attack" with "protect against noisy-neighbor traffic," under-serving the former (an attacker can spread guesses within a generous aggregate budget) while risking the latter's own worst case (throttling a legitimate traffic burst). Split into §6.1/§6.2 instead.
- **Tenant-configurable anti-abuse thresholds** (letting an Organization tune its own credential-stuffing protection, not just its capacity ceiling) — **rejected**: a compromised or careless tenant admin loosening their own users' core protection is a real, known anti-pattern in multi-tenant IdPs; kept fixed and system-defined (§6.1) instead.

## Scope decision — built in full for v1, not deferred (2026-08-17)

Whether this ADR's mechanics (§5 per-Organization issuer/JWKS, §6 two-layer rate limiting) needed to be *working* to unblock JobSeeker's Wave 1, versus shipping as "data model ready, protocol mechanics deferred to v1.1," was raised explicitly during design review (`roadmap-and-release-plan.md` §8). **Decided: build it in full, from the start.** The `iss` claim is embedded in every issued token and pinned by every verifier's own configuration — adding per-Organization issuance *after* a consumer has already integrated against a shared-issuer baseline would be a breaking migration for a live consumer, not an additive change. Paying the extra implementation time now, while `identity-module` has zero lines of code and zero real accounts, is accepted as strictly cheaper than that migration later.

This means the Spring Authorization Server spike (ADR-0003's addendum) is no longer validating an optional enhancement — it is validating the v1 baseline itself. There is no longer a "ship the simple version if the spike is harder than expected" fallback for *this* ADR's mechanics; see ADR-0003's addendum for the resulting priority change.

**Spike result (2026-08-17): GO.** §5's per-Organization issuer/JWKS mechanism is validated end-to-end against real Spring Authorization Server code — discovery, JWKS isolation, and client-registry isolation all confirmed with real HTTP calls and real RSA signature verification, not just config review. Full spike report: `docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md`; summary in ADR-0003's addendum. `identity-module`/`client-registry-module` implementation can proceed against §5 as designed.

## Organization provisioning — resolved (2026-08-17), and the bootstrap problem it exposed

**Decided: `Organization` creation is a Clavaris-operator-only action**, via `POST /api/v1/admin/organizations`, mirroring manual `OAuthClient` registration (`prd-mvp.md` §2.2) — no self-service tenant creation in v1.

Working through this surfaced a real problem the original open question didn't anticipate: the management API is protected via `client_credentials` (ADR-0006), and every `client_credentials` token is issued by *some* `Organization`'s own issuer (§5) — but creating the very first `Organization` (or any `Organization`, for that matter) can't itself be authenticated by a token scoped to an `Organization`, since the whole point of the call is that the target `Organization` doesn't exist yet. Worse: if organization-creation *were* authenticated by some tenant's own token, a compromised client of Tenant A could create or interfere with Tenant B — directly contradicting the isolation guarantee §1–§2 exist to establish.

**Resolved by introducing a platform tier, structurally separate from every tenant:**

- **A platform issuer**, `{clavarisBaseUrl}/oauth2/...` (root path, no `/o/{organizationId}` prefix) — built with the exact same mechanism §5's spike validated (its own `SecurityFilterChain`, its own JWKS, its own client registry), just not tied to any `Organization` row. It never issues tokens for end-user accounts and is never reachable through any tenant's own login flow.
- **`PlatformClient`** — a client registered at the platform tier, structurally distinct from `OAuthClient`, not a nullable-`organizationId` special case on the same table. Keeping `OAuthClient.organizationId` strictly non-null (BR-ORG-02, unweakened) is worth a second, tiny table rather than a footgun where a forgotten `WHERE organization_id IS NOT NULL` accidentally treats a platform client as belonging to some tenant, or vice versa.
- **`PlatformSigningKey`** — same reasoning as `PlatformClient`: the platform issuer signs its own tokens with its own key, structurally separate from `SigningKey` (which BR-ORG-04 already states every row of belongs to exactly one Organization — no exception carved out there either).
- **Bootstrapping the first `PlatformClient`**: seeded from environment variables (`PLATFORM_BOOTSTRAP_CLIENT_ID` / `PLATFORM_BOOTSTRAP_CLIENT_SECRET`) via an idempotent startup check — never a "break glass" HTTP endpoint, never a default credential shipped in code. This is the one piece of the system whose trust doesn't derive from something else in the system; it has to originate from the deployment environment.
- **Scope namespace**: platform-tier scopes are prefixed `platform:` (e.g. `platform:organizations:write`), reserved and structurally distinct from any per-organization management scope (`api-contract-overview.md` §6's still-open naming question) — prevents any future scope-string collision between the two tiers.
- **v1 authorization model, kept deliberately simple**: the entire `/api/v1/admin/*` surface accepts platform-tier tokens only — no tenant's own `OAuthClient` can call any management-API endpoint in v1, not even to manage its own `Organization`. Self-service (a tenant managing itself with its own credentials) is already v1.1+ scope (self-service client console, self-service `RateLimitPolicy`) — this is the same deferral, applied consistently, not a new one.

**What happens, synchronously, when `POST /api/v1/admin/organizations` succeeds:**
1. The `Organization` row is created.
2. Its initial `SigningKey` is generated and activated in the same operation — an `Organization` that exists but cannot yet issue a token is a broken intermediate state, never allowed to be observable.
3. **No `RateLimitPolicy` row is created.** Per §6.2, absence already means "use the system default" — provisioning one eagerly would just be a redundant copy of the default, not a distinct decision. An operator sets one explicitly, later, only if this tenant needs to deviate.
4. Registering the `Organization`'s first real `OAuthClient` (for its actual consumer application) is a **separate, subsequent** operator call (`POST /api/v1/admin/organizations/{id}/clients`, already in `api-contract-overview.md` §3) — provisioning a tenant and registering its first client are kept as two distinct, individually-auditable actions, not bundled into one.

## Open questions

- **Concrete default thresholds**: the system-wide rate-limit ceiling for §6.2 and the fixed per-identifier thresholds for §6.1 both need real numbers (requests/window for login and token endpoints) — deferred to implementation time, not a design question this ADR needs to resolve.
- **v1.1 automated key-rotation trigger criteria**: §5.2 scopes v1 to manual rotation; the *scheduling* design for v1.1 (time-based, usage-based, or externally triggered) is not designed here, only committed to the roadmap.

## Addendum — self-service Organization creation now exists (2026-08-22, ratification review)

The "Organization provisioning" section above states creation is "a Clavaris-operator-only action... no self-service tenant creation in v1." **That is no longer accurate as written.** ADR-0012 (`PlatformAccount` — self-service Organization ownership) introduced a second, self-service creation path: a `PlatformAccount` registers itself, logs into a session-authenticated `/platform/dashboard`, and creates the `Organization`(s) it owns directly — calling the same `CreateOrganizationUseCase` the operator-only REST endpoint already used.

This does **not** weaken or contradict anything §1–§6 of this ADR establish — cross-tenant isolation, per-Organization JWKS/issuer, and the two-layer rate-limiting design are all unaffected by *who* is allowed to create the tenant row; only the provisioning *actor* changed. `CreateOrganizationCommand.ownerPlatformAccountId` now records that actor either way (derived from the session on the dashboard path, supplied explicitly in the request body on the operator/REST path — an ops script creating an Organization on a customer's behalf must still name who owns it). The platform-tier authentication boundary itself (§"Organization provisioning," `PlatformClient`/platform issuer) is unchanged; ADR-0012's `PlatformAccount` is a structurally separate, human, session-authenticated identity, never a `PlatformClient` credential.

Ratification of this ADR proceeds treating this as a correction, not a re-opened design question — see ADR-0012 for the full rationale, alternatives considered, and consequences of the self-service path itself.

## Ratification (2026-08-22)

Approved. This ADR has been the load-bearing design every implemented module (`identity-module`, `organization-module`, `client-registry-module`, `app`) was already built directly against (TD-PROC-001) — reviewed against the actual code, not re-litigated: §1 (`UNIQUE(organization_id, email)`), §2 (`oauth_clients.organization_id` mandatory, no cross-module FK), §4 (module dependency direction), and §5 (per-Organization issuer/JWKS, spike-validated GO) all verified live in this pass, matching what this ADR describes. Two real, already-tracked implementation gaps remain open against §5.2/§6 specifically — no dedicated `signing-keys/rotate` HTTP endpoint yet, and §6's two-layer rate limiting is entirely unbuilt (**TD-SEC-001**, the one P0 row in the technical-debt register) — neither changes the *decision* this ADR records, both are tracked as what they are: implementation debt against an approved design, not open design questions.

## Addendum — Workspace v1 scope simplified (2026-08-27)

§3 above ("The old 'company workspace inside one app' concept survives, renamed to `Workspace`") and the domain/business-rules documents this ADR originally drove (`domain-model.md` §3, `business-rules.md` BR-WS) described a 3-role (`OWNER`/`ADMIN`/`MEMBER`) `WorkspaceMembership`, plus a `WorkspaceInvitation` (invite-by-email-then-accept) entity. **Neither shipped as originally documented.** An explicit, deliberate v1 scope decision now supersedes that description:

- **No `WorkspaceInvitation` in v1** — deferred to v1.1+. Adding a member provisions a real `Account` directly (BR-WS-04): the workspace admin action itself creates the `Account`, scoped to the Workspace's own Organization, and the existing password-reset-request flow is reused verbatim to let the new member set their own password. There is no separate invite-then-accept step.
- **No `OWNER` role, no ownership-transfer machinery.** `WorkspaceMembership.role` is `ADMIN | MEMBER` only (BR-WS-05). The replacement invariant (BR-WS-01) is simpler than the original "exactly one OWNER, atomic transfer": a workspace must always retain **at least one** `ADMIN` — no transfer semantics needed, since demoting/removing the last `ADMIN` is rejected outright rather than requiring a hand-off.
- **Business/product-domain roles are explicitly out of scope.** Whatever a consuming application needs beyond admin/member (e.g. JobSeeker's "recruiter" vs. "candidate") is that application's own concern, never modeled in Clavaris — consistent with this whole codebase's founding constraint (CLAUDE.md §1: "no product-specific logic").

This changes nothing about the tenant-isolation boundary itself (§1–§2) or the `Organization`/`Workspace` naming split (§3's own renaming rationale) — only the shape of `WorkspaceMembership` and the mechanism for adding a member. Revisit `OWNER`/ownership-transfer and `WorkspaceInvitation` in v1.1+ if a real consumer need for either ever surfaces; nothing about the v1 schema (`workspaces`/`workspace_memberships`, both real tables now) blocks adding either later.
