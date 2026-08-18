# Vision Document — Clavaris

🟡 En revisión

## 1. Problem statement

Every new software project needs authentication, and every new software project ends up solving it the same two bad ways: fast-and-insecure (a hand-rolled login form, sessions stored wrong, passwords hashed with whatever the tutorial used) or slow-and-correct (weeks spent getting OAuth2/OIDC right before a single product feature ships). Adopting a hosted identity provider (Auth0, Clerk) avoids both, at the cost of per-MAU pricing that scales against you and no control over the data or the roadmap. Adopting a self-hosted one (Keycloak, Zitadel, Ory) avoids the pricing problem but trades it for heavyweight, admin-console-first products not designed to be embedded into a solo developer's workflow across several small projects.

This gap is what motivated extracting authentication out of [JobSeeker](../../../JobSeeker) — JobSeeker's own auth needs (multi-role accounts, JWT issuance, refresh rotation) were themselves already a full subsystem, and building it *inside* JobSeeker meant paying that cost once and never being able to reuse it. Clavaris pays that cost once, generally.

## 2. Vision statement

**Clavaris is the identity layer every one of the author's future projects reaches for first** — a self-hosted, OIDC/OAuth2-compliant identity provider that any application, in any language, can integrate against in under a day, with full ownership of the data, the codebase, and the roadmap.

It is infrastructure, not a product feature. It succeeds when a new project's developer never has to ask "how do I do login" again — they register a client, redirect to `/authorize`, and get a standards-compliant token back.

## 3. Who this is for

Clavaris has exactly one class of "user" that matters at the product level: **developers of consuming applications** — currently the author, currently one consumer (JobSeeker), with more expected over time. End users of those applications (a JobSeeker candidate, a JobSeeker recruiter) interact with Clavaris only indirectly, through the hosted login/consent screens during an OIDC flow — they should never need to know Clavaris exists as a separate system.

This distinction matters for every product decision: features are evaluated against "does this make integrating a new consumer easier / does this keep existing consumers' users secure," never against "does this help a JobSeeker candidate specifically" — that framing belongs to JobSeeker, not here (see `CLAUDE.md` §1).

## 4. Why build instead of adopt

| Option | Rejected because |
|---|---|
| Auth0 / Clerk (hosted) | Per-MAU pricing that scales against a portfolio of side projects; no control over data residency; vendor lock-in on a component that is, by design, the hardest one to migrate away from later |
| Keycloak / Zitadel / Ory (self-hosted, turnkey) | Heavyweight admin-console-first products, steep operational learning curve, harder to extend with custom domain concepts (e.g. the `organization-module` shape used here) than to own the codebase directly |
| Build inside each consumer app | Exactly the problem being solved — this is what JobSeeker was doing before this extraction, and it does not scale past one project |

Full reasoning: `docs/03-architecture/adr/0001-build-custom-identity-vs-adopt-existing-idp.md`.

## 5. Non-goals for v1/v1.1 (explicitly out of scope *now*, not a claim about forever — see §7)

- **Not a commercial SaaS product yet.** No public signup, no billing, no multi-tenant-at-the-infrastructure-level *offered to third parties* in v1/v1.1 — a single Clavaris deployment serves the author's own projects today. This is a sequencing decision, not a ceiling: see §7 for the declared long-term direction. Do not build billing/plan-gating/public-signup now; that remains real scope to earn later, not silently drifted toward (`CLAUDE.md` §12).
- **Not an enterprise CIAM competitor.** No SAML, no SCIM provisioning, no enterprise SSO federation in v1. Those are real, well-understood features, just not needed by any current or near-term consumer.
- **Not multi-region.** Single-region deployment is sufficient for the current consumer set.
- **Not a general password manager or MFA authenticator app** — Clavaris issues and verifies credentials for its own consumer applications only.

## 6. What success looks like in 12 months

- JobSeeker's Wave 1 identity integration is live against Clavaris in production, with zero credential-related security incidents.
- A second, unrelated project (not yet identified) integrates in under a day using nothing but standard OIDC client libraries — the real test of "reusable," since a second successful integration is the only way to disprove "reusable in theory, actually still coupled to JobSeeker's assumptions in practice."
- An external security review has been completed with no open critical/high findings (§6 of `CLAUDE.md`).

## 7. Long-term direction (declared intent, not current scope — added 2026-08-17)

The author's stated model for Clavaris is **Clerk**: a reusable authentication/authorization system meant to be plugged into a growing portfolio of the author's own projects — explicitly including future projects in **other languages/stacks (.NET, Go named specifically)**, not just Java. This is precisely why OIDC/OAuth2 as the sole interface (ADR-0006, no bespoke per-language SDK) is treated as non-negotiable rather than a nice-to-have — it's the mechanism that makes cross-language reuse actually work.

**Long-term, once the system is mature, the explicit intent is to take it to market as a commercial product.** This does not change v1/v1.1 scope (§5) — no billing, no public signup, no plan-gating now — but it *does* mean architectural investments that are hard to retrofit later (the per-`Organization` tenant isolation shape in ADR-0010 — isolated account pools, isolated JWKS, isolated rate-limit budgets) should be evaluated as forward-compatible infrastructure for a future multi-tenant product, not judged solely against today's single real consumer. Each `Organization` under ADR-0010 is already shaped the way a paying tenant would be in a future commercial version — that alignment is deliberate, not coincidental, and is a reason *not* to walk back that design even though it costs more engineering time than a single-consumer-shaped model would.

This section exists so this intent survives independently of any one conversation — treat it as real product direction when weighing "build now vs. defer" tradeoffs, not as speculation to discount.
