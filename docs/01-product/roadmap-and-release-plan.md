# Roadmap and Release Plan — Clavaris

🟡 En revisión

## 1. Structure

Three releases, sequenced by what unblocks a real consumer first, not by module boundary.

## 2. v1 — Core identity + OIDC (unblocks JobSeeker's Wave 1)

**Spike complete (2026-08-17): GO** — the Spring Authorization Server spike required by ADR-0003's addendum (per-Organization issuer resolution, per-tenant `JWKSource`, tenant-scoped `RegisteredClientRepository`) validated ADR-0010 §5 end-to-end against real code (discovery, JWKS isolation, client-registry isolation, all confirmed with real HTTP calls and real signature verification — see ADR-0003's addendum for full results). Two small, targeted implementation patterns are required (a custom discovery filter; explicitly wiring `JWKSource` into `HttpSecurity`'s shared-object registry) — neither is a fight against the framework. The rows below can now be built for real, using the spike's findings as implementation guidance for the first `identity-module`/`client-registry-module` use cases.

**Status column added 2026-08-24** (an SDE-III review found the table below, unchanged since 2026-08-17, gave no visual signal that 5 of 8 rows are fully shipped and 3 have zero code behind them — someone skimming it would reasonably conclude v1 is nearly done, when the honest read is "the hardest, highest-risk rows are done; three real features remain unstarted").

| Capability | Module | Status |
|---|---|---|
| Registration, password login, email verification, password recovery | `identity-module` | ✅ Shipped |
| Refresh token issuance + rotation with reuse detection | `identity-module` | ✅ Shipped |
| Social login (Google, GitHub) | `identity-module` | ⛔ Not started — zero `SocialIdentity`/OAuth-client-registration code exists |
| Authorization Code flow + PKCE, discovery/JWKS/userinfo/revoke/end-session | `client-registry-module` | ✅ Shipped |
| `Organization` tenant isolation (operator-created, isolated account pool per consuming system) | `organization-module` | ✅ Shipped |
| Basic workspaces (create, invite, roles, remove member) — ADR-0010 | `organization-module` | ⛔ Not started — `organization-module` has no `Workspace`/`WorkspaceMembership` domain model at all today, only `Organization`/`RateLimitPolicy`; see `technical-debt-register.md` TD-FUT-005 for the audit-logging/rate-limiting hooks this will need designed in from day one, not retrofitted |
| Per-Organization issuer/JWKS (path-based `{clavarisBaseUrl}/o/{organizationId}`) + manually-triggered, audited key rotation (ADR-0010 §5) | `identity-module` | ✅ Shipped, including rotation-with-overlap (TD-SEC-008) |
| Two-layer rate limiting: fixed anti-abuse threshold (never tenant-configurable) + operator-managed per-Organization capacity ceiling (ADR-0010 §6) | `identity-module` / `organization-module` | ✅ Shipped, including Redis-fail-open behavior (TD-SEC-022) |
| Admin account-deletion API (consumed by JobSeeker's own ADR-0013 cascade) | `identity-module` | ⛔ Not started |

**Exit criterion:** JobSeeker completes a real login → token → `/userinfo` round trip against a deployed Clavaris instance, and the mandatory external security review has no open critical/high findings.

**Gate items — updated 2026-08-24, both original items now closed, and a second pass closed the rest.** The 2026-08-23 rate-limiting review named two P1 gate items (`technical-debt-register.md` §5 item 8, TD-SEC-022/TD-TEST-003) that should close before the external security review is scheduled — **both closed same-day.** A separate, later pass the same day (§5 item 8's own note, TD-ARCH-002/TD-SEC-019) closed the two remaining P1 rows that existed anywhere in the register at that point. **As of 2026-08-24: zero P1 rows are open anywhere in `technical-debt-register.md` §2.** The external security review named in this section's own exit criterion can now genuinely be scheduled — see `technical-debt-register.md` §5 item 10. This does not mean v1 itself is done (see the table's Status column above) — it means the security-review *gate* specifically is clear, independent of whether social login/workspaces/account-deletion have shipped yet.

## 3. v1.1 — Developer experience + hardening

| Capability | Why deferred, not dropped |
|---|---|
| Self-service client registration console | v1 clients are registered manually — acceptable at one-consumer scale, not at "reusable across projects" scale |
| Self-service `RateLimitPolicy` tuning (tenant-facing) + scheduled/unattended signing-key rotation | ADR-0010 §5.2/§6.2 — v1 ships operator-managed capacity ceilings and manually-triggered rotation on purpose (small operator-managed tenant count); self-service capacity tuning needs audit logging shipped first, and unattended rotation needs a real scheduler, neither of which is v1 scope |
| MFA (TOTP) | Real security improvement, not required for JobSeeker's own threat model at launch — see `docs/01-product/prd-mvp.md` §2.1 |
| Session management UI polish | v1 ships a functional, not polished, version |
| Webhooks (`webhook-module`) | 🟡 Proposed ADR-0007 — Clerk-style signed event delivery; needed once a second consumer exists to react to, not blocking JobSeeker's own login integration |
| Passkeys, breached-password check, new-device step-up MFA, user impersonation | Sourced from `docs/00-vision/clerk-feature-analysis.md` §6/§7 — real gaps against a mature comparable system, none of them launch-blocking for JobSeeker's Wave 1 |
| Rate-limit observability (per-rule/per-endpoint allow/block metrics and alerting) | `technical-debt-register.md` TD-FUT-011 — no observability stack exists anywhere in this codebase yet, not a rate-limiting-specific gap; natural to build once one is chosen rather than bolting metrics onto one feature ahead of the rest |
| Embedded/branded login (iframe-modal + per-client custom domain + `ClientBranding`) | 🟡 Proposed ADR-0009 — real infra prerequisite (dynamic TLS, DNS verification); the plain full-page redirect login (already v1 scope) works with zero setup in the meantime, so this is additive, not blocking |

## 4. v2 — Enterprise-shaped features (no committed date)

SAML, SCIM provisioning, configurable per-organization roles, multi-region deployment. None of these are needed by any current or near-term consumer — they stay on this list as acknowledged future scope, not as commitments, per `project-charter.md` §3.

## 5. What is deliberately not on this roadmap (v1/v1.1/v2)

A commercial multi-tenant offering, a hosted/managed version of Clavaris for third parties, a general password-manager or authenticator-app product. See `vision-document.md` §5 — these are non-goals *for the releases scheduled here*, not a claim about forever: `vision-document.md` §7 records a declared long-term intent to eventually take Clavaris to market, but that has no committed date and does not add scope to v1/v1.1/v2 — it only informs *which* architectural investments (e.g. ADR-0010's tenant isolation shape) are worth making early. Also explicitly not on this roadmap, ever *for a consumer's own product concerns*: billing/subscription management, feature/plan gating, "waitlist" launch-gating for a consuming application — product-specific business logic that belongs in each consumer's own backend, not here. (Whether Clavaris someday needs its *own* billing for its *own* tenant customers, if commercialized, is a separate future question `vision-document.md` §7 does not resolve either.)

## 6. Sequencing dependency on JobSeeker

Clavaris's v1 exists specifically to unblock JobSeeker's Wave 1 identity integration (`../JobSeeker/docs/01-product/roadmap-and-release-plan.md` §3). This is the one hard external deadline pressure on an otherwise self-paced roadmap.

## 7. Open risk — uncoordinated roadmaps (not resolved here)

JobSeeker's roadmap and this one are maintained in two separate repositories with no shared tracking mechanism. If Clavaris's v1 slips, JobSeeker's Wave 1 has no documented fallback (`project-charter.md` §7 names this same gap from Clavaris's side). Resolving this requires an explicit decision — build a throwaway minimal auth in JobSeeker to unblock it, or accept the JobSeeker delay — that has not been made. Flagging it here so it isn't silently forgotten.

## 8. Open risk — no explicit v1 cut list (partially resolved)

Every row in §2's table currently reads as equally required. For a solo developer pulled by an external deadline (§6), that's a risk in itself: if time runs short, there is no pre-agreed answer to "what ships late vs. what ships thin."

**One specific instance of this question is now decided, not open:** whether full per-`Organization` isolation (JWKS, issuer, two-layer rate limiting — ADR-0010 §5/§6) needs to be *working* to unblock JobSeeker, or could ship as "data model ready, protocol mechanics deferred." **Decided: build it in full from the start, deliberately, even though it is not strictly required for JobSeeker's login flow alone.** Reasoning (user decision, 2026-08-17): the `iss` claim is embedded in every issued token and pinned by every verifier's config — adding per-organization issuance *after* JobSeeker has already integrated against a shared-issuer v1 would be a breaking migration for an already-live consumer. Paying the extra up-front implementation time now, while zero accounts and zero integrated consumers exist, is accepted as cheaper than refactoring later. This is consistent with, and reinforces, the reasoning already in ADR-0010 §5.1.

**Consequence worth naming explicitly:** this removes the fallback option of shipping a simpler v1 if the Spring Authorization Server spike (ADR-0003 addendum) turns out harder than expected — the full tenant-isolation mechanism is no longer an optional add-on to a working simple baseline, it *is* the v1 baseline. This raises the cost of the spike failing and is the reason the spike should run immediately, before further design work, rather than later in the schedule.

The broader question (an explicit cut list for *every other* row in §2, not just this one) remains open — not resolved here, flagged so any further cuts are a deliberate decision, not a panic decision mid-implementation.

## 9. ~~Open risk — onboarding docs describe a state that stopped being true weeks ago~~ (resolved 2026-08-24, same day it was named)

`README.md` and `CLAUDE.md` §11 described this project as documentation-first with zero domain/application code — a fair description of 2026-08-17, actively false by the time this row was written. Both files rewritten same-day to describe the actual current state; `technical-debt-register.md` TD-PROC-004 has the full closure detail.

## 10. ~~Open risk — public/SPA OAuth client support is an undecided integration question~~ (resolved 2026-08-24, same day it was named)

Whether a consumer's frontend may ever call Clavaris's token endpoint directly from browser JavaScript (a public/SPA client, needing a real CORS policy) or must always go through its own backend (a confidential client, same-origin token exchange) had never been decided or documented. **Decided: confidential clients only, v1 and for the foreseeable future — see ADR-0013.** The investigation behind that decision found every layer (DB schema, domain model, `RegisterOAuthClientService`, both `RegisteredClientRepository` adapters) already assumed a confidential client; this was an unstated conclusion, not a genuine open question, formalized once someone actually looked. Consequence for this roadmap's own §2 exit criterion (JobSeeker completing a real login round trip): JobSeeker's `auth-module` is already a real backend per `integration-design.md` §3, so this decision doesn't block it — but it does mean any future consumer without its own backend cannot integrate with Clavaris as designed today, a real constraint worth knowing before, not after, that consumer is scheduled. `technical-debt-register.md` TD-FUT-015 has the full closure detail.
