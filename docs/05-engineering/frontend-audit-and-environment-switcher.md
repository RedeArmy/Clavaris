# Frontend Audit and Environment Switcher — Clavaris

🟡 En revisión

| | |
|---|---|
| **Owner** | Engineering (solo project) |
| **Date** | 2026-10-03 |
| **Scope** | Every server-rendered page (107 templates), `clavaris.css`, the 18 scripts under `static/js`, and the Development / Production environment switcher |
| **Companion** | `frontend-design-system.md` (the rules this audit measures against) |

## 1. Method

Two passes: a read of the templates, stylesheet and scripts against the design-system rules, and a scripted census (counts below come from that script, re-runnable from git). Findings are rated like `technical-debt-register.md`: **P1** fix before the next release, **P2** schedule, **P3** opportunistic. Items marked ✅ were fixed in the same change as this document.

## 2. What is in good shape

- One stylesheet in six layers, token-driven, with a complete dark theme; **0** inline `style` attributes and **0** inline event handlers remain in any template (the CSP forbids both, and the audit confirms nothing relies on them).
- Every mutating form carries CSRF, every destructive one-click action now confirms first, every alert has a live-region role, and focus rings, skip link and `aria-current` are consistent.
- A Page frame that no longer moves between tabs (fixed header, tab bar and title-block heights, viewport-high pages).

## 3. Findings

### 3.1 Accessibility

| # | Finding | Evidence | Rating |
|---|---|---|---|
| A1 | ✅ Dialogs had no accessible name | 26 `<dialog>` elements, 0 with `aria-label`/`aria-labelledby` | P1 |
| A2 | 28 data tables have no `<caption>` or accessible name | census | P2 |
| A3 | Scroll regions (wide tables, long picker lists) are not keyboard-reachable | `overflow: auto` containers without a focusable descendant | P2 |
| A4 | The actions column scrolled out of reach on a 1024px viewport (Rotate / Deactivate invisible until the table was scrolled) | seen in the browser | P1 ✅ pinned with `position: sticky` |
| A5 | ✅ No global feedback when an HTMX request fails (4xx/5xx left the page silently unchanged); an expired session swapped a whole login page into a small fragment target | no `htmx:responseError` handling anywhere | P1 |

### 3.2 Duplication and drift

| # | Finding | Evidence | Rating |
|---|---|---|---|
| D1 | ✅ The dashboard navigation, the organization tab row, the `<head>` and the back button were hand-copied once per module | `dashboard-nav.html` ×4, `org-tabs.html` ×4, `head.html` ×4, `back-link.html` ×2; the copies had already drifted (the client-registry and webhook ones loaded extra scripts) | P1 |
| D2 | The platform auth pages duplicate the tenant ones | 17 same-named template pairs (`login`, `register`, `forgot-password`, …) | P2 |
| D3 | Seven scripts have no test | `assign-role-refresh`, `event-type-picker`, `login-submit-guard`, `oauth-client-scope-row`, `scope-picker`, `webauthn-login`, `webauthn-register` | P2 |
| D4 | The tenant brand colour never reaches the browser (inline `<style>` blocked by the CSP) | TD-UX-001 | P2 |

**D1 and A5 — resolved.** The four shared fragments now exist once, in `common` (`platform/fragments/dashboard-nav`, `org-tabs`, `back-link`, and `fragments/head`), the same place `configure-sidebar` and `organization-header` already lived and are covered by every module's standalone-MockMvc tests; 14 per-module files were removed and 75 templates repointed. Consolidating exposed real drift: only the client-registry and webhook copies of the navigation loaded the scope and event-type pickers, so those scripts are now loaded by the single navigation fragment on every dashboard page (each is a delegated listener that does nothing without its own container). `htmx-feedback.js` is loaded from the same place and gives every failed HTMX request an announced toast (in the top layer, so it shows above an open dialog) and turns a request redirected to the login page into a reload instead of a login page swapped into a fragment.

### 3.3 Visual system

| # | Finding | Rating |
|---|---|---|
| V1 | z-index values are ad hoc (`-1, 10, 20, 30, 40, 100, 200`); should be named tokens | P3 |
| V2 | 47 `rgba(...)` literals (shadows, overlays) outside the token layer | P3 |
| V3 | The Organizations dashboard shows a Development Organization and its Production sibling as two unrelated cards; Clerk presents one application with two environments | P1 (see §4.4) |
| V4 | Status is conveyed by colour plus text everywhere except the environment dot, which is decorative next to a text label (acceptable) | — |

### 3.4 Flows

| # | Finding | Rating |
|---|---|---|
| F1 | Switching environment was impossible without going back to the Organizations list and finding the sibling card | P1 ✅ |
| F2 | "Promote to Production" is only reachable from Danger Zone, a place users do not look for a routine step | P1 ✅ now offered from the environment menu |
| F3 | Deep links (`/oauth-clients/{clientId}/…`) have no equivalent in another environment | handled by the switcher (falls back to the tab) |

### 3.5 The Logs tab (Audit Log)

The page existed to answer "who changed what?", but showed the storage record instead of the answer.

| # | Finding (before) | Rating |
|---|---|---|
| L1 | ✅ The actor was a machine key and a UUID (`PLATFORM_ACCOUNT:8e864f9b-…`); nobody could tell if it was them | P1 |
| L2 | ✅ The action was the raw event name (`organization_client.deleted`) | P1 |
| L3 | ✅ The detail was an unparsed `key=value` string (`deletedClientId=sk_test_example0-0000-…`) with ids instead of names | P1 |
| L4 | ✅ A "Target" column of `Type:uuid` that no reader could use | P1 |
| L5 | ✅ The time was a bare date with no time of day, so same-day events could not be ordered by eye | P2 |
| L6 | ✅ No way to narrow 100 events to the part of the product you care about | P2 |
| L7 | ✅ Jargon in the page intro ("TD-SEC-007") and no caption on the table | P3 |
| L8 | Capped at the 100 most recent events, with no paging, search or date range | P2 |

What the page does now: a sentence per action from a catalog of all 80 audit actions (`AuditActionCatalog`; an action added later and not yet listed reads as a sentence made from its own name, and a test fails the build if the application starts writing an unlisted one); "You" for the signed-in account, "Operator (id)" for an API client, "Another platform account" otherwise; relative time ("3 hours ago", exact moment on hover); details as labelled pairs where workspace and role ids are replaced by their names when they still exist, booleans read Yes/No, long identifiers are cut with the full value on hover, and ids that only repeat the page (this Organization's own) are dropped; a coloured marker for created / changed / deleted / credential-touching events; filter chips by group with counts (`?category=`); and the raw record kept in a collapsed "Technical details" per row for support.

Remaining limits (L8 and what the data allows): names of entities that were deleted cannot be recovered, so those show a shortened id; OAuth clients and webhook endpoints are identified by their own id (readable for Secret Keys and OAuth clients, a UUID for webhook endpoints); only the owning platform account exists today, so "Another platform account" is rare. Paging, search and a date range are the natural next step if the 100-event window proves too small.

## 4. Environment switcher

### 4.1 How Clerk does it

Clerk calls each environment an **instance**. A Clerk application starts with a **Development** instance and gains a **Production** one through an explicit "create production instance" step:

- The dashboard header carries an **instance switcher**: a pill naming the current instance that opens a menu with Development and Production. Choosing one reloads the dashboard on the *same section* of the other instance.
- Instances are **fully isolated**: users, sessions, API keys, OAuth applications, webhooks and JWT templates are never shared. Keys are visibly different (`pk_test_…` / `sk_test_…` against `pk_live_…` / `sk_live_…`).
- Creating the production instance can **clone settings** (never users) from development; production additionally needs a domain and DNS records before it goes live.
- Development is deliberately constrained (user cap, relaxed verification, a "development mode" cue everywhere) so nobody mistakes it for production.

(The repo's own `docs/00-vision/clerk-feature-analysis.md` records the production-deployment details and sources; the switcher behaviour above is from Clerk's public dashboard and documentation.)

### 4.2 What Clavaris already had

The backend is the Clerk model already (SDE-III feature build, 2026-09-04): `Organization.environment` is `DEVELOPMENT` or `PRODUCTION`; a promoted Development Organization and its Production sibling are two **separate `Organization` rows** (own issuer, JWKS, account pool, Secret Keys, OAuth clients, webhooks) tied together only by `linkedEnvironmentOrganizationId`, set on both sides. Credentials are already environment-visible, as in Clerk: publishable keys read `pk_test_…` in Development and `pk_live_…` in Production, Secret Keys `sk_test_…` / `sk_live_…`. Promoting creates the sibling **empty** (nothing copied). Development gets a lower capacity ceiling (BR-ORG-06). **Correction, 2026-10-08**: verification-email bypass is no longer an environment-wide Development behavior — it now follows a per-address `+clavaris_test` marker instead (BR-ID-15), so Development sends real mail to real addresses exactly like Production does. Deleting one side clears the pointer on the survivor.

So the isolation the product asks for is **structural, not a rule to enforce in the switcher**: the switcher only navigates between two Organizations and moves no data.

### 4.3 What was built

- `DashboardOrganizationHeaderAdvice` (app module) now also builds the switcher entries for the Organization header; `OrganizationHeaderView` carries them as `EnvironmentOption`s.
- The environment badge in the header becomes a dropdown (a native `<details>`, usable without JavaScript; `details-dismiss.js` adds outside-click and Escape): **Development**, **Production**, each with its Organization name; the current one is checked.
- **Same page on the other side**: the target keeps the current top-level section (Users, Logs, Secret Keys, OAuth Clients, …) and falls back to the Organization's home for pages whose ids exist in one environment only (a client id, a workspace id), so a switch never lands on a 404.
- **Not yet set up**: a Development Organization with no sibling lists Production as "Set up", linking to Promote to Production.
- **No switcher** for a Production Organization that predates the feature and was never paired (nothing to switch to): the badge stays a plain label.
- Ownership is checked on both ends: a paired sibling the signed-in account does not own is never offered.
- Tests: the advice (six cases) and the rendered dropdown (with and without options).

### 4.4 Follow-ups, in order

1. **Group the pair on the Organizations dashboard** (V3): one card per application showing both environment chips, opening Development by default — the missing half of the Clerk experience.
2. **Offer "copy settings" when promoting** (Clerk's clone): copy configuration such as social-login policy and rate-limit policy — never accounts, keys or clients — behind an explicit checkbox. Needs a use-case change and its own decision record.
3. **Persistent development cue** on Development pages (Clerk's "development mode" banner), so the environment is never ambiguous when two tabs are open.

## 5. Recommended order for the rest

1. §4.4 items 1 and 3 (group the environment pair on the dashboard; the development cue).
2. A2/A3, D3, D2.
3. V1, V2, D4.
