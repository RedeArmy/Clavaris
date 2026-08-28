# Clerk.com — Deep-Dive Feature & Criticism Analysis

🟡 En revisión — research artifact, informs `prd-mvp.md`, `roadmap-and-release-plan.md`, `threat-model-stride.md`, and `business-rules.md`; does not itself change any locked ADR.

Companion to `market-research.md` §1, which already names Clerk as **"this project's explicit UX inspiration"**. That line deserved more than one row in a table — this document is the deep dive: every feature Clerk ships today, what its own users say is good and bad about it (sourced), and a concrete, per-item list of what Clavaris should copy, adapt, or deliberately reject and why.

## 1. Method

Web research conducted 2026-08-12 against Clerk's own docs/changelog/blog, plus third-party sources: G2 reviews, DEV Community migration write-ups, Val Town's public engineering blog, GitHub Security Advisories (CVE records), and status-page/incident postmortems. Full source list in §7. This is not an exhaustive audit — Clerk ships fast and this snapshot will drift; treat it as a baseline, not a permanent contract.

## 2. Complete feature inventory (as of this snapshot)

| Category | Clerk feature | Clavaris today (post ADR-0007/0008) |
|---|---|---|
| **Auth methods** | Password, email/SMS OTP, magic links, social login (30+ providers), passkeys (primary factor, paid tiers) | Password + Google/GitHub social (`prd-mvp.md` §2.1). No magic links, no passkeys yet |
| **MFA** | TOTP, SMS, backup codes, WebAuthn (Pro plan) | Backlog (`prd-mvp.md` §2.1) — real gap, already flagged |
| **Attack protection** | Bot sign-up detection (CDN-signal based, interactive challenge), device/network fingerprinting, "Client Trust" (forces 2nd factor on new device even with correct password), credential-stuffing rate limiting | Rate limiting only (BR-ID-06). No bot detection, no new-device step-up, no breached-password check |
| **Sessions** | Hybrid model: long-lived `__client` cookie + 60-second `__session` JWT, auto-refreshed every 50s, server-side redirect "handshake" for SSR apps | Standard OIDC access/ID token + rotating refresh token (ADR-0002, BR-ID-03) — simpler, fully standard, no Clerk-proprietary handshake protocol |
| **Webhooks** | Powered by **Svix** (Clerk doesn't build its own delivery infra) — HMAC signing, automatic retries, manual replay, event catalog (`user.*`, `organization.*`, `session.*`) | 🟡 Proposed in-house (ADR-0007): transactional outbox + HMAC + backoff retry + replay — same shape, built rather than outsourced |
| **Organizations** | Orgs, invitations, 2 default roles + up to 10 custom roles per instance (`org:<name>`), fine-grained custom permissions, org switching UI | Fixed `OWNER`/`ADMIN`/`MEMBER` enum (`prd-mvp.md` §2.4) — custom roles explicitly backlog |
| **Enterprise** | SAML + OIDC SSO, SCIM provisioning, tiered pricing | Not in scope — `client_credentials` OAuth2 already covers the "standard, no proprietary scheme" goal (ADR-0006); SAML/SCIM correctly deferred to v2 (`roadmap-and-release-plan.md` §4) |
| **Machine auth** | "M2M tokens" — usage-priced ($0.001/token), scoped machine-to-machine credentials, marketed under an "AI Authentication" pillar | Already covered structurally by the standard `client_credentials` grant (ADR-0006) — Clavaris doesn't need a parallel proprietary concept for this |
| **Admin/support tooling** | **User impersonation** — described by Clerk's own team as their "most-loved feature" post-launch; logged, 10-minute inactivity timeout, rate-limited on free tier (5/month) | Not in scope. Real gap for an operator debugging a consumer's reported issue |
| **Billing** | Stripe-backed subscription/plan management, feature gating, pricing tables — built directly into the auth product | **Explicitly out of scope** — product-specific business logic belongs in each consumer app, never in Clavaris |
| **Dev experience** | Prebuilt React/Next.js/Remix/Astro/Expo components, "Account Portal" hosted pages, CLI, free-in-development feature flag | Thymeleaf server-rendered hosted UI — different shape by design (no-SPA philosophy), not a gap |
| **Waitlist mode** | Built-in "coming soon" gated signup | Not in scope — genuinely product-specific (a consumer's launch strategy, not an identity concern) |

## 3. Architecture note — why Clavaris should *not* copy Clerk's session model verbatim

Clerk's dual-token design (long-lived `__client` cookie on Clerk's own domain + 60-second auto-refreshed `__session` JWT + a redirect-based "handshake" so SSR backends can read cookies across domains) is a genuinely clever answer to a problem Clerk created for itself: the token is issued from a *different domain* than the consumer's app, because Clerk is a third-party hosted service sitting between the browser and the consumer's backend.

Clavaris doesn't have that problem in the same way — it *is* the identity provider the consumer redirects to, and standard OIDC already has a clean answer (short-lived signed access token + rotating refresh token, ADR-0002/BR-ID-03). Reinventing Clerk's handshake would add proprietary complexity to solve a domain-boundary problem Clavaris doesn't structurally have. **Recommendation: keep the current standard-OIDC token design as-is; this is a case where "simpler and fully standard" beats "clever," not a gap to close.**

## 4. What Clerk's own users say is good (sourced)

- **Developer experience** — "one of the most developer-oriented authentication systems available," streamlined SDKs across React/Next.js/Remix/Gatsby/RedwoodJS, intuitive dashboard even for complex setups. [G2](https://www.g2.com/products/clerk-dev/reviews?qs=pros-and-cons)
- **Everything free in development mode** — no paywalls while building/testing, only production traffic is metered. [G2](https://www.g2.com/products/clerk-dev/reviews?qs=pros-and-cons)
- **Built-in security/compliance reduces operational overhead** — brute-force protection, bot prevention, GDPR support out of the box. [G2](https://www.g2.com/products/clerk-dev/reviews?qs=pros-and-cons)
- **User impersonation is beloved by support/engineering teams** — Clerk's own team calls it their most-requested-then-most-loved feature. [Clerk blog](https://clerk.com/blog/empower-support-team-user-impersonation)
- Teams that adopted Clerk specifically for speed-to-launch report it delivering exactly that. [Turso](https://turso.tech/blog/why-we-transitioned-to-clerk-for-authentication), [Reflag](https://reflag.com/blog/clerk)

## 5. What Clerk's own users criticize (sourced)

- **Reliability under real load.** Val Town's public engineering blog: *"Clerk was a pretty bad replacement for a users table because it was heavily rate-limited and not very reliable... Clerk outages break the login & logout flow and make the site unusable to logged-in users, and Clerk went down pretty often for long periods of time."* They filed an issue to migrate off Clerk and eventually did. [Val Town](https://blog.val.town/better-auth)
- **Ongoing incident frequency.** Independent status-tracking shows 17 incidents in a recent 90-day window (median 40 minutes each), plus two publicly posted postmortems in early 2026 — one traced to a Postgres query-plan flip after an auto-`ANALYZE`, taking ~90 minutes to fully recover. [StatusGator](https://statusgator.com/services/clerk), [Clerk postmortem, Feb 19 2026](https://clerk.com/blog/2026-02-19-system-outage-postmortem), [Clerk postmortem, Mar 10 2026](https://clerk.com/blog/2026-03-10-service-outage-postmortem)
- **Pricing at scale.** At 100,000 MAU the bill is reported around $2,000+/month; multiple independent sources describe a "migrate at month nine" pattern as usage-based costs compound. The free tier (10K→50K MAU in 2025) blunted this for early-stage projects but didn't remove the concern at scale. [BudgetForge](https://www.budgetforge.dev/tools/clerk-pricing-2026), [costbench](https://costbench.com/software/developer-tools/clerk/)
- **Vendor lock-in — not on raw data, but on architecture.** User records and password hashes *are* exportable via API/migration tooling — the real lock-in comes from storing subscription plans, permissions, and org roles inside Clerk's proprietary `metadata` fields, which don't map cleanly to any other provider on the way out. [SuperTokens migration guide](https://supertokens.com/blog/clerk-migration), [LeanVibe](https://leanvibe.io/blog/bp-08831)
- **Support quality.** "Customer service support is lacking while service and features continue to be inconsistent and flaky." [G2](https://www.g2.com/products/clerk-dev/reviews?qs=pros-and-cons)
- **Real, disclosed security vulnerabilities** (not vague FUD — specific CVEs):
  - **CVE-2025-63700** — `clerk-js` 5.88.0 allowed bypassing the OAuth authentication flow entirely by manipulating the request at the OTP-verification stage. [GitHub Advisory](https://github.com/advisories/GHSA-3mm3-wfpv-q85g)
  - **January 2024** — a critical `@clerk/nextjs` bug (present since `@clerk/nextjs@4.7.0`, Jan 2023 — over a year in the wild) let a malicious actor gain privileged access or act on behalf of another user. [Clerk changelog](https://clerk.com/changelog/2024-02-02)
  - **A `@clerk/nextjs` authorization-bypass pattern**: passing `role`/`permission`/`feature`/`plan`/`reverification` in the *same* options object as `unauthenticatedUrl`, `unauthorizedUrl`, or `token` caused the authorization params to be **silently discarded** — the check appeared to run but didn't. [DailyCVE](https://dailycve.com/clerk-sdks-authorization-bypass-cve-2026-0000-medium/)
- **Feature gating that surprises at production time.** Custom organization roles are free in development but require a paid "B2B Authentication" add-on in production — a gap between what you build against and what you actually ship with. [Clerk docs](https://deepwiki.com/clerk/clerk-docs/5.1-roles-and-permissions)

## 6. Gap analysis — decision per item

| Clerk capability | Decision for Clavaris | Rationale |
|---|---|---|
| Passkeys (WebAuthn) | **Adopt, v1.1** | Passwordless is a real security and UX improvement; not v1-blocking since password + social already covers launch |
| Breached-password check (k-anonymity API, e.g. HIBP-style) | **Adopt, v1.1** | Directly closes a real attack class (credential stuffing with known-leaked passwords) that rate limiting alone doesn't address |
| Bot/device-fingerprint sign-up protection | **Adopt, v1.1, scoped down** | Full ML-based bot detection is disproportionate for this project's scale; a simpler CAPTCHA-on-suspicion + step-up-MFA-on-new-device (BR-ID-06 extension) captures most of the value |
| User impersonation (admin support tool) | **Adopt, v1.1** | Directly reuses the "no audit-logging design" gap already flagged in `threat-model-stride.md` §5 — building this and the audit log together is more coherent than sequencing them apart |
| Custom organization roles | **Keep as backlog (v2)**, not urgent | Already an open question in `domain-model.md` §8; Clerk gating this behind a paid tier even for itself is evidence it's genuinely complex to do well, not evidence Clavaris is behind by deferring it |
| M2M tokens | **No new concept needed** | `client_credentials` (ADR-0006) already is Clavaris's M2M story — a structural advantage of building on standard OAuth2 from day one instead of inventing a parallel token type |
| SAML/SCIM enterprise SSO | **Correctly deferred, v2** | Already the roadmap's position (`roadmap-and-release-plan.md` §4); nothing in this research changes that |
| Billing/subscriptions | **Reject — explicit non-goal** | Product-specific business logic; belongs in each consumer's own backend, never here |
| Waitlist mode | **Reject — explicit non-goal** | A consumer's launch/growth strategy, not an identity concern |
| Clerk's dual-token session/handshake model | **Reject — keep standard OIDC** | Solves a domain-boundary problem Clavaris doesn't have (§3 above); adopting it would trade "fully standard" for "clever," the wrong trade for this project |
| Webhooks via a specialized delivery vendor (Svix) | **Build in-house per ADR-0007, but budget for real effort** | Clerk itself didn't build this from scratch — treat retry/backoff/dispatch as non-trivial engineering, not a weekend task; consider an existing outbox-pattern library at implementation time rather than hand-rolling the scheduler |

## 7. Concrete improvement list — what Clavaris should do *better* than Clerk

Each item below is tied directly to a finding in §5, not a generic aspiration:

1. **Publish incident postmortems as policy, not as damage control.** Clerk's 2026 postmortems came after visible user frustration ("Clerk has faced several incidents recently and frustration is rightfully mounting, and they have failed at their commitment to customers"). Commit now, before any incident happens, to a public postmortem within N business days for any event breaching the 99.5% target (`nfr-quality-attributes.md` §2) — cheap to promise early, expensive to retrofit trust later.
2. **Never let rate limiting break an already-authenticated session.** Val Town's core complaint was that Clerk's rate limits broke login/logout for users who were already signed in. Clavaris's standard-JWT model already has a structural advantage here (a consumer can verify a token locally against JWKS without calling Clavaris on every request, NFR §3) — this should be explicitly load-tested as an invariant: *rate limiting on `/oauth2/token` must never be so aggressive that a legitimate refresh cycle for an active session gets throttled.*
3. **Treat Clerk's real CVEs as Clavaris's own negative test cases**, not just interesting trivia — added directly to `threat-model-stride.md` (§8 below): (a) never let an OTP/verification step be reorderable or skippable by request manipulation, (b) never let one authorization-parameter's presence silently suppress another's evaluation — every authorization check must fail closed if any part of its configuration is ambiguous, and this must be a dedicated test, not an inference from "the code looks right."
4. **Design organization roles as data from day one, even while shipping a fixed 3-role enum in v1.** Custom roles are valuable enough that Clerk paywalls them even for itself — validates keeping it on Clavaris's roadmap, but the schema (`membership.role` as a string/FK, not a hardcoded DB enum type) should already leave room for it, avoiding a painful migration later.
5. **Self-hosting is not just a pricing story — make data residency a first-class, explicit selling point**, not just an implicit side effect of ADR-0001. No Clerk criticism about GDPR/EU data residency surfaced in this research (a fair, honest null result) — but structurally, a hosted-only provider cannot offer per-deployment data residency at all, while Clavaris can by construction. Worth a line in `security-architecture.md` once a second consumer's compliance needs are real.
6. **Reduce the need for support instead of just improving support.** G2's "support is lacking, service is inconsistent" complaint doesn't map 1:1 to a solo-maintained project (no paid support tier exists to disappoint), but the underlying lesson generalizes: a documented, stable error-code catalog for OIDC/management-API error responses reduces "why did this fail" questions before they're ever asked.
7. **Never let metadata become a hidden coupling point.** Clerk's real lock-in risk is consumers stuffing business data into its `publicMetadata`/`privateMetadata` fields. Clavaris has no such field by design (it doesn't know what a candidate is) — keep it that way deliberately; resist any future request to add a generic key-value bag to `Account` "just for convenience," since that is exactly the seam that caused Clerk's own users migration pain.
8. **Budget webhook delivery as real infrastructure work, not a checkbox.** Clerk delegates this to Svix rather than building it themselves — a signal that reliable retry/backoff/idempotent delivery is genuinely hard, reinforcing that ADR-0007's outbox design needs real test coverage (concurrent dispatcher instances, `SKIP LOCKED` correctness) before it ships, not just a happy-path demo.

## 8. Where this analysis already changed the docs

- `prd-mvp.md` §2.1/§4 — added passkeys, breached-password check, and impersonation to v1.1 scope; Billing/waitlist reaffirmed as explicit non-goals.
- `roadmap-and-release-plan.md` §3 — v1.1 bucket updated with the above, sourced back to this document.
- `threat-model-stride.md` §6 (new) — Clerk's disclosed CVEs translated into concrete Clavaris test scenarios.
- `business-rules.md` — `BR-ID-07` (breached-password check) and a new `BR-ADMIN` group (impersonation logging/timeout) added.

## 9. Sources

- [Clerk homepage](https://clerk.com/) — feature inventory
- [How Clerk works — Clerk Docs](https://clerk.com/docs/guides/how-clerk-works/overview) — session/token architecture
- [Clerk Pricing](https://clerk.com/pricing), [BudgetForge cost breakdown](https://www.budgetforge.dev/tools/clerk-pricing-2026), [costbench](https://costbench.com/software/developer-tools/clerk/)
- [Clerk.dev reviews — G2](https://www.g2.com/products/clerk-dev/reviews?qs=pros-and-cons)
- [Val Town — "From Supabase to Clerk to Better Auth"](https://blog.val.town/better-auth)
- [LeanVibe — Clerk vendor lock-in](https://leanvibe.io/blog/bp-08831)
- [SuperTokens — Clerk migration guide](https://supertokens.com/blog/clerk-migration)
- [Clerk — Postmortem Feb 19 2026](https://clerk.com/blog/2026-02-19-system-outage-postmortem)
- [Clerk — Postmortem Mar 10 2026](https://clerk.com/blog/2026-03-10-service-outage-postmortem)
- [StatusGator — Clerk status history](https://statusgator.com/services/clerk)
- [GitHub Advisory — CVE-2025-63700](https://github.com/advisories/GHSA-3mm3-wfpv-q85g)
- [Clerk changelog — January 2024 vulnerability](https://clerk.com/changelog/2024-02-02)
- [DailyCVE — Clerk SDK authorization bypass](https://dailycve.com/clerk-sdks-authorization-bypass-cve-2026-0000-medium/)
- [Clerk — Role-based access control with Organizations](https://clerk.com/blog/role-based-access-control-with-clerk-orgs), [Roles and Permissions — DeepWiki](https://deepwiki.com/clerk/clerk-docs/5.1-roles-and-permissions)
- [Clerk — Empower Your Support Team With User Impersonation](https://clerk.com/blog/empower-support-team-user-impersonation)
- [Clerk Docs — User impersonation](https://clerk.com/docs/guides/users/impersonation)
- [Clerk Docs — M2M tokens](https://clerk.com/docs/guides/development/machine-auth/m2m-tokens), [M2M Tokens Public Beta changelog](https://clerk.com/changelog/2025-08-15-m2m-beta)

## 10. Refresh, 2026-08-27 — Clerk's own drift since §1's snapshot, and Clavaris's real current state

Requested explicitly as a fresh internet research pass, not a re-read of §1–§9 — those are 2 weeks old and §1 itself warned they'd drift. Clerk shipped real things in that window; so did Clavaris (see `technical-debt-register.md`/`roadmap-and-release-plan.md` §13–14 for the full list). This section reconciles both against each other as they actually stand today, not as either stood on 2026-08-12.

### 10.1 What changed on Clerk's side since 2026-08-12

- **Two new critical CVEs, not in §5's list**: **CVE-2026-41248** (CVSS 9.1) — a middleware-bypass flaw in `@clerk/nextjs`/`@clerk/nuxt`/`@clerk/astro` letting a crafted request skip middleware gating entirely and reach a downstream handler unauthenticated. **CVE-2026-42349** — a *compound authorization check* bypass: when a check ANDs reverification with a role/permission (or a billing entitlement with a role/permission), satisfying only one condition made the combined predicate incorrectly evaluate `true`. Both patched, both real, both exactly the "fail closed on ambiguous/partial config" class §7 item 3 already named — now with two more concrete real-world instances to translate into Clavaris test scenarios, not zero. [SentinelOne — CVE-2026-42349](https://www.sentinelone.com/vulnerability-database/cve-2026-42349/), [Appsecure — CVE-2026-41248](https://www.appsecure.security/vulnerability-database/cve-2026-41248/)
- **A third 2026 postmortem §5 missed**: a **Feb 10, 2026 DNS-provider outage**, 2h32m of degraded authoritative resolution — bringing the real count to 3 publicly-postmortemed incidents in about 5 weeks (Feb 10, Feb 19, Mar 10), not 2. Reinforces §7 item 1 (publish postmortems as policy) more strongly than the original research showed. [Clerk — DNS outage postmortem](https://clerk.com/blog/2026-02-10-dns-outage-postmortem)
- **SAML/SCIM enterprise SSO is now fully GA**, not beta: Directory Sync GA'd 2026-04-16, group-to-role/custom-attribute mapping GA'd 2026-05-21, one unified `/enterprise_connections` endpoint for SAML+OIDC. Gated behind Pro/Business plans in production. Doesn't change Clavaris's own correctly-deferred v2 decision (§6) — just confirms the feature is now mature on Clerk's side, if this gets revisited later.
- **New, not previously analyzed: `@clerk/agent-toolkit` + a public-beta Clerk MCP server.** Clerk is building real product surface for AI-agent identity — adapters for Vercel AI SDK/LangChain/MCP that inject `userId`/`sessionId`/`orgId` into an agent's context/system prompt (for tenant-scoping and attribution of agent actions to a human), plus a standalone MCP server so coding assistants (Claude Desktop, Cursor, Copilot) get accurate Clerk-specific implementation snippets. This is a genuinely new product category, not a rebrand of the existing M2M-tokens feature §2 already covers. **Not a v1/v1.1 gap for Clavaris** — no current or near-term consumer needs agent-identity tooling — but worth naming as a real differentiator opportunity later (§10.3): Clerk prices M2M usage per-token; a self-hosted `client_credentials`-based equivalent with no per-token metering is a structural advantage Clavaris already has the primitive for (ADR-0006), the moment this becomes a real requirement.
- **Uptime SLA is Enterprise-only and unpublished-price**: confirmed 99.99% SLA (≈52 min/year) exists *only* on Clerk's Enterprise plan; Free/Pro/Business carry no contractual SLA at all. Directly relevant to §10.3 below — Clavaris's own 99.5% target (`nfr-quality-attributes.md` §2) is a floor for every deployment, not a paid add-on.
- **"Device tracking and revocation" is a named, Pro-tier-gated Clerk feature** (self-service: a user sees their own active sessions/devices and can revoke one; plus a new-device sign-in email notification) — not previously called out as its own row in §2's table, which only captured the underlying session *architecture*, not this specific user-facing capability. Directly relevant to §10.2 below. [Clerk — essential user-management features](https://clerk.com/articles/essential-user-management-features-startups)

### 10.2 Clavaris's own real current state (corrects §2's stale "Clavaris today" column, which described the 2026-08-12 codebase — before `identity-module` had 178 of its current `src/main` source files)

The comparison below is what actually ships today, live-verified across this session and the two immediately prior ones — not what was planned in §2.

| Category | Clerk (refreshed) | Clavaris today (2026-08-27) |
|---|---|---|
| Password auth + email verification + password recovery | Yes | ✅ Shipped, real email delivery (Resend) |
| Social login | 30+ providers | ⛔ Still zero code (confirmed by grep this session) |
| MFA (TOTP/SMS/WebAuthn) | Yes (Pro) | ⛔ Backlog, unchanged |
| Passkeys | Yes (paid tiers) | ⛔ Backlog v1.1, unchanged |
| Breached-password check | No dedicated feature found this pass | ⛔ Backlog v1.1, unchanged |
| Refresh/session token model | Proprietary dual-token + handshake | ✅ Standard OIDC access + rotating refresh token, reuse detection (BR-ID-03) |
| **Live session revocation on security events** (password reset, refresh-token-reuse, account/org delete) | Not specifically documented as a named feature | ✅ **Shipped this week** (TD-SEC-031) — `AccountSessionRevoker`, wired into all 4 call sites |
| **Self-service "see my devices, revoke one" UI + new-device email** | Yes, Pro-tier only | ⛔ **Gap, but a cheap one** — see §10.3 point 1: the hard part (`SessionRegistry`, `expireNow()`) already exists from TD-SEC-031; only a read query + a Thymeleaf page + an email hook are missing |
| Tenant isolation (Clerk: one shared platform, no equivalent concept) | N/A | ✅ Shipped, per-`Organization` issuer + JWKS + rotation-with-overlap (ADR-0010 §5) — structurally something Clerk cannot offer at all, single-tenant SaaS by construction |
| Teams within a tenant (Clerk's own "Organizations") | Yes, 2 default + up to 10 custom roles | ⛔ `Workspace` still zero code, unchanged |
| Rate limiting / anti-abuse | Documented but not detailed publicly this pass | ✅ Shipped, two-layer (ADR-0010 §6) — now including the admin/management API itself (TD-SEC-030, shipped this week) |
| Admin/management API + hard delete | Impersonation yes; explicit hard-delete API not found in this pass | ✅ Shipped — both Account and Organization hard-delete, rate-limited, audited, outbox-eventing on both (TD-ARCH-007) |
| User impersonation | Yes, Pro-tier, "most-loved feature" | ⛔ Backlog v1.1, unchanged |
| Webhooks | Yes, via Svix | ⛔ Still not built — but the outbox table/writer/retention-job scaffolding now exists in *two* modules (identity + organization), ready for a real dispatcher the moment ADR-0007 is picked up |
| Audit logging | Not a named public feature | ✅ Shipped, `AuditEventRecorder` on every sensitive write |
| Observability (metrics/alerting/tracing) | "Application Logs" (event stream), shipped 2026-05-06 | ✅ Shipped, arguably more complete for a self-hosted operator: Prometheus + Alertmanager (5 real alert rules, real email) + Grafana + full per-request Zipkin tracing (TD-FUT-011) |
| SAML/SCIM enterprise SSO | Now fully GA | ⛔ Correctly deferred v2, unchanged |
| AI-agent identity tooling (Agent Toolkit / MCP server) | New, public beta | Not evaluated — no current consumer need; `client_credentials` (ADR-0006) is the structural equivalent primitive already in place if this becomes real |
| Uptime | 99.99% SLA, **Enterprise-tier only** | Targeting 99.5% (`nfr-quality-attributes.md` §2) as the one and only tier — no paywalled reliability |
| Self-hosting / data residency | Not offered at any tier | ✅ The whole point of ADR-0001 — ships free, on every deployment |

### 10.3 Concrete list — Clerk features Clavaris could implement next, ranked by real cost vs. value found this pass

1. **Self-service session/device management page — genuinely cheap now, not a new capability.** TD-SEC-031 (shipped this week) already built the exact backend primitive Clerk's Pro-tier "device tracking and revocation" needs: `SessionRegistry`/`SessionInformation` per `Account`, and `expireNow()` already wired through `AccountSessionRevoker`. What's missing is a read-only query (list the current account's own active sessions — device/IP/last-active, from the same `SessionRegistry`) and one Thymeleaf page with a "Sign out" button per row, calling the same revocation path already proven correct by 3 integration test suites. This is the single highest value-per-effort item this whole research pass found — recommend adding to `roadmap-and-release-plan.md` v1.1 explicitly, not left implicit.
2. **New-device sign-in email notification.** Small, reuses the existing Resend email infrastructure (ADR-0011) and the existing session-creation event path — flag a login from a `SessionRegistry` identity Clavaris hasn't seen for this account recently (a device/IP heuristic, not full fingerprinting) and send one templated email. Directly closes part of the "bot/device-fingerprint sign-up protection, scoped down" item §6 already adopted for v1.1 but hadn't broken into this concrete a sub-step before.
3. **Impersonation (already adopted, §6) — reconfirm priority.** Still zero code. With audit logging now real and shipped (unlike when §6 originally reasoned "building this and the audit log together is more coherent"), the blocking dependency this decision named is gone — nothing structural blocks starting this now besides sequencing.
4. **Passkeys / breached-password check (already adopted, §6) — reconfirm priority, unchanged status.** Both still zero code; no new information this pass changes the v1.1 timing decision, just confirms neither has silently become less relevant.
5. **Do not adopt AI-agent-identity tooling (Agent Toolkit/MCP) yet.** Named for completeness, not as a recommendation — no current or near-term consumer (JobSeeker) has an AI-agent auth need. Revisit only if a real consumer requirement appears; the underlying primitive (`client_credentials`) is already there.

### 10.4 Differentiators to implement or make explicit — beating Clerk on Clerk's own weak points, not just matching its feature list

1. **"No SLA below Enterprise" is Clerk's own admitted gap — make Clavaris's flat 99.5% target (`nfr-quality-attributes.md` §2) a stated, marketed differentiator once a second consumer exists**, not just an internal NFR. Every Clerk tier below Enterprise (i.e., every startup-stage user) gets zero contractual reliability guarantee; a self-hosted operator controls (and can commit to) their own uptime target regardless of spend tier — this is structural, not aspirational.
2. **Two new real CVEs this pass (10.1) reinforce, don't just repeat, §7 item 3 — convert both into named `threat-model-stride.md` test scenarios**: (a) a request that should be blocked by an unauthenticated-middleware-equivalent (Clavaris's own filter-chain gates, `AdminApiSecurityConfig`/`TenantSessionConcurrencyFilter`/etc.) must never have a crafted-request path around it — worth one dedicated adversarial test per security filter chain, not just the happy-path integration tests each already has; (b) **any future compound authorization check** (Clavaris doesn't have one yet — every `.hasAuthority(...)` check in `AdminApiSecurityConfig` today is a single condition — but the moment a second condition is ANDed together, e.g. scope + Organization-ownership, this becomes a live risk) must fail closed if either half is ambiguous, and this needs to be a named coding-standard, not tribal knowledge, before the first compound check is written.
3. **Ship the self-service session page (10.3 item 1) as a marketed differentiator, not just a feature-parity checkbox**: Clerk paywalls this behind Pro; Clavaris shipping it free, in v1.1, for every deployment, directly demonstrates the self-hosted cost model's real advantage — a concrete example beats an abstract "no vendor lock-in" claim.
4. **Publish Clavaris's own postmortem policy before the first incident, not after** (§7 item 1, now with 3 Clerk postmortems as evidence instead of 2 — the pattern is more established, not less). Cheapest differentiator on this whole list: it costs a paragraph in `incident-response-plan.md` today, and reads very differently written proactively than defensively.

### 10.5 Sources (this section only — §9 above covers §1–§8)

- [SentinelOne — CVE-2026-42349](https://www.sentinelone.com/vulnerability-database/cve-2026-42349/)
- [Appsecure — CVE-2026-41248](https://www.appsecure.security/vulnerability-database/cve-2026-41248/)
- [Clerk — DNS outage postmortem, Feb 10 2026](https://clerk.com/blog/2026-02-10-dns-outage-postmortem)
- [Clerk — SSO/Directory Sync GA status](https://clerk.com/docs/guides/configure/auth-strategies/enterprise-connections/overview)
- [Clerk — Agent Toolkit changelog](https://clerk.com/changelog/2025-03-7-clerk-agent-toolkit), [Clerk MCP Server changelog](https://clerk.com/changelog/2026-01-20-clerk-mcp-server), [Using Clerk with AI](https://clerk.com/docs/guides/ai/overview)
- [Clerk — essential user-management features (device tracking/revocation)](https://clerk.com/articles/essential-user-management-features-startups)
- [Clerk Pricing Explained — Enterprise SLA](https://clerk.com/articles/clerk-pricing-explained)
- [Clerk articles — SSO/SAML](https://clerk.com/articles/how-to-add-sso-and-saml-to-my-saas-product)
