# Access Review — Clavaris

🟡 En revisión

TD-FUT-016 (ISO/IEC 27001 + SOC 2 Type II readiness, ADR-0016): both frameworks (ISO Annex A 5.15–
5.18, SOC 2 CC6) expect a documented answer to "who can access what, and how often is that checked" —
not just that access control exists technically (`security-architecture.md` already covers that),
but that it's *reviewed*. This document is that answer, honestly scoped to a solo-developer project
rather than describing a review board that doesn't exist.

## 1. What "access" means here

Two distinct categories, deliberately not conflated:

- **Operator access** — who can reach production infrastructure, secrets, and raw data: the
  `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET`, the Postgres/Redis instances, the production VM itself
  (TD-FUT-013, closed 2026-08-28), the GitHub repository (source, Actions secrets, GHCR image
  registry). Today, exactly one person has all of this — there is no team to review access
  *within*.
- **Tenant access** — who can act as a given `Organization`'s administrator inside Clavaris itself
  (create `OAuthClient`s, view that Organization's own audit log). This is the access model
  Clavaris's own product actually governs (`domain-model.md`, `data-model.md`) — reviewed as part of
  each `Organization`'s own operation, not by this project's operator, per the tenant-isolation
  principle (ADR-0010) that a Clavaris operator has no standing reason to act inside a tenant's own
  account pool.

## 2. Operator access — current state and review

| System | Who has access | Review trigger |
|---|---|---|
| `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` | Engineering (sole developer); env-seeded, never via an HTTP endpoint (§5, root CLAUDE.md); delivered via `.env` on the production VM, `chmod 600`, deploy-user-owned (ADR-0019) | Rotate on any suspected exposure (`incident-response-platform-client-compromise.md`); no routine rotation schedule exists yet — a real, named gap, not an oversight |
| GitHub repository (source, Actions secrets, branch settings, GHCR image registry) | Engineering (sole developer) | Reviewed whenever a collaborator is added — none exist today |
| Production VM (SSH access, `.env` on disk) — TD-FUT-013, closed 2026-08-28 | Engineering (sole developer); provider/host not yet named in this document | Reviewed alongside the `PlatformClient` credential above — same file, same access boundary |

**Honest bottom line for §2**: with one person holding every credential above, "access review" today
means confirming those credentials still live only where they're supposed to (never in code, never
in a log, per BR-DATA-01) — not adjudicating whether a *second* person's access is still
appropriate, because no second person exists. This is the same structural gap
`risk-register.md` §3 names as "solo-developer bus factor" and `incident-response-plan.md` §1 names
in its own roles table — recorded once here as the access-control-specific instance of it, not
re-argued.

## 3. Tenant access

Governed entirely by Clavaris's own domain model, not by this document's operator: `Workspace`
roles (`OWNER`/`ADMIN`/`MEMBER`, §4/§5 root CLAUDE.md) are the mechanism a consuming
`Organization` uses to manage who can act within its own account pool — unbuilt as of this writing
(zero code exists for `Workspace`, root CLAUDE.md §11), so there is genuinely nothing to review yet
on this side. Once built, the review cadence for tenant-level access is each `Organization`'s own
responsibility, consistent with the tenant-isolation principle — Clavaris provides the mechanism
(role assignment, invitation, removal), not the review process itself, the same way a database
vendor provides `GRANT`/`REVOKE` without auditing a customer's own usage of it.

## 4. Review cadence

Same cadence as `risk-register.md` §4 and `technical-debt-register.md`'s own lifecycle rules — at
every full register review, and mandatorily before the external security review is scheduled.
Any new operator credential (a second team member, a new cloud provider account) must be added to
§2's table in the same change that introduces it, not retrofitted later.
