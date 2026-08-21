# Project Charter — Clavaris

🟡 En revisión

## 1. Sponsor / owner

Solo developer project, same author as [JobSeeker](../../../JobSeeker). No external sponsor, no funding, no team — every constraint in this charter follows from that.

## 2. Objectives

| Objective | Why it matters |
|---|---|
| Ship a working v1 (password + email verification + password reset + OIDC authorization code flow with PKCE + basic organizations) that JobSeeker can integrate against | Without this, JobSeeker's own Wave 1 cannot complete — this is the concrete, dated forcing function behind an otherwise open-ended infrastructure project |
| Zero critical/high security findings at external review before real user traffic | Non-negotiable — this system is the credential store for everything downstream of it |
| Prove reusability with a second, independent integration | "Built to be reusable" is a claim, not a fact, until a second consumer actually does it (see vision-document §6) |

## 3. Scope

**In scope for v1:** everything listed under `identity-module`, `client-registry-module`, and a basic `organization-module` — password + social login (Google, GitHub), email verification, password recovery, refresh token rotation, OIDC authorization code flow with PKCE, JWKS/key rotation, organizations with `OWNER`/`ADMIN`/`MEMBER` roles and invitations.

**Explicitly out of scope for v1:** MFA/TOTP (backlog — flagged as an open question, not silently dropped), SAML/SCIM, a polished admin UI (a minimal functional one is enough), multi-region deployment. See `docs/01-product/roadmap-and-release-plan.md` for exact sequencing.

## 4. Timeline

No fixed calendar deadline of its own — Clavaris's v1 timeline is **pulled by JobSeeker's Wave 1**, which assumes Clavaris's core login/token functionality exists first. This is a real, currently **unresolved** cross-project coordination risk: the two roadmaps are maintained in two separate repositories with no shared tracking mechanism yet. Do not assume this has been reconciled — see `docs/01-product/roadmap-and-release-plan.md` §7.

## 5. Constraints

- **Solo developer.** Every scope decision must be evaluated against "can one person build and operate this," which is the entire reason organization-module's v1 is deliberately minimal and enterprise features (SAML/SCIM) are deferred indefinitely rather than roadmapped.
- **Must not indefinitely block JobSeeker.** If Clavaris's v1 timeline slips significantly, JobSeeker needs an explicit fallback decision (wait, or stand up a throwaway minimal auth to unblock itself) — this charter does not resolve that trade-off, it only names it as live. See open questions below.
- **No operational budget for a managed cloud IdP fallback.** Self-hosting is the only economically viable option at this stage (reinforces ADR-0001 — not a re-justification of it, just a note that this constraint is why the "adopt hosted" alternative was never seriously live).

## 6. Assumptions

- JobSeeker remains the only real consumer through v1 — designing organization-module and client-registry-module to be *reusable in principle* is correct, but *validating* reusability against a hypothetical second consumer before one exists is not (see vision-document §6 — the validation happens after v1, not during).
- Spring Authorization Server remains actively maintained and compatible with Spring Boot 3.4 for the life of this project's initial build — a framework-abandonment risk that is currently unmitigated (no fallback plan exists).

## 7. Open questions

- What is the actual fallback if Clavaris's v1 is not ready when JobSeeker needs it? Unresolved — flagged in both repositories' roadmaps, not decided here.
- Does organization-module's v1 (roles, invitations) actually get exercised by JobSeeker at all, given JobSeeker's own domain has no "organization" concept yet? If not, is building it in v1 premature scope, or legitimate reusability investment ahead of a not-yet-identified second consumer? Currently resolved as "build it" (per the user's explicit product-scope decision), but worth re-examining once a second consumer is real.
- Long-term operating cost (infrastructure, key rotation ceremony, on-call for something with a ≥99.5% availability target) for a solo developer — not modeled yet.
