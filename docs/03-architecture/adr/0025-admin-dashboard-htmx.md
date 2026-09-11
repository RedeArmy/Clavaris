# ADR-0025: Admin dashboard interactivity — Thymeleaf + HTMX, not a SPA

**Status:** ✅ Aprobado (2026-09-11)

## Context

CLAUDE.md §3 locks the hosted-UI stack: Thymeleaf, server-side rendered, "consistent with the
no-SPA philosophy of the ecosystem this was extracted from." That decision is correct and stays
unchanged for the entire authentication surface (login, registration, consent, password recovery,
session management, device trust) — every one of those pages is a short-lived, low-state
transactional form, exactly what SSR is the right tool for (see the reasoning captured in this
session's own discussion, summarized below).

The admin dashboard (`/platform/dashboard/**`) is a genuinely different kind of surface: an
operator managing Organizations, Workspaces, OAuth clients, webhooks, rate-limit policies, signing
keys, and audit history — tables with filtering/pagination, drill-down detail views, and actions
(rotate a secret, revoke a session, replay a webhook delivery) that read better as an in-place
update than a full-page reload. Building it as plain Thymeleaf-only (every action = full navigation)
would work but feels dated next to the comparable systems this project positions itself against
(Clerk, Auth0, Stripe Dashboard). Building it as a full SPA (React/Vue) would reintroduce exactly
the class of risk the rest of this project deliberately avoided: a relaxed CSP (`'unsafe-inline'`/
nonces for a hydration bundle), a second build pipeline (`package.json`, a JS lockfile, a separate
dependency-CVE surface) for a low-traffic, operator-only surface, and a second stack to keep at the
same security bar as the rest of a system whose whole job is being *the* security boundary for
everything that depends on it (CLAUDE.md §1).

## Decision

**HTMX** (self-hosted, vendored under `app/src/main/resources/static/js/htmx.min.js`, BSD
Zero-Clause licensed — not loaded from a CDN, same "no external script host" convention every other
hosted-UI script in this codebase already follows) for the admin dashboard only. Thymeleaf stays the
one rendering engine everywhere, including every HTMX-driven fragment — an `hx-get`/`hx-post`
request is handled by the exact same Spring MVC controllers as a normal navigation, just returning a
Thymeleaf fragment (a `th:fragment`-scoped chunk of the same template, via Thymeleaf's own
`X-Requested-With`/fragment-selection support) instead of the full page when the request carries
HTMX's own `HX-Request` header.

This is **not** a stack change for the authentication surface — nothing under `/o/{organizationId}/
**`, `/oauth2/consent`, or `/platform/login`/`/platform/register`/etc. loads HTMX or changes
rendering approach. Scoped exclusively to `/platform/dashboard/**`.

### Why HTMX specifically, not Alpine.js/Stimulus/Turbo

- Needs no build step, no compiler, no `node_modules` — a single vendored `.js` file, same
  operational shape as `login-submit-guard.js`/`embedded-login-popup.js` already have.
- No client-side templating/virtual DOM of its own — the server (Thymeleaf) remains the single
  source of truth for markup, which is what keeps this a genuine SSR-with-partial-updates
  architecture rather than a SPA with extra steps.
- CSP-compatible with `script-src 'self'` alone — no `eval`, no inline-script requirement (confirmed
  from HTMX's own docs; its attribute-driven model reads `hx-*` attributes already present in the
  server-rendered HTML, never executes injected strings).
- Small (~50KB unminified footprint, single file) — appropriate for an operator-only, low-traffic
  surface where bundle size was never a real constraint anyway.

### CSP impact

`ContentSecurityPolicyHeaderWriter` gains one more named policy, scoped to `/platform/dashboard/**`
only: `script-src 'self'` (same as `LOGIN_PAGE_POLICY` already grants `login.html` for its own two
same-origin scripts) — never `'unsafe-inline'`, never a CDN host. Every other path on
`PlatformDashboardSecurityConfig`'s own chain (login, register, forgot/reset-password) keeps the
strict default (`script-src 'none'`) unchanged — this relaxation is scoped to the dashboard's own
authenticated-only paths, not the whole `/platform/**` chain.

### What this does not change

- `PlatformDashboardSecurityConfig`'s own security matcher/authorization rules — every new dashboard
  route stays under `/platform/dashboard/**`, already covered by the existing `hasAuthority
  ("ROLE_PLATFORM_ACCOUNT")` rule with no new Spring Security wiring needed.
- The REST admin API (`/api/v1/admin/**`, `client_credentials`-protected) — the dashboard is a new
  *consumer* of the same use cases those endpoints already call, reached through session-
  authenticated Thymeleaf controllers instead, exactly the same "two protected surfaces over the
  same application layer" shape `PlatformOrganizationDashboardController`'s own Javadoc already
  documents for organization creation.

## Consequences

- A new dependency class enters the frontend for the first time (a vendored JS library, not just
  hand-written same-origin scripts) — tracked here, not silently introduced.
- Every dashboard controller method that serves an HTMX-driven partial needs to branch on the
  `HX-Request` header (full page vs. fragment) — a small, consistent pattern applied per controller,
  not a framework-level abstraction this ADR builds up front.
- If the dashboard's own interactivity needs eventually outgrow HTMX (nested client-side state,
  offline support, anything a hypermedia-driven model genuinely can't express), that is a real
  future decision to make explicitly, not a silent drift — this ADR's own scope is the dashboard as
  currently planned (`roadmap-and-release-plan.md` §4-shaped feature set: Organizations, Workspaces,
  OAuth clients, webhooks, rate-limit policies, signing keys, audit history, sessions).
